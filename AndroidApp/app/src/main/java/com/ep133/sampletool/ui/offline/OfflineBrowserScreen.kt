package com.ep133.sampletool.ui.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.backup.ManifestPad
import com.ep133.sampletool.domain.backup.ManifestSample
import com.ep133.sampletool.domain.backup.ProjectManifest
import com.ep133.sampletool.domain.backup.ProjectManifestLoader
import com.ep133.sampletool.domain.midi.BackupItem
import com.ep133.sampletool.ui.TestTags
import com.ep133.sampletool.ui.theme.Ep133SectionLabel
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
import com.ep133.sampletool.ui.theme.Mono
import com.ep133.sampletool.ui.theme.PanelRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The four EP-133 pad groups, tab order. */
internal val OFFLINE_GROUPS = listOf("A", "B", "C", "D")

/**
 * Sound parameters parsed out of a pad's METADATA JSON for the offline readout
 * (flat dotted keys — see docs/ep133-sysex-protocol.md "Metadata JSON shape").
 */
data class OfflinePadParams(
    val playmode: String?,
    val pitch: Double,
    val sampleStart: Long,
    val sampleEnd: Long,
    val attack: Int,
    val release: Int,
    val amplitude: Int,
    val pan: Int,
)

/** Parse [pad]'s metadata into readout params, or null for an unassigned/unreadable pad. */
fun offlinePadParams(pad: ManifestPad): OfflinePadParams? {
    if (pad.sym == 0) return null
    val json = try {
        JSONObject(pad.metadataJson)
    } catch (_: JSONException) {
        return null
    }
    return OfflinePadParams(
        playmode = json.optString("sound.playmode").takeIf { it.isNotEmpty() },
        pitch = json.optDouble("sound.pitch", 0.0),
        sampleStart = json.optLong("sample.start", 0L),
        sampleEnd = json.optLong("sample.end", 0L),
        attack = json.optInt("envelope.attack", 0),
        release = json.optInt("envelope.release", 0),
        amplitude = json.optInt("sound.amplitude", 100),
        pan = json.optInt("sound.pan", 0),
    )
}

/**
 * ViewModel for the offline backup browser (ROADMAP 999.11 / issue #55): open a manifest-backed
 * project backup with no device attached, browse its pads by group, audition samples locally,
 * and read sound params.
 *
 * Mirrors the sibling ViewModels' StateFlow encapsulation. [loadManifest] and [player] are the
 * unit-test seams — the defaults are the real loader and a MediaPlayer-backed player.
 */
class OfflineBrowserViewModel(
    private val player: SamplePlayer,
    private val loadManifest: (File) -> ProjectManifest? = ProjectManifestLoader::loadFor,
) : ViewModel() {

    private val _manifest = MutableStateFlow<ProjectManifest?>(null)
    /** The manifest currently open for browsing, or null when the browser is closed. */
    val manifest: StateFlow<ProjectManifest?> = _manifest.asStateFlow()

    private val _backupName = MutableStateFlow("")
    val backupName: StateFlow<String> = _backupName.asStateFlow()

    private val _selectedGroup = MutableStateFlow(OFFLINE_GROUPS.first())
    val selectedGroup: StateFlow<String> = _selectedGroup.asStateFlow()

    private val _selectedPad = MutableStateFlow<ManifestPad?>(null)
    val selectedPad: StateFlow<ManifestPad?> = _selectedPad.asStateFlow()

    /** `sym` of the sample currently auditioning, or null when idle. */
    private val _auditioningSym = MutableStateFlow<Int?>(null)
    val auditioningSym: StateFlow<Int?> = _auditioningSym.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Open [item] for offline browsing. No-ops with a message when it has no manifest. */
    fun open(item: BackupItem) {
        if (!item.hasManifest) {
            _message.value = "This backup has no manifest — back it up again to browse offline."
            return
        }
        viewModelScope.launch {
            try {
                val loaded = loadManifest(item.file)
                if (loaded == null) {
                    _message.value = "Couldn't read the manifest for ${item.name}."
                    return@launch
                }
                _backupName.value = item.name
                _selectedGroup.value = OFFLINE_GROUPS.first()
                _selectedPad.value = null
                _auditioningSym.value = null
                _manifest.value = loaded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Couldn't open ${item.name}: ${e.message ?: e}"
            }
        }
    }

    /** Pads of [group] in device order ("01".."12"). */
    fun padsInGroup(group: String): List<ManifestPad> =
        _manifest.value?.pads.orEmpty().filter { it.group == group }.sortedBy { it.pad }

    /** Sample referenced by [pad], or null when the pad is unassigned or the sample is unknown. */
    fun sampleFor(pad: ManifestPad): ManifestSample? =
        pad.sym.takeIf { it != 0 }?.let { sym ->
            _manifest.value?.samples?.firstOrNull { it.sym == sym }
        }

    fun selectGroup(group: String) {
        if (group !in OFFLINE_GROUPS) return
        _selectedGroup.value = group
        _selectedPad.value = null
    }

    /** Tap-to-audition: select [pad] and start (or toggle off) local playback of its sample. */
    fun tapPad(pad: ManifestPad) {
        _selectedPad.value = pad
        val sample = sampleFor(pad)
        if (pad.sym == 0 || sample == null) {
            stopAudition()
            return
        }
        if (_auditioningSym.value == sample.sym) {
            stopAudition()
            return
        }
        val file = sample.file
        if (file == null) {
            stopAudition()
            _message.value = "Sample file missing from the manifest: ${sample.relativePath}"
            return
        }
        val started = player.play(file) { _auditioningSym.value = null }
        _auditioningSym.value = if (started) sample.sym else null
        if (!started) _message.value = "Couldn't play ${sample.name ?: file.name}."
    }

    private fun stopAudition() {
        player.stop()
        _auditioningSym.value = null
    }

    /** Close the browser and stop playback. */
    fun close() {
        stopAudition()
        _manifest.value = null
        _selectedPad.value = null
        _backupName.value = ""
    }

    fun dismissMessage() {
        _message.value = null
    }

    override fun onCleared() {
        player.stop()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen — rendered by ProjectsScreen in place of the library when a manifest is open.
// ─────────────────────────────────────────────────────────────────────────────

private fun formatCreated(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))

@Composable
fun OfflineBrowserScreen(viewModel: OfflineBrowserViewModel) {
    val t = LocalEP133Tokens.current
    val manifest by viewModel.manifest.collectAsState()
    val backupName by viewModel.backupName.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()
    val selectedPad by viewModel.selectedPad.collectAsState()
    val auditioningSym by viewModel.auditioningSym.collectAsState()
    val m = manifest ?: return

    Column(
        modifier = Modifier
            .testTag(TestTags.OFFLINE_BROWSER)
            .fillMaxSize()
            .background(t.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // ── Header: back + name + OFFLINE chip ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = "‹ LIBRARY",
                modifier = Modifier
                    .testTag(TestTags.OFFLINE_CLOSE)
                    .clip(PanelRadius)
                    .clickable { viewModel.close() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                color = t.accent,
                fontFamily = Mono,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Text(
                text = backupName,
                modifier = Modifier.weight(1f),
                color = t.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OfflineChip()
        }

        Text(
            text = "PROJECT ${m.projectName} · ${formatCreated(m.createdAtMillis)} · " +
                "${m.samples.size} SAMPLES",
            color = t.text3,
            fontFamily = Mono,
            fontSize = 9.sp,
            letterSpacing = 0.3.sp,
        )

        // ── Group tabs A-D ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            OFFLINE_GROUPS.forEach { g ->
                val selected = g == selectedGroup
                Box(
                    modifier = Modifier
                        .testTag(TestTags.offlineGroupTab(g))
                        .weight(1f)
                        .clip(PanelRadius)
                        .background(if (selected) t.accent else t.panel, PanelRadius)
                        .border(1.dp, if (selected) t.accent else t.rule, PanelRadius)
                        .clickable { viewModel.selectGroup(g) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = g,
                        color = if (selected) t.onAccent else t.text2,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // ── 12-pad grid, 4 rows x 3 (device layout) ──
        val pads = viewModel.padsInGroup(selectedGroup)
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            pads.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    row.forEach { pad ->
                        PadCell(
                            pad = pad,
                            sampleName = viewModel.sampleFor(pad)?.name,
                            selected = selectedPad === pad,
                            playing = pad.sym != 0 && auditioningSym == pad.sym,
                            modifier = Modifier.weight(1f),
                            onTap = { viewModel.tapPad(pad) },
                        )
                    }
                    // Pad out incomplete rows so cells keep their 1/3 width.
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }

        // ── Param readout for the selected pad ──
        selectedPad?.let { pad ->
            ParamReadout(pad = pad, sample = viewModel.sampleFor(pad))
        }

        if (m.skipped.isNotEmpty()) {
            Ep133SectionLabel("Skipped at backup time")
            m.skipped.forEach { note ->
                Text(
                    text = "· $note",
                    color = t.text3,
                    fontFamily = Mono,
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

/** Accent "OFFLINE" state chip — the browser works entirely from on-disk manifest data. */
@Composable
private fun OfflineChip() {
    val t = LocalEP133Tokens.current
    Text(
        text = "OFFLINE",
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(t.accent, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        color = t.onAccent,
        fontFamily = Mono,
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
    )
}

/** One pad — number + sample name (dim when empty); accent border when selected/playing. */
@Composable
private fun PadCell(
    pad: ManifestPad,
    sampleName: String?,
    selected: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    val assigned = pad.sym != 0
    Column(
        modifier = modifier
            .testTag(TestTags.offlinePad(pad.group, pad.pad))
            .clip(PanelRadius)
            .background(if (playing) t.accent.copy(alpha = 0.18f) else t.panel, PanelRadius)
            .border(1.dp, if (selected || playing) t.accent else t.rule, PanelRadius)
            .clickable { onTap() }
            .height(52.dp)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = pad.pad,
            color = if (assigned) t.text2 else t.text3.copy(alpha = 0.6f),
            fontFamily = Mono,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (assigned) sampleName ?: "SYM ${pad.sym}" else "—",
            color = if (assigned) t.text else t.text3.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontWeight = if (assigned) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Mono param readout — pitch / start / end / envelope for the selected pad. */
@Composable
private fun ParamReadout(pad: ManifestPad, sample: ManifestSample?) {
    val t = LocalEP133Tokens.current
    val params = offlinePadParams(pad)
    Column(
        modifier = Modifier
            .testTag(TestTags.OFFLINE_PARAM_READOUT)
            .fillMaxWidth()
            .clip(PanelRadius)
            .background(t.inset, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "PAD ${pad.group}${pad.pad}",
                modifier = Modifier.weight(1f),
                color = t.text,
                fontFamily = Mono,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            sample?.name?.let {
                Text(
                    text = it,
                    color = t.text2,
                    fontFamily = Mono,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (params == null) {
            Text(
                text = "EMPTY PAD",
                color = t.text3,
                fontFamily = Mono,
                fontSize = 9.5.sp,
                letterSpacing = 0.5.sp,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ParamCell("PITCH", String.format(Locale.US, "%+.2f", params.pitch), Modifier.weight(1f))
                ParamCell("START", params.sampleStart.toString(), Modifier.weight(1f))
                ParamCell("END", params.sampleEnd.toString(), Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ParamCell("ATTACK", params.attack.toString(), Modifier.weight(1f))
                ParamCell("RELEASE", params.release.toString(), Modifier.weight(1f))
                ParamCell("MODE", params.playmode?.uppercase(Locale.US) ?: "—", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ParamCell(label: String, value: String, modifier: Modifier = Modifier) {
    val t = LocalEP133Tokens.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            color = t.text3,
            fontFamily = Mono,
            fontSize = 8.5.sp,
            letterSpacing = 0.5.sp,
        )
        Text(
            text = value,
            color = t.text,
            fontFamily = Mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
