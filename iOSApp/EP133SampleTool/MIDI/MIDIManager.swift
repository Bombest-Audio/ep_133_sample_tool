import Combine
import CoreMIDI
import Foundation

/// Manages CoreMIDI USB device discovery, input, and output.
/// Conforms to MIDIPort — Phase 3 ViewModels depend on the protocol, not this class.
final class MIDIManager: MIDIPort {

    /// Called on the main thread when MIDI data arrives from a device.  (portId, bytes)
    var onMIDIReceived: ((String, [UInt8]) -> Void)?

    /// Called on the main thread when MIDI devices are connected or disconnected.
    var onDevicesChanged: (() -> Void)?

    private var midiClient: MIDIClientRef = 0
    private var inputPort: MIDIPortRef = 0
    private var outputPort: MIDIPortRef = 0
    private var connectedSources: Set<MIDIEndpointRef> = []

    // MARK: - Setup

    func setup() {
        let status = MIDIClientCreateWithBlock(
            "EP133SampleTool" as CFString,
            &midiClient
        ) { [weak self] notification in
            self?.handleMIDINotification(notification)
        }

        guard status == noErr else {
            print("[EP133] Failed to create MIDI client: \(status)")
            return
        }

        // Input port — receives MIDI from all connected sources.
        // Legacy MIDIPacketList API on purpose: packets carry raw MIDI 1.0 bytes, so
        // SysEx arrives exactly as sent (matching the Android MIDIManager). The UMP
        // event-list API wraps SysEx in SysEx7 packets with header nibbles, which the
        // old naive word-unpacking corrupted.
        MIDIInputPortCreateWithBlock(
            midiClient,
            "EP133Input" as CFString,
            &inputPort
        ) { [weak self] packetList, srcConnRefCon in
            self?.handleMIDIInput(packetList: packetList, srcRefCon: srcConnRefCon)
        }

        // Output port — sends MIDI to destinations
        MIDIOutputPortCreate(midiClient, "EP133Output" as CFString, &outputPort)

        // Connect to all existing sources
        connectAllSources()
    }

    // MARK: - Teardown

    func close() {
        for source in connectedSources {
            MIDIPortDisconnectSource(inputPort, source)
        }
        connectedSources.removeAll()
        if inputPort != 0 { MIDIPortDispose(inputPort); inputPort = 0 }
        if outputPort != 0 { MIDIPortDispose(outputPort); outputPort = 0 }
        if midiClient != 0 { MIDIClientDispose(midiClient); midiClient = 0 }
    }

    // MARK: - Device Enumeration

    func getUSBDevices() -> MIDIDeviceList {
        var inputs: [MIDIDevice] = []
        var outputs: [MIDIDevice] = []

        // Enumerate MIDI sources (inputs from our perspective — we receive from them)
        for i in 0..<MIDIGetNumberOfSources() {
            let endpoint = MIDIGetSource(i)
            if let device = midiDeviceFrom(endpoint: endpoint) {
                inputs.append(device)
            }
        }

        // Enumerate MIDI destinations (outputs from our perspective — we send to them)
        for i in 0..<MIDIGetNumberOfDestinations() {
            let endpoint = MIDIGetDestination(i)
            if let device = midiDeviceFrom(endpoint: endpoint) {
                outputs.append(device)
            }
        }

        return MIDIDeviceList(inputs: inputs, outputs: outputs)
    }

    // MARK: - MIDIPort stub methods (Phase 3 will implement)

    /// No-op in Phase 1 — MIDIManager auto-connects to all sources in setup().
    func startListening(portId: String) {}

    /// No-op in Phase 1.
    func stopListening(portId: String) {}

    // MARK: - Send MIDI

    func sendMIDI(to portId: String, data: [UInt8]) {
        guard let uniqueID = Int32(portId) else { return }

        // Find the destination endpoint matching this ID
        var destination: MIDIEndpointRef = 0
        for i in 0..<MIDIGetNumberOfDestinations() {
            let ep = MIDIGetDestination(i)
            var epID: Int32 = 0
            MIDIObjectGetIntegerProperty(ep, kMIDIPropertyUniqueID, &epID)
            if epID == uniqueID {
                destination = ep
                break
            }
        }

        guard destination != 0 else {
            print("[EP133] MIDI destination not found: \(portId)")
            return
        }

        // Build and send the MIDI event list
        data.withUnsafeBufferPointer { buffer in
            guard let baseAddress = buffer.baseAddress else { return }

            let wordCount = (data.count + 3) / 4  // UInt32 words needed
            var eventList = MIDIEventList()
            var packet = MIDIEventListInit(&eventList, ._1_0)

            // Convert bytes to UInt32 words (MIDI 1.0 UMP format)
            // For sysex and multi-byte messages, we send raw bytes
            if data.count > 3 || (data.first ?? 0) == 0xF0 {
                // Sysex or large message — use MIDISend with packet list
                sendRawBytes(to: destination, data: data)
                return
            }

            // Regular short MIDI message — pack into a single UInt32
            var word: UInt32 = 0
            for (idx, byte) in data.enumerated() where idx < 4 {
                word |= UInt32(byte) << (8 * (3 - idx))
            }
            packet = MIDIEventListAdd(&eventList, 1024, packet, 0, 1, &word)
            MIDISendEventList(outputPort, destination, &eventList)
        }
    }

    /// Sends raw bytes (including sysex) using the legacy MIDIPacketList API
    /// which handles arbitrary-length messages.
    private func sendRawBytes(to destination: MIDIEndpointRef, data: [UInt8]) {
        // Correct allocation: MIDIPacketList header + MIDIPacket header + payload bytes.
        // Using UnsafeMutableRawPointer avoids the capacity:1 bug which only allocates
        // space for one MIDIPacketList struct, not packetListSize bytes (D-12).
        let packetListSize = MemoryLayout<MIDIPacketList>.size
            + MemoryLayout<MIDIPacket>.size
            + data.count
        let rawPtr = UnsafeMutableRawPointer.allocate(
            byteCount: packetListSize,
            alignment: MemoryLayout<MIDIPacketList>.alignment
        )
        defer { rawPtr.deallocate() }

        let packetListPtr = rawPtr.bindMemory(to: MIDIPacketList.self, capacity: 1)
        var packet = MIDIPacketListInit(packetListPtr)
        packet = MIDIPacketListAdd(packetListPtr, packetListSize, packet, 0, data.count, data)
        MIDISend(outputPort, destination, packetListPtr)
    }

    // MARK: - MIDI Input Handling

    private func handleMIDIInput(
        packetList: UnsafePointer<MIDIPacketList>,
        srcRefCon: UnsafeMutableRawPointer?
    ) {
        let portId = srcRefCon.map { String(Int(bitPattern: $0)) } ?? "unknown"

        // unsafeSequence() yields pointers into the original list — copying a
        // MIDIPacket struct and calling MIDIPacketNext on the copy's address reads
        // past the copy's 256-byte data tuple for any list with numPackets > 1 or
        // packets longer than 256 bytes.
        for packetPtr in packetList.unsafeSequence() {
            let length = Int(packetPtr.pointee.length)
            guard length > 0 else { continue }

            // Read the data bytes through a raw pointer offset from the packet base,
            // not via packetPtr.pointee.data — the tuple copy caps at 256 bytes while
            // a packet's declared length can exceed it.
            let dataOffset = MemoryLayout<MIDIPacket>.offset(of: \MIDIPacket.data)!
            let dataPtr = UnsafeRawPointer(packetPtr) + dataOffset
            let bytes = [UInt8](UnsafeRawBufferPointer(start: dataPtr, count: length))

            DispatchQueue.main.async { [weak self] in
                self?.onMIDIReceived?(portId, bytes)
            }
        }
    }

    // MARK: - Device Notifications

    private func handleMIDINotification(_ notification: UnsafePointer<MIDINotification>) {
        switch notification.pointee.messageID {
        case .msgObjectAdded, .msgObjectRemoved:
            connectAllSources()
            DispatchQueue.main.async { [weak self] in
                self?.onDevicesChanged?()
            }
        case .msgSetupChanged:
            DispatchQueue.main.async { [weak self] in
                self?.onDevicesChanged?()
            }
        default:
            break
        }
    }

    // MARK: - Source Connection

    private func connectAllSources() {
        // Disconnect any sources that no longer exist
        for source in connectedSources {
            MIDIPortDisconnectSource(inputPort, source)
        }
        connectedSources.removeAll()

        // Connect to all available sources
        for i in 0..<MIDIGetNumberOfSources() {
            let source = MIDIGetSource(i)
            var uniqueID: Int32 = 0
            MIDIObjectGetIntegerProperty(source, kMIDIPropertyUniqueID, &uniqueID)

            let refCon = UnsafeMutableRawPointer(bitPattern: Int(uniqueID))
            MIDIPortConnectSource(inputPort, source, refCon)
            connectedSources.insert(source)
        }
    }

    // MARK: - Helpers

    private func midiDeviceFrom(endpoint: MIDIEndpointRef) -> MIDIDevice? {
        guard endpoint != 0 else { return nil }

        var uniqueID: Int32 = 0
        MIDIObjectGetIntegerProperty(endpoint, kMIDIPropertyUniqueID, &uniqueID)

        var name: Unmanaged<CFString>?
        MIDIObjectGetStringProperty(endpoint, kMIDIPropertyDisplayName, &name)
        let displayName = (name?.takeRetainedValue() as String?) ?? "Unknown MIDI Device"

        return MIDIDevice(id: String(uniqueID), name: displayName)
    }
}
