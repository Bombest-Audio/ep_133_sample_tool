import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIRepositoryTest.kt.
/// (FakeMIDIPort lives in Support/FakeMIDIPort.swift.)
@MainActor
final class MIDIRepositoryTests: XCTestCase {

    func testDeviceState_emitsConnectedTrueWhenDeviceAdded() {
        let fake = FakeMIDIPort()
        let repo = MIDIRepository(fake)

        // Initially disconnected
        XCTAssertFalse(repo.deviceState.connected)

        // Simulate device added
        fake.simulateDeviceAdded(id: "test-out", name: "EP-133")

        // deviceState should now be connected
        XCTAssertTrue(repo.deviceState.connected)
    }
}
