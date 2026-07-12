import Foundation
import Observation

/// Errors surfaced by MIDIRepository file operations.
///
/// Kotlin counterparts: `IllegalStateException` → `noOutputPort` / `invalidState`;
/// `java.io.IOException(deviceMessage)` → `deviceRejected`.
enum MIDIRepositoryError: Error, Equatable {
    case noOutputPort
    case invalidState(String)
    /// The device rejected an upload with a human-readable reason (e.g. "not enough space").
    case deviceRejected(String)
}

extension MIDIRepositoryError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .noOutputPort: return "No EP-133 connected"
        case .invalidState(let m), .deviceRejected(let m): return m
        }
    }
}

/// Non-reentrant async mutex mirroring kotlinx.coroutines.sync.Mutex as MIDIRepository uses it.
///
/// Main-actor isolation alone does NOT serialize whole file ops — every `await` inside an op is
/// a suspension point where another op can interleave — so the op-level mutual exclusion is
/// load-bearing exactly as it is in Kotlin. Same discipline applies: NOT reentrant, so locked
/// bodies must call *NoLock helpers, never `withLock` again.
///
/// Deviation from kotlinx Mutex: a task cancelled while suspended in `lock()` still acquires the
/// lock when its turn comes (the op body then fails fast on its first cancellable await) instead
/// of aborting in the queue. Same eventual outcome, slightly later.
@MainActor
final class AsyncMutex {
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    private func lock() async {
        if !locked {
            locked = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func unlock() {
        if waiters.isEmpty {
            locked = false
        } else {
            waiters.removeFirst().resume()
        }
    }

    func withLock<T>(_ body: @MainActor () async throws -> T) async rethrows -> T {
        await lock()
        defer { unlock() }
        return try await body()
    }
}

/// High-level MIDI interface for the EP-133.
///
/// Mirrors AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt
/// (hardware-verified ground truth — byte- and semantics-identical protocol behavior).
///
/// Translation map from the Kotlin original:
/// - `StateFlow` properties → `@Observable` stored properties (same names/shapes)
/// - `SharedFlow<MidiEvent>` → `incomingMidi` AsyncStream subscriptions
/// - suspend funs → `async throws` funcs (only cancellation and programming errors throw
///   where Kotlin returned a default; the defaults are preserved at the same catch sites)
/// - `Mutex` + atomics → main-actor isolation + `AsyncMutex` for file-op serialization
///   (MIDIPort delivers callbacks on the main thread, so no other locking is needed)
///
/// Not a final class: protocol tests subclass it and override `dispatchSysEx`,
/// `resolveNodeId`, and `resolveSoundsNodeId` — the same seams the Kotlin tests use.
@MainActor
@Observable
class MIDIRepository {

    /// The underlying MIDI port (Kotlin `midiManager`). Swapped at runtime by `swapPort`.
    @ObservationIgnored private var port: MIDIPort

    // ── Observable state (Kotlin StateFlows) ─────────────────────────────────

    /// Current device connection state (Kotlin `deviceState: StateFlow<DeviceState>`).
    /// Internal setter is the test seam replacing Kotlin's `protected _deviceState`.
    var deviceState = DeviceState()

    /// Currently selected MIDI channel 0–15 (Kotlin `channelFlow` + `channel`).
    private(set) var channel: Int = 0

    /// Currently selected scale for scale-lock highlighting. Nil = no scale (all pads normal).
    private(set) var selectedScale: Scale?

    /// Currently selected root note for scale-lock.
    private(set) var selectedRootNote: String = "C"

    func setScale(_ scale: Scale?) { selectedScale = scale }
    func setRootNote(_ note: String) { selectedRootNote = note }

    func setChannel(_ ch: Int) {
        channel = min(max(ch, 0), 15)
    }

    // ── Incoming MIDI events (Kotlin `incomingMidi: SharedFlow<MidiEvent>`) ──

    /// Incoming MIDI event: status type, note, velocity, channel (Kotlin `MidiEvent`).
    struct MIDIEvent: Equatable {
        let status: Int
        let note: Int
        let velocity: Int
        let channel: Int
    }

    @ObservationIgnored private var midiEventSubscribers: [UUID: AsyncStream<MIDIEvent>.Continuation] = [:]

    /// Each access returns a fresh subscription stream (SharedFlow semantics: every collector
    /// sees every event; extraBufferCapacity=64 → bufferingNewest(64), drop on overflow).
    var incomingMidi: AsyncStream<MIDIEvent> {
        AsyncStream(bufferingPolicy: .bufferingNewest(64)) { continuation in
            let id = UUID()
            midiEventSubscribers[id] = continuation
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.midiEventSubscribers[id] = nil }
            }
        }
    }

    private func emitMidiEvent(_ event: MIDIEvent) {
        for continuation in midiEventSubscribers.values {
            continuation.yield(event)
        }
    }

    // ── File-op infrastructure ────────────────────────────────────────────────

    /// Serializes all device file operations (see AsyncMutex doc — NOT reentrant; locked
    /// bodies call *NoLock helpers only).
    @ObservationIgnored private let fileOpMutex = AsyncMutex()

    /// Monotonic request-ID counter for all FILE ops. Wraps in 100..2046; skips 1 (greet)
    /// and 30..99 (owned by putSampleFile's transfer-local counter). A single shared counter
    /// means every FILE frame in flight has a globally unique reqId — stale or duplicate
    /// responses from a prior op can never satisfy a different op's waiter.
    @ObservationIgnored private var fileReqIdCounter = MIDIRepository.FILE_REQ_ID_INITIAL

    private func nextFileReqId() -> Int {
        while true {
            let next = fileReqIdCounter >= Self.FILE_REQ_ID_MAX ? Self.FILE_REQ_ID_MIN : fileReqIdCounter + 1
            fileReqIdCounter = next
            if next == 1 || (30...99).contains(next) { continue }
            return next
        }
    }

    /// Entry guard for the active-group poll: checked BEFORE acquiring fileOpMutex so a queued
    /// poll returns nil immediately instead of stacking up behind a running one.
    @ObservationIgnored private var activeGroupPollInFlight = false

    // ── Active-group structure caches ─────────────────────────────────────────
    // groupsNodeCache:    activeProjNodeId → groupsNodeId  (resolved once per project)
    // groupNodeNameCache: groupsNodeId     → (groupNodeId → name "A".."D")
    // Both invalidated on device greet (new connection) and on swapPort.
    @ObservationIgnored private var groupsNodeCache: [Int: Int] = [:]
    @ObservationIgnored private var groupNodeNameCache: [Int: [Int: String]] = [:]

    // ── SysEx accumulation buffer (D-09, D-10) ──
    @ObservationIgnored private var sysExBuffer: [UInt8] = []
    @ObservationIgnored private var inSysEx = false

    // ── Channel message partial-byte buffer ──
    @ObservationIgnored private var channelBuffer: [UInt8] = []

    // ── SysEx response correlation (D-12 / backlog 999.4) ──
    @ObservationIgnored private let fileWaiters = FileWaiterRegistry()
    @ObservationIgnored private var pendingGreetDeferred: CompletableDeferred<[String: String]>?
    @ObservationIgnored private var currentDeviceId: Int = 0
    @ObservationIgnored private var statsQueryInFlight = false

    // ── GREET idempotency (issue #27) — DRAFT, hardware-gated ──
    // The device double-sends responses, and the stats query self-issues a GREET. A second GREET
    // copy processed after a stats query has advanced into file-session init/ops would fail the
    // in-flight waiter and tear the session down. We swallow a greet whose signature matches the
    // previous one within greetDedupWindowMs (an echo); a genuine reconnect greet arrives well
    // after the window and still resets. This does NOT gate on solicited-vs-unsolicited, so an
    // unsolicited reconnect greet is unaffected. Clock is injectable so the decision is testable.
    @ObservationIgnored var greetClockMs: () -> Double = {
        Double(DispatchTime.now().uptimeNanoseconds) / 1_000_000.0
    }
    @ObservationIgnored private var lastGreetSignature: Int?
    @ObservationIgnored private var lastGreetAtMs: Double = 0

    nonisolated static let greetDedupWindowMs: Double = 500

    /// Stable signature of a greet: adopted device id + its payload bytes (deterministic, unlike
    /// Swift's per-run-seeded `hashValue`). Pure, so nonisolated (callable from tests).
    nonisolated static func greetSignature(deviceId: Int, payload: [UInt8]) -> Int {
        var h = deviceId &* 31
        for b in payload { h = h &* 31 &+ Int(b) }
        return h
    }

    /// True if this greet is a duplicate echo of the previous one: same signature and within
    /// `greetDedupWindowMs`. False on the first greet (`lastSignature` nil) and for a
    /// genuinely-later greet with the same signature (a reconnect), which must still reset.
    nonisolated static func isDuplicateGreet(signature: Int, nowMs: Double, lastSignature: Int?, lastAtMs: Double) -> Bool {
        signature == lastSignature && (nowMs - lastAtMs) < greetDedupWindowMs
    }

    // ── FILE_INIT session state (hardware-required handshake, once per connection) ──
    @ObservationIgnored private var fileSessionInitialized = false
    @ObservationIgnored private var deviceChunkSize: Int = 512

    @ObservationIgnored private var isRefreshing = false
    @ObservationIgnored private var autoStatsTask: Task<Void, Never>?

    // ── Timeouts ──────────────────────────────────────────────────────────────
    // Kotlin companion-object constants (ms) as instance vars (seconds). Vars, not lets:
    // the Kotlin tests fast-forward kotlinx virtual time; XCTest has no virtual clock, so
    // timeout-path tests shorten these instead.
    @ObservationIgnored var greetTimeout: TimeInterval = 5.0
    @ObservationIgnored var getInitTimeout: TimeInterval = 5.0
    @ObservationIgnored var getPageTimeout: TimeInterval = 5.0
    @ObservationIgnored var putAckTimeout: TimeInterval = 15.0
    @ObservationIgnored var fileListTimeout: TimeInterval = 5.0
    @ObservationIgnored var metadataTimeout: TimeInterval = 5.0
    @ObservationIgnored var fileInitTimeout: TimeInterval = 5.0
    @ObservationIgnored var forceCloseTimeout: TimeInterval = 2.0

    /// putSampleFile / putProjectArchive transfer-local reqId counter starts here (INIT=30,
    /// DATA pages 31, 32, …). Stepped with `nextTransferReqId` so it stays inside the 11-bit
    /// wire field; nextFileReqId() skips 30..99 so the low end never aliases another op.
    static let PUT_INIT_REQUEST_ID = 30

    /// Step a transfer-local reqId to the next value that fits the wire.
    ///
    /// `buildFrame` encodes only 11 bits of the reqId (flags nibble `>> 7` + 7-bit low byte),
    /// so anything above 2046 wraps on the wire and the device's echoed ack decodes to a
    /// different id than the waiter was registered under — the ack is dropped and the page
    /// times out. A long upload (mono > ~9s, stereo > ~4.6s) reaches page 2048 and fails
    /// mid-transfer. Wrapping back to FILE_REQ_ID_MIN keeps every page in range; reuse across
    /// pages within one transfer is safe because a transfer awaits each page's ack before
    /// sending the next, so only one waiter is ever live (fileOpMutex also serializes transfers).
    static func nextTransferReqId(_ current: Int) -> Int {
        current >= FILE_REQ_ID_MAX ? FILE_REQ_ID_MIN : current + 1
    }

    // nextFileReqId() counter bounds — 11-bit space, 0/2047 reserved, 1 and 30..99 skipped.
    static let FILE_REQ_ID_MIN = 100
    static let FILE_REQ_ID_MAX = 2046
    static let FILE_REQ_ID_INITIAL = 100

    // ── Init / port wiring ────────────────────────────────────────────────────

    init(_ port: MIDIPort) {
        self.port = port
        wireCallbacks(port)
    }

    private func wireCallbacks(_ p: MIDIPort) {
        // MIDIPort contract: callbacks arrive on the main thread, so assumeIsolated is safe.
        p.onDevicesChanged = { [weak self] in
            MainActor.assumeIsolated { self?.updateDeviceStateOnly() }
        }
        p.onMIDIReceived = { [weak self] _, data in
            MainActor.assumeIsolated { self?.parseMidiInput(data) }
        }
    }

    /// Updates state and re-establishes listeners on new devices.
    private func updateDeviceStateOnly() {
        let devices = port.getUSBDevices()
        let wasConnected = deviceState.connected
        let connected = !devices.inputs.isEmpty || !devices.outputs.isEmpty
        let outputPort = devices.outputs.first
        var s = deviceState
        s.connected = connected
        s.deviceName = outputPort?.name ?? ""
        s.outputPortId = outputPort?.id
        s.inputPorts = devices.inputs
        s.outputPorts = devices.outputs
        deviceState = s
        // Kotlin closes stale listeners then re-listens and pre-warms the send port; those are
        // Android USB-MIDI concerns. The iOS MIDIManager auto-connects, so re-listen alone.
        // permissionState is left untouched — CoreMIDI has no runtime permission model.
        for input in devices.inputs {
            port.startListening(portId: input.id)
        }

        // Auto-trigger stats query on device connect (D-13).
        if connected && !wasConnected {
            autoStatsTask = Task { [weak self] in
                _ = try? await self?.queryDeviceStats()
            }
        }
    }

    /// Refresh device state from the port. (Android's `refreshDevices()` re-enumeration has
    /// no iOS counterpart — CoreMIDI pushes device changes — so this just re-reads the list.)
    func refreshDeviceState() {
        if isRefreshing { return }
        isRefreshing = true
        defer { isRefreshing = false }
        let devices = port.getUSBDevices()
        let connected = !devices.inputs.isEmpty || !devices.outputs.isEmpty
        let outputPort = devices.outputs.first
        var s = deviceState
        s.connected = connected
        s.deviceName = outputPort?.name ?? ""
        s.outputPortId = outputPort?.id
        s.inputPorts = devices.inputs
        s.outputPorts = devices.outputs
        deviceState = s
    }

    /// Swap the underlying MIDI port at runtime (debug-only Simulated EP-133 toggle seam).
    ///
    /// Detaches the old port's callbacks, resets per-connection session state so the new port
    /// gets a fresh FILE_INIT handshake, rewires callbacks exactly like init, and re-enumerates.
    /// Does NOT close either port — the caller owns port lifecycles, so the real MIDIManager
    /// survives a round trip to the simulator and back.
    func swapPort(newPort: MIDIPort) {
        port.onMIDIReceived = nil
        port.onDevicesChanged = nil
        port = newPort
        fileSessionInitialized = false
        groupsNodeCache.removeAll()
        groupNodeNameCache.removeAll()
        deviceState = DeviceState()
        wireCallbacks(newPort)
        refreshDeviceState()
    }

    /// The currently active port. Read-only seam for the debug Simulated EP-133 toggle: the
    /// composition point captures the real CoreMIDI port here before `swapPort` installs a
    /// simulator, so deactivation can restore it. All swapping still goes through `swapPort`.
    var activePort: MIDIPort { port }

    // ── MIDI input parsing ────────────────────────────────────────────────────

    /// Byte-by-byte MIDI input processor with SysEx accumulation.
    ///
    /// - 0xF0 starts SysEx accumulation
    /// - 0xF7 ends SysEx and dispatches the complete message
    /// - All other bytes during SysEx accumulation are buffered
    /// - Non-SysEx bytes are passed to the channel message parser
    private func parseMidiInput(_ data: [UInt8]) {
        for b in data {
            if b == 0xF0 {
                sysExBuffer.removeAll(keepingCapacity: true)
                sysExBuffer.append(b)
                inSysEx = true
            } else if inSysEx && b == 0xF7 {
                sysExBuffer.append(b)
                inSysEx = false
                let complete = sysExBuffer
                sysExBuffer.removeAll(keepingCapacity: true)
                dispatchSysEx(complete)
            } else if inSysEx {
                sysExBuffer.append(b)
            } else {
                parseChannelMessageByte(b)
            }
        }
    }

    /// Accumulate channel message bytes (status + data bytes) and dispatch complete messages.
    /// Status bytes (high bit set) reset the buffer. Channel messages are 2–3 bytes.
    private func parseChannelMessageByte(_ b: UInt8) {
        if b & 0x80 != 0 {
            channelBuffer.removeAll(keepingCapacity: true)
        }
        channelBuffer.append(b)

        guard let first = channelBuffer.first else { return }
        let status = Int(first)
        let type = status & 0xF0

        // 3-byte: Note On/Off, Poly AT, CC, Pitch Bend. 2-byte: PC, Channel Pressure.
        let expectedLen: Int
        switch type {
        case 0x80, 0x90, 0xA0, 0xB0, 0xE0: expectedLen = 3
        case 0xC0, 0xD0: expectedLen = 2
        default: return
        }

        if channelBuffer.count >= expectedLen {
            let ch = status & 0x0F
            let note = channelBuffer.count > 1 ? Int(channelBuffer[1]) & 0x7F : 0
            let velocity = channelBuffer.count > 2 ? Int(channelBuffer[2]) & 0x7F : 0
            if type == 0x90 || type == 0x80 || type == 0xC0 {
                emitMidiEvent(MIDIEvent(status: type, note: note, velocity: velocity, channel: ch))
            }
            channelBuffer.removeAll(keepingCapacity: true)
        }
    }

    /// Dispatch a complete SysEx message. Routes GREET to the pending greet deferred and FILE
    /// (cmd=5) responses to their reqId-keyed waiters. Overridable — the accumulator tests
    /// subclass and capture messages here (Kotlin `protected open fun dispatchSysEx`).
    func dispatchSysEx(_ message: [UInt8]) {
        if message.count < 10 { return }
        let isTEManufacturer = message[1] == SysExProtocol.TE_ID_0 &&
            message[2] == SysExProtocol.TE_ID_1 &&
            message[3] == SysExProtocol.TE_ID_2
        if !isTEManufacturer { return }
        let command = Int(message[8])
        let payload: [UInt8] = message.count > 10 ? Array(message[9..<(message.count - 1)]) : []

        switch command {
        case SysExProtocol.CMD_GREET:
            // Adopt the device's real ID from the greet response so requests echo it back.
            let reportedDeviceId = Int(message[4]) & 0x7F
            if reportedDeviceId != 0 {
                currentDeviceId = reportedDeviceId
            }
            // Reset file session and structure caches on new greet (new connection), and fail
            // any file ops still awaiting so they don't hang to timeout against a reset device —
            // but skip a duplicate greet echo so it can't tear down an in-flight file session
            // that started after the first copy (issue #27).
            let signature = Self.greetSignature(deviceId: reportedDeviceId, payload: payload)
            let now = greetClockMs()
            let duplicate = Self.isDuplicateGreet(
                signature: signature, nowMs: now,
                lastSignature: lastGreetSignature, lastAtMs: lastGreetAtMs)
            lastGreetSignature = signature
            lastGreetAtMs = now
            if duplicate {
                EP133Log.debug(.app, "GREET: duplicate within \(Self.greetDedupWindowMs)ms - skipping session reset (issue #27)")
            } else {
                fileSessionInitialized = false
                groupsNodeCache.removeAll()
                groupNodeNameCache.removeAll()
                fileWaiters.failAll(MIDIRepositoryError.invalidState("device greet/reconnect"))
            }

            let parsed = SysExProtocol.parseGreetResponse(payload)
            pendingGreetDeferred?.complete(parsed)
            pendingGreetDeferred = nil

        case SysExProtocol.TE_SYSEX_FILE:
            // Hardware-verified: responses carry a STATUS byte BEFORE the 7-bit-packed body
            // (payload[0] = status; packed body starts at payload[1]). Do NOT early-return on
            // an empty body — a status-only response is a valid PUT DATA page ack. Guard only
            // when there is no status byte at all.
            if payload.isEmpty { return }
            let fileStatus = Int(payload[0])
            let packedBody: [UInt8] = payload.count > 1 ? Array(payload[1...]) : []
            let body = packedBody.isEmpty ? [] : SysExProtocol.unpack7bit(packedBody)

            // reqId-first routing: every file op registers a waiter under its unique reqId; an
            // unmatched reqId is a stale or duplicate response (the device sends each response
            // twice) and is dropped.
            let responseReqId = SysExProtocol.decodeRequestId(flags: Int(message[6]), low: Int(message[7]))
            _ = fileWaiters.route(FileResponse(reqId: responseReqId, status: fileStatus, body: body))

        default:
            break
        }
    }

    // ── Device stats (D-12) ───────────────────────────────────────────────────

    /// Query real device stats from the EP-133:
    /// - GREET → firmwareVersion
    /// - FILE_LIST on /sounds → sampleCount
    /// - METADATA on /sounds → storage (used = max_capacity − free_space_in_bytes)
    ///
    /// Returns true if GREET succeeded; false on timeout, overlap, or no output port.
    func queryDeviceStats() async throws -> Bool {
        guard let portId = deviceState.outputPortId else { return false }
        // Guard against overlapping queries (e.g. rapid disconnect/reconnect).
        if statsQueryInFlight { return false }
        return try await fileOpMutex.withLock {
            // Re-check after acquiring the lock — another call may have started while we waited.
            if statsQueryInFlight { return false }
            statsQueryInFlight = true
            defer { statsQueryInFlight = false }
            return try await queryDeviceStatsInner(portId)
        }
    }

    private func queryDeviceStatsInner(_ portId: String) async throws -> Bool {
        // Step 1: GREET (firmware + device identity). reqId=1 is the conventional greet ID;
        // greet uses CMD_GREET, not TE_SYSEX_FILE, so it does not draw from nextFileReqId().
        let greetDeferred = CompletableDeferred<[String: String]>()
        pendingGreetDeferred = greetDeferred
        let greetFrame = SysExProtocol.buildGreetFrame(deviceId: currentDeviceId, requestId: 1)
        port.sendMIDI(to: portId, data: greetFrame)
        let greetResult: [String: String]
        do {
            greetResult = try await greetDeferred.value(timeout: greetTimeout)
        } catch FileWaiterError.timeout {
            pendingGreetDeferred = nil
            return false
        }
        deviceState.firmwareVersion = greetResult["sw_version"] ?? ""

        // Steps 2–3: sample count + storage from /sounds over the reqId-correlated file ops.
        _ = try await ensureFileSessionInitNoLock()
        if let soundsNode = try await resolveNodeIdInternal("/sounds") {
            // Sample count = entries in /sounds (named files only).
            if let body = try await listNodeBody(soundsNode, requestId: nextFileReqId()) {
                let count = SysExProtocol.parseFileListEntries(body)
                    .filter { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty }
                    .count
                deviceState.sampleCount = count
            }
            // Storage from /sounds metadata: device reports max_capacity + free_space_in_bytes
            // (hardware-verified 2026-06-27), so used = max − free.
            let meta = try await getMetadataJson(soundsNode)
            let total = jsonInt64(meta, "max_capacity").flatMap { $0 >= 0 ? $0 : nil }
            let free = jsonInt64(meta, "free_space_in_bytes").flatMap { $0 >= 0 ? $0 : nil }
            if let total {
                deviceState.storageUsedBytes = free.map { max(total - $0, 0) }
                deviceState.storageTotalBytes = total
            }
        }

        return true
    }

    // ── Paged project archive transfer (Phase 4 GATE) ─────────────────────────

    /// Download a full project archive via the device's two-phase INIT/DATA protocol.
    ///
    /// Sends GET_INIT, awaits the parsed {fileSize, fileName}, then loops GET_DATA(page)
    /// requests until fileSize bytes are assembled or an empty-data page terminates early.
    /// Each DATA response must match the expected page (mismatch throws). Returns the
    /// assembled `.tar` bytes; throws `MIDIRepositoryError.invalidState` on page mismatch,
    /// buffer overflow, timeout, or device error.
    func getProjectArchive(nodeId: Int) async throws -> [UInt8] {
        guard let portId = deviceState.outputPortId else {
            throw MIDIRepositoryError.noOutputPort
        }
        return try await fileOpMutex.withLock { () async throws -> [UInt8] in
            // INIT: one request → one response (OneShot via the registry).
            let initReqId = nextFileReqId()
            let initFrame = SysExProtocol.buildFileGetInitFrame(
                deviceId: currentDeviceId, nodeId: nodeId, requestId: initReqId)
            guard let initResp = try await awaitFileOp(
                reqId: initReqId, kind: .getInit, portId: portId,
                frame: initFrame, timeout: getInitTimeout)
            else {
                throw MIDIRepositoryError.invalidState("GET INIT timed out")
            }
            let initInfo = try SysExProtocol.parseGetInitResponse(initResp.body)

            var out = [UInt8]()
            out.reserveCapacity(max(initInfo.fileSize, 0))
            let cap = initInfo.fileSize + SysExProtocol.MAX_PAGE_BYTES
            var page = 0
            while out.count < initInfo.fileSize {
                // Each GET_DATA page is its own request → its own response (OneShot by reqId).
                let dataReqId = nextFileReqId()
                let dataFrame = SysExProtocol.buildFileGetDataFrame(
                    deviceId: currentDeviceId, page: page, requestId: dataReqId)
                guard let resp = try await awaitFileOp(
                    reqId: dataReqId, kind: .getData, portId: portId,
                    frame: dataFrame, timeout: getPageTimeout)
                else {
                    throw MIDIRepositoryError.invalidState("GET DATA page \(page) timed out")
                }
                if resp.status != SysExProtocol.STATUS_OK &&
                    resp.status < SysExProtocol.STATUS_SPECIFIC_SUCCESS_START {
                    throw MIDIRepositoryError.invalidState(
                        "GET DATA page \(page) device error status \(resp.status)")
                }
                let data = try SysExProtocol.parseGetDataResponse(resp.body)
                guard data.page == page else {
                    throw MIDIRepositoryError.invalidState("unexpected page \(data.page), expected \(page)")
                }
                if data.data.isEmpty { break }
                guard out.count + data.data.count <= cap else {
                    throw MIDIRepositoryError.invalidState(
                        "GET overflow: \(out.count + data.data.count) bytes exceeds cap \(cap)")
                }
                out.append(contentsOf: data.data)
                page = data.nextPage
            }
            return out
        }
    }

    /// True if a PUT ack FileResponse signals success (STATUS_OK or a continuation status).
    private func putAckOk(_ resp: FileResponse?) -> Bool {
        guard let resp else { return false }
        return resp.status == SysExProtocol.STATUS_OK ||
            resp.status >= SysExProtocol.STATUS_SPECIFIC_SUCCESS_START
    }

    /// Printable ASCII text from a device error response body, or nil if there is none.
    /// The EP-133 returns a human-readable reason on rejection (e.g. "not enough space").
    private func deviceErrorText(_ resp: FileResponse?) -> String? {
        guard let body = resp?.body, !body.isEmpty else { return nil }
        let printable = SysExProtocol.asciiString(body).unicodeScalars
            .filter { (32...126).contains($0.value) }
            .map(Character.init)
        let text = String(printable).trimmingCharacters(in: .whitespaces)
        return text.isEmpty ? nil : text
    }

    /// Free device sample storage in bytes from the latest device stats, or nil if unknown.
    func availableStorageBytes() -> Int64? {
        guard let used = deviceState.storageUsedBytes,
              let total = deviceState.storageTotalBytes else { return nil }
        return max(total - used, 0)
    }

    /// Upload a project archive via the device's two-phase INIT/DATA protocol (restore).
    func putProjectArchive(slotNodeId: Int, tarBytes: [UInt8]) async throws -> Bool {
        guard let portId = deviceState.outputPortId else {
            throw MIDIRepositoryError.noOutputPort
        }
        return try await fileOpMutex.withLock { () async throws -> Bool in
            var nextReqId = Self.PUT_INIT_REQUEST_ID
            let initReqId = nextReqId; nextReqId = Self.nextTransferReqId(nextReqId)
            let initFrame = SysExProtocol.buildFilePutInitFrame(
                deviceId: currentDeviceId, nodeId: slotNodeId,
                fileSize: tarBytes.count, requestId: initReqId)
            if !putAckOk(try await awaitFileOp(
                reqId: initReqId, kind: .putInit, portId: portId,
                frame: initFrame, timeout: putAckTimeout)) {
                return false
            }
            var page = 0
            var offset = 0
            while offset < tarBytes.count {
                let end = min(offset + SysExProtocol.MAX_PAGE_BYTES, tarBytes.count)
                let chunk = Array(tarBytes[offset..<end])
                let dataReqId = nextReqId; nextReqId = Self.nextTransferReqId(nextReqId)
                let dataFrame = SysExProtocol.buildFilePutDataFrame(
                    deviceId: currentDeviceId, page: page, chunk: chunk, requestId: dataReqId)
                if !putAckOk(try await awaitFileOp(
                    reqId: dataReqId, kind: .putData, portId: portId,
                    frame: dataFrame, timeout: putAckTimeout)) {
                    return false
                }
                offset = end
                page = (page + 1) & 0xFFFF
            }
            let termReqId = nextReqId
            let termFrame = SysExProtocol.buildFilePutDataFrame(
                deviceId: currentDeviceId, page: page, chunk: [], requestId: termReqId)
            return putAckOk(try await awaitFileOp(
                reqId: termReqId, kind: .putData, portId: portId,
                frame: termFrame, timeout: putAckTimeout))
        }
    }

    // ── Sample file upload to /sounds (Phase 5 Wave 2, SAMPLE-03) ─────────────

    /// Upload raw s16 LE PCM bytes to /sounds by creating a new file via the device's node-ID
    /// INIT protocol (data/index.js uploadSound / fileHandler.put, verified verbatim).
    ///
    /// Returns the device-assigned node ID on success (parsed from the INIT response
    /// body[0..1] u16 BE — hardware-verified 2026-06-29 — or resolved via `/sounds/<name>`
    /// when the body lacks it). Nil on timeout / device error / unresolvable node; throws
    /// `deviceRejected` when the device supplies a reason, `noOutputPort` when disconnected.
    func putSampleFile(
        name: String,
        pcmBytes: [UInt8],
        channels: Int = 1,
        sampleRate: Int = EP133Device.sampleRate
    ) async throws -> Int? {
        guard let portId = deviceState.outputPortId else {
            throw MIDIRepositoryError.noOutputPort
        }
        return try await fileOpMutex.withLock { () async throws -> Int? in
            // Ensure the FILE_INIT handshake (once per connection) before resolving node IDs.
            _ = try await ensureFileSessionInitNoLock()

            guard let parent = try await resolveSoundsNodeId() else {
                return nil
            }

            // ~420-byte raw chunks (within the device's 512-byte budget) get STATUS_OK; 4096
            // was rejected ("unexpected page") because it 7-bit-packs past the budget.
            let rawChunkSize = computeSampleChunkSize(deviceChunkSize)

            // Metadata JSON must match the hardware-verified upload: channels/samplerate alone
            // get the PUT INIT rejected — the device needs format, name, and sound.playmode.
            let metaName = name.substringBeforeLast(".")
            let metadataJson =
                "{\"channels\":\(channels),\"samplerate\":\(sampleRate),\"format\":\"s16\",\"name\":\"\(metaName)\",\"sound.playmode\":\"oneshot\"}"

            // Transfer-local reqId counter (INIT=30, then each DATA page + terminator).
            var nextReqId = Self.PUT_INIT_REQUEST_ID
            var initAcked = false
            var assignedNodeId: Int?
            do {
                // INIT: announce parent dir, fileId=0 (new file), size, filename, metadata.
                let initReqId = nextReqId; nextReqId = Self.nextTransferReqId(nextReqId)
                let initFrame = SysExProtocol.buildFileCreatePutInitFrame(
                    deviceId: currentDeviceId,
                    parentNodeId: parent,
                    fileSize: pcmBytes.count,
                    filename: name,
                    requestId: initReqId,
                    metadataJson: metadataJson)
                let initResp = try await awaitFileOp(
                    reqId: initReqId, kind: .putInit, portId: portId,
                    frame: initFrame, timeout: putAckTimeout)
                if !putAckOk(initResp) {
                    // Surface the device's own reason (e.g. "not enough space"); fall back to
                    // nil only when the device said nothing (genuine timeout).
                    if let devMsg = deviceErrorText(initResp) {
                        throw MIDIRepositoryError.deviceRejected(devMsg)
                    }
                    return nil
                }
                // Device-assigned node ID from body[0..1] u16 BE (hardware returned 193).
                if let initResp, initResp.body.count >= 2 {
                    assignedNodeId = (Int(initResp.body[0]) << 8) | Int(initResp.body[1])
                }
                initAcked = true

                // DATA pages: serial, awaiting each page's ack before sending the next
                // (USB-MIDI has no flow control; the reference tool sends serially with await).
                var page = 0
                var offset = 0
                while offset < pcmBytes.count {
                    let end = min(offset + rawChunkSize, pcmBytes.count)
                    let chunk = Array(pcmBytes[offset..<end])
                    let dataReqId = nextReqId; nextReqId = Self.nextTransferReqId(nextReqId)
                    let dataFrame = SysExProtocol.buildFilePutDataFrame(
                        deviceId: currentDeviceId, page: page, chunk: chunk, requestId: dataReqId)
                    let dataResp = try await awaitFileOp(
                        reqId: dataReqId, kind: .putData, portId: portId,
                        frame: dataFrame, timeout: putAckTimeout)
                    if !putAckOk(dataResp) {
                        let devMsg = deviceErrorText(dataResp)
                        let closeReqId = nextReqId; nextReqId = Self.nextTransferReqId(nextReqId)
                        await forceCloseTransfer(portId: portId, terminatorPage: page + 1, reqId: closeReqId)
                        if let devMsg {
                            throw MIDIRepositoryError.deviceRejected(devMsg)
                        }
                        return nil
                    }
                    offset = end
                    page = (page + 1) & 0xFFFF
                }

                // Zero-length DATA terminator (required by the reference tool). Await its ack.
                let termReqId = nextReqId; nextReqId = Self.nextTransferReqId(nextReqId)
                let terminatorFrame = SysExProtocol.buildFilePutDataFrame(
                    deviceId: currentDeviceId, page: page, chunk: [], requestId: termReqId)
                if putAckOk(try await awaitFileOp(
                    reqId: termReqId, kind: .putData, portId: portId,
                    frame: terminatorFrame, timeout: putAckTimeout)) {
                    // Callers bind pads to this ID, so an unverifiable ID must read as failure:
                    // INIT-body ID first, path resolution second, nil (failure) last.
                    if let assignedNodeId { return assignedNodeId }
                    return try await resolveNodeIdInternal("/sounds/\(name)")
                } else {
                    return nil
                }
            } catch let e where e is CancellationError {
                // A dangling incomplete PUT wedges the device — best-effort close if INIT acked.
                if initAcked {
                    await forceCloseTransfer(portId: portId, terminatorPage: 0, reqId: nextReqId)
                }
                throw e
            }
        }
    }

    /// Best-effort: send a zero-length DATA terminator to close an incomplete PUT transfer.
    ///
    /// A dangling incomplete PUT wedges the device — it ignores subsequent PUTs until
    /// power-cycle (hardware-proven, 2026-06-24). Short timeout, all errors ignored.
    private func forceCloseTransfer(portId: String, terminatorPage: Int, reqId: Int) async {
        do {
            let frame = SysExProtocol.buildFilePutDataFrame(
                deviceId: currentDeviceId, page: terminatorPage, chunk: [], requestId: reqId)
            _ = try await awaitFileOp(
                reqId: reqId, kind: .putData, portId: portId,
                frame: frame, timeout: forceCloseTimeout)
        } catch {
            // Best-effort — ignore all errors (Kotlin catches CancellationException here too).
        }
    }

    /// Compute the raw PCM bytes per DATA page for /sounds uploads, bounded by the device's
    /// negotiated chunk size from the FILE_INIT handshake.
    ///
    /// Mirrors the reference tool's calculateMaxPayloadLength formula (data/index.js), with the
    /// JS single-letter names spelled out:
    ///   envelopeOverhead = 11; chunkBudget = chunkSize − 6; inner = chunkBudget − 1 − envelopeOverhead;
    ///   maxPayload = inner − inner/8 (7-bit packing expands by 1/8); raw = maxPayload − 6.
    /// Clamped to [64, 440]. Falls back to 256 when chunkSize is 0 or unknown.
    func computeSampleChunkSize(_ chunkSize: Int) -> Int {
        if chunkSize <= 0 { return 256 }
        let envelopeOverhead = 11
        let chunkBudget = chunkSize - 6
        let inner = chunkBudget - 1 - envelopeOverhead
        if inner <= 0 { return 64 }
        let maxPayload = inner - (inner / 8)
        let raw = maxPayload - 6
        return min(max(raw, 64), 440)
    }

    // ── FILE_INIT session handshake (once per connection) ─────────────────────

    /// Ensure the FILE_INIT handshake has been completed for this connection.
    ///
    /// Hardware-verified: the device returns "can't list unless initialized" until a FILE_INIT
    /// (subcommand 1) is sent. Resets on greet (new connection) and swapPort.
    ///
    /// This public entry point acquires fileOpMutex. Code that already holds the mutex MUST
    /// call `ensureFileSessionInitNoLock` directly to avoid a re-entrancy deadlock.
    func ensureFileSessionInit() async throws -> Bool {
        try await fileOpMutex.withLock { try await ensureFileSessionInitNoLock() }
    }

    /// NoLock core for `ensureFileSessionInit`. MUST only be called while holding fileOpMutex.
    private func ensureFileSessionInitNoLock() async throws -> Bool {
        if fileSessionInitialized { return true }
        guard let portId = deviceState.outputPortId else { return false }
        let reqId = nextFileReqId()
        let frame = SysExProtocol.buildFileInitFrame(deviceId: currentDeviceId, requestId: reqId)
        let resp = try await awaitFileOp(
            reqId: reqId, kind: .fileInit, portId: portId,
            frame: frame, timeout: fileInitTimeout)
        if let resp {
            // chunkSize parse is approximate (real HW capture yields a large number —
            // tolerated). Session opening is what matters; the parser never throws.
            deviceChunkSize = SysExProtocol.parseFileInitResponse(resp.body)
            fileSessionInitialized = true
            return true
        } else {
            // Best-effort: if the device didn't respond, mark initialized so we don't loop.
            fileSessionInitialized = true
            return false
        }
    }

    // ── Project slot enumeration (Phase 4 Wave 2, PROJ-01) ────────────────────

    /// A single EP-133 project slot (one of /projects/P00 .. /projects/P08).
    struct ProjectSlot: Equatable {
        let nodeId: Int
        let name: String
        let sizeBytes: Int64
        let isActive: Bool
    }

    /// Pure decode of a FILE_LIST response body into directory entries.
    func parseFileListEntries(_ body: [UInt8]) -> [SysExProtocol.FileEntry] {
        SysExProtocol.parseFileListEntries(body)
    }

    /// Issue a node-ID FILE_LIST and return the assembled (unpacked) entry body.
    /// Body = [page u16 BE][entries...]; the 2-byte page word is stripped.
    private func listNodeBody(_ nodeId: Int, requestId: Int) async throws -> [UInt8]? {
        guard let portId = deviceState.outputPortId else { return nil }
        let frame = SysExProtocol.buildFileListByNodeFrame(
            deviceId: currentDeviceId, nodeId: nodeId, requestId: requestId)
        guard let resp = try await awaitFileOp(
            reqId: requestId, kind: .list, portId: portId,
            frame: frame, timeout: fileListTimeout)
        else { return nil }
        return resp.body.count > 2 ? Array(resp.body[2...]) : []
    }

    /// Resolve a path like "/projects" to a numeric node ID by walking segments from root
    /// (nodeId 0), matching each child name in turn. Nil if any segment is not found or the
    /// device does not respond.
    func resolveNodeId(_ path: String) async throws -> Int? {
        try await fileOpMutex.withLock { () async throws -> Int? in
            _ = try await ensureFileSessionInitNoLock()
            return try await resolveNodeIdInternal(path)
        }
    }

    /// Enumerate the 9 EP-133 project slots (PROJ-01). Resolves /projects → nodeId, reads its
    /// metadata "active" pointer, FILE_LISTs that node, and maps each child to a ProjectSlot.
    /// Empty when offline or unanswered.
    func listProjects() async throws -> [ProjectSlot] {
        if deviceState.outputPortId == nil { return [] }
        return try await fileOpMutex.withLock { () async throws -> [ProjectSlot] in
            _ = try await ensureFileSessionInitNoLock()
            guard let projectsNode = try await resolveNodeIdInternal("/projects") else { return [] }

            let activeNode = jsonInt(try await getMetadataJson(projectsNode), "active")
                .flatMap { $0 > 0 ? $0 : nil }

            guard let body = try await listNodeBody(projectsNode, requestId: nextFileReqId()) else {
                return []
            }
            return SysExProtocol.parseFileListEntries(body).map { entry in
                ProjectSlot(
                    nodeId: entry.nodeId,
                    name: entry.name,
                    sizeBytes: entry.sizeBytes,
                    isActive: activeNode != nil && entry.nodeId == activeNode)
            }
        }
    }

    /// Enumerate the EP-133 /sounds directory — every sample file as a FileEntry
    /// (nodeId + name + size). Empty when offline or unanswered.
    func listSoundEntries() async throws -> [SysExProtocol.FileEntry] {
        if deviceState.outputPortId == nil { return [] }
        return try await fileOpMutex.withLock { () async throws -> [SysExProtocol.FileEntry] in
            _ = try await ensureFileSessionInitNoLock()
            guard let soundsNode = try await resolveNodeIdInternal("/sounds") else { return [] }
            guard let body = try await listNodeBody(soundsNode, requestId: nextFileReqId()) else {
                return []
            }
            return SysExProtocol.parseFileListEntries(body)
                .filter { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty }
        }
    }

    /// Download a file node's full contents via the multi-chunk GET. Nil on timeout / device
    /// error instead of throwing, so a backup can skip one bad file and keep going.
    func getFileBytes(nodeId: Int) async throws -> [UInt8]? {
        do {
            return try await getProjectArchive(nodeId: nodeId)
        } catch let e where e is CancellationError {
            throw e
        } catch {
            return nil
        }
    }

    // ── Active-group sync: nodeId-form metadata round-trips (Step 1) ──────────

    /// Fetch metadata for nodeId using the nodeId-form GET (METADATA_GET = 2).
    ///
    /// Hardware-verified (2026-06-24): the body is a 2-byte page prefix + JSON + trailing NUL;
    /// active-group metadata fits one page, so the outermost { … } span is extracted and
    /// parsed. Falls back to greet-format `key:value` parsing when JSON parsing fails
    /// (HW-VERIFY-3). Empty dictionary on timeout or no port.
    ///
    /// MUST be called from within a fileOpMutex-locked context.
    func getMetadataJson(_ nodeId: Int) async throws -> [String: Any] {
        guard let portId = deviceState.outputPortId else { return [:] }
        let reqId = nextFileReqId()
        let frame = SysExProtocol.buildMetadataGetFrame(
            deviceId: currentDeviceId, nodeId: nodeId, page: 0, requestId: reqId)
        guard let resp = try await awaitFileOp(
            reqId: reqId, kind: .metadataGet, portId: portId,
            frame: frame, timeout: metadataTimeout)
        else { return [:] }

        let accumulated = SysExProtocol.asciiString(resp.body)
        let jsonSpan: String
        if let s = accumulated.firstIndex(of: "{"),
           let e = accumulated.lastIndex(of: "}"), s < e {
            jsonSpan = String(accumulated[s...e])
        } else {
            jsonSpan = accumulated
        }
        if let data = jsonSpan.data(using: .utf8),
           let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            return obj
        }
        // Greet-format fallback (device returned key:value text instead of JSON).
        let greetMap = SysExProtocol.parseGreetResponse(SysExProtocol.asciiBytes(accumulated))
        var result: [String: Any] = [:]
        for (k, v) in greetMap { result[k] = v }
        return result
    }

    /// Write json as the metadata for nodeId via a single METADATA SET frame (METADATA_SET = 1).
    /// True if the device responded (any ack), false on timeout.
    func setMetadata(nodeId: Int, json: String) async throws -> Bool {
        guard let portId = deviceState.outputPortId else { return false }
        let reqId = nextFileReqId()
        let frame = SysExProtocol.buildMetadataSetFrame(
            deviceId: currentDeviceId, nodeId: nodeId, json: json, requestId: reqId)
        return try await awaitFileOp(
            reqId: reqId, kind: .metadataSet, portId: portId,
            frame: frame, timeout: metadataTimeout) != nil
    }

    /// Shared spine for single-response file ops: register a OneShot waiter under reqId, send
    /// the frame, and await the response the device echoes back under that reqId. Always
    /// deregisters. Nil on timeout; rethrows cancellation and registry failures. Does NOT
    /// acquire fileOpMutex — callers that need device-access serialization hold it already.
    private func awaitFileOp(
        reqId: Int,
        kind: FileOpKind,
        portId: String,
        frame: [UInt8],
        timeout: TimeInterval
    ) async throws -> FileResponse? {
        let waiter = FileWaiter.OneShot(reqId: reqId, kind: kind)
        try fileWaiters.register(waiter)
        defer { _ = fileWaiters.remove(reqId) }
        port.sendMIDI(to: portId, data: frame)
        do {
            return try await withTaskCancellationHandler {
                try await waiter.deferred.value(timeout: timeout)
            } onCancel: {
                // Kotlin's deferred.await() is cancellable; mirror by racing a cancellation
                // completion in (first completion wins, so a device response can still land).
                Task { @MainActor in
                    waiter.deferred.completeExceptionally(CancellationError())
                }
            }
        } catch FileWaiterError.timeout {
            return nil
        }
    }

    /// FILE_INFO (getNode) round-trip. Nil on timeout or parse failure.
    func getNodeInfo(_ nodeId: Int) async throws -> SysExProtocol.NodeInfo? {
        guard let portId = deviceState.outputPortId else { return nil }
        let reqId = nextFileReqId()
        let frame = SysExProtocol.buildFileInfoFrame(
            deviceId: currentDeviceId, nodeId: nodeId, requestId: reqId)
        guard let resp = try await awaitFileOp(
            reqId: reqId, kind: .info, portId: portId,
            frame: frame, timeout: metadataTimeout)
        else { return nil }
        return try? SysExProtocol.parseFileInfo(resp.body)
    }

    /// Read the device's current active group and return its index (0=A, 1=B, 2=C, 3=D).
    ///
    /// Fast path (after first call): the heavy directory walk is cached so subsequent poll
    /// ticks issue only two METADATA GET round-trips. If a poll is already in flight the new
    /// call returns nil immediately rather than queuing behind the mutex (prevents 1.5 s poll
    /// ticks from accumulating into a backlog when the device is slow). Caches are cleared on
    /// device greet (new connection).
    func getActiveGroupIndex() async throws -> Int? {
        if deviceState.outputPortId == nil { return nil }
        if activeGroupPollInFlight { return nil }
        activeGroupPollInFlight = true
        defer { activeGroupPollInFlight = false }
        return try await fileOpMutex.withLock { () async throws -> Int? in
            _ = try await ensureFileSessionInitNoLock()
            do {
                return try await getActiveGroupIndexNoLock()
            } catch let e where e is CancellationError {
                throw e
            } catch {
                return nil
            }
        }
    }

    /// NoLock body for `getActiveGroupIndex`. MUST only be called while holding fileOpMutex.
    ///
    /// Full walk:
    ///   1. METADATA GET on /projects node → "active" = activeProjNodeId
    ///   2. groupsNodeCache miss: FILE_INFO for project name, resolve /projects/<p>/groups
    ///   3. METADATA GET on groups node → "active" = activeGroupNodeId
    ///   4. groupNodeNameCache miss: list groups children into nodeId→name map
    ///   5. Look up the active group's name; map to PadChannel index
    private func getActiveGroupIndexNoLock() async throws -> Int? {
        guard let projectsNode = try await resolveNodeIdInternal("/projects") else { return nil }

        guard let activeProjNodeId = jsonInt(try await getMetadataJson(projectsNode), "active")
            .flatMap({ $0 >= 0 ? $0 : nil })
        else { return nil }

        // Kotlin getOrPut with non-local return: a failed one-time resolution returns nil
        // WITHOUT populating the cache (structure is retried on the next poll).
        let groupsNode: Int
        if let cached = groupsNodeCache[activeProjNodeId] {
            groupsNode = cached
        } else {
            guard let projName = try await getNodeInfo(activeProjNodeId)?.name else { return nil }
            guard let gn = try await resolveNodeIdInternal("/projects/\(projName)/groups") else {
                return nil
            }
            groupsNodeCache[activeProjNodeId] = gn
            groupsNode = gn
        }

        guard let activeGroupNodeId = jsonInt(try await getMetadataJson(groupsNode), "active")
            .flatMap({ $0 >= 0 ? $0 : nil })
        else { return nil }

        let nameMap: [Int: String]
        if let cached = groupNodeNameCache[groupsNode] {
            nameMap = cached
        } else {
            guard let body = try await listNodeBody(groupsNode, requestId: nextFileReqId()) else {
                return nil
            }
            let entries = SysExProtocol.parseFileListEntries(body)
            if entries.isEmpty { return nil }
            let map = Dictionary(entries.map { ($0.nodeId, $0.name) }, uniquingKeysWith: { _, last in last })
            groupNodeNameCache[groupsNode] = map
            nameMap = map
        }

        guard let groupName = nameMap[activeGroupNodeId] else { return nil }
        guard let idx = PadChannel.allCases.firstIndex(where: { $0.rawValue == groupName }) else {
            return nil
        }
        return idx
    }

    /// Set the device's active group to index (0=A, 1=B, 2=C, 3=D).
    ///
    /// Resolves: /projects → active-project name → /projects/<name>/groups/<letter> (target)
    /// → /projects/<name>/groups (dir) → SET groups-dir `{active:<targetNodeId>}`.
    /// True if the SET ack was received, false on error or timeout.
    func setActiveGroup(index: Int) async throws -> Bool {
        guard index >= 0, index < PadChannel.allCases.count else { return false }
        let channel = PadChannel.allCases[index]
        if deviceState.outputPortId == nil { return false }
        return try await fileOpMutex.withLock { () async throws -> Bool in
            do {
                guard let projName = try await resolveActiveProjectNameNoLock() else {
                    return false
                }
                guard let groupNode = try await resolveNodeIdInternal(
                    "/projects/\(projName)/groups/\(channel.rawValue)")
                else { return false }
                guard let groupsNode = try await resolveNodeIdInternal("/projects/\(projName)/groups")
                else { return false }
                return try await setMetadata(nodeId: groupsNode, json: "{\"active\":\(groupNode)}")
            } catch let e where e is CancellationError {
                throw e
            } catch {
                return false
            }
        }
    }

    // ── Pad assignment (hardware-verified 2026-06-29) ─────────────────────────
    // Each pad in the active project is a FILE node at:
    //   active-project → groups/<letter> → "<gridIndex+1 zero-padded>"
    // A pad's metadata JSON IS its sound binding: writing the verified JSON with
    // sym=<sampleNodeId> makes the device play that sample when the pad is hit.

    /// Bind a sample to a pad on the active project via a single METADATA SET. True if the
    /// device acknowledged; false when offline, unresolvable pad node, or device error.
    func assignSampleToPad(
        group: PadChannel,
        gridIndex: Int,
        sampleNodeId: Int,
        sampleStart: Int,
        sampleEnd: Int,
        playmode: String = "oneshot",
        muteGroup: Bool = false
    ) async throws -> Bool {
        if deviceState.outputPortId == nil { return false }
        return try await fileOpMutex.withLock { () async throws -> Bool in
            _ = try await ensureFileSessionInitNoLock()
            do {
                guard let padNodeId = try await resolvePadNodeIdNoLock(group: group, gridIndex: gridIndex)
                else { return false }
                let json = buildPadAssignmentJson(
                    sampleNodeId: sampleNodeId,
                    sampleStart: sampleStart,
                    sampleEnd: sampleEnd,
                    playmode: playmode,
                    muteGroup: muteGroup)
                return try await setMetadata(nodeId: padNodeId, json: json)
            } catch let e where e is CancellationError {
                throw e
            } catch {
                return false
            }
        }
    }

    /// Build the hardware-verified pad assignment metadata JSON. Shape verified on the real
    /// device (2026-06-29): any field deviation may make the device silently ignore the write.
    func buildPadAssignmentJson(
        sampleNodeId: Int,
        sampleStart: Int,
        sampleEnd: Int,
        playmode: String,
        muteGroup: Bool = false
    ) -> String {
        "{\"sym\":\(sampleNodeId),\"sample.start\":\(sampleStart),\"sample.end\":\(sampleEnd),\"sound.playmode\":\"\(playmode)\",\"sound.amplitude\":100,\"sound.pan\":0,\"sound.pitch\":0.00,\"envelope.attack\":0,\"envelope.release\":255,\"sound.mutegroup\":\(muteGroup),\"time.mode\":\"off\",\"midi.channel\":0}"
    }

    /// Metadata for an empty (unbound) pad — the device's own representation. Hardware-verified:
    /// an empty pad's FILE_METADATA GET returns exactly `{"sym":0}`, so SET-ting it back unbinds.
    private let padEmptyJson = "{\"sym\":0}"

    /// Walk `/projects` → its `"active"` metadata pointer → the active project's node name.
    /// NoLock: callers are already inside `fileOpMutex.withLock`. nil if any hop is missing.
    /// (`getActiveGroupIndexNoLock` keeps its own inline walk — it caches on the project *node id*,
    /// which this name-only helper doesn't expose.)
    private func resolveActiveProjectNameNoLock() async throws -> String? {
        guard let projectsNode = try await resolveNodeIdInternal("/projects") else { return nil }
        guard let activeProjNodeId = jsonInt(try await getMetadataJson(projectsNode), "active")
            .flatMap({ $0 >= 0 ? $0 : nil })
        else { return nil }
        return try await getNodeInfo(activeProjNodeId)?.name
    }

    /// Resolve a pad node id at `/projects/<active>/groups/<X>/<NN>` inside a fileOpMutex-locked
    /// context. Shared by `assignSampleToPad` and `clearPad`.
    private func resolvePadNodeIdNoLock(group: PadChannel, gridIndex: Int) async throws -> Int? {
        guard let projName = try await resolveActiveProjectNameNoLock() else { return nil }
        let groupDirPath = "/projects/\(projName)/groups/\(group.rawValue)"
        guard let groupDirNode = try await resolveNodeIdInternal(groupDirPath) else { return nil }
        guard let body = try await listNodeBody(groupDirNode, requestId: nextFileReqId()) else {
            return nil
        }
        // Pad node name = "%02d" of (gridIndex + 1) per hardware-verified naming.
        let padName = String(format: "%02d", gridIndex + 1)
        return SysExProtocol.parseFileListEntries(body).first(where: { $0.name == padName })?.nodeId
    }

    /// Unbind a pad on the device: write the empty-pad metadata (`{"sym":0}`) so it plays
    /// nothing. False when offline or the pad can't be resolved.
    func clearPad(group: PadChannel, gridIndex: Int) async throws -> Bool {
        if deviceState.outputPortId == nil { return false }
        return try await fileOpMutex.withLock { () async throws -> Bool in
            _ = try await ensureFileSessionInitNoLock()
            do {
                guard let padNodeId = try await resolvePadNodeIdNoLock(group: group, gridIndex: gridIndex)
                else { return false }
                return try await setMetadata(nodeId: padNodeId, json: padEmptyJson)
            } catch let e where e is CancellationError {
                throw e
            } catch {
                return false
            }
        }
    }

    /// Clear a project slot: reset every pad in all four groups to empty (`{"sym":0}`). The
    /// slot itself always survives — project directories carry no DELETE capability, so
    /// "clearing" means emptying its pads. Returns the number of pads emptied, or -1 on
    /// failure / no output port.
    func clearProject(projectName: String) async throws -> Int {
        if deviceState.outputPortId == nil { return -1 }
        return try await fileOpMutex.withLock { () async throws -> Int in
            _ = try await ensureFileSessionInitNoLock()
            do {
                var cleared = 0
                for group in PadChannel.allCases {
                    guard let groupDir = try await resolveNodeIdInternal(
                        "/projects/\(projectName)/groups/\(group.rawValue)")
                    else { continue }
                    guard let body = try await listNodeBody(groupDir, requestId: nextFileReqId())
                    else { continue }
                    let padNodes = SysExProtocol.parseFileListEntries(body)
                        .filter { $0.name.count == 2 && $0.name.allSatisfy(\.isNumber) }  // pads 01..12
                        .map(\.nodeId)
                    for padNode in padNodes {
                        if try await setMetadata(nodeId: padNode, json: padEmptyJson) {
                            cleared += 1
                        }
                    }
                }
                return cleared
            } catch let e where e is CancellationError {
                throw e
            } catch {
                return -1
            }
        }
    }

    /// Read which sample is bound to each pad of group in the active project.
    ///
    /// Returns gridIndex (0..11) → sample display name for every OCCUPIED pad (metadata `sym`
    /// non-zero); empty pads omitted. Names have their extension stripped; an unnameable
    /// sample node falls back to "sample". Empty when offline or on error.
    func readGroupPadState(group: PadChannel) async throws -> [Int: String] {
        if deviceState.outputPortId == nil { return [:] }
        return try await fileOpMutex.withLock { () async throws -> [Int: String] in
            _ = try await ensureFileSessionInitNoLock()
            do {
                guard let projName = try await resolveActiveProjectNameNoLock() else { return [:] }
                guard let groupDir = try await resolveNodeIdInternal(
                    "/projects/\(projName)/groups/\(group.rawValue)")
                else { return [:] }
                guard let body = try await listNodeBody(groupDir, requestId: nextFileReqId())
                else { return [:] }
                let padEntries = SysExProtocol.parseFileListEntries(body)
                    .filter { $0.name.count == 2 && $0.name.allSatisfy(\.isNumber) }  // pads 01..12
                var result: [Int: String] = [:]
                for entry in padEntries {
                    guard let padNumber = Int(entry.name) else { continue }
                    let gridIndex = padNumber - 1
                    guard (0..<12).contains(gridIndex) else { continue }
                    let sym = jsonInt(try await getMetadataJson(entry.nodeId), "sym") ?? 0
                    if sym == 0 { continue }
                    let info = try await getNodeInfo(sym)
                    result[gridIndex] = info?.name.substringBeforeLast(".") ?? "sample"
                }
                return result
            } catch let e where e is CancellationError {
                throw e
            } catch {
                return [:]
            }
        }
    }

    /// Resolve the /sounds directory node ID inside a fileOpMutex-locked context.
    ///
    /// A non-final method so test subclasses can stub the resolution without overriding the
    /// entire locking machinery in `putSampleFile` (Kotlin `protected open`).
    /// MUST only be called while holding fileOpMutex.
    func resolveSoundsNodeId() async throws -> Int? {
        try await resolveNodeIdInternal("/sounds")
    }

    /// NoLock core for `resolveNodeId`: walks path segments from root (nodeId 0) using
    /// `listNodeBody`. MUST only be called while holding fileOpMutex.
    private func resolveNodeIdInternal(_ path: String) async throws -> Int? {
        let segments = path.split(separator: "/").map(String.init).filter { !$0.isEmpty }
        var nodeId = 0  // root
        for segment in segments {
            guard let body = try await listNodeBody(nodeId, requestId: nextFileReqId()) else {
                return nil
            }
            let entries = SysExProtocol.parseFileListEntries(body)
            guard let child = entries.first(where: { $0.name == segment }) else { return nil }
            nodeId = child.nodeId
        }
        return nodeId
    }

    // ── MIDI message senders ──────────────────────────────────────────────────
    // Kotlin default args reference the instance `channel` property; Swift default parameter
    // values cannot reference self, so these take `ch: Int? = nil` meaning "current channel".

    func noteOn(note: Int, velocity: Int = 100, ch: Int? = nil) {
        guard let portId = deviceState.outputPortId else { return }
        let ch = ch ?? channel
        let status = 0x90 | (ch & 0x0F)
        port.sendMIDI(to: portId, data: [
            UInt8(status),
            UInt8(note & 0x7F),
            UInt8(velocity & 0x7F),
        ])
    }

    func noteOff(note: Int, ch: Int? = nil) {
        guard let portId = deviceState.outputPortId else { return }
        let ch = ch ?? channel
        let status = 0x80 | (ch & 0x0F)
        port.sendMIDI(to: portId, data: [
            UInt8(status),
            UInt8(note & 0x7F),
            0,
        ])
    }

    func controlChange(control: Int, value: Int, ch: Int? = nil) {
        guard let portId = deviceState.outputPortId else { return }
        let ch = ch ?? channel
        let status = 0xB0 | (ch & 0x0F)
        port.sendMIDI(to: portId, data: [
            UInt8(status),
            UInt8(control & 0x7F),
            UInt8(value & 0x7F),
        ])
    }

    func programChange(program: Int, ch: Int? = nil) {
        guard let portId = deviceState.outputPortId else { return }
        let ch = ch ?? channel
        let status = 0xC0 | (ch & 0x0F)
        port.sendMIDI(to: portId, data: [
            UInt8(status),
            UInt8(program & 0x7F),
        ])
    }

    func allNotesOff(ch: Int? = nil) {
        controlChange(control: 123, value: 0, ch: ch)
    }

    /// Send raw MIDI bytes (system real-time messages: Start 0xFA, Stop 0xFC, Clock 0xF8).
    func sendRawBytes(_ bytes: [UInt8]) {
        guard let portId = deviceState.outputPortId else { return }
        port.sendMIDI(to: portId, data: bytes)
    }

    /// Load a factory sound onto a pad via note-on (select pad) → Bank Select → Program Change.
    ///
    /// The EP-133 assigns sounds to the last-played pad, so a note-on selects the target first.
    /// All messages are sent as a single byte array to guarantee ordering through the async
    /// port path. Sound numbers are 1–999 (EP-133 factory library).
    func loadSoundToPad(soundNumber: Int, padNote: Int, padChannel: Int, ch: Int? = nil) {
        guard let portId = deviceState.outputPortId else { return }
        let ch = ch ?? channel
        let index = max(soundNumber - 1, 0)
        let bankMsb = index / 128
        let program = index % 128

        let noteOnStatus = UInt8(0x90 | (padChannel & 0x0F))
        let noteOffStatus = UInt8(0x80 | (padChannel & 0x0F))
        let ccStatus = UInt8(0xB0 | (ch & 0x0F))
        let pcStatus = UInt8(0xC0 | (ch & 0x0F))
        let padNoteByte = UInt8(padNote & 0x7F)

        port.sendMIDI(to: portId, data: [
            noteOnStatus, padNoteByte, 100,
            noteOffStatus, padNoteByte, 0,
            ccStatus, 0, UInt8(bankMsb & 0x7F),
            ccStatus, 32, 0,
            pcStatus, UInt8(program & 0x7F),
        ])
    }

    /// Cancel the auto-stats task and close the MIDI port. Call on scene teardown.
    /// (Kotlin also cancels the repository coroutine scope; the auto-stats task is the only
    /// work that scope carries on iOS.)
    func close() {
        autoStatsTask?.cancel()
        port.close()
    }

    // ── JSON helpers (Kotlin org.json JSONObject.optInt/optLong equivalents) ──

    private func jsonInt(_ obj: [String: Any], _ key: String) -> Int? {
        (obj[key] as? NSNumber)?.intValue
    }

    private func jsonInt64(_ obj: [String: Any], _ key: String) -> Int64? {
        (obj[key] as? NSNumber)?.int64Value
    }
}

private extension String {
    /// Kotlin `substringBeforeLast(delimiter, missingDelimiterValue = this)`.
    func substringBeforeLast(_ delimiter: Character) -> String {
        guard let idx = lastIndex(of: delimiter) else { return self }
        return String(self[..<idx])
    }
}
