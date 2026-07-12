import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp's ScaleLockTest.kt — scale lock computation, pure math, no production
/// class dependency beyond Scale/EP133Scales.

/// Compute the set of pitch classes (0-11) that are in the given scale starting at the given root.
func computeInScaleSet(scale: Scale, rootNoteName: String) -> Set<Int> {
    guard let rootIndex = EP133Scales.ROOT_NOTES.firstIndex(of: rootNoteName) else { return [] }
    return Set(scale.intervals.map { (rootIndex + $0) % 12 })
}

final class ScaleLockTests: XCTestCase {

    func test_computeInScaleSet_cMajor_yields0_2_4_5_7_9_11() {
        let major = EP133Scales.ALL.first { $0.id == "major" }!
        let result = computeInScaleSet(scale: major, rootNoteName: "C")
        XCTAssertEqual(Set([0, 2, 4, 5, 7, 9, 11]), result)
    }

    func test_note60_cMajor_isInScale() {
        let major = EP133Scales.ALL.first { $0.id == "major" }!
        let inScaleSet = computeInScaleSet(scale: major, rootNoteName: "C")
        // C4 = note 60, pitch class = 60 % 12 = 0
        XCTAssertTrue(inScaleSet.contains(60 % 12))
    }

    func test_note61_cMajor_notInScale() {
        let major = EP133Scales.ALL.first { $0.id == "major" }!
        let inScaleSet = computeInScaleSet(scale: major, rootNoteName: "C")
        // C#4 = note 61, pitch class = 61 % 12 = 1, not in C major
        XCTAssertFalse(inScaleSet.contains(61 % 12))
    }

    func test_chromatic_allNotesInScale() {
        let chromatic = EP133Scales.ALL.first { $0.id == "chromatic" }!
        let inScaleSet = computeInScaleSet(scale: chromatic, rootNoteName: "C")
        for pitchClass in 0...11 {
            XCTAssertTrue(inScaleSet.contains(pitchClass),
                          "Pitch class \(pitchClass) should be in chromatic scale")
        }
    }

    func test_noScaleSelected_allPadsShow() {
        // When inScaleSet is empty (no scale selected), isInScale is true for all pads (per D-29)
        let inScaleSet = Set<Int>()
        for note in 36...83 {
            let isInScale = inScaleSet.isEmpty || inScaleSet.contains(note % 12)
            XCTAssertTrue(isInScale, "Note \(note) should show as in-scale when no scale selected")
        }
    }
}
