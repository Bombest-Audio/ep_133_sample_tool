import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/FileWaiterRegistryTest.kt.
/// Kotlin `runTest` bodies become @MainActor async tests; Channel drains become
/// `for try await` over the Stream waiter's sink.
@MainActor
final class FileWaiterRegistryTests: XCTestCase {

    private func resp(_ reqId: Int, _ status: Int, _ body: [UInt8] = []) -> FileResponse {
        FileResponse(reqId: reqId, status: status, body: body)
    }

    func testOneShot_routes_completesDeferred_andRemovesWaiter() async throws {
        let reg = FileWaiterRegistry()
        let waiter = FileWaiter.OneShot(reqId: 100, kind: .info)
        try reg.register(waiter)
        XCTAssertTrue(reg.isAwaiting(100))

        let r = reg.route(resp(100, SysExProtocol.STATUS_OK, [1, 2, 3]))

        XCTAssertEqual(FileWaiterRegistry.RouteResult.oneShotCompleted, r)
        let delivered = try await waiter.deferred.value()
        XCTAssertEqual(100, delivered.reqId)
        XCTAssertEqual([1, 2, 3], delivered.body)
        XCTAssertFalse(reg.isAwaiting(100))
    }

    func testOneShot_secondResponseSameReqId_isUnmatched() throws {
        let reg = FileWaiterRegistry()
        try reg.register(FileWaiter.OneShot(reqId: 101, kind: .info))
        _ = reg.route(resp(101, SysExProtocol.STATUS_OK))
        let second = reg.route(resp(101, SysExProtocol.STATUS_OK))
        XCTAssertEqual(FileWaiterRegistry.RouteResult.unmatched, second)
    }

    func testStream_continuationKeepsRegistered_thenCompleteCloses_inOrder() async throws {
        let reg = FileWaiterRegistry()
        let waiter = FileWaiter.Stream(reqId: 200, kind: .metadataGet)
        try reg.register(waiter)

        let r0 = reg.route(resp(200, SysExProtocol.STATUS_SPECIFIC_SUCCESS_START, [0]))
        XCTAssertEqual(FileWaiterRegistry.RouteResult.streamContinued, r0)
        XCTAssertTrue(reg.isAwaiting(200))

        let r1 = reg.route(resp(200, SysExProtocol.STATUS_SPECIFIC_SUCCESS_START, [1]))
        XCTAssertEqual(FileWaiterRegistry.RouteResult.streamContinued, r1)

        let r2 = reg.route(resp(200, SysExProtocol.STATUS_OK, [2]))
        XCTAssertEqual(FileWaiterRegistry.RouteResult.streamCompleted, r2)
        XCTAssertFalse(reg.isAwaiting(200))

        var pages = [Int]()
        for try await response in waiter.sink {
            pages.append(Int(response.body[0]))
        }
        XCTAssertEqual([0, 1, 2], pages)
    }

    func testStream_errorStatus_closesExceptionally_andDeregisters() async throws {
        let reg = FileWaiterRegistry()
        let waiter = FileWaiter.Stream(reqId: 201, kind: .metadataGet)
        try reg.register(waiter)

        let r = reg.route(resp(201, 1))
        XCTAssertEqual(FileWaiterRegistry.RouteResult.streamError, r)
        XCTAssertFalse(reg.isAwaiting(201))

        do {
            for try await _ in waiter.sink {}
            XCTFail("expected sink to be closed exceptionally")
        } catch FileWaiterError.deviceErrorStatus(let status) {
            XCTAssertEqual(1, status)
        }
    }

    func testRoute_unknownReqId_isUnmatched() {
        let reg = FileWaiterRegistry()
        XCTAssertEqual(FileWaiterRegistry.RouteResult.unmatched, reg.route(resp(999, SysExProtocol.STATUS_OK)))
    }

    func testRegister_duplicateLiveReqId_throws() throws {
        let reg = FileWaiterRegistry()
        try reg.register(FileWaiter.OneShot(reqId: 300, kind: .fileInit))
        XCTAssertThrowsError(try reg.register(FileWaiter.OneShot(reqId: 300, kind: .info))) { error in
            guard case FileWaiterError.duplicateWaiter = error else {
                return XCTFail("expected duplicateWaiter, got \(error)")
            }
        }
    }

    func testFailAll_completesOneShotExceptionally_andClosesStream_andClears() async throws {
        let reg = FileWaiterRegistry()
        let oneShot = FileWaiter.OneShot(reqId: 400, kind: .info)
        let stream = FileWaiter.Stream(reqId: 401, kind: .metadataGet)
        try reg.register(oneShot)
        try reg.register(stream)

        reg.failAll(FileWaiterError.deviceErrorStatus(-1))

        XCTAssertFalse(reg.isAwaiting(400))
        XCTAssertFalse(reg.isAwaiting(401))
        do {
            _ = try await oneShot.deferred.value()
            XCTFail("expected OneShot deferred to fail")
        } catch {
            // expected
        }
        do {
            for try await _ in stream.sink {}
            XCTFail("expected Stream sink to be closed")
        } catch {
            // expected
        }
    }

    func testInterleavedOneShots_eachResponseRoutesToItsOwnWaiter() async throws {
        // The exact 999.4 failure the flag model had: two ops in flight, responses arrive
        // out of order. With reqId routing each must land on its own waiter.
        let reg = FileWaiterRegistry()
        let a = FileWaiter.OneShot(reqId: 500, kind: .info)
        let b = FileWaiter.OneShot(reqId: 501, kind: .metadataSet)
        try reg.register(a)
        try reg.register(b)

        _ = reg.route(resp(501, SysExProtocol.STATUS_OK, [0x0B]))
        _ = reg.route(resp(500, SysExProtocol.STATUS_OK, [0x0A]))

        let aBody = try await a.deferred.value().body
        let bBody = try await b.deferred.value().body
        XCTAssertEqual([0x0A], aBody)
        XCTAssertEqual([0x0B], bBody)
    }

    func testFileResponse_equality_comparesBodyByContent() {
        XCTAssertEqual(resp(1, 0, [1, 2, 3]), resp(1, 0, [1, 2, 3]))
        XCTAssertFalse(resp(1, 0, [1, 2, 3]) == resp(1, 0, [9]))
    }
}
