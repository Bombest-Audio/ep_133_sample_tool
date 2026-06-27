package com.ep133.sampletool.ui.chords

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ep133.sampletool.domain.model.ChordDegree
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.domain.model.midiToNoteName
import com.ep133.sampletool.domain.model.resolveChordMidiNotes
import com.ep133.sampletool.domain.model.resolveChordName
import com.ep133.sampletool.ui.theme.Ep133GhostButton
import com.ep133.sampletool.ui.theme.Ep133LiveBadge
import com.ep133.sampletool.ui.theme.Ep133PrimaryButton
import com.ep133.sampletool.ui.theme.Ep133SectionLabel
import com.ep133.sampletool.ui.theme.Ep133StatusDot
import com.ep133.sampletool.ui.theme.LocalEP133Tokens

/** Hard-corner radius mirroring the design's faceplate UI. */
private val Radius = RoundedCornerShape(3.dp)

/** Mono labels/codes (the design uses JetBrains Mono; Monospace is the on-device fallback). */
private val Mono = FontFamily.Monospace

@Composable
fun ChordBuilderScreen(
    viewModel: ChordsViewModel,
    onSendToBeats: () -> Unit = {},
) {
    val progression by viewModel.selectedProgression.collectAsState()
    val keyRoot by viewModel.keyRoot.collectAsState()
    val bpm by viewModel.bpm.collectAsState()
    val playingStep by viewModel.playingStep.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val looping by viewModel.looping.collectAsState()
    val deviceState by viewModel.deviceState.collectAsState()
    val selectedSound by viewModel.selectedSound.collectAsState()
    val showSoundPicker by viewModel.showSoundPicker.collectAsState()
    val chordMapGroup by viewModel.chordMapGroup.collectAsState()
    val showGroupPicker by viewModel.showGroupPicker.collectAsState()

    val prog = progression ?: return
    var tappedIndex by remember { mutableIntStateOf(-1) }

    val t = LocalEP133Tokens.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.bg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        TopBar(
            name = prog.name,
            keyRoot = keyRoot,
            blockCount = prog.degrees.size,
            isPlaying = isPlaying,
            looping = looping,
            onBack = { viewModel.selectProgression(null) },
            onPlay = { viewModel.playProgression(prog) },
            onStop = { viewModel.stopPlayback() },
            onToggleLoop = { viewModel.toggleLoop() },
        )

        // Sound selector row (shared composable — behavior preserved)
        SoundSelectorRow(sound = selectedSound, onClick = viewModel::openSoundPicker)

        when {
            !deviceState.connected -> OfflineNotice()
            chordMapGroup != null ->
                ChordMapBanner(group = chordMapGroup!!, onCancel = viewModel::cancelChordMap)
        }

        KeyBpmRow(
            keyRoot = keyRoot,
            bpm = bpm,
            onBpmAdjust = viewModel::adjustBpm,
        )

        Ep133SectionLabel("CHORDS")

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(end = 4.dp),
        ) {
            itemsIndexed(prog.degrees) { index, degree ->
                ChordBlock(
                    degree = degree,
                    keyRoot = keyRoot,
                    isActive = playingStep == index,
                    isTapped = tappedIndex == index,
                    onTap = {
                        tappedIndex = index
                        viewModel.previewChord(degree)
                    },
                )
            }
        }

        ChordTonesSection(
            degree = prog.degrees.getOrNull(
                if (tappedIndex in prog.degrees.indices) tappedIndex
                else if (playingStep in prog.degrees.indices) playingStep
                else -1,
            ),
            keyRoot = keyRoot,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Loop / BPM playback row
        LoopRow(
            bpm = bpm,
            looping = looping,
            isPlaying = isPlaying,
            onToggleLoop = { viewModel.toggleLoop() },
            onPlay = { viewModel.playProgression(prog) },
            onStop = { viewModel.stopPlayback() },
        )

        // Send-to-Beats + push-to-KO-II
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ep133GhostButton(
                label = "send to Beats",
                modifier = Modifier.weight(1f),
                onClick = onSendToBeats,
            )

            if (deviceState.connected && selectedSound != null) {
                Ep133PrimaryButton(
                    label = "push to KO-II",
                    modifier = Modifier.weight(1f),
                    onClick = viewModel::openGroupPicker,
                )
            }
        }
    }

    // Bottom sheets
    if (showSoundPicker) {
        SoundPickerSheet(
            onSoundSelected = viewModel::selectSound,
            onDismiss = viewModel::dismissSoundPicker,
        )
    }
    if (showGroupPicker && selectedSound != null) {
        GroupPickerSheet(
            soundName = selectedSound!!.name,
            progressionName = prog.name,
            onGroupSelected = viewModel::programToGroup,
            onDismiss = viewModel::dismissGroupPicker,
        )
    }
}

/** Active chord-map banner — "GROUP X · press pads to play chords" with a live badge and cancel. */
@Composable
private fun ChordMapBanner(group: PadChannel, onCancel: () -> Unit) {
    val t = LocalEP133Tokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius)
            .background(t.live.copy(alpha = 0.12f), Radius)
            .border(1.dp, t.live.copy(alpha = 0.4f), Radius)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Ep133LiveBadge("GROUP ${group.name}")
        Text(
            text = "press pads to play chords",
            color = t.liveInk,
            fontFamily = Mono,
            fontSize = 10.sp,
            letterSpacing = 0.3.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "CANCEL",
            color = t.liveInk,
            fontFamily = Mono,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            modifier = Modifier
                .clip(Radius)
                .clickable(onClick = onCancel)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** Builder header: back chevron, mono title, key · block-count code, play/stop + loop toggle. */
@Composable
private fun TopBar(
    name: String,
    keyRoot: String,
    blockCount: Int,
    isPlaying: Boolean,
    looping: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onToggleLoop: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "←",
            color = t.text2,
            fontFamily = Mono,
            fontSize = 16.sp,
            modifier = Modifier
                .clip(Radius)
                .clickable(onClick = onBack)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = t.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = (-0.1).sp,
            )
            Text(
                text = "$keyRoot · $blockCount BLOCKS",
                color = t.text3,
                fontFamily = Mono,
                fontSize = 9.sp,
                letterSpacing = 0.5.sp,
            )
        }

        // Play / stop circle
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(50))
                .background(t.accent)
                .clickable(onClick = if (isPlaying) onStop else onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Stop" else "Play",
                tint = t.onAccent,
                modifier = Modifier.size(20.dp),
            )
        }

        // Loop toggle
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(Radius)
                .border(1.dp, if (looping) t.accent else t.rule, Radius)
                .clickable(onClick = onToggleLoop),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Repeat,
                contentDescription = "Toggle loop",
                tint = if (looping) t.accent else t.text3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** KEY readout chip + a mono BPM stepper, on a faceplate row. */
@Composable
private fun KeyBpmRow(
    keyRoot: String,
    bpm: Int,
    onBpmAdjust: (Int) -> Unit,
) {
    val t = LocalEP133Tokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // KEY chip
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Ep133SectionLabel("KEY")
            Box(
                modifier = Modifier
                    .clip(Radius)
                    .background(t.inset, Radius)
                    .border(1.dp, t.rule, Radius)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = keyRoot,
                    color = t.text,
                    fontFamily = Mono,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // BPM stepper
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton(symbol = Icons.Filled.Remove, contentDescription = "Decrease BPM") {
                onBpmAdjust(-5)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 10.dp),
            ) {
                Text(
                    text = "$bpm",
                    color = t.text,
                    fontFamily = Mono,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(40.dp),
                )
                Text(
                    text = "BPM",
                    color = t.text3,
                    fontFamily = Mono,
                    fontSize = 8.sp,
                    letterSpacing = 1.0.sp,
                )
            }
            StepperButton(symbol = Icons.Filled.Add, contentDescription = "Increase BPM") {
                onBpmAdjust(5)
            }
        }
    }
}

@Composable
private fun StepperButton(
    symbol: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(Radius)
            .border(1.dp, t.rule, Radius)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = symbol,
            contentDescription = contentDescription,
            tint = t.text2,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * One chord block: rubber-pad-style cell showing the roman degree, the resolved chord name, and the
 * note tones. Active step gets an accent hairline + glow face; tap highlights the name. Mirrors the
 * design's chord blocks.
 */
@Composable
private fun ChordBlock(
    degree: ChordDegree,
    keyRoot: String,
    isActive: Boolean,
    isTapped: Boolean,
    onTap: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    val chordName = resolveChordName(degree, keyRoot)
    val midiNotes = resolveChordMidiNotes(degree, keyRoot)
    val noteNames = midiNotes.joinToString(" ") { midiToNoteName(it) }

    val borderColor by animateColorAsState(
        targetValue = if (isActive) t.accent else t.padEdge,
        animationSpec = tween(100),
        label = "chordBlockBorder",
    )

    val nameColor = when {
        isActive -> t.accent
        isTapped -> t.accent
        else -> Color(0xFFC9CACB)
    }

    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(Radius)
            .background(if (isActive) t.padTop else t.padFace, Radius)
            .border(if (isActive) 1.5.dp else 1.dp, borderColor, Radius)
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = degree.roman,
            color = t.padName,
            fontFamily = Mono,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = chordName,
            color = nameColor,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Text(
            text = noteNames,
            color = t.padName,
            fontFamily = Mono,
            fontSize = 9.sp,
            letterSpacing = 0.3.sp,
        )
    }
}

/** Inset panel naming the selected chord and laying out its note + MIDI tones. */
@Composable
private fun ChordTonesSection(
    degree: ChordDegree?,
    keyRoot: String,
) {
    val t = LocalEP133Tokens.current
    if (degree == null) return

    val midiNotes = resolveChordMidiNotes(degree, keyRoot)
    val chordName = resolveChordName(degree, keyRoot)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius)
            .background(t.inset, Radius)
            .border(1.dp, t.rule, Radius)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = chordName,
                color = t.accent,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = degree.quality.label.uppercase(),
                color = t.text3,
                fontFamily = Mono,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            midiNotes.forEach { midi ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = midiToNoteName(midi),
                        color = t.text,
                        fontFamily = Mono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "$midi",
                        color = t.text3,
                        fontFamily = Mono,
                        fontSize = 8.5.sp,
                    )
                }
            }
        }
    }
}

/** LOOP · BPM strip with a play/stop circle, on a faceplate panel. */
@Composable
private fun LoopRow(
    bpm: Int,
    looping: Boolean,
    isPlaying: Boolean,
    onToggleLoop: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius)
            .background(t.panel2, Radius)
            .border(1.dp, t.rule, Radius)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(Radius)
                .clickable(onClick = onToggleLoop)
                .padding(vertical = 2.dp),
        ) {
            Ep133StatusDot(if (looping) t.accent else t.text3, size = 7)
            Text(
                text = "LOOP · $bpm BPM",
                color = if (looping) t.text else t.text2,
                fontFamily = Mono,
                fontSize = 10.sp,
                letterSpacing = 0.4.sp,
            )
        }

        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(50))
                .background(if (isPlaying) t.live else t.accent)
                .clickable(onClick = if (isPlaying) onStop else onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Stop" else "Play",
                tint = if (isPlaying) t.liveInk else t.onAccent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
