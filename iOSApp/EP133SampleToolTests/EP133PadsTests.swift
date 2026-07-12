import XCTest
@testable import EP133SampleTool

/// Regression tests for EP133Pads after removing the SPECIAL_PAD0 mapping.
/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/EP133PadsTest.kt.
///
/// Hardware ground truth (adb capture, Pixel + EP-133):
///   A0 pressed → MIDI IN note=37 ch=0  (A.baseNote=36 + offset=1)
///   The earlier note-60/ch-6 behavior was never present on the device.
final class EP133PadsTests: XCTestCase {

    // ── padsForChannel — A group ───────────────────────────────────────────────

    func testA0_has_note_37() {
        let pads = EP133Pads.padsForChannel(.A)
        let a0 = pads.first { $0.label == "A0" }!
        XCTAssertEqual(37, a0.note)
    }

    func testADot_has_note_36() {
        let pads = EP133Pads.padsForChannel(.A)
        let aDot = pads.first { $0.label == "A." }!
        XCTAssertEqual(36, aDot.note)
    }

    func testA1_has_note_39() {
        let pads = EP133Pads.padsForChannel(.A)
        let a1 = pads.first { $0.label == "A1" }!
        XCTAssertEqual(39, a1.note)
    }

    func testNo_A_pad_has_note_60() {
        let pads = EP133Pads.padsForChannel(.A)
        XCTAssertTrue(pads.allSatisfy { $0.note != 60 }, "No A pad should have note 60")
    }

    // ── padsForChannel — D group ───────────────────────────────────────────────

    func testD0_has_note_73() {
        let pads = EP133Pads.padsForChannel(.D)
        let d0 = pads.first { $0.label == "D0" }!
        XCTAssertEqual(73, d0.note)
    }

    // ── No pad on channel 6 or 7 (across all groups) ──────────────────────────

    func testNo_pad_on_channel_6_or_7() {
        let allPads = PadChannel.allCases.flatMap { EP133Pads.padsForChannel($0) }
        let special = allPads.filter { $0.midiChannel == 6 || $0.midiChannel == 7 }
        XCTAssertTrue(special.isEmpty, "No pad should be on ch 6 or 7, found: \(special)")
    }

    // ── resolveIncoming ────────────────────────────────────────────────────────

    func testResolveIncoming_37_ch0_returns_A_pad0() {
        let result = EP133Pads.resolveIncoming(note: 37, ch: 0)
        XCTAssertNotNil(result)
        let (group, idx) = result!
        XCTAssertEqual(PadChannel.A, group)
        // The "0" pad has offset=1; verify the returned index is correct by
        // checking the pad at that index in a fresh list.
        let pad = EP133Pads.padsForChannel(.A)[idx]
        XCTAssertEqual("A0", pad.label)
    }

    func testResolveIncoming_48_ch0_returns_B_dotPad() {
        let result = EP133Pads.resolveIncoming(note: 48, ch: 0)
        XCTAssertNotNil(result)
        let (group, idx) = result!
        XCTAssertEqual(PadChannel.B, group)
        let pad = EP133Pads.padsForChannel(.B)[idx]
        XCTAssertEqual("B.", pad.label)
    }

    func testResolveIncoming_60_ch0_returns_C_dotPad() {
        // note 60 is now unambiguously C's base note (C.baseNote=60, offset=0 → ".")
        let result = EP133Pads.resolveIncoming(note: 60, ch: 0)
        XCTAssertNotNil(result)
        let (group, idx) = result!
        XCTAssertEqual(PadChannel.C, group)
        let pad = EP133Pads.padsForChannel(.C)[idx]
        XCTAssertEqual("C.", pad.label)
    }

    func testResolveIncoming_out_of_range_returns_null() {
        XCTAssertNil(EP133Pads.resolveIncoming(note: 0, ch: 0))
        XCTAssertNil(EP133Pads.resolveIncoming(note: 35, ch: 0))
        XCTAssertNil(EP133Pads.resolveIncoming(note: 84, ch: 0))
        XCTAssertNil(EP133Pads.resolveIncoming(note: 127, ch: 0))
    }
}
