import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIRepositoryStatsTest.kt.
/// Tests for MIDIRepository.queryDeviceStats() — device stat queries via SysEx.
@MainActor
final class MIDIRepositoryStatsTests: XCTestCase {

    func testQueryDeviceStats_timesOutAfter5Seconds() throws {
        // Kotlin @Ignore: requires physical EP-133 or a full SysEx response simulator.
        throw XCTSkip("queryDeviceStats timeout test requires a physical EP-133 or a full SysEx response simulator")
    }

    func testQueryDeviceStats_populatesFirmwareVersion() throws {
        throw XCTSkip("queryDeviceStats requires simulating a SysEx GREET response — needs TestableRepository wiring")
    }

    func testQueryDeviceStats_populatesStorageFields() throws {
        throw XCTSkip("queryDeviceStats requires simulating a SysEx FILE_METADATA response")
    }

    func testStatsNull_beforeQuery() {
        // Fresh DeviceState has all stats fields as nil (default values)
        let state = DeviceState()
        XCTAssertNil(state.sampleCount, "sampleCount should be nil before query")
        XCTAssertNil(state.storageUsedBytes, "storageUsedBytes should be nil before query")
        XCTAssertNil(state.storageTotalBytes, "storageTotalBytes should be nil before query")
        XCTAssertNil(state.firmwareVersion, "firmwareVersion should be nil before query")
    }

    func testQueryDeviceStats_populatesSampleCount() throws {
        throw XCTSkip("queryDeviceStats requires simulating a SysEx FILE_LIST response")
    }
}
