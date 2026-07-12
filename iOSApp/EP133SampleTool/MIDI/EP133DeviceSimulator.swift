import Foundation

#if DEBUG

/// Wire-level EP-133 device simulator.
///
/// Implements `MIDIPort` and answers real SysEx frames per the hardware-verified protocol in
/// docs/ep133-sysex-protocol.md — greet, FILE_INIT session, node-ID FILE_LIST/INFO/GET/PUT,
/// METADATA GET/SET, DELETE, PLAYBACK — so tests exercise the app's ACTUAL protocol stack
/// (frame building, 7-bit packing, reqId waiter routing, paged PUT with per-page acks) with
/// no hardware attached.
///
/// Swift port of AndroidApp/app/src/debug/java/com/ep133/sampletool/support/sim/
/// EP133DeviceSimulator.kt (byte-identical wire behavior). Debug-only: the whole file is
/// compiled out of release builds; main source reaches it only through
/// `SimulatedPortProvider`.
///
/// Device model (mirrors the doc's verified tree):
///   0 /            2000 /projects → 01..09 @ 3000..11000 (step 1000)
///   1000 /sounds     each project: groups = P+100, A..D = P+200..P+500
///
/// Fidelity notes (all hardware-verified behaviors):
/// - responses insert a STATUS byte between command and packed body; reqId is echoed
/// - FILE ops before FILE_INIT get `can't list unless initialized`
/// - PUT DATA pages must arrive in order (`unexpected page`) and each is acked
/// - an unterminated PUT wedges the device: further PUT INITs are silently ignored
///   until `powerCycle()`
///
/// Fault injection: `failPutDataAtPage`, `silent`, `wedge`.
///
/// Threading: the Kotlin simulator handles frames on a single-thread delivery executor;
/// here frames are handled asynchronously on the main queue, which is both serial (same
/// ordering guarantee) and satisfies the MIDIPort contract that callbacks arrive on the
/// main thread (mirrors MIDIManager's DispatchQueue.main delivery).
final class EP133DeviceSimulator: MIDIPort {

    static let DEVICE_ID = 0x33
    static let INPUT_PORT = "sim-ep133-in"
    static let OUTPUT_PORT = "sim-ep133-out"
    static let CHUNK_SIZE = 512
    static let SOUNDS_NODE = 1000
    static let PROJECTS_NODE = 2000
    static let MAX_CAPACITY: Int64 = 62_853_120
    private static let FLAGS_DIR = 0x0E   // READ|WRITE|DIR (observed)
    private static let FLAGS_FILE = 0x1D  // READ|WRITE|DELETE|FILE (observed)

    private let firmwareVersion: String
    private let serial: String

    init(firmwareVersion: String = "2.0.5", serial: String = "SIMULATED1") {
        self.firmwareVersion = firmwareVersion
        self.serial = serial
        addNode(SimNode(id: 0, parentId: 0, name: "/", isDir: true))
        addNode(SimNode(id: Self.SOUNDS_NODE, parentId: 0, name: "sounds", isDir: true))
        addNode(SimNode(
            id: Self.PROJECTS_NODE, parentId: 0, name: "projects", isDir: true,
            metadata: "{\"active\":3000}"))
        for slot in 1...9 {
            let p = 2000 + slot * 1000
            addNode(SimNode(
                id: p, parentId: Self.PROJECTS_NODE,
                name: String(format: "%02d", slot), isDir: true))
            addNode(SimNode(
                id: p + 100, parentId: p, name: "groups", isDir: true,
                metadata: "{\"active\":\(p + 200)}"))
            for g in ["A", "B", "C", "D"] {
                addNode(SimNode(
                    id: groupNode(project: p, letter: Character(g)),
                    parentId: p + 100, name: g, isDir: true,
                    metadata: "{\"active\":0}"))
            }
        }
    }

    // ── Node model ──────────────────────────────────────────────────────────

    final class SimNode {
        let id: Int
        let parentId: Int
        let name: String
        let isDir: Bool
        var data: [UInt8]
        var metadata: String

        init(
            id: Int, parentId: Int, name: String, isDir: Bool,
            data: [UInt8] = [], metadata: String = "{}"
        ) {
            self.id = id
            self.parentId = parentId
            self.name = name
            self.isDir = isDir
            self.data = data
            self.metadata = metadata
        }

        var flags: Int { isDir ? EP133DeviceSimulator.FLAGS_DIR : EP133DeviceSimulator.FLAGS_FILE }
        var size: Int64 { isDir ? 0 : Int64(data.count) }
    }

    /// id → node plus insertion order (the Kotlin LinkedHashMap — FILE_LIST order matters).
    private var nodes: [Int: SimNode] = [:]
    private var nodeOrder: [Int] = []
    private var nextSoundNodeId = 1

    /// Groups of a project P live at P+200 (A) .. P+500 (D).
    private func groupNode(project: Int, letter: Character) -> Int {
        project + 200 + (Int(letter.asciiValue ?? 65) - 65) * 100
    }

    private func addNode(_ node: SimNode) {
        if nodes[node.id] == nil { nodeOrder.append(node.id) }
        nodes[node.id] = node
    }

    private func removeNode(_ id: Int) {
        nodes[id] = nil
        nodeOrder.removeAll { $0 == id }
    }

    private func childrenOf(_ nodeId: Int) -> [SimNode] {
        nodeOrder.compactMap { nodes[$0] }.filter { $0.parentId == nodeId && $0.id != 0 }
    }

    // ── Test seams ──────────────────────────────────────────────────────────

    /// Seed a sample into /sounds (device names slots NNN.pcm unless a name is given).
    @discardableResult
    func seedSound(_ bytes: [UInt8], name: String? = nil) -> SimNode {
        let id = nextSoundNodeId
        nextSoundNodeId += 1
        let fileName = name ?? String(format: "%03d.pcm", id)
        let node = SimNode(
            id: id, parentId: Self.SOUNDS_NODE, name: fileName, isDir: false, data: bytes,
            metadata: "{\"channels\":1,\"samplerate\":46875,\"format\":\"s16\",\"name\":\"\(fileName)\"}")
        addNode(node)
        return node
    }

    /// Files currently in /sounds — for asserting uploads landed.
    func soundFiles() -> [SimNode] { childrenOf(Self.SOUNDS_NODE) }

    func setActiveProject(_ projectNode: Int) {
        nodes[Self.PROJECTS_NODE]?.metadata = "{\"active\":\(projectNode)}"
    }

    /// Point the active project's groups metadata at group `letter` (A..D).
    func setActiveGroup(_ letter: Character) {
        let project = activeProject()
        nodes[project + 100]?.metadata = "{\"active\":\(groupNode(project: project, letter: letter))}"
    }

    /// The group letter the active project's groups metadata points at.
    func activeGroupLetter() -> Character {
        let project = activeProject()
        guard let meta = nodes[project + 100]?.metadata,
              let active = activeNodeId(in: meta),
              let first = nodes[active]?.name.first
        else { return "?" }
        return first
    }

    private func activeProject() -> Int {
        guard let meta = nodes[Self.PROJECTS_NODE]?.metadata,
              let active = activeNodeId(in: meta)
        else { return 3000 }
        return active
    }

    /// Kotlin `Regex("\"active\"\\s*:\\s*(\\d+)")` — the matched substring's digits ARE the id.
    private func activeNodeId(in metadata: String) -> Int? {
        guard let range = metadata.range(
            of: #""active"\s*:\s*(\d+)"#, options: .regularExpression)
        else { return nil }
        return Int(metadata[range].filter(\.isNumber))
    }

    /// Channel-voice frames the app sent (noteOn/noteOff/CC…).
    private(set) var sentChannelFrames: [[UInt8]] = []

    func sentNoteOns() -> [(note: Int, velocity: Int)] {
        sentChannelFrames
            .filter { $0.count == 3 && ($0[0] & 0xF0) == 0x90 }
            .map { (note: Int($0[1]), velocity: Int($0[2])) }
    }

    // ── Fault injection ─────────────────────────────────────────────────────

    /// Respond `unexpected page` (status 1) to the PUT DATA page with this index, once.
    var failPutDataAtPage: Int?

    /// Drop everything on the floor — simulates a dead/unplugged device.
    var silent = false

    /// True while an unterminated PUT is open; PUT INITs are silently ignored (HW-verified
    /// wedge behavior). Settable so tests can start from a wedged device.
    var wedge = false

    /// Power-cycle: clears the wedge and the file session, like replugging the device.
    func powerCycle() {
        wedge = false
        sessionInitialized = false
        activePut = nil
    }

    // ── MIDIPort ────────────────────────────────────────────────────────────

    var onMIDIReceived: ((String, [UInt8]) -> Void)?
    var onDevicesChanged: (() -> Void)?

    /// Set on close() so frames queued after teardown stop being handled (the Kotlin
    /// counterpart shuts its delivery executor down).
    private var isClosed = false

    func setup() {}

    func close() { isClosed = true }

    func getUSBDevices() -> MIDIDeviceList {
        MIDIDeviceList(
            inputs: [MIDIDevice(id: Self.INPUT_PORT, name: "EP-133")],
            outputs: [MIDIDevice(id: Self.OUTPUT_PORT, name: "EP-133")])
    }

    func sendMIDI(to portId: String, data: [UInt8]) {
        if isClosed || silent { return }
        guard let first = data.first else { return }
        if first != SysExProtocol.MIDI_SYSEX_START {
            sentChannelFrames.append(data)
            return
        }
        // Async main-queue hop = the Kotlin single-thread delivery executor: serial,
        // ordered, and never re-entrant into the sender's stack frame.
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.isClosed else { return }
            self.handleSysEx(data)
        }
    }

    func startListening(portId: String) {}
    func stopListening(portId: String) {}

    // ── Protocol state ──────────────────────────────────────────────────────

    private var sessionInitialized = false

    private final class ActivePut {
        let fileId: Int
        let parentId: Int
        let name: String
        let metadata: String
        let declaredSize: Int
        var buffer: [UInt8] = []
        var expectedPage = 0

        init(fileId: Int, parentId: Int, name: String, metadata: String, declaredSize: Int) {
            self.fileId = fileId
            self.parentId = parentId
            self.name = name
            self.metadata = metadata
            self.declaredSize = declaredSize
        }
    }

    private var activePut: ActivePut?
    private var activeGetNode: SimNode?

    // ── Frame handling ──────────────────────────────────────────────────────

    private func handleSysEx(_ frame: [UInt8]) {
        // Request layout: F0 00 20 76 devId 40 flags reqId cmd <packed payload> F7
        guard frame.count >= 10 else { return }
        guard frame[1] == SysExProtocol.TE_ID_0,
              frame[2] == SysExProtocol.TE_ID_1,
              frame[3] == SysExProtocol.TE_ID_2
        else { return }
        let flags = Int(frame[6])
        let reqId = SysExProtocol.decodeRequestId(flags: flags, low: Int(frame[7]))
        let command = Int(frame[8])
        let packed: [UInt8] = frame.count > 10 ? Array(frame[9..<(frame.count - 1)]) : []
        let body = packed.isEmpty ? [] : SysExProtocol.unpack7bit(packed)

        switch command {
        case SysExProtocol.CMD_GREET:
            respond(reqId: reqId, command: command, status: 0, body: greetBody())
        case SysExProtocol.TE_SYSEX_FILE:
            handleFileOp(reqId: reqId, body: body)
        default:
            respondError(reqId: reqId, command: command, message: "invalid id")
        }
    }

    private func greetBody() -> [UInt8] {
        SysExProtocol.asciiBytes(
            "product:EP-133;mode:normal;base_sku:TE032AS001;sku:TE032AS001;"
                + "os_version:\(firmwareVersion);sw_version:\(firmwareVersion);"
                + "bl_version:1000.0.10;serial:\(serial)")
    }

    private func handleFileOp(reqId: Int, body: [UInt8]) {
        guard let subByte = body.first else {
            return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
        }
        let sub = Int(subByte)

        if !sessionInitialized && sub != SysExProtocol.TE_SYSEX_FILE_INIT {
            return respondError(
                reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE,
                message: "can't list unless initialized")
        }

        switch sub {
        case SysExProtocol.TE_SYSEX_FILE_INIT:
            sessionInitialized = true
            // HW-capture-shaped body; parseFileInitResponse reads a u32 at [1..4].
            respond(
                reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, status: 0,
                body: [
                    0x00, 0x00, 0x00,
                    UInt8((Self.CHUNK_SIZE >> 8) & 0xFF), UInt8(Self.CHUNK_SIZE & 0xFF),
                    0x00,
                ])

        case SysExProtocol.TE_SYSEX_FILE_LIST:
            // [LIST, page u16, nodeId u16]
            guard body.count >= 5 else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
            }
            let page = u16(body, 1)
            let nodeId = u16(body, 3)
            guard let node = nodes[nodeId], node.isDir else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
            }
            var out = [UInt8]()
            appendU16(&out, page)
            if page == 0 {
                for child in childrenOf(nodeId) {
                    appendU16(&out, child.id)
                    out.append(UInt8(child.flags & 0xFF))
                    appendU32(&out, Int(child.size))
                    out.append(contentsOf: SysExProtocol.asciiBytes(child.name))
                    out.append(0)
                }
            } // pages >0 return an empty page — the device paginates, callers stop on empty
            respond(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, status: 0, body: out)

        case SysExProtocol.TE_SYSEX_FILE_INFO:
            guard body.count >= 3 else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "not found")
            }
            let nodeId = u16(body, 1)
            guard let node = nodes[nodeId] else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "not found")
            }
            var out = [UInt8]()
            appendU16(&out, node.id)
            appendU16(&out, node.parentId)
            out.append(UInt8(node.flags & 0xFF))
            appendU32(&out, Int(node.size))
            out.append(contentsOf: SysExProtocol.asciiBytes(node.name))
            out.append(0)
            respond(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, status: 0, body: out)

        case SysExProtocol.TE_SYSEX_FILE_GET:
            handleGet(reqId: reqId, body: body)

        case SysExProtocol.TE_SYSEX_FILE_PUT:
            handlePut(reqId: reqId, body: body)

        case SysExProtocol.TE_SYSEX_FILE_METADATA:
            handleMetadata(reqId: reqId, body: body)

        case SysExProtocol.TE_SYSEX_FILE_DELETE:
            guard body.count >= 3, let node = nodes[u16(body, 1)] else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "not found")
            }
            removeNode(node.id)
            respond(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, status: 0, body: [])

        case SysExProtocol.TE_SYSEX_FILE_PLAYBACK: // start/stop preview playback
            respond(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, status: 0, body: [])

        default:
            respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
        }
    }

    private func handleGet(reqId: Int, body: [UInt8]) {
        guard body.count >= 2 else {
            return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
        }
        switch Int(body[1]) {
        case SysExProtocol.TE_SYSEX_FILE_GET_TYPE_INIT:
            guard body.count >= 4, let node = nodes[u16(body, 2)] else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "not found")
            }
            activeGetNode = node
            var out = [UInt8]()
            appendU16(&out, node.id)
            out.append(UInt8(node.flags & 0xFF))
            appendU32(&out, node.data.count)
            out.append(contentsOf: SysExProtocol.asciiBytes(node.name))
            out.append(0)
            respond(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, status: 0, body: out)

        case SysExProtocol.TE_SYSEX_FILE_GET_TYPE_DATA:
            guard body.count >= 4 else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
            }
            let page = u16(body, 2)
            guard let node = activeGetNode else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
            }
            let chunkSize = Self.CHUNK_SIZE - 96 // raw budget below the packed frame ceiling
            let start = page * chunkSize
            let end = min(start + chunkSize, node.data.count)
            let chunk: [UInt8] = start < node.data.count ? Array(node.data[start..<end]) : []
            var out = [UInt8]()
            appendU16(&out, page)
            out.append(contentsOf: chunk)
            respond(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, status: 0, body: out)

        default:
            respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
        }
    }

    private func handlePut(reqId: Int, body: [UInt8]) {
        guard body.count >= 2 else {
            return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
        }
        switch Int(body[1]) {
        case SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_INIT:
            if wedge { return } // HW-verified: a wedged device silently ignores new PUT INITs
            // [PUT, INIT, flags, fileId u16, parentId u16, size u32, name NUL, meta NUL]
            guard body.count >= 11 else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
            }
            let parentId = u16(body, 5)
            let size = u32(body, 7)
            let rest = Array(body[11...])
            let nameEnd = rest.firstIndex(of: 0) ?? rest.count
            let name = SysExProtocol.asciiString(rest[0..<nameEnd])
            let metaBytes: [UInt8] = nameEnd + 1 < rest.count ? Array(rest[(nameEnd + 1)...]) : []
            let metaEnd = metaBytes.firstIndex(of: 0) ?? metaBytes.count
            let metaString = SysExProtocol.asciiString(metaBytes[0..<metaEnd])
            let metadata = metaString.isEmpty ? "{}" : metaString
            guard nodes[parentId]?.isDir == true else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
            }
            let fileId = nextSoundNodeId
            nextSoundNodeId += 1
            activePut = ActivePut(
                fileId: fileId, parentId: parentId, name: name,
                metadata: metadata, declaredSize: size)
            wedge = true // open transfer: stays wedged until the zero-length terminator
            respond(
                reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, status: 0,
                body: [UInt8((fileId >> 8) & 0xFF), UInt8(fileId & 0xFF)])

        case SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA:
            guard let put = activePut, body.count >= 4 else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "unexpected page")
            }
            let page = u16(body, 2)
            let chunk: [UInt8] = body.count > 4 ? Array(body[4...]) : []
            if chunk.isEmpty {
                // Terminator — closes the transfer even after a failed page (this is what
                // the app's forceCloseTransfer relies on to avoid the HW wedge). Only a
                // complete upload commits the file.
                if put.buffer.count == put.declaredSize {
                    addNode(SimNode(
                        id: put.fileId, parentId: put.parentId, name: put.name,
                        isDir: false, data: put.buffer, metadata: put.metadata))
                }
                activePut = nil
                wedge = false
                return respond(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, status: 0, body: [])
            }
            if failPutDataAtPage == page {
                failPutDataAtPage = nil
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "unexpected page")
            }
            if page != put.expectedPage {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "unexpected page")
            }
            put.buffer.append(contentsOf: chunk)
            put.expectedPage = (put.expectedPage + 1) & 0xFFFF
            respond(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, status: 0, body: [])

        default:
            respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
        }
    }

    private func handleMetadata(reqId: Int, body: [UInt8]) {
        guard body.count >= 2 else {
            return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
        }
        switch Int(body[1]) {
        case SysExProtocol.TE_SYSEX_FILE_METADATA_GET:
            guard body.count >= 6 else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "not found")
            }
            let nodeId = u16(body, 2)
            let page = u16(body, 4)
            guard let node = nodes[nodeId] else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "not found")
            }
            let json = nodeId == Self.SOUNDS_NODE ? soundsMetadata() : node.metadata
            var out = [UInt8]()
            appendU16(&out, page)
            out.append(contentsOf: SysExProtocol.asciiBytes(json))
            out.append(0)
            respond(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, status: 0, body: out)

        case SysExProtocol.TE_SYSEX_FILE_METADATA_SET:
            guard body.count >= 4 else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "not found")
            }
            let nodeId = u16(body, 2)
            guard let node = nodes[nodeId] else {
                return respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "not found")
            }
            let jsonBytes = Array(body[4...])
            let end = jsonBytes.firstIndex(of: 0) ?? jsonBytes.count
            node.metadata = SysExProtocol.asciiString(jsonBytes[0..<end])
            respond(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, status: 0, body: [])

        default:
            respondError(reqId: reqId, command: SysExProtocol.TE_SYSEX_FILE, message: "invalid id")
        }
    }

    /// /sounds storage metadata computed live from the seeded/uploaded files.
    private func soundsMetadata() -> String {
        let used = childrenOf(Self.SOUNDS_NODE).reduce(Int64(0)) { $0 + $1.size }
        let free = max(Self.MAX_CAPACITY - used, 0)
        return "{\"max_capacity\":\(Self.MAX_CAPACITY),\"free_space_in_bytes\":\(free),"
            + "\"formats\":[{\"type\":\"pcm\",\"formats\":[{\"samplerate.range\":[1,65535],"
            + "\"samplerate.native\":46875,\"channels\":[1,2],\"format\":[\"s16\"]}]}]}"
    }

    // ── Response building ───────────────────────────────────────────────────

    /// Response layout the app parses: F0 00 20 76 devId 40 flags reqId cmd STATUS <packed body> F7
    /// — reqId high bits ride the flags nibble so dispatch reconstructs the 14-bit id.
    private func respond(reqId: Int, command: Int, status: Int, body: [UInt8]) {
        if silent { return }
        var out = [UInt8]()
        out.reserveCapacity(12 + body.count * 2)
        out.append(SysExProtocol.MIDI_SYSEX_START)
        out.append(SysExProtocol.TE_ID_0)
        out.append(SysExProtocol.TE_ID_1)
        out.append(SysExProtocol.TE_ID_2)
        out.append(UInt8(Self.DEVICE_ID))
        out.append(SysExProtocol.MIDI_SYSEX_TE)
        out.append(UInt8((SysExProtocol.BIT_REQUEST_ID_AVAILABLE | ((reqId >> 7) & 0x0F)) & 0xFF))
        out.append(UInt8(reqId & 0x7F))
        out.append(UInt8(command & 0x7F))
        out.append(UInt8(status & 0x7F))
        if !body.isEmpty { out.append(contentsOf: SysExProtocol.pack7bit(body)) }
        out.append(SysExProtocol.MIDI_SYSEX_END)
        onMIDIReceived?(Self.INPUT_PORT, out)
    }

    private func respondError(reqId: Int, command: Int, message: String) {
        respond(reqId: reqId, command: command, status: 1, body: SysExProtocol.asciiBytes(message))
    }

    // ── Byte helpers ────────────────────────────────────────────────────────

    private func u16(_ b: [UInt8], _ at: Int) -> Int {
        (Int(b[at]) << 8) | Int(b[at + 1])
    }

    private func u32(_ b: [UInt8], _ at: Int) -> Int {
        (Int(b[at]) << 24) | (Int(b[at + 1]) << 16) | (Int(b[at + 2]) << 8) | Int(b[at + 3])
    }

    private func appendU16(_ out: inout [UInt8], _ v: Int) {
        out.append(UInt8((v >> 8) & 0xFF))
        out.append(UInt8(v & 0xFF))
    }

    private func appendU32(_ out: inout [UInt8], _ v: Int) {
        out.append(UInt8((v >> 24) & 0xFF))
        out.append(UInt8((v >> 16) & 0xFF))
        out.append(UInt8((v >> 8) & 0xFF))
        out.append(UInt8(v & 0xFF))
    }
}

#endif
