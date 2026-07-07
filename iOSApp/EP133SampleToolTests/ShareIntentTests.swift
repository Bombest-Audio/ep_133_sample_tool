import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/ShareIntentTest.kt.
///
/// Share payload contract (PROJ-04).
///
/// Asserts the actual `SHARE_MIME` constant the share flow declares — an opaque octet-stream
/// (T-04-09), never a typed/over-broad MIME. On Android it feeds
/// `ShareCompat.IntentBuilder(...).setType(SHARE_MIME)`; on iOS the ShareLink share sheet
/// derives the type from the exported `.tar` file URL and the constant remains the
/// cross-platform contract.
///
/// The Android FileProvider URI + ACTION_SEND construction test needs a real Context and a
/// registered FileProvider; the iOS analog (presenting the share sheet) needs a live UI host —
/// so, like the Kotlin @Ignore, it's XCTSkip'd here and validated on a physical device.
final class ShareIntentTests: XCTestCase {

    func test_shareMimeType_isOctetStream() {
        // ProjectsScreen shares the backup as an opaque octet-stream (ShareLink on the .tar URL;
        // Android: ShareCompat.IntentBuilder(context).setType(SHARE_MIME).setStream(uri)...).
        XCTAssertEqual("application/octet-stream", SHARE_MIME)
    }

    func test_buildsFileProviderUri_andActionSend() throws {
        // FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        // ShareCompat.IntentBuilder(context).setType("application/octet-stream").setStream(uri)...
        // Assert intent.action == ACTION_SEND and the data URI scheme is content://
        throw XCTSkip(
            "ShareCompat/FileProvider intent construction is Android-only; the iOS share sheet "
                + "(ShareLink) requires a live UI host — validated on an instrumented/physical "
                + "device (mirrors the Kotlin @Ignore)")
    }
}
