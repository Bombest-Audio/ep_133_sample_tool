package com.ep133.sampletool.support.sim

import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.midi.MIDIPort
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Wire-level EP-133 device simulator.
 *
 * Implements [MIDIPort] and answers real SysEx frames per the hardware-verified protocol in
 * docs/ep133-sysex-protocol.md — greet, FILE_INIT session, node-ID FILE_LIST/INFO/GET/PUT,
 * METADATA GET/SET, DELETE, PLAYBACK — so tests exercise the app's ACTUAL protocol stack
 * (frame building, 7-bit packing, reqId waiter routing, paged PUT with per-page acks) with
 * no hardware attached. Contrast with ScriptedMIDIRepository, which overrides repository
 * seams above the protocol.
 *
 * Device model (mirrors the doc's verified tree):
 *   0 /            2000 /projects → 01..09 @ 3000..11000 (step 1000)
 *   1000 /sounds     each project: groups = P+100, A..D = P+200..P+500
 *
 * Fidelity notes (all hardware-verified behaviors):
 * - responses insert a STATUS byte between command and packed body; reqId is echoed
 * - FILE ops before FILE_INIT get `can't list unless initialized`
 * - PUT DATA pages must arrive in order (`unexpected page`) and each is acked
 * - an unterminated PUT wedges the device: further PUT INITs are silently ignored
 *   until [powerCycle]
 *
 * Fault injection: [failPutDataAtPage], [silent], [wedge].
 */
class EP133DeviceSimulator(
    private val firmwareVersion: String = "2.0.5",
    private val serial: String = "SIMULATED1",
) : MIDIPort {

    companion object {
        const val DEVICE_ID = 0x33
        const val INPUT_PORT = "sim-ep133-in"
        const val OUTPUT_PORT = "sim-ep133-out"
        const val CHUNK_SIZE = 512
        const val SOUNDS_NODE = 1000
        const val PROJECTS_NODE = 2000
        const val MAX_CAPACITY = 62_853_120L
        private const val FLAGS_DIR = 0x0e   // READ|WRITE|DIR (observed)
        private const val FLAGS_FILE = 0x1d  // READ|WRITE|DELETE|FILE (observed)
    }

    // ── Node model ──────────────────────────────────────────────────────────

    class SimNode(
        val id: Int,
        val parentId: Int,
        val name: String,
        val isDir: Boolean,
        var data: ByteArray = ByteArray(0),
        var metadata: String = "{}",
    ) {
        val flags: Int get() = if (isDir) FLAGS_DIR else FLAGS_FILE
        val size: Long get() = if (isDir) 0L else data.size.toLong()
    }

    private val nodes = LinkedHashMap<Int, SimNode>()
    private var nextSoundNodeId = 1

    /** Groups of a project P live at P+200 (A) .. P+500 (D). */
    private fun groupNode(project: Int, letter: Char): Int = project + 200 + (letter - 'A') * 100

    init {
        addNode(SimNode(0, 0, "/", isDir = true))
        addNode(SimNode(SOUNDS_NODE, 0, "sounds", isDir = true))
        addNode(SimNode(PROJECTS_NODE, 0, "projects", isDir = true, metadata = """{"active":3000}"""))
        for (slot in 1..9) {
            val p = 2000 + slot * 1000
            addNode(SimNode(p, PROJECTS_NODE, "%02d".format(slot), isDir = true))
            addNode(SimNode(p + 100, p, "groups", isDir = true, metadata = """{"active":${p + 200}}"""))
            for (g in 'A'..'D') {
                addNode(SimNode(groupNode(p, g), p + 100, g.toString(), isDir = true, metadata = """{"active":0}"""))
            }
        }
    }

    private fun addNode(node: SimNode) { nodes[node.id] = node }

    private fun childrenOf(nodeId: Int): List<SimNode> =
        nodes.values.filter { it.parentId == nodeId && it.id != 0 }

    // ── Test seams ──────────────────────────────────────────────────────────

    /** Seed a sample into /sounds (device names slots NNN.pcm unless a name is given). */
    fun seedSound(bytes: ByteArray, name: String? = null): SimNode {
        val id = nextSoundNodeId++
        val fileName = name ?: "%03d.pcm".format(id)
        val node = SimNode(
            id, SOUNDS_NODE, fileName, isDir = false, data = bytes,
            metadata = """{"channels":1,"samplerate":46875,"format":"s16","name":"$fileName"}""",
        )
        addNode(node)
        return node
    }

    /** Files currently in /sounds — for asserting uploads landed. */
    fun soundFiles(): List<SimNode> = childrenOf(SOUNDS_NODE)

    /**
     * Positive-control seam (Phase 6 pattern-write spike, Plan 02 Task 3): seed a fake
     * write-flagged FILE node under [groupNode], mirroring [seedSound]'s id/flags shape but
     * named "pattern" so `PatternSpikeWalkerSimTest` can prove the walker WOULD surface a real
     * on-device pattern node if one existed — guarding a NO-GO finding against being a walker
     * bug rather than a genuine absence.
     */
    fun seedPatternNode(groupNode: Int, name: String = "pattern"): SimNode {
        val id = nextSoundNodeId++
        val node = SimNode(
            id, groupNode, name, isDir = false,
            data = ByteArray(64),
            metadata = """{"steps":16}""",
        )
        addNode(node)
        return node
    }

    fun setActiveProject(projectNode: Int) {
        nodes.getValue(PROJECTS_NODE).metadata = """{"active":$projectNode}"""
    }

    /** Point the active project's groups metadata at group [letter] (A..D). */
    fun setActiveGroup(letter: Char) {
        val project = activeProject()
        nodes.getValue(project + 100).metadata = """{"active":${groupNode(project, letter)}}"""
    }

    /** The group letter the active project's groups metadata points at. */
    fun activeGroupLetter(): Char {
        val project = activeProject()
        val meta = nodes.getValue(project + 100).metadata
        val active = Regex(""""active"\s*:\s*(\d+)""").find(meta)?.groupValues?.get(1)?.toInt() ?: return '?'
        return nodes[active]?.name?.firstOrNull() ?: '?'
    }

    private fun activeProject(): Int =
        Regex(""""active"\s*:\s*(\d+)""").find(nodes.getValue(PROJECTS_NODE).metadata)
            ?.groupValues?.get(1)?.toInt() ?: 3000

    /** Channel-voice frames the app sent (noteOn/noteOff/CC…). */
    val sentChannelFrames = mutableListOf<ByteArray>()

    fun sentNoteOns(): List<Pair<Int, Int>> = sentChannelFrames
        .filter { it.size == 3 && (it[0].toInt() and 0xF0) == 0x90 }
        .map { it[1].toInt() to it[2].toInt() }

    // ── Fault injection ─────────────────────────────────────────────────────

    /** Respond `unexpected page` (status 1) to the PUT DATA page with this index, once. */
    var failPutDataAtPage: Int? = null

    /** Drop everything on the floor — simulates a dead/unplugged device. */
    var silent: Boolean = false

    /**
     * True while an unterminated PUT is open; PUT INITs are silently ignored (HW-verified
     * wedge behavior). Settable so tests can start from a wedged device.
     */
    var wedge: Boolean = false

    /** Power-cycle: clears the wedge and the file session, like replugging the device. */
    fun powerCycle() {
        wedge = false
        sessionInitialized = false
        activePut = null
    }

    /**
     * Count of FILE_INIT handshakes the host has performed. A fresh session re-inits; a session
     * that survived does not. Lets a test assert an unsolicited greet did NOT reset the session
     * (issue #27).
     */
    var fileInitCount = 0
        private set

    /**
     * Deliver an UNSOLICITED greet to the host, out-of-band — models a device that re-greets with
     * the USB link still up (no re-enumeration). Runs on the delivery thread so it is ordered
     * against normal request/response traffic. Pair with [awaitDelivery] for a deterministic
     * barrier before asserting.
     */
    fun pushGreet() {
        deliveryExecutor.execute {
            respond(reqId = 0, command = SysExProtocol.CMD_GREET, status = 0, body = greetBody())
        }
    }

    /** Block until the single-threaded delivery queue has drained (all frames dispatched). */
    fun awaitDelivery() {
        deliveryExecutor.submit {}.get()
    }

    // ── MIDIPort ────────────────────────────────────────────────────────────

    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null

    // Single-thread delivery mimics the real USB-MIDI callback thread and keeps
    // parseMidiInput's shared accumulation buffers single-threaded.
    private val deliveryExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EP133Sim-delivery").apply { isDaemon = true }
    }

    override fun getUSBDevices() = MIDIPort.Devices(
        inputs = listOf(MIDIPort.Device(INPUT_PORT, "EP-133")),
        outputs = listOf(MIDIPort.Device(OUTPUT_PORT, "EP-133")),
    )

    override fun sendMidi(portId: String, data: ByteArray) {
        if (silent) return
        if (data.isEmpty()) return
        if (data[0] != SysExProtocol.MIDI_SYSEX_START) {
            sentChannelFrames += data.copyOf()
            return
        }
        val frame = data.copyOf()
        deliveryExecutor.execute { handleSysEx(frame) }
    }

    override fun requestUSBPermissions() {}
    override fun refreshDevices() { onDevicesChanged?.invoke() }
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() { deliveryExecutor.shutdown() }

    // ── Protocol state ──────────────────────────────────────────────────────

    private var sessionInitialized = false

    private class ActivePut(
        val fileId: Int,
        val parentId: Int,
        val name: String,
        val metadata: String,
        val declaredSize: Int,
    ) {
        val buffer = ByteArrayOutputStream()
        var expectedPage = 0
    }

    private var activePut: ActivePut? = null
    private var activeGetNode: SimNode? = null

    // ── Frame handling ──────────────────────────────────────────────────────

    private fun handleSysEx(frame: ByteArray) {
        // Request layout: F0 00 20 76 devId 40 flags reqId cmd <packed payload> F7
        if (frame.size < 10) return
        if (frame[1] != SysExProtocol.TE_ID_0 || frame[2] != SysExProtocol.TE_ID_1 ||
            frame[3] != SysExProtocol.TE_ID_2
        ) return
        val flags = frame[6].toInt() and 0xFF
        val reqId = ((flags and 0x0F) shl 7) or (frame[7].toInt() and 0x7F)
        val command = frame[8].toInt() and 0xFF
        val packed = if (frame.size > 10) frame.copyOfRange(9, frame.size - 1) else ByteArray(0)
        val body = if (packed.isNotEmpty()) SysExProtocol.unpack7bit(packed) else ByteArray(0)

        when (command) {
            SysExProtocol.CMD_GREET -> respond(reqId, command, status = 0, body = greetBody())
            SysExProtocol.TE_SYSEX_FILE -> handleFileOp(reqId, body)
            else -> respondError(reqId, command, "invalid id")
        }
    }

    private fun greetBody(): ByteArray =
        ("product:EP-133;mode:normal;base_sku:TE032AS001;sku:TE032AS001;" +
            "os_version:$firmwareVersion;sw_version:$firmwareVersion;" +
            "bl_version:1000.0.10;serial:$serial").toByteArray(Charsets.US_ASCII)

    private fun handleFileOp(reqId: Int, body: ByteArray) {
        if (body.isEmpty()) return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "invalid id")
        val sub = body[0].toInt() and 0xFF

        if (!sessionInitialized && sub != SysExProtocol.TE_SYSEX_FILE_INIT) {
            return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "can't list unless initialized")
        }

        when (sub) {
            SysExProtocol.TE_SYSEX_FILE_INIT -> {
                sessionInitialized = true
                fileInitCount++
                // HW-capture-shaped body; parseFileInitResponse reads a u32 at [1..4].
                respond(
                    reqId, SysExProtocol.TE_SYSEX_FILE, 0,
                    byteArrayOf(0x00, 0x00, 0x00, (CHUNK_SIZE shr 8).toByte(), (CHUNK_SIZE and 0xFF).toByte(), 0x00),
                )
            }

            SysExProtocol.TE_SYSEX_FILE_LIST -> {
                // [LIST, page u16, nodeId u16]
                val page = u16(body, 1)
                val nodeId = u16(body, 3)
                nodes[nodeId] ?: return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "invalid id")
                // FILE_LIST against a FILE (non-dir) node is a read-only, generic-FSNode query —
                // it can't wedge and simply has no children (Phase 6 spike RESEARCH gap C). Fall
                // through and let childrenOf(nodeId) naturally return empty, rather than erroring.
                val out = ByteArrayOutputStream()
                out.write(page shr 8); out.write(page and 0xFF)
                if (page == 0) {
                    childrenOf(nodeId).forEach { child ->
                        out.write(child.id shr 8); out.write(child.id and 0xFF)
                        out.write(child.flags)
                        writeU32(out, child.size.toInt())
                        out.write(child.name.toByteArray(Charsets.US_ASCII)); out.write(0)
                    }
                } // pages >0 return an empty page — the device paginates, callers stop on empty
                respond(reqId, SysExProtocol.TE_SYSEX_FILE, 0, out.toByteArray())
            }

            SysExProtocol.TE_SYSEX_FILE_INFO -> {
                val nodeId = u16(body, 1)
                val node = nodes[nodeId] ?: return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "not found")
                val out = ByteArrayOutputStream()
                out.write(node.id shr 8); out.write(node.id and 0xFF)
                out.write(node.parentId shr 8); out.write(node.parentId and 0xFF)
                out.write(node.flags)
                writeU32(out, node.size.toInt())
                out.write(node.name.toByteArray(Charsets.US_ASCII)); out.write(0)
                respond(reqId, SysExProtocol.TE_SYSEX_FILE, 0, out.toByteArray())
            }

            SysExProtocol.TE_SYSEX_FILE_GET -> handleGet(reqId, body)
            SysExProtocol.TE_SYSEX_FILE_PUT -> handlePut(reqId, body)
            SysExProtocol.TE_SYSEX_FILE_METADATA -> handleMetadata(reqId, body)

            SysExProtocol.TE_SYSEX_FILE_DELETE -> {
                val nodeId = u16(body, 1)
                val node = nodes[nodeId] ?: return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "not found")
                nodes.remove(node.id)
                respond(reqId, SysExProtocol.TE_SYSEX_FILE, 0, ByteArray(0))
            }

            5 -> respond(reqId, SysExProtocol.TE_SYSEX_FILE, 0, ByteArray(0)) // FILE_PLAYBACK start/stop

            else -> respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "invalid id")
        }
    }

    private fun handleGet(reqId: Int, body: ByteArray) {
        when (body[1].toInt() and 0xFF) {
            SysExProtocol.TE_SYSEX_FILE_GET_TYPE_INIT -> {
                val nodeId = u16(body, 2)
                val node = nodes[nodeId] ?: return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "not found")
                activeGetNode = node
                val out = ByteArrayOutputStream()
                out.write(node.id shr 8); out.write(node.id and 0xFF)
                out.write(node.flags)
                writeU32(out, node.data.size)
                out.write(node.name.toByteArray(Charsets.US_ASCII)); out.write(0)
                respond(reqId, SysExProtocol.TE_SYSEX_FILE, 0, out.toByteArray())
            }
            SysExProtocol.TE_SYSEX_FILE_GET_TYPE_DATA -> {
                val page = u16(body, 2)
                val node = activeGetNode ?: return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "invalid id")
                val chunkSize = CHUNK_SIZE - 96 // raw budget below the packed frame ceiling
                val start = page * chunkSize
                val end = minOf(start + chunkSize, node.data.size)
                val chunk = if (start < node.data.size) node.data.copyOfRange(start, end) else ByteArray(0)
                val out = ByteArrayOutputStream()
                out.write(page shr 8); out.write(page and 0xFF)
                out.write(chunk)
                respond(reqId, SysExProtocol.TE_SYSEX_FILE, 0, out.toByteArray())
            }
            else -> respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "invalid id")
        }
    }

    private fun handlePut(reqId: Int, body: ByteArray) {
        when (body[1].toInt() and 0xFF) {
            SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_INIT -> {
                if (wedge) return // HW-verified: a wedged device silently ignores new PUT INITs
                // [PUT, INIT, flags, fileId u16, parentId u16, size u32, name NUL, meta NUL]
                val parentId = u16(body, 5)
                val size = u32(body, 7)
                val rest = body.copyOfRange(11, body.size)
                val nameEnd = rest.indexOfFirst { it.toInt() == 0 }.takeIf { it >= 0 } ?: rest.size
                val name = String(rest.copyOfRange(0, nameEnd), Charsets.US_ASCII)
                val metaBytes = if (nameEnd + 1 < rest.size) rest.copyOfRange(nameEnd + 1, rest.size) else ByteArray(0)
                val metaEnd = metaBytes.indexOfFirst { it.toInt() == 0 }.takeIf { it >= 0 } ?: metaBytes.size
                val metadata = String(metaBytes.copyOfRange(0, metaEnd), Charsets.US_ASCII).ifEmpty { "{}" }
                if (nodes[parentId]?.isDir != true) {
                    return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "invalid id")
                }
                val fileId = nextSoundNodeId++
                activePut = ActivePut(fileId, parentId, name, metadata, size)
                wedge = true // open transfer: stays wedged until the zero-length terminator
                respond(
                    reqId, SysExProtocol.TE_SYSEX_FILE, 0,
                    byteArrayOf((fileId shr 8).toByte(), (fileId and 0xFF).toByte()),
                )
            }
            SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA -> {
                val put = activePut ?: return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "unexpected page")
                val page = u16(body, 2)
                val chunk = body.copyOfRange(4, body.size)
                if (chunk.isEmpty()) {
                    // Terminator — closes the transfer even after a failed page (this is what
                    // the app's forceCloseTransfer relies on to avoid the HW wedge). Only a
                    // complete upload commits the file.
                    if (put.buffer.size() == put.declaredSize) {
                        addNode(
                            SimNode(
                                put.fileId, put.parentId, put.name, isDir = false,
                                data = put.buffer.toByteArray(), metadata = put.metadata,
                            )
                        )
                    }
                    activePut = null
                    wedge = false
                    return respond(reqId, SysExProtocol.TE_SYSEX_FILE, 0, ByteArray(0))
                }
                if (failPutDataAtPage == page) {
                    failPutDataAtPage = null
                    return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "unexpected page")
                }
                if (page != put.expectedPage) {
                    return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "unexpected page")
                }
                put.buffer.write(chunk)
                put.expectedPage = (put.expectedPage + 1) and 0xFFFF
                respond(reqId, SysExProtocol.TE_SYSEX_FILE, 0, ByteArray(0))
            }
            else -> respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "invalid id")
        }
    }

    private fun handleMetadata(reqId: Int, body: ByteArray) {
        when (body[1].toInt() and 0xFF) {
            SysExProtocol.TE_SYSEX_FILE_METADATA_GET -> {
                val nodeId = u16(body, 2)
                val page = u16(body, 4)
                val node = nodes[nodeId] ?: return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "not found")
                val json = if (nodeId == SOUNDS_NODE) soundsMetadata() else node.metadata
                val out = ByteArrayOutputStream()
                out.write(page shr 8); out.write(page and 0xFF)
                out.write(json.toByteArray(Charsets.US_ASCII)); out.write(0)
                respond(reqId, SysExProtocol.TE_SYSEX_FILE, 0, out.toByteArray())
            }
            SysExProtocol.TE_SYSEX_FILE_METADATA_SET -> {
                val nodeId = u16(body, 2)
                val node = nodes[nodeId] ?: return respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "not found")
                val jsonBytes = body.copyOfRange(4, body.size)
                val end = jsonBytes.indexOfFirst { it.toInt() == 0 }.takeIf { it >= 0 } ?: jsonBytes.size
                node.metadata = String(jsonBytes.copyOfRange(0, end), Charsets.US_ASCII)
                respond(reqId, SysExProtocol.TE_SYSEX_FILE, 0, ByteArray(0))
            }
            else -> respondError(reqId, SysExProtocol.TE_SYSEX_FILE, "invalid id")
        }
    }

    /** /sounds storage metadata computed live from the seeded/uploaded files. */
    private fun soundsMetadata(): String {
        val used = childrenOf(SOUNDS_NODE).sumOf { it.size }
        val free = (MAX_CAPACITY - used).coerceAtLeast(0)
        return """{"max_capacity":$MAX_CAPACITY,"free_space_in_bytes":$free,""" +
            """"formats":[{"type":"pcm","formats":[{"samplerate.range":[1,65535],""" +
            """"samplerate.native":46875,"channels":[1,2],"format":["s16"]}]}]}"""
    }

    // ── Response building ───────────────────────────────────────────────────

    /**
     * Response layout the app parses: F0 00 20 76 devId 40 flags reqId cmd STATUS <packed body> F7
     * — reqId high bits ride the flags nibble so dispatch reconstructs the 14-bit id.
     */
    private fun respond(reqId: Int, command: Int, status: Int, body: ByteArray) {
        if (silent) return
        val out = ByteArrayOutputStream(12 + body.size * 2)
        out.write(SysExProtocol.MIDI_SYSEX_START.toInt())
        out.write(SysExProtocol.TE_ID_0.toInt())
        out.write(SysExProtocol.TE_ID_1.toInt())
        out.write(SysExProtocol.TE_ID_2.toInt())
        out.write(DEVICE_ID)
        out.write(SysExProtocol.MIDI_SYSEX_TE.toInt())
        out.write(SysExProtocol.BIT_REQUEST_ID_AVAILABLE or ((reqId shr 7) and 0x0F))
        out.write(reqId and 0x7F)
        out.write(command and 0x7F)
        out.write(status and 0x7F)
        if (body.isNotEmpty()) out.write(SysExProtocol.pack7bit(body))
        out.write(SysExProtocol.MIDI_SYSEX_END.toInt())
        onMidiReceived?.invoke(INPUT_PORT, out.toByteArray())
    }

    private fun respondError(reqId: Int, command: Int, message: String) =
        respond(reqId, command, status = 1, body = message.toByteArray(Charsets.US_ASCII))

    // ── Byte helpers ────────────────────────────────────────────────────────

    private fun u16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun writeU32(out: ByteArrayOutputStream, v: Int) {
        out.write(v shr 24); out.write((v shr 16) and 0xFF); out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
    }
}
