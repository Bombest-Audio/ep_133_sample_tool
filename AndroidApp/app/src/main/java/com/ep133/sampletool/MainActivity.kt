package com.ep133.sampletool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.midi.MidiManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import android.media.AudioManager
import com.ep133.sampletool.domain.midi.ChordPlayer
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.NativeSynth
import com.ep133.sampletool.domain.midi.ProjectBackupManager
import com.ep133.sampletool.domain.sequencer.SequencerEngine
import com.ep133.sampletool.midi.MIDIManager
import androidx.lifecycle.lifecycleScope
import com.ep133.sampletool.ui.EP133App
import com.ep133.sampletool.ui.beats.BeatsViewModel
import com.ep133.sampletool.ui.chords.ChordsViewModel
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.pads.PadsViewModel
import com.ep133.sampletool.ui.projects.ProjectsViewModel
import com.ep133.sampletool.ui.sounds.SoundsViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var midiManager: MIDIManager
    private lateinit var midiRepo: MIDIRepository
    private lateinit var sequencer: SequencerEngine

    private val mainHandler = Handler(Looper.getMainLooper())

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
        sequencer = SequencerEngine(midiRepo)

        val nativeSynth = NativeSynth(getSystemService(AudioManager::class.java))
        val chordPlayer = ChordPlayer(midiRepo, nativeSynth)
        val padsViewModel = PadsViewModel(midiRepo)
        val beatsViewModel = BeatsViewModel(sequencer, midiRepo)
        val soundsViewModel = SoundsViewModel(midiRepo)
        val chordsViewModel = ChordsViewModel(chordPlayer, midiRepo)
        val projectBackupManager = ProjectBackupManager(midiRepo)
        val projectsViewModel = ProjectsViewModel(midiRepo, projectBackupManager)
        val deviceViewModel = DeviceViewModel(midiRepo)

        // SAF launchers for backup/restore — MUST be registered before setContent (Activity lifecycle)
        val backupLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? -> uri?.let { deviceViewModel.onBackupUriSelected(it, this) } }

        val restoreLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? -> uri?.let { deviceViewModel.onRestoreUriSelected(it, this) } }

        deviceViewModel.onRequestBackup = { name -> backupLauncher.launch(name) }
        deviceViewModel.onRequestRestore = { restoreLauncher.launch(arrayOf("*/*")) }

        setContent {
            val deviceState by midiRepo.deviceState.collectAsState()
            EP133App(
                padsViewModel = padsViewModel,
                beatsViewModel = beatsViewModel,
                soundsViewModel = soundsViewModel,
                chordsViewModel = chordsViewModel,
                projectsViewModel = projectsViewModel,
                deviceViewModel = deviceViewModel,
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

        observeScreenOnState()
    }

    private fun observeScreenOnState() {
        lifecycleScope.launch {
            sequencer.state.collectLatest { state ->
                if (state.playing) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sequencer.close()
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
        }
        midiRepo.close()
    }
}
