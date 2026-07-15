package com.ep133.sampletool.ui.chords

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class ChordsViewModel(
    private val chordPlayer: ChordPlayer,
    private val midiRepo: MIDIRepository,
) : ViewModel() {

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
            midiRepo.programChange(sound.number - 1, ch = midiRepo.channel)
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
                    if (step == -1) _isPlaying.value = false
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
            midiRepo.programChange(sound.number - 1, ch = midiRepo.channel)
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

        // 1. Load the selected sound to all 12 pads in the group (staggered to avoid MIDI flooding)
        viewModelScope.launch(Dispatchers.IO) {
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
            midiRepo.incomingMidi.collect { event ->
                val offset = event.note - baseNote
                when {
                    // noteOn in this group's range → play chord at that index
                    event.status == 0x90 && event.velocity > 0 && offset in degrees.indices ->
                        chordPlayer.playChord(degrees[offset], _keyRoot.value)
                    // noteOff → release chord
                    (event.status == 0x80 || (event.status == 0x90 && event.velocity == 0))
                        && offset in degrees.indices ->
                        chordPlayer.stopCurrentChord()
                }
            }
        }
    }

    fun cancelChordMap() {
        chordMapJob?.cancel()
        chordMapJob = null
        _chordMapGroup.value = null
        chordPlayer.stopCurrentChord()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        cancelChordMap()
        chordPlayer.close()
    }
}
