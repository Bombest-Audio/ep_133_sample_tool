import WebKit
import CoreMIDI

/// Bridges JavaScript MIDI calls from the WebView to CoreMIDI.
///
/// The polyfill sends messages via:
///   window.webkit.messageHandlers.midibridge.postMessage({action, callbackId, portId, data})
///
/// This handler processes them and resolves callbacks via:
///   window.__ep133_resolveCallback(callbackId, jsonString)
///
/// Incoming MIDI is pushed to JS via:
///   window.__ep133_onMidiIn(portId, [bytes])
final class MIDIBridge: NSObject, WKScriptMessageHandler {

    weak var webView: WKWebView?
    private let midiManager = MIDIManager()

    override init() {
        super.init()
        midiManager.onMIDIReceived = { [weak self] portId, data in
            self?.forwardMIDIToJS(portId: portId, data: data)
        }
        midiManager.onDevicesChanged = { [weak self] in
            // Notify the web app that devices changed so it can re-enumerate
            self?.evaluateJS("if (navigator.requestMIDIAccess) { console.log('[EP133] MIDI devices changed'); }")
        }
        midiManager.setup()
    }

    // MARK: - WKScriptMessageHandler

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        guard let body = message.body as? [String: Any],
              let action = body["action"] as? String else {
            return
        }

        switch action {
        case "getMidiDevices":
            handleGetMidiDevices(callbackId: body["callbackId"] as? String)

        case "sendMidi":
            handleSendMidi(
                portId: body["portId"] as? String,
                data: body["data"] as? [Int]
            )

        default:
            print("[EP133] Unknown MIDI bridge action: \(action)")
        }
    }

    // MARK: - Action Handlers

    private func handleGetMidiDevices(callbackId: String?) {
        let devices = midiManager.getUSBDevices()

        let inputsJSON = devices.inputs.map { d in
            "{\"id\":\"\(escapeJS(d.id))\",\"name\":\"\(escapeJS(d.name))\"}"
        }.joined(separator: ",")

        let outputsJSON = devices.outputs.map { d in
            "{\"id\":\"\(escapeJS(d.id))\",\"name\":\"\(escapeJS(d.name))\"}"
        }.joined(separator: ",")

        let json = "{\"inputs\":[\(inputsJSON)],\"outputs\":[\(outputsJSON)]}"

        if let cbId = callbackId {
            resolveCallback(callbackId: cbId, json: json)
        }
    }

    private func handleSendMidi(portId: String?, data: [Int]?) {
        guard let portId = portId, let data = data else { return }
        let bytes = data.map { UInt8(clamping: $0) }
        midiManager.sendMIDI(to: portId, data: bytes)
    }

    // MARK: - JS Communication

    private func forwardMIDIToJS(portId: String, data: [UInt8]) {
        let dataArray = data.map { String($0) }.joined(separator: ",")
        let js = "window.__ep133_onMidiIn('\(escapeJS(portId))', [\(dataArray)])"
        evaluateJS(js)
    }

    private func resolveCallback(callbackId: String, json: String) {
        let escapedJSON = json.replacingOccurrences(of: "'", with: "\\'")
        let js = "window.__ep133_resolveCallback('\(escapeJS(callbackId))', '\(escapedJSON)')"
        evaluateJS(js)
    }

    private func evaluateJS(_ script: String) {
        DispatchQueue.main.async { [weak self] in
            self?.webView?.evaluateJavaScript(script)
        }
    }

    private func escapeJS(_ str: String) -> String {
        str.replacingOccurrences(of: "\\", with: "\\\\")
           .replacingOccurrences(of: "'", with: "\\'")
           .replacingOccurrences(of: "\"", with: "\\\"")
           .replacingOccurrences(of: "\n", with: "\\n")
    }
}
