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
            // The flash re-enumerates the EP-133 on the USB bus several times; if our USB-attach
            // handler is live it relaunches us and rips focus away from the browser, killing the
            // flash partway. Suppress it for the duration; onStart re-enables it on return.
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
                setUsbAutoLaunchEnabled(true)
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
        // Always restore USB auto-launch on return to the foreground. This is the re-enable half
        // of the firmware-updater suppression (set in onOpenFirmwareUpdater); doing it
        // unconditionally also self-heals if the process was killed while the alias was disabled.
        setUsbAutoLaunchEnabled(true)
        // USB-MIDI ports are exclusive, and the WebView (SampleManagerActivity) owns a second
        // MIDIManager. Re-claim the port when we come back to the foreground. Skip the very first
        // onStart — onCreate + the USB-attach intent handle the initial connect.
        if (startedOnce) reacquireMidi(attempt = 0)
        startedOnce = true
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
