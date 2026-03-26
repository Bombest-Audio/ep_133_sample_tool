package com.ep133.sampletool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.midi.MidiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.ep133.sampletool.domain.midi.ChordPlayer
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.sequencer.SequencerEngine
import com.ep133.sampletool.midi.MIDIManager
import com.ep133.sampletool.ui.EP133App
import com.ep133.sampletool.ui.beats.BeatsViewModel
import com.ep133.sampletool.ui.chords.ChordsViewModel
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.pads.PadsViewModel
import com.ep133.sampletool.ui.sounds.SoundsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var midiManager: MIDIManager
    private lateinit var midiRepo: MIDIRepository
    private lateinit var sequencer: SequencerEngine

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private var screenOnJob: Job? = null

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

        val chordPlayer = ChordPlayer(midiRepo)
        val padsViewModel = PadsViewModel(midiRepo)
        val beatsViewModel = BeatsViewModel(sequencer, midiRepo)
        val soundsViewModel = SoundsViewModel(midiRepo)
        val chordsViewModel = ChordsViewModel(chordPlayer)
        val deviceViewModel = DeviceViewModel(midiRepo)

        setContent {
            EP133App(
                padsViewModel = padsViewModel,
                beatsViewModel = beatsViewModel,
                soundsViewModel = soundsViewModel,
                chordsViewModel = chordsViewModel,
                deviceViewModel = deviceViewModel,
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
        screenOnJob = scope.launch {
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
        screenOnJob?.cancel()
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
        }
        midiRepo.close()
    }
}
