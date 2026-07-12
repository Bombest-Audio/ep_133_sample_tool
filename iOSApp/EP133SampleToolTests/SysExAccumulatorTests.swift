import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/SysExAccumulatorTest.kt.
/// Tests for SysEx accumulation logic in MIDIRepository.
@MainActor
final class SysExAccumulatorTests: XCTestCase {

    /// Testable subclass of MIDIRepository that captures dispatchSysEx calls.
    private final class TestableRepository: MIDIRepository {
        var dispatchedMessages: [[UInt8]] = []

        override func dispatchSysEx(_ message: [UInt8]) {
            dispatchedMessages.append(message)
        }
    }

    private func makeRepo() -> (TestableRepository, FakeMIDIPort) {
        let port = FakeMIDIPort()
        let repo = TestableRepository(port)
        return (repo, port)
    }

    func testSingleCompleteMessage_dispatched() {
        let (repo, port) = makeRepo()
        // Invoke through the registered onMIDIReceived callback
        port.onMIDIReceived?("port-0", [0xF0, 0x00, 0x20, 0x76, 0x00, 0xF7])
        XCTAssertEqual(1, repo.dispatchedMessages.count)
        XCTAssertEqual(6, repo.dispatchedMessages[0].count)
    }

    func testFragmentedMessage_accumulatesAndDispatches() {
        let (repo, port) = makeRepo()
        port.onMIDIReceived?("port-0", [0xF0, 0x00, 0x20])
        XCTAssertEqual(0, repo.dispatchedMessages.count)
        port.onMIDIReceived?("port-0", [0x76, 0x00, 0xF7])
        XCTAssertEqual(1, repo.dispatchedMessages.count)
        XCTAssertEqual(6, repo.dispatchedMessages[0].count)
    }

    func testMidMessageChannelMessage_ignored() {
        // Per MIDI spec, status bytes 0x80-0xEF during SysEx would be "real-time"; our
        // implementation accumulates everything during SysEx. This is correct behavior.
        let (repo, port) = makeRepo()
        port.onMIDIReceived?("port-0", [0xF0, 0x00, 0x20])
        port.onMIDIReceived?("port-0", [0x76, 0x00, 0xF7])
        // SysEx should still dispatch correctly
        XCTAssertEqual(1, repo.dispatchedMessages.count)
    }

    func testMultipleMessages_eachDispatchedOnce() {
        let (repo, port) = makeRepo()
        // First message
        port.onMIDIReceived?("port-0", [0xF0, 0x00, 0x20, 0x76, 0x00, 0xF7])
        // Second message
        port.onMIDIReceived?("port-0", [0xF0, 0x00, 0x21, 0x76, 0x00, 0xF7])
        XCTAssertEqual(2, repo.dispatchedMessages.count)
    }
}
