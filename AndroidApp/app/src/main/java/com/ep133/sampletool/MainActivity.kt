package com.ep133.sampletool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.midi.MidiManager
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.ProjectBackupManager
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.midi.MIDIManager
import com.ep133.sampletool.ui.EP133App
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.pads.PadsViewModel
import com.ep133.sampletool.ui.projects.ProjectsViewModel
import com.ep133.sampletool.ui.`import`.SampleImportViewModel
import com.ep133.sampletool.ui.kit.KitViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {

    private lateinit var midiManager: MIDIManager
    private lateinit var midiRepo: MIDIRepository

    private val mainHandler = Handler(Looper.getMainLooper())

    // Tracks whether onStart has run before, so we only re-acquire MIDI when *returning* to the
    // foreground (the first launch's connect flow is driven by onCreate + the USB-attach intent).
    private var startedOnce = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    mainHandler.postDelayed({ midiRepo.refreshDeviceState() }, 1000)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    midiRepo.refreshDeviceState()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val systemMidiManager = getSystemService(Context.MIDI_SERVICE) as MidiManager
        midiManager = MIDIManager(this, systemMidiManager)
        midiRepo = MIDIRepository(midiManager)

        val padsViewModel = PadsViewModel(midiRepo)
        val projectBackupManager = ProjectBackupManager(midiRepo)
        val projectsViewModel = ProjectsViewModel(midiRepo, projectBackupManager)
        val deviceViewModel = DeviceViewModel(midiRepo)
        val sampleImportManager = SampleImportManager(midiRepo)
        val sampleImportViewModel = SampleImportViewModel(midiRepo, sampleImportManager)
        val kitViewModel = KitViewModel(midiRepo, sampleImportManager)

        // SAF launchers — MUST be registered before setContent (Activity lifecycle constraint).
        // See STATE.md decision: "SAF launchers must be registered before setContent() in MainActivity"

        val backupLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? -> uri?.let { deviceViewModel.onBackupUriSelected(it, this) } }

        val restoreLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? -> uri?.let { deviceViewModel.onRestoreUriSelected(it, this) } }

        val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> -> sampleImportViewModel.onFilesPicked(uris, this) }

        // Kit SAF launchers: single-file picker for chop mode, multi-file picker for kit mode.
        val kitLoopLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? -> uri?.let { kitViewModel.onLoopFilePicked(it, this) } }

        val kitFilesLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> -> kitViewModel.onKitFilesPicked(uris, this) }

        deviceViewModel.onRequestBackup = { name -> backupLauncher.launch(name) }
        deviceViewModel.onRequestRestore = { restoreLauncher.launch(arrayOf("*/*")) }
        sampleImportViewModel.onRequestPick = { importLauncher.launch(arrayOf("audio/*")) }
        deviceViewModel.onOpenFirmwareUpdater = {
            try {
                val customTabsIntent = CustomTabsIntent.Builder().build()
                customTabsIntent.launchUrl(this, Uri.parse("https://teenage.engineering/apps/update"))
            } catch (e: Exception) {
                deviceViewModel.showSnackbar("No browser available to open the updater")
            }
        }
        kitViewModel.onRequestLoopPick = { kitLoopLauncher.launch(arrayOf("audio/*")) }
        kitViewModel.onRequestKitPick = { kitFilesLauncher.launch(arrayOf("audio/*")) }

        setContent {
            val deviceState by midiRepo.deviceState.collectAsState()
            EP133App(
                padsViewModel = padsViewModel,
                projectsViewModel = projectsViewModel,
                deviceViewModel = deviceViewModel,
                sampleImportViewModel = sampleImportViewModel,
                kitViewModel = kitViewModel,
                isConnected = deviceState.connected,
            )
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Enumerate MIDI devices after USB permission grant delay
        mainHandler.postDelayed({ midiRepo.refreshDeviceState() }, 2000)
    }

    override fun onStart() {
        super.onStart()
        // USB-MIDI ports are exclusive, and the WebView (SampleManagerActivity) owns a second
        // MIDIManager. Re-claim the port when we come back to the foreground. Skip the very first
        // onStart — onCreate + the USB-attach intent handle the initial connect.
        if (startedOnce) reacquireMidi(attempt = 0)
        startedOnce = true
    }

    override fun onStop() {
        super.onStop()
        // Drop any queued reacquire/refresh callbacks — they must not fire after the port is
        // released, or they'd fight the foreground owner for the exclusive USB-MIDI port.
        mainHandler.removeCallbacksAndMessages(null)
        // Release the exclusive USB-MIDI port so the foreground owner can claim it. Re-acquired
        // in onStart. (See SampleManagerActivity for the matching half.)
        midiManager.releasePorts()
    }

    /**
     * Re-open the device + receive port after returning to the foreground. Retried twice because
     * our onStart fires *before* the outgoing activity's onStop releases the port (the launch-order
     * race); by the second retry it has released. refreshDevices() is idempotent (dedups ports).
     */
    private fun reacquireMidi(attempt: Int) {
        midiManager.refreshDevices()
        if (attempt < 2) mainHandler.postDelayed({ reacquireMidi(attempt + 1) }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
        }
        midiRepo.close()
    }
}
