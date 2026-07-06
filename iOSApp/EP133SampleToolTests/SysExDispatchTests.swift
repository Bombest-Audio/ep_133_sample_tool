import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/SysExDispatchTest.kt.
///
/// Multi-response paged-transfer lifecycle discrimination. The status-classification rule
/// (the heart of the continuation state machine) lives in the pure
/// `SysExProtocol.classifyTransferStatus` so it is unit-testable without a device:
/// STATUS_SPECIFIC_SUCCESS_START keeps the request pending, STATUS_OK completes it,
/// anything else (non-OK, < SUCCESS_START) is an error.
final class SysExDispatchTests: XCTestCase {

    func testSuccessStart_keepsTransferPending() {
        XCTAssertEqual(
            SysExProtocol.TransferStatus.pending,
            SysExProtocol.classifyTransferStatus(SysExProtocol.STATUS_SPECIFIC_SUCCESS_START)
        )
        // Any status at or above the continuation threshold stays pending.
        XCTAssertEqual(SysExProtocol.TransferStatus.pending, SysExProtocol.classifyTransferStatus(100))
    }

    func testOk_completesTransfer() {
        XCTAssertEqual(
            SysExProtocol.TransferStatus.complete,
            SysExProtocol.classifyTransferStatus(SysExProtocol.STATUS_OK)
        )
    }

    func testNonOkBelowThreshold_isError() {
        XCTAssertEqual(SysExProtocol.TransferStatus.error, SysExProtocol.classifyTransferStatus(1))
        XCTAssertEqual(SysExProtocol.TransferStatus.error, SysExProtocol.classifyTransferStatus(63))
    }

    func testStatusConstants_haveKnownValues() {
        XCTAssertEqual(64, SysExProtocol.STATUS_SPECIFIC_SUCCESS_START)
        XCTAssertEqual(0, SysExProtocol.STATUS_OK)
    }

    func testSuccessStart_keepsRequestPending_okCompletes() throws {
        // Kotlin @Ignore: paged GET dispatch lifecycle requires a physical EP-133 or a full
        // SysEx response simulator — validated via physical EP-133 UAT.
        throw XCTSkip("paged GET dispatch lifecycle requires a physical EP-133 or a full SysEx response simulator")
    }
}
