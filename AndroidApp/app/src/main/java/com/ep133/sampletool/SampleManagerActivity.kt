package com.ep133.sampletool

import android.content.Context
import android.content.Intent
import android.media.midi.MidiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.activity.ComponentActivity
import com.ep133.sampletool.midi.MIDIManager
import com.ep133.sampletool.webview.EP133WebViewSetup
import com.ep133.sampletool.webview.MIDIBridge

/**
 * Standalone WebView activity for the legacy Sample Manager UI.
 * Launched from the Device screen for Backup/Restore/Sync/Format operations
 * that use the original compiled web app (data/index.html).
 */
class SampleManagerActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var midiManager: MIDIManager
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val systemMidi = getSystemService(Context.MIDI_SERVICE) as MidiManager
        midiManager = MIDIManager(this, systemMidi)
        val bridge = MIDIBridge(midiManager, webView)

        midiManager.onMidiReceived = { portId, data ->
            bridge.forwardMIDIToJS(portId, data)
        }
        midiManager.onDevicesChanged = {
            bridge.notifyDevicesChanged()
        }

        EP133WebViewSetup.configure(this, webView, bridge)

        // Load the original web app (data/index.html), not mobile.html
        webView.loadUrl("https://appassets.androidplatform.net/assets/data/index.html")
    }

    override fun onStart() {
        super.onStart()
        // Re-claim the exclusive USB-MIDI port whenever the WebView is foregrounded. Retried
        // because MainActivity still holds the port until its onStop fires (the launch-order race).
        reacquireMidi(attempt = 0)
    }

    override fun onStop() {
        super.onStop()
        // Hand the port back so the native UI (MainActivity) can re-claim it. Re-acquired in onStart.
        midiManager.releasePorts()
    }

    private fun reacquireMidi(attempt: Int) {
        midiManager.refreshDevices()
        if (attempt < 2) mainHandler.postDelayed({ reacquireMidi(attempt + 1) }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        midiManager.close()
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, SampleManagerActivity::class.java))
        }
    }
}
