import XCTest
@testable import EP133SampleTool

/// Verifies the unit-test target links against the app module.
final class SmokeTests: XCTestCase {

    func testAppModuleIsTestable() {
        let manager = MIDIManager()
        XCTAssertNil(manager.onMIDIReceived)
    }
}
