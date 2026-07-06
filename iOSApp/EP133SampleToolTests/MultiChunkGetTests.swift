import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/MultiChunkGetTest.kt.
///
/// Paged FILE_GET assembly: drives the pure `SysExProtocol.assembleGetPages` loop with a
/// fake page supplier — no device, no timing. Covers fileSize accumulation across pages,
/// page-mismatch detection, empty-data termination, and a CRC32 integrity guard.
final class MultiChunkGetTests: XCTestCase {

    /// Build a fake supplier that returns fixed-size pages slicing `blob`.
    private func pagedSupplier(_ blob: [UInt8], pageSize: Int) -> (Int) -> SysExProtocol.GetDataResponse {
        var pages: [[UInt8]] = []
        var i = 0
        while i < blob.count {
            pages.append(Array(blob[i..<min(i + pageSize, blob.count)]))
            i += pageSize
        }
        return { page in
            let data = page < pages.count ? pages[page] : []
            return SysExProtocol.GetDataResponse(page: page, data: data)
        }
    }

    /// CRC32 (IEEE 802.3, zlib-compatible) — java.util.zip.CRC32 equivalent for the fixture.
    private func crc32(_ data: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0xFFFFFFFF
        for b in data {
            crc ^= UInt32(b)
            for _ in 0..<8 {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xEDB88320 : crc >> 1
            }
        }
        return ~crc
    }

    func testAssemblesFileSizeAcrossPages() throws {
        let blob = (0..<1000).map { UInt8($0 & 0xFF) }
        let assembled = try SysExProtocol.assembleGetPages(fileSize: blob.count, supplier: pagedSupplier(blob, pageSize: 256))
        XCTAssertEqual(blob, assembled)
    }

    func testAssemblesExactSizeWhenLastPagePartial() throws {
        let blob = (0..<777).map { UInt8(($0 * 3) & 0xFF) }
        let assembled = try SysExProtocol.assembleGetPages(fileSize: blob.count, supplier: pagedSupplier(blob, pageSize: 100))
        XCTAssertEqual(777, assembled.count)
        XCTAssertEqual(blob, assembled)
    }

    func testPageMismatchThrows() {
        // Supplier lies about the page number on the second request.
        let supplier: (Int) -> SysExProtocol.GetDataResponse = { page in
            page == 0
                ? SysExProtocol.GetDataResponse(page: 0, data: [UInt8](repeating: 1, count: 256))
                : SysExProtocol.GetDataResponse(page: 99, data: [UInt8](repeating: 2, count: 256))  // expected 1, got 99
        }
        XCTAssertThrowsError(try SysExProtocol.assembleGetPages(fileSize: 1024, supplier: supplier)) { error in
            guard case let SysExProtocol.ProtocolError.invalidState(message) = error else {
                return XCTFail("expected ProtocolError.invalidState, got \(error)")
            }
            XCTAssertTrue(message.contains("unexpected page"))
        }
    }

    func testEmptyDataTerminatesEvenBelowFileSize() throws {
        // Declare a large fileSize but return an empty page after one real page.
        let supplier: (Int) -> SysExProtocol.GetDataResponse = { page in
            page == 0
                ? SysExProtocol.GetDataResponse(page: 0, data: [UInt8](repeating: 7, count: 64))
                : SysExProtocol.GetDataResponse(page: page, data: [])  // EOF
        }
        let assembled = try SysExProtocol.assembleGetPages(fileSize: 100_000, supplier: supplier)
        XCTAssertEqual(64, assembled.count)
    }

    func testOversizedPageAborts() {
        // A malformed/oversized DATA page that alone exceeds fileSize + slack must abort
        // the accumulation rather than corrupt the buffer (threat T-04-03).
        let supplier: (Int) -> SysExProtocol.GetDataResponse = { page in
            SysExProtocol.GetDataResponse(page: page, data: [UInt8](repeating: 1, count: SysExProtocol.MAX_PAGE_BYTES * 4))
        }
        XCTAssertThrowsError(try SysExProtocol.assembleGetPages(fileSize: 100, supplier: supplier)) { error in
            guard case let SysExProtocol.ProtocolError.invalidState(message) = error else {
                return XCTFail("expected ProtocolError.invalidState, got \(error)")
            }
            XCTAssertTrue(message.contains("overflow"))
        }
    }

    func testAssembledArchiveCrc32Matches() throws {
        let blob = Array("EP133".utf8)
        let assembled = try SysExProtocol.assembleGetPages(fileSize: blob.count, supplier: pagedSupplier(blob, pageSize: 2))
        XCTAssertEqual(0xF32EA407, crc32(assembled))
    }
}
