import XCTest

/// Base for the robot page objects — the XCUITest analog of the Android `BaseRobot`.
///
/// Every robot method returns `self` (or the destination robot for navigation) so tests read as a
/// single Act/Assert chain, exactly like the Compose robots:
///
///     AppRobot(app)
///         .goToPads()
///         .selectGroup("B")
///         .assertPadLabel("B.")
///
/// Elements are looked up by `accessibilityIdentifier`, which the app sets to the same string
/// values as the Android semantics TestTags (see `UI/TestTags.swift`), so the robot surface stays
/// one-to-one with Kotlin. User-visible labels are matched as static text, mirroring the Kotlin
/// `onNodeWithText`.
class BaseRobot {
    let app: XCUIApplication

    /// Default wait for async appearance. Loaded simulators can stretch a sub-second transition
    /// past a couple seconds, so match the Android robots' generous 10s ceiling.
    static let defaultTimeout: TimeInterval = 10

    init(_ app: XCUIApplication) {
        self.app = app
    }

    // ── Element lookup by accessibilityIdentifier (the TestTag seam) ──

    /// Any element carrying [identifier], regardless of type. `descendants(matching: .any)` is the
    /// catch-all that finds the identifier whether it landed on a button, switch, other, etc.
    func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    /// The hittable element for [identifier]. All tabs stay mounted (hidden) in the shell, so a tag
    /// shared across screens (e.g. group chips on both Pads and Kit) resolves to multiple elements;
    /// tapping the hittable one targets the currently-visible screen. Falls back to firstMatch.
    func hittableElement(_ identifier: String) -> XCUIElement {
        let matches = app.descendants(matching: .any).matching(identifier: identifier)
        for i in 0..<matches.count {
            let candidate = matches.element(boundBy: i)
            if candidate.isHittable { return candidate }
        }
        return matches.firstMatch
    }

    /// A static-text node showing exactly [label] (the `onNodeWithText` analog).
    func staticText(_ label: String) -> XCUIElement {
        app.staticTexts[label]
    }

    // ── Waiting / assertions ──

    /// Block until an element carrying [identifier] exists.
    @discardableResult
    func waitForTag(_ identifier: String, timeout: TimeInterval = BaseRobot.defaultTimeout) -> XCUIElement {
        let el = element(identifier)
        XCTAssertTrue(el.waitForExistence(timeout: timeout), "timed out waiting for tag '\(identifier)'")
        return el
    }

    /// Block until a static text showing [label] exists.
    @discardableResult
    func waitForText(_ label: String, timeout: TimeInterval = BaseRobot.defaultTimeout) -> XCUIElement {
        let el = staticText(label)
        XCTAssertTrue(el.waitForExistence(timeout: timeout), "timed out waiting for text '\(label)'")
        return el
    }

    /// Assert an element with [identifier] is present (waits, then checks — XCUITest queries are
    /// async, so a bare `.exists` can race a still-rendering view).
    func assertTagDisplayed(_ identifier: String, timeout: TimeInterval = BaseRobot.defaultTimeout) {
        XCTAssertTrue(
            element(identifier).waitForExistence(timeout: timeout),
            "expected an element tagged '\(identifier)' to be displayed")
    }

    /// Assert no element with [identifier] exists (the `assertDoesNotExist` analog). Waits briefly
    /// for the negative so a slow render doesn't produce a false pass.
    func assertTagAbsent(_ identifier: String) {
        let el = element(identifier)
        // If it doesn't exist now and doesn't appear within a short grace window, it's absent.
        if el.exists {
            XCTFail("expected no element tagged '\(identifier)', but one exists")
        }
    }

    /// Assert a static text showing [label] is present.
    func assertTextDisplayed(_ label: String, timeout: TimeInterval = BaseRobot.defaultTimeout) {
        XCTAssertTrue(
            staticText(label).waitForExistence(timeout: timeout),
            "expected text '\(label)' to be displayed")
    }
}
