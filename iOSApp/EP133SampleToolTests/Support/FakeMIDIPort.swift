import Foundation
@testable import EP133SampleTool

/// FakeMIDIPort: minimal MIDIPort implementation for unit testing MIDIRepository.
/// Mirrors the Kotlin FakeMIDIPort in
/// AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIRepositoryTest.kt.
final class FakeMIDIPort: MIDIPort {
    var onMIDIReceived: ((String, [UInt8]) -> Void)?
    var onDevicesChanged: (() -> Void)?

    private var inputs: [MIDIDevice] = []
    private var outputs: [MIDIDevice] = []

    func setup() {}
    func close() {}

    func getUSBDevices() -> MIDIDeviceList {
        MIDIDeviceList(inputs: inputs, outputs: outputs)
    }

    func sendMIDI(to portId: String, data: [UInt8]) {}
    func startListening(portId: String) {}
    func stopListening(portId: String) {}

    /// Simulate a device being added — triggers the onDevicesChanged callback.
    func simulateDeviceAdded(id: String, name: String) {
        outputs.append(MIDIDevice(id: id, name: name))
        inputs.append(MIDIDevice(id: id, name: name))
        onDevicesChanged?()
    }
}
