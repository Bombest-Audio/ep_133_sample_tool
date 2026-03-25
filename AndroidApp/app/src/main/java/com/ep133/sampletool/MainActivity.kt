package com.ep133.sampletool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.midi.MidiManager
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.ep133.sampletool.midi.MIDIManager
import com.ep133.sampletool.webview.EP133WebViewSetup
import com.ep133.sampletool.webview.MIDIBridge

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var midiManager: MIDIManager
    private lateinit var midiBridge: MIDIBridge

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // Delay slightly to let the system enumerate the device
                    webView.postDelayed({ midiManager.refreshDevices() }, 1000)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    midiManager.refreshDevices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        // Initialize MIDI
        val systemMidiManager = getSystemService(Context.MIDI_SERVICE) as MidiManager
        midiManager = MIDIManager(this, systemMidiManager)

        // Set up the MIDI bridge and WebView
        midiBridge = MIDIBridge(midiManager, webView)

        midiManager.onMidiReceived = { portId, data ->
            midiBridge.forwardMIDIToJS(portId, data)
        }

        // When MIDI devices change, tell the web app to re-query
        midiManager.onDevicesChanged = {
            midiBridge.notifyDevicesChanged()
        }

        EP133WebViewSetup.configure(this, webView, midiBridge)
        EP133WebViewSetup.loadApp(this, webView)

        // Listen for USB device events
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Request USB permissions for any already-connected devices
        webView.postDelayed({ midiManager.requestUSBPermissions() }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {}
        midiManager.close()
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
