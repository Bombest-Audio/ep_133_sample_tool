import SwiftUI
import WebKit

/// Hosts the EP-133 Sample Tool web app in a WKWebView with MIDI bridge
/// polyfill injected at document start.
struct EP133WebView: UIViewRepresentable {
    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> WKWebView {
        let coordinator = context.coordinator

        // --- Configuration ---
        let config = WKWebViewConfiguration()
        let prefs = WKWebpagePreferences()
        prefs.allowsContentAccessFromFileURLs = true
        config.defaultWebpagePreferences = prefs

        // --- Polyfill injection ---
        let userContentController = WKUserContentController()

        if let polyfillSource = Self.loadPolyfill() {
            let polyfillScript = WKUserScript(
                source: polyfillSource,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: true
            )
            userContentController.addUserScript(polyfillScript)
        }

        // --- MIDI bridge message handlers ---
        userContentController.add(coordinator.midiBridge, name: "midibridge")

        config.userContentController = userContentController

        // --- Create WebView ---
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.scrollView.backgroundColor = .black
        webView.scrollView.bounces = false

        #if DEBUG
        if #available(iOS 16.4, *) {
            webView.isInspectable = true
        }
        #endif

        // Give the bridge a reference to the WebView for pushing MIDI to JS
        coordinator.midiBridge.webView = webView

        // --- Load the web app ---
        if let dataDir = Self.dataDirectory(),
           let indexURL = Optional(dataDir.appendingPathComponent("index.html")),
           FileManager.default.fileExists(atPath: indexURL.path) {
            webView.loadFileURL(indexURL, allowingReadAccessTo: dataDir)
        } else {
            let errorHTML = """
            <html><body style="background:#000;color:#f55;font-family:sans-serif;
            padding:40px;text-align:center;">
            <h1>Error</h1><p>Could not locate web app data directory.</p>
            </body></html>
            """
            webView.loadHTMLString(errorHTML, baseURL: nil)
        }

        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    // MARK: - Helpers

    /// Loads the MIDI polyfill JavaScript from the app bundle.
    private static func loadPolyfill() -> String? {
        guard let url = Bundle.main.url(
            forResource: "MIDIBridgePolyfill",
            withExtension: "js",
            subdirectory: "Resources"
        ) ?? Bundle.main.url(
            forResource: "MIDIBridgePolyfill",
            withExtension: "js"
        ) else {
            print("[EP133] Warning: MIDIBridgePolyfill.js not found in bundle")
            return nil
        }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    /// Locates the `data/` directory containing web assets inside the app bundle.
    private static func dataDirectory() -> URL? {
        // First try: data/ folder reference in bundle
        if let dataURL = Bundle.main.url(forResource: "data", withExtension: nil) {
            return dataURL
        }
        // Fallback: look inside bundle resources path
        let bundlePath = Bundle.main.bundlePath
        let candidatePath = (bundlePath as NSString).appendingPathComponent("data")
        if FileManager.default.fileExists(atPath: candidatePath) {
            return URL(fileURLWithPath: candidatePath)
        }
        return nil
    }

    // MARK: - Coordinator

    class Coordinator {
        let midiBridge = MIDIBridge()
    }
}
