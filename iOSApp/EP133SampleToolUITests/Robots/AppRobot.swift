import XCTest

/// Robot for the app shell: bottom-tab navigation, header title, connection indicator, SKU + theme
/// toggles. XCUITest port of `AppRobot.kt`; method names/semantics match one-to-one.
final class AppRobot: BaseRobot {

    // ── Navigation (returns the destination robot for chaining) ──

    @discardableResult
    func goToPads() -> PadsRobot {
        element(TestTags.NAV_PADS).tap()
        return PadsRobot(app)
    }

    @discardableResult
    func goToProjects() -> ProjectsRobot {
        element(TestTags.NAV_PROJECTS).tap()
        return ProjectsRobot(app)
    }

    @discardableResult
    func goToDevice() -> DeviceRobot {
        element(TestTags.NAV_DEVICE).tap()
        return DeviceRobot(app)
    }

    @discardableResult
    func goToKit() -> KitRobot {
        element(TestTags.NAV_KIT).tap()
        return KitRobot(app)
    }

    // ── Header ──

    @discardableResult
    func assertTitle(_ title: String) -> AppRobot {
        // The header title element carries HEADER_TITLE; its label is the uppercased screen name.
        let el = waitForTag(TestTags.HEADER_TITLE)
        XCTAssertEqual(el.label, title, "header title should be '\(title)'")
        return self
    }

    @discardableResult
    func assertConnectionLabel(_ label: String) -> AppRobot {
        // The connection pill combines its dot + text into one element; its label contains the
        // status word ("CONNECTED" / "NO DEVICE").
        let el = waitForTag(TestTags.HEADER_CONNECTION)
        XCTAssertTrue(
            el.label.localizedCaseInsensitiveContains(label),
            "connection label should contain '\(label)' (was '\(el.label)')")
        return self
    }

    @discardableResult
    func toggleSkuBadge() -> AppRobot {
        element(TestTags.HEADER_SKU_BADGE).tap()
        return self
    }

    @discardableResult
    func assertSkuBadge(_ label: String) -> AppRobot {
        // The Button collapses its child Text out of the accessibility tree and its label is the
        // "switch to…" action string, so the current SKU is read from the accessibilityValue.
        let badge = waitForTag(TestTags.HEADER_SKU_BADGE)
        XCTAssertEqual(badge.value as? String, label, "SKU badge should show '\(label)'")
        return self
    }

    @discardableResult
    func cycleTheme() -> AppRobot {
        element(TestTags.HEADER_THEME_TOGGLE).tap()
        return self
    }

    @discardableResult
    func assertHeaderVisible() -> AppRobot {
        assertTagDisplayed(TestTags.HEADER_TITLE)
        return self
    }
}
