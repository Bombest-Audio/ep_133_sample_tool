package com.ep133.sampletool.webview

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.ep133.sampletool.midi.MIDIPort
import org.json.JSONArray
import org.json.JSONObject

/**
 * JavaScript interface exposed to the WebView as `window.EP133Bridge`.
 *
 * The shared polyfill detects this object and routes MIDI calls through it:
 *   - getMidiDevices() → returns JSON string of available MIDI ports
 *   - sendMidi(portId, dataJson) → sends MIDI bytes to a device
 *
 * Incoming MIDI is pushed to JS via [forwardMIDIToJS].
 */
class MIDIBridge(
    private val midiManager: MIDIPort,
    private val webView: WebView
) {

    @JavascriptInterface
    fun getMidiDevices(): String {
        val devices = midiManager.getUSBDevices()

        val inputs = JSONArray().apply {
            devices.inputs.forEach { d ->
                put(JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                })
            }
        }

        val outputs = JSONArray().apply {
            devices.outputs.forEach { d ->
                put(JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                })
            }
        }

        return JSONObject().apply {
            put("inputs", inputs)
            put("outputs", outputs)
        }.toString()
    }

    @JavascriptInterface
    fun sendMidi(portId: String, dataJson: String) {
        val arr = JSONArray(dataJson)
        val bytes = ByteArray(arr.length()) { i ->
            arr.getInt(i).toByte()
        }
        midiManager.sendMidi(portId, bytes)
    }

    /**
     * Pushes incoming MIDI data from a device to the WebView's JavaScript.
     * Called from [MIDIManager.onMidiReceived].
     */
    fun forwardMIDIToJS(portId: String, data: ByteArray) {
        val dataArray = data.joinToString(",") { (it.toInt() and 0xFF).toString() }
        val escapedPortId = portId.replace("'", "\\'")
        val js = "window.__ep133_onMidiIn('$escapedPortId', [$dataArray])"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    /**
     * Notifies the web app that MIDI devices have changed.
     * Triggers the polyfill's statechange handler and also dispatches a
     * custom event the web app can listen for.
     */
    fun notifyDevicesChanged() {
        val js = """
            if (window.__ep133_onDevicesChanged) {
                window.__ep133_onDevicesChanged();
            }
        """.trimIndent()
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
