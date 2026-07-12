import XCTest
@testable import EP133SampleTool

/// Regression for issue #28: GroupSession selection observers must be removable.
///
/// `addSelectionObserver` previously appended to an array with no deregister path, so entries
/// accumulated forever once a VM holding one was recreated. It now returns a token and
/// `removeSelectionObserver(token)` drops it. A nil `defaults` keeps the session in memory.
@MainActor
final class GroupSessionObserverTests: XCTestCase {

    func test_addObserver_firesOnSelect() {
        let session = GroupSession()
        var fired: [PadChannel] = []
        session.addSelectionObserver { fired.append($0) }

        session.select(.B)
        session.select(.C)

        XCTAssertEqual([.B, .C], fired)
    }

    func test_removeObserver_stopsFurtherCallbacks() {
        let session = GroupSession()
        var fired: [PadChannel] = []
        let token = session.addSelectionObserver { fired.append($0) }

        session.select(.B)
        session.removeSelectionObserver(token)
        session.select(.C)

        XCTAssertEqual([.B], fired, "no callback should fire after the observer is removed")
    }

    func test_removeObserver_leavesOtherObserversRegistered() {
        let session = GroupSession()
        var kept: [PadChannel] = []
        var dropped: [PadChannel] = []
        session.addSelectionObserver { kept.append($0) }
        let dropToken = session.addSelectionObserver { dropped.append($0) }

        session.select(.B)
        session.removeSelectionObserver(dropToken)
        session.select(.D)

        XCTAssertEqual([.B, .D], kept, "the surviving observer keeps firing")
        XCTAssertEqual([.B], dropped, "the removed observer stops firing")
    }

    func test_removeUnknownToken_isNoOp() {
        let session = GroupSession()
        var fired: [PadChannel] = []
        session.addSelectionObserver { fired.append($0) }

        session.removeSelectionObserver(UUID())  // never registered
        session.select(.B)

        XCTAssertEqual([.B], fired)
    }
}
