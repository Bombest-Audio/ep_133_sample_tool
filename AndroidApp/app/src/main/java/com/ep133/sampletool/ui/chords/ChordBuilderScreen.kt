package com.ep133.sampletool.ui.chords

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ep133.sampletool.domain.model.ChordDegree
import com.ep133.sampletool.domain.model.midiToNoteName
import com.ep133.sampletool.domain.model.resolveChordMidiNotes
import com.ep133.sampletool.domain.model.resolveChordName
import com.ep133.sampletool.ui.theme.TEColors

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

    val prog = progression ?: return
    var tappedIndex by remember { mutableIntStateOf(-1) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        TopBar(
            name = prog.name,
            isPlaying = isPlaying,
            looping = looping,
            onBack = { viewModel.selectProgression(null) },
            onPlay = { viewModel.playProgression(prog) },
            onStop = { viewModel.stopPlayback() },
            onToggleLoop = { viewModel.toggleLoop() },
        )

        Spacer(modifier = Modifier.height(16.dp))

        KeyBpmRow(
            keyRoot = keyRoot,
            bpm = bpm,
            onBpmAdjust = viewModel::adjustBpm,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "CHORDS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
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

        Spacer(modifier = Modifier.height(20.dp))

        ChordTonesSection(
            degree = prog.degrees.getOrNull(
                if (tappedIndex in prog.degrees.indices) tappedIndex
                else if (playingStep in prog.degrees.indices) playingStep
                else -1,
            ),
            keyRoot = keyRoot,
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onSendToBeats,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = "SEND TO BEATS",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            OutlinedButton(
                onClick = { viewModel.toggleLoop() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Repeat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (looping) TEColors.Orange else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "LOOP",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (looping) TEColors.Orange else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun TopBar(
    name: String,
    isPlaying: Boolean,
    looping: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onToggleLoop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
            )
        }

        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )

        FilledIconButton(
            onClick = if (isPlaying) onStop else onPlay,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = TEColors.Orange,
                contentColor = Color.White,
            ),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Stop" else "Play",
                modifier = Modifier.size(24.dp),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(onClick = onToggleLoop) {
            Icon(
                imageVector = Icons.Filled.Repeat,
                contentDescription = "Toggle loop",
                tint = if (looping) TEColors.Orange else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyBpmRow(
    keyRoot: String,
    bpm: Int,
    onBpmAdjust: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "KEY",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = keyRoot,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedIconButton(
                onClick = { onBpmAdjust(-5) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease BPM")
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = "$bpm",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "BPM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedIconButton(
                onClick = { onBpmAdjust(5) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase BPM")
            }
        }
    }
}

@Composable
private fun ChordBlock(
    degree: ChordDegree,
    keyRoot: String,
    isActive: Boolean,
    isTapped: Boolean,
    onTap: () -> Unit,
) {
    val chordName = resolveChordName(degree, keyRoot)
    val midiNotes = resolveChordMidiNotes(degree, keyRoot)
    val noteNames = midiNotes.joinToString(" ") { midiToNoteName(it) }

    val borderColor by animateColorAsState(
        targetValue = if (isActive) TEColors.Orange else Color.Transparent,
        animationSpec = tween(100),
        label = "chordBlockBorder",
    )

    val nameColor = when {
        isActive -> TEColors.Orange
        isTapped -> TEColors.OrangeLight
        else -> TEColors.InkOnDark
    }

    Surface(
        modifier = Modifier
            .width(80.dp)
            .border(
                width = if (isActive) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(10.dp),
        color = TEColors.PadBlack,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = degree.roman,
                style = MaterialTheme.typography.labelLarge,
                color = TEColors.InkOnDarkSecondary,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = chordName,
                style = MaterialTheme.typography.titleSmall,
                color = nameColor,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = noteNames,
                style = MaterialTheme.typography.labelSmall,
                color = TEColors.InkOnDarkSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ChordTonesSection(
    degree: ChordDegree?,
    keyRoot: String,
) {
    if (degree == null) return

    val midiNotes = resolveChordMidiNotes(degree, keyRoot)
    val chordName = resolveChordName(degree, keyRoot)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "$chordName  (${degree.quality.label})",
                style = MaterialTheme.typography.titleMedium,
                color = TEColors.Orange,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                midiNotes.forEach { midi ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = midiToNoteName(midi),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = "$midi",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
