package com.ep133.sampletool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
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
import com.ep133.sampletool.domain.project.PrefsProjectNameStore
import com.ep133.sampletool.midi.MIDIManager
import com.ep133.sampletool.midi.MIDIPort
import com.ep133.sampletool.midi.SimulatedPortProvider
import com.ep133.sampletool.ui.EP133App
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.pads.PadsViewModel
import com.ep133.sampletool.ui.projects.ProjectsViewModel
import com.ep133.sampletool.ui.kit.GroupSession
import com.ep133.sampletool.ui.kit.KitViewModel
import com.ep133.sampletool.ui.kitbuilder.KitBuilderViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {

    private lateinit var midiManager: MIDIManager
    private lateinit var midiRepo: MIDIRepository

    // Active simulator port (debug builds only) — non-null while the Simulated EP-133
    // toggle is on. A fresh simulator is created per activation and closed on deactivation.
    private var simPort: MIDIPort? = null

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
        val projectNameStore = PrefsProjectNameStore(this)
        val projectsViewModel = ProjectsViewModel(midiRepo, projectBackupManager, projectNameStore)
        val deviceViewModel = DeviceViewModel(midiRepo)
        val sampleImportManager = SampleImportManager(midiRepo)
        // ONE GroupSession shared by the chopper and the Kit Builder — group selection, per-group
        // CHOP/KIT designation, and choke are a single source of truth, persisted across launches.
        val groupSession = GroupSession(getSharedPreferences("ep133_group_session", MODE_PRIVATE))
        val kitViewModel = KitViewModel(midiRepo, sampleImportManager, groupSession)
        val kitBuilderViewModel = KitBuilderViewModel(midiRepo, sampleImportManager, groupSession)

        // SAF launchers — MUST be registered before setContent (Activity lifecycle constraint).
        // See STATE.md decision: "SAF launchers must be registered before setContent() in MainActivity"

        val backupLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? -> uri?.let { deviceViewModel.onBackupUriSelected(it, this) } }

        val restoreLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? -> uri?.let { deviceViewModel.onRestoreUriSelected(it, this) } }

        // Kit SAF launchers: single-file picker for chop mode, multi-file picker for kit mode.
        val kitLoopLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? -> uri?.let { kitViewModel.onLoopFilePicked(it) } }

        val kitFilesLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> -> kitViewModel.onKitFilesPicked(uris, this) }

        // Kit Builder: whole-folder pack picker. Persist the grant so pack URIs stay readable
        // across sessions (audition + load happen well after the picker callback returns).
        val packLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri: Uri? ->
            uri?.let {
                try {
                    contentResolver.takePersistableUriPermission(
                        it, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (e: SecurityException) {
                    Log.w("EP133APP", "takePersistableUriPermission failed", e)
                }
                kitBuilderViewModel.onPackPicked(it, this)
            }
        }

        deviceViewModel.onRequestBackup = { name -> backupLauncher.launch(name) }
        deviceViewModel.onRequestRestore = { restoreLauncher.launch(arrayOf("*/*")) }
        deviceViewModel.onOpenFirmwareUpdater = {
            try {
                val customTabsIntent = CustomTabsIntent.Builder().build()
                customTabsIntent.launchUrl(this, Uri.parse("https://teenage.engineering/apps/update"))
            } catch (e: Exception) {
                deviceViewModel.showSnackbar("No browser available to open the updater")
            }
        }
        // Simulated EP-133 toggle (debug builds only — the release SimulatedPortProvider
        // reports available=false, so this wiring never installs and the toggle stays hidden).
        // Real USB remains the default: nothing runs until the user flips the switch.
        if (SimulatedPortProvider.available) {
            deviceViewModel.onSimToggle = { enabled ->
                if (enabled) {
                    SimulatedPortProvider.create()?.let { sim ->
                        simPort = sim
                        midiRepo.swapPort(sim)
                    }
                } else {
                    midiRepo.swapPort(midiManager)
                    simPort?.close()
                    simPort = null
                }
            }
        }
        kitViewModel.onRequestLoopPick = { kitLoopLauncher.launch(arrayOf("audio/*")) }
        kitViewModel.onRequestKitPick = { kitFilesLauncher.launch(arrayOf("audio/*")) }
        kitBuilderViewModel.onRequestPackPick = { packLauncher.launch(null) }

        setContent {
            val deviceState by midiRepo.deviceState.collectAsState()
            EP133App(
                padsViewModel = padsViewModel,
                projectsViewModel = projectsViewModel,
                deviceViewModel = deviceViewModel,
                kitViewModel = kitViewModel,
                kitBuilderViewModel = kitBuilderViewModel,
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
        // repo.close() closes only its CURRENT port. If the simulator is active, the real
        // MIDIManager is detached but still open — close it explicitly.
        if (simPort != null) midiManager.close()
        midiRepo.close()
    }
}
