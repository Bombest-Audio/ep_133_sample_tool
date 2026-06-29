package com.ep133.sampletool

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** Chrome is the only Android browser whose Custom Tab exposes WebUSB, the EP-133 flash transport. */
private const val CHROME_PACKAGE = "com.android.chrome"

class MainActivity : ComponentActivity() {

    private lateinit var midiManager: MIDIManager
    private lateinit var midiRepo: MIDIRepository

    private val mainHandler = Handler(Looper.getMainLooper())

    // Tracks whether onStart has run before, so we only re-acquire MIDI when *returning* to the
    // foreground (the first launch's connect flow is driven by onCreate + the USB-attach intent).
    private var startedOnce = false

    // True while the firmware-updater Custom Tab is open. During a flash the browser owns the
    // EP-133 over WebUSB, so we must stay completely passive — not react to USB attach, not open
    // the device, not request USB permission — or we steal focus (a USB-permission dialog) and the
    // device itself, breaking the flash. Set in onOpenFirmwareUpdater, cleared in onStart on return.
    private var firmwareUpdaterActive = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Stay out of the way while the firmware updater is flashing (see firmwareUpdaterActive).
            if (firmwareUpdaterActive) return
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

        deviceViewModel.onRequestBackup = { name -> backupLauncher.launch(name) }
        deviceViewModel.onRequestRestore = { restoreLauncher.launch(arrayOf("*/*")) }
        sampleImportViewModel.onRequestPick = { importLauncher.launch(arrayOf("audio/*")) }
        deviceViewModel.onOpenFirmwareUpdater = {
            // The flash re-enumerates the EP-133 on the USB bus several times. Two ways that breaks
            // the flash, both guarded here and cleared in onStart on return:
            //   1. Our manifest USB-attach handler relaunching us — disable the alias.
            //   2. Our running process re-opening the device / triggering a USB-permission dialog on
            //      each re-enumeration — set firmwareUpdaterActive so the receiver ignores USB events,
            //      and release the ports now so the browser can claim the device immediately.
            firmwareUpdaterActive = true
            midiManager.releasePorts()
            setUsbAutoLaunchEnabled(false)
            try {
                val customTabsIntent = CustomTabsIntent.Builder().build()
                // The flash transport is WebUSB, which only works in Chrome — a default browser
                // like Brave or Firefox opens the Custom Tab but can't talk to the device. Pin to
                // Chrome when it's installed; otherwise fall back to the default and let the user sort it.
                if (isPackageInstalled(CHROME_PACKAGE)) {
                    customTabsIntent.intent.setPackage(CHROME_PACKAGE)
                }
                customTabsIntent.launchUrl(this, Uri.parse("https://teenage.engineering/apps/update"))
            } catch (e: Exception) {
                endFirmwareUpdaterSession()
                deviceViewModel.showSnackbar("No browser available to open the updater")
            }
        }

        setContent {
            val deviceState by midiRepo.deviceState.collectAsState()
            EP133App(
                padsViewModel = padsViewModel,
                projectsViewModel = projectsViewModel,
                deviceViewModel = deviceViewModel,
                sampleImportViewModel = sampleImportViewModel,
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
        // While the firmware-updater Custom Tab is in front, stay completely passive. The session is
        // ended in onWindowFocusChanged when we genuinely regain top focus (the tab closed), NOT
        // here — onStart can fire behind a transient dialog and would re-arm us mid-flash, which is
        // exactly the bug this guards against.
        if (firmwareUpdaterActive) {
            startedOnce = true
            return
        }
        // Restore USB auto-launch on every normal foreground. Also self-heals if the process was
        // killed while the alias was disabled — firmwareUpdaterActive resets on restart, so we land
        // here rather than in the early return above.
        setUsbAutoLaunchEnabled(true)
        // USB-MIDI ports are exclusive, and the WebView (SampleManagerActivity) owns a second
        // MIDIManager. Re-claim the port when we come back to the foreground. Skip the very first
        // onStart — onCreate + the USB-attach intent handle the initial connect.
        if (startedOnce) reacquireMidi(attempt = 0)
        startedOnce = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Regaining top window focus means the firmware-updater Custom Tab is no longer in front, so
        // the flash session is over. A transient dialog (e.g. a USB-permission prompt) over the tab
        // does NOT give us focus, so this won't fire spuriously during a flash — that's why it's a
        // safer end signal than onStart.
        if (hasFocus && firmwareUpdaterActive) endFirmwareUpdaterSession()
    }

    /** Ends a firmware-updater session: resume reacting to the EP-133 and restore USB auto-launch. */
    private fun endFirmwareUpdaterSession() {
        firmwareUpdaterActive = false
        setUsbAutoLaunchEnabled(true)
        reacquireMidi(attempt = 0)
    }

    /**
     * Toggles the [".UsbAttachAlias"] manifest component that auto-launches us on USB attach.
     * Disabled while the firmware updater Custom Tab is open so device re-enumeration during the
     * flash can't relaunch us and steal focus from the browser. [PackageManager.DONT_KILL_APP]
     * keeps our process alive across the change.
     */
    private fun setUsbAutoLaunchEnabled(enabled: Boolean) {
        val state =
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(
            ComponentName(this, "$packageName.UsbAttachAlias"),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun isPackageInstalled(pkg: String): Boolean =
        try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    override fun onStop() {
        super.onStop()
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
