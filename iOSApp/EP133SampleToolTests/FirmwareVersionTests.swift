import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/FirmwareVersionTest.kt —
/// every test method ports 1:1 (XCTest requires the `test` prefix).
final class FirmwareVersionTests: XCTestCase {

    func test_parse_validTwoPart_returnsFirmwareVersion() {
        let v = FirmwareVersion.parse("2.5")
        XCTAssertNotNil(v)
        XCTAssertEqual(FirmwareVersion(parts: [2, 5]), v)
    }

    func test_parse_validThreePart_returnsFirmwareVersion() {
        let v = FirmwareVersion.parse("2.0.5")
        XCTAssertNotNil(v)
        XCTAssertEqual(FirmwareVersion(parts: [2, 0, 5]), v)
    }

    func test_parse_null_returnsNull() {
        XCTAssertNil(FirmwareVersion.parse(nil))
    }

    func test_parse_emptyString_returnsNull() {
        XCTAssertNil(FirmwareVersion.parse(""))
    }

    func test_parse_blankString_returnsNull() {
        XCTAssertNil(FirmwareVersion.parse("  "))
    }

    func test_parse_nonNumeric_returnsNull() {
        XCTAssertNil(FirmwareVersion.parse("abc"))
    }

    func test_parse_mixedNonNumeric_returnsNull() {
        XCTAssertNil(FirmwareVersion.parse("2.x.5"))
    }

    func test_parse_trailingDot_returnsNull() {
        // components(separatedBy:) keeps the trailing empty token (like Kotlin's split), so
        // "2.5." → ["2","5",""]; the empty part fails Int() and parse rejects it.
        XCTAssertNil(FirmwareVersion.parse("2.5."))
    }

    func test_parse_emptyInteriorComponent_returnsNull() {
        XCTAssertNil(FirmwareVersion.parse("2..5"))
    }

    func test_parse_leadingDot_returnsNull() {
        XCTAssertNil(FirmwareVersion.parse(".2.5"))
    }

    func test_compare_largeComponents_doesNotOverflow() {
        // Guards the compare contract against subtraction overflow: a tiny version
        // must order below a huge one even when the delta exceeds Int range.
        // (Swift Int is 64-bit vs Kotlin's 32-bit; Int.max is the same worst case.)
        let small = FirmwareVersion(parts: [0])
        let huge = FirmwareVersion(parts: [Int.max])
        XCTAssertTrue(small < huge)
        XCTAssertTrue(huge > small)
    }

    func test_compare_zeroPaddingRule_twoFiveGreaterThanTwoZeroFive() {
        // 2.5 → [2,5,0] padded; 2.0.5 → [2,0,5]; at index 1: 5 > 0
        let v25 = FirmwareVersion.parse("2.5")!
        let v205 = FirmwareVersion.parse("2.0.5")!
        XCTAssertTrue(v25 > v205, "2.5 must be > 2.0.5 (zero-padding rule)")
    }

    func test_compare_twoZeroFiveGreaterThanTwoZeroTwo() {
        let v205 = FirmwareVersion.parse("2.0.5")!
        let v202 = FirmwareVersion.parse("2.0.2")!
        XCTAssertTrue(v205 > v202, "2.0.5 must be > 2.0.2")
    }

    func test_compare_twoFiveEqualsWhenPaddedToTwoFiveZero() {
        // 2.5 == 2.5.0 after zero-padding
        let v25 = FirmwareVersion.parse("2.5")!
        let v250 = FirmwareVersion.parse("2.5.0")!
        XCTAssertEqual(0, v25.compare(to: v250), "2.5 and 2.5.0 must be equal after zero-padding")
    }

    func test_compare_knownTeHistoryOrder() {
        // Known TE version history (descending): 2.5 > 2.0.5 > 2.0.2 > 2.0.1 > 2.0 > 1.2.3
        let versions = ["2.5", "2.0.5", "2.0.2", "2.0.1", "2.0", "1.2.3"]
            .compactMap { FirmwareVersion.parse($0) }
        XCTAssertEqual(6, versions.count, "All 6 versions must parse")
        for i in 0..<(versions.count - 1) {
            XCTAssertTrue(
                versions[i] > versions[i + 1],
                "\(versions[i]) must be > \(versions[i + 1])"
            )
        }
    }
}
