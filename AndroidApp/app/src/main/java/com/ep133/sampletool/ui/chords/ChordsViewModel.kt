package com.ep133.sampletool.ui.chords

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.midi.ChordPlayer
import com.ep133.sampletool.domain.model.ChordDegree
import com.ep133.sampletool.domain.model.ChordProgression
import com.ep133.sampletool.domain.model.Progressions
import com.ep133.sampletool.domain.model.Vibe
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChordsViewModel(
    private val chordPlayer: ChordPlayer,
) : ViewModel() {

    private val _selectedVibes = MutableStateFlow<Set<Vibe>>(emptySet())
    val selectedVibes: StateFlow<Set<Vibe>> = _selectedVibes.asStateFlow()

    private val _keyRoot = MutableStateFlow("G")
    val keyRoot: StateFlow<String> = _keyRoot.asStateFlow()

    private val _bpm = MutableStateFlow(90)
    val bpm: StateFlow<Int> = _bpm.asStateFlow()

    val filteredProgressions: StateFlow<List<ChordProgression>> = _selectedVibes
        .combine(MutableStateFlow(Unit)) { vibes, _ -> Progressions.forVibes(vibes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Progressions.ALL)

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

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        chordPlayer.stopCurrentChord()
    }
}
