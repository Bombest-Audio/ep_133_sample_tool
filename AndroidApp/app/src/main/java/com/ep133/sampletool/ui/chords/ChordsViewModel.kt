package com.ep133.sampletool.ui.chords

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.audio.voice.NativeSynthVoice
import com.ep133.sampletool.domain.audio.voice.RenderableVoice
import com.ep133.sampletool.domain.midi.ChordBakeManager
import com.ep133.sampletool.domain.midi.ChordBakeProgress
import com.ep133.sampletool.domain.midi.ChordPlayer
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.ChordDegree
import com.ep133.sampletool.domain.model.ChordProgression
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.EP133Pads
import com.ep133.sampletool.domain.model.EP133Sound
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.domain.model.Progressions
import com.ep133.sampletool.domain.model.Vibe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the bake-to-sample action on the Chords screen. */
sealed class BakeUiState {
    object Idle : BakeUiState()
    /** Bake running: [stage] is a short user-facing label ("Rendering", "Uploading"). */
    data class Running(val stage: String) : BakeUiState()
    /** Bake finished: sample saved on the device as [name]. */
    data class Done(val name: String) : BakeUiState()
    data class Error(val message: String) : BakeUiState()
}

class ChordsViewModel(
    private val chordPlayer: ChordPlayer,
    private val midiRepo: MIDIRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    bakeManager: ChordBakeManager? = null,
    /** Lazy so JVM unit tests can inject [KotlinSynthVoice] and never touch libnativesynth.so. */
    private val bakeVoiceProvider: () -> RenderableVoice = { NativeSynthVoice() },
) : ViewModel() {

    private val bakeManager: ChordBakeManager = bakeManager ?: ChordBakeManager(midiRepo)

    // ── Vibe / key / BPM filters ──────────────────────────────────────────────

    private val _selectedVibes = MutableStateFlow<Set<Vibe>>(emptySet())
    val selectedVibes: StateFlow<Set<Vibe>> = _selectedVibes.asStateFlow()

    private val _keyRoot = MutableStateFlow("G")
    val keyRoot: StateFlow<String> = _keyRoot.asStateFlow()

    private val _bpm = MutableStateFlow(90)
    val bpm: StateFlow<Int> = _bpm.asStateFlow()

    val filteredProgressions: StateFlow<List<ChordProgression>> = _selectedVibes
        .combine(MutableStateFlow(Unit)) { vibes, _ -> Progressions.forVibes(vibes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Progressions.ALL)

    // ── Progression selection / playback ──────────────────────────────────────

    private val _selectedProgression = MutableStateFlow<ChordProgression?>(null)
    val selectedProgression: StateFlow<ChordProgression?> = _selectedProgression.asStateFlow()

    private val _playingStep = MutableStateFlow(-1)
    val playingStep: StateFlow<Int> = _playingStep.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playingProgressionId = MutableStateFlow<String?>(null)
    val playingProgressionId: StateFlow<String?> = _playingProgressionId.asStateFlow()

    private val _looping = MutableStateFlow(false)
    val looping: StateFlow<Boolean> = _looping.asStateFlow()

    private var playbackJob: Job? = null

    // ── Device state (for offline notice / push button visibility) ────────────

    val deviceState: StateFlow<DeviceState> = midiRepo.deviceState

    // ── Sound selection ───────────────────────────────────────────────────────

    private val _selectedSound = MutableStateFlow<EP133Sound?>(null)
    val selectedSound: StateFlow<EP133Sound?> = _selectedSound.asStateFlow()

    private val _showSoundPicker = MutableStateFlow(false)
    val showSoundPicker: StateFlow<Boolean> = _showSoundPicker.asStateFlow()

    // ── Chord map / push-to-group ─────────────────────────────────────────────

    private val _chordMapGroup = MutableStateFlow<PadChannel?>(null)
    val chordMapGroup: StateFlow<PadChannel?> = _chordMapGroup.asStateFlow()

    private val _showGroupPicker = MutableStateFlow(false)
    val showGroupPicker: StateFlow<Boolean> = _showGroupPicker.asStateFlow()

    private var chordMapJob: Job? = null
    private var padLoadJob: Job? = null

    // ── Public actions ────────────────────────────────────────────────────────

    fun toggleVibe(vibe: Vibe) {
        _selectedVibes.value = _selectedVibes.value.let { current ->
            if (vibe in current) current - vibe else current + vibe
        }
    }

    fun setKey(root: String) {
        _keyRoot.value = root
    }

    fun selectProgression(p: ChordProgression?) {
        stopPlayback()
        // A tapped preview chord or an active chord-map session belongs to the previous
        // progression; kill both so no notes or listeners outlive the selection change.
        chordPlayer.stopCurrentChord()
        cancelChordMap()
        _selectedProgression.value = p
    }

    fun previewChord(degree: ChordDegree) {
        chordPlayer.playChord(degree, _keyRoot.value)
    }

    fun stopPreview() {
        chordPlayer.stopCurrentChord()
    }

    fun toggleLoop() {
        _looping.value = !_looping.value
    }

    fun playProgression(progression: ChordProgression) {
        stopPlayback()
        // Pre-load sound on EP-133 if connected
        val sound = _selectedSound.value
        if (sound != null && midiRepo.deviceState.value.connected) {
            midiRepo.selectSound(sound.number)
        }
        _isPlaying.value = true
        _playingProgressionId.value = progression.id
        playbackJob = viewModelScope.launch {
            chordPlayer.playProgression(
                progression = progression,
                keyRoot = _keyRoot.value,
                bpm = _bpm.value,
                loop = _looping.value,
                onStep = { step ->
                    _playingStep.value = step
                    // Natural completion: clear all playback state so the card's Stop
                    // icon flips back to Play without requiring an explicit stop tap.
                    if (step == -1) {
                        _isPlaying.value = false
                        _playingProgressionId.value = null
                        playbackJob = null
                    }
                },
            )
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _isPlaying.value = false
        _playingProgressionId.value = null
        _playingStep.value = -1
    }

    fun adjustBpm(delta: Int) {
        _bpm.value = (_bpm.value + delta).coerceIn(40, 240)
    }

    // ── Sound picker ──────────────────────────────────────────────────────────

    fun openSoundPicker() { _showSoundPicker.value = true }
    fun dismissSoundPicker() { _showSoundPicker.value = false }

    fun selectSound(sound: EP133Sound?) {
        _selectedSound.value = sound
        _showSoundPicker.value = false
        // Immediately load onto EP-133 when connected
        if (sound != null && midiRepo.deviceState.value.connected) {
            midiRepo.selectSound(sound.number)
        }
    }

    // ── Push to group ─────────────────────────────────────────────────────────

    fun openGroupPicker() { _showGroupPicker.value = true }
    fun dismissGroupPicker() { _showGroupPicker.value = false }

    fun programToGroup(group: PadChannel) {
        _showGroupPicker.value = false
        val sound = _selectedSound.value ?: return
        val prog = _selectedProgression.value ?: return

        cancelChordMap()

        // 1. Load the selected sound to all 12 pads in the group (staggered to avoid MIDI
        // flooding). Tracked in padLoadJob so reprogramming or cancelling stops the sweep.
        padLoadJob = viewModelScope.launch(ioDispatcher) {
            EP133Pads.padsForChannel(group).forEach { pad ->
                midiRepo.loadSoundToPad(sound.number, pad.note, pad.midiChannel)
                delay(30L)
            }
        }

        // 2. Start chord-map listener: pad press → play matching chord
        _chordMapGroup.value = group
        val baseNote = group.baseNote
        val degrees = prog.degrees

        chordMapJob = viewModelScope.launch {
            // Track which pad note owns the sounding chord: pads are monophonic here
            // (playChord replaces the previous chord), so a release of an older, already
            // superseded pad must not kill the chord the newest pad is playing.
            var activePadNote: Int? = null
            midiRepo.incomingMidi.collect { event ->
                val offset = event.note - baseNote
                when {
                    // noteOn in this group's range → play chord at that index
                    event.status == 0x90 && event.velocity > 0 && offset in degrees.indices -> {
                        activePadNote = event.note
                        chordPlayer.playChord(degrees[offset], _keyRoot.value)
                    }
                    // noteOff → release only if it matches the pad that owns the chord
                    (event.status == 0x80 || (event.status == 0x90 && event.velocity == 0))
                        && offset in degrees.indices && event.note == activePadNote -> {
                        activePadNote = null
                        chordPlayer.stopCurrentChord()
                    }
                }
            }
        }
    }

    fun cancelChordMap() {
        padLoadJob?.cancel()
        padLoadJob = null
        chordMapJob?.cancel()
        chordMapJob = null
        _chordMapGroup.value = null
        chordPlayer.stopCurrentChord()
    }

    // ── Bake to sample ────────────────────────────────────────────────────────

    private val _bakeState = MutableStateFlow<BakeUiState>(BakeUiState.Idle)
    val bakeState: StateFlow<BakeUiState> = _bakeState.asStateFlow()

    private var bakeJob: Job? = null

    /**
     * Bake the selected progression to a sample on the connected EP-133.
     *
     * Guarded on the real preconditions (device connected, progression selected,
     * no bake already running), not just the UI button state - firing early must
     * be a no-op, never a bogus bake.
     */
    fun bakeSelectedProgression() {
        val prog = _selectedProgression.value ?: return
        if (!midiRepo.deviceState.value.connected) return
        if (_bakeState.value is BakeUiState.Running) return

        val chords = RenderableVoice.chordsOf(prog, _keyRoot.value)
        _bakeState.value = BakeUiState.Running("Rendering")

        bakeJob = viewModelScope.launch {
            try {
                bakeManager.bake(prog.name, chords, _bpm.value, bakeVoiceProvider())
                    .collect { progress ->
                        _bakeState.value = when (progress) {
                            is ChordBakeProgress.Rendering -> BakeUiState.Running("Rendering")
                            is ChordBakeProgress.Uploading -> BakeUiState.Running("Uploading")
                            is ChordBakeProgress.Done -> BakeUiState.Done(progress.name)
                            is ChordBakeProgress.Error -> BakeUiState.Error(progress.message)
                        }
                    }
            } finally {
                // Failure and cancellation must both restore the control - never
                // strand the button in a stuck Running state.
                if (_bakeState.value is BakeUiState.Running) {
                    _bakeState.value = BakeUiState.Idle
                }
            }
        }
    }

    fun cancelBake() {
        bakeJob?.cancel()
        bakeJob = null
    }

    /** Dismiss a Done/Error bake result banner. */
    fun dismissBakeResult() {
        if (_bakeState.value !is BakeUiState.Running) {
            _bakeState.value = BakeUiState.Idle
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        cancelChordMap()
        chordPlayer.close()
    }
}
