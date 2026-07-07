import Foundation

/// Abstraction over "what is the latest published firmware version?"
/// Implemented by `TeFirmwareCatalog`; tests inject a fake.
///
/// The return is optional on purpose: it is the seam the device view model check treats
/// defensively (a nil latest → Unknown, no banner), and it lets a fake return nil to
/// exercise that path. The production `TeFirmwareCatalog` floors to `LATEST_KNOWN_FIRMWARE`
/// and so never actually returns nil.
///
/// Mirrors AndroidApp/app/src/main/java/com/ep133/sampletool/domain/firmware/TeFirmwareCatalog.kt.
protocol FirmwareCatalog {
    func latestVersion() async -> FirmwareVersion?
}

/// Bundled floor: the most recent firmware version known at ship time. Bump on each app release.
let LATEST_KNOWN_FIRMWARE = FirmwareVersion.parse("2.5")!

/// Applies the bundled floor so a missing or stale manifest never reports a version older than what
/// the app already knows about. This is the FW-02 offline-detection guarantee: with no network — or
/// before the manifest is ever published — the user still gets a meaningful comparison against 2.5.
func floorToBundled(_ fetched: FirmwareVersion?) -> FirmwareVersion {
    if let fetched, fetched > LATEST_KNOWN_FIRMWARE { return fetched }
    return LATEST_KNOWN_FIRMWARE
}

/// EP-133 / EP-133 K.O. II SKU in TE's release manifest.
private let EP133_SKU = "TE032AS001"

/// Parses TE's firmware release manifest (see `TeFirmwareCatalog.RELEASES_URL`) and returns the
/// latest published EP-133 firmware version. The manifest is `{ "devices": [ { "sku", "version",
/// "fw_url", … }, … ] }`; we pick the EP-133 record by its SKU and read `version`. Returns
/// nil if the EP-133 record or its version is absent or unparseable.
///
/// Hand-rolled regexes (rather than JSONSerialization) to stay byte-for-byte faithful to the
/// Kotlin parser, which is a pure function unit-tested on raw strings. Each device object is flat
/// (no nested braces), so matching brace-delimited objects isolates them; the sku/version lookup
/// within an object is order-independent.
func parseReleases(_ json: String) -> FirmwareVersion? {
    // Patterns are constant (the SKU literal is regex-escaped), so compilation cannot fail.
    let skuPattern = NSRegularExpression.escapedPattern(for: EP133_SKU)
    let skuPresent = try! NSRegularExpression(pattern: "\"sku\"\\s*:\\s*\"\(skuPattern)\"")
    let version = try! NSRegularExpression(pattern: "\"version\"\\s*:\\s*\"([^\"]+)\"")
    let objects = try! NSRegularExpression(pattern: "\\{[^{}]*\\}")

    let nsJson = json as NSString
    for match in objects.matches(in: json, range: NSRange(location: 0, length: nsJson.length)) {
        let obj = nsJson.substring(with: match.range)
        let objRange = NSRange(location: 0, length: (obj as NSString).length)
        if skuPresent.firstMatch(in: obj, range: objRange) != nil {
            guard let v = version.firstMatch(in: obj, range: objRange) else { return nil }
            let captured = (obj as NSString).substring(with: v.range(at: 1))
            return FirmwareVersion.parse(captured)
        }
    }
    return nil
}

/// Resolves the latest published EP-133 firmware version from TE's own release manifest, keyed by
/// the EP-133 SKU. This is TE's authoritative source (the same JSON their update utility reads), so
/// it tracks new firmware the moment TE ships it — no app release and nothing for us to host or
/// maintain.
///
/// Uses URLSession async/await (the HttpURLConnection-on-Dispatchers.IO analog) with a 5 s request
/// timeout. Any failure is logged and resolved to `LATEST_KNOWN_FIRMWARE` by the floor. One
/// deviation from the Kotlin: Kotlin rethrows CancellationException, but a non-throwing Swift
/// async function cannot rethrow, so cancellation also resolves to the floor — callers observe
/// cancellation through their own Task.
final class TeFirmwareCatalog: FirmwareCatalog {

    /// TE's authoritative firmware manifest — a `devices` array keyed by SKU.
    static let RELEASES_URL = "https://teenage.engineering/_software/releases.json"

    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func latestVersion() async -> FirmwareVersion? {
        let fetched: FirmwareVersion?
        do {
            var request = URLRequest(url: URL(string: Self.RELEASES_URL)!)
            request.timeoutInterval = 5
            let (data, response) = try await session.data(for: request)
            let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 200
            if statusCode != 200 {
                print("[EP133APP] FW releases: HTTP \(statusCode)")
                fetched = nil
            } else if let body = String(data: data, encoding: .utf8) {
                fetched = parseReleases(body)
            } else {
                fetched = nil
            }
        } catch {
            print("[EP133APP] FW releases fetch failed: \(error)")
            fetched = nil
        }
        return floorToBundled(fetched)
    }
}
