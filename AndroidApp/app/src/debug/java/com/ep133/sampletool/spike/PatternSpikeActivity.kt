package com.ep133.sampletool.spike

import android.content.Context
import android.media.midi.MidiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.midi.MIDIManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Debug-only, standalone entry point for the Phase 6 pattern-write spike (Plan 02).
 *
 * Intentionally self-contained: owns its own [MIDIManager]/[MIDIRepository] rather than
 * reaching into MainActivity, so no src/main file needs to change to expose this affordance.
 * USB-MIDI ports are exclusive — do not run this while MainActivity holds the foreground.
 *
 * Never shipped: declared only in the debug-variant manifest (src/debug/AndroidManifest.xml).
 */
class PatternSpikeActivity : ComponentActivity() {

    private lateinit var midiManager: MIDIManager
    private lateinit var midiRepo: MIDIRepository
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val systemMidiManager = getSystemService(Context.MIDI_SERVICE) as MidiManager
        midiManager = MIDIManager(this, systemMidiManager)
        midiRepo = MIDIRepository(midiManager)
        mainHandler.postDelayed({ midiRepo.refreshDeviceState() }, 1000)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PatternSpikeScreen(midiRepo)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        midiRepo.close()
    }
}

@Composable
private fun PatternSpikeScreen(repo: MIDIRepository) {
    val deviceState by repo.deviceState.collectAsState()
    val scope = rememberCoroutineScope()
    var scratchPath by remember { mutableStateOf("/projects/09") }
    var status by remember { mutableStateOf("Idle") }
    var running by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("EP-133 Pattern Spike (debug-only, never shipped)")
        Text("Connected: ${deviceState.connected}")

        OutlinedTextField(
            value = scratchPath,
            onValueChange = { scratchPath = it },
            label = { Text("Scratch project path") },
            singleLine = true,
        )

        // Only let the walk run once the device is connected AND the GREET has populated
        // firmwareVersion. Running before then races the device-stats handshake and yields a
        // partial/empty dump, which would read as a misleading NO-GO.
        val canRun = deviceState.connected &&
            !deviceState.firmwareVersion.isNullOrEmpty() &&
            !running
        Button(
            enabled = canRun,
            onClick = {
                if (!canRun) return@Button
                running = true
                status = "Running…"
                scope.launch {
                    try {
                        val dump = PatternSpikeRunner.run(repo, scratchPath)
                        status = "Done - ${dump.size} node(s) found. Full dump in Logcat (tag EP133SPIKE)."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status = "Failed: ${e.message ?: e.toString()}"
                    } finally {
                        // Always reset, even if the walk threw, so the button re-enables.
                        running = false
                    }
                }
            },
        ) {
            Text("Run Pattern Spike")
        }

        Button(onClick = { repo.requestUSBPermissions() }) {
            Text("Request USB Permission")
        }

        Text(status)
    }
}
