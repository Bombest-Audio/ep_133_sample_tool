# Phase 5: Sample Import (Android) — Pattern Map

**Mapped:** 2026-06-20
**Files analyzed:** 11 (7 new, 1 modified, 4 new tests... 8 source + 4 test = see classification)
**Analogs found:** 9 / 11 (2 partial — `AudioDecoder` + `Resampler` have no in-repo analog)

Anchors are all just-shipped Phase 4 code in `AndroidApp/app/src/main/java/com/ep133/sampletool/`.
Paths below are relative to that root unless noted. The whole phase is "import any audio
file to the EP-133" — **no Splice machinery** (per 05-CONTEXT / 05-RESEARCH verdict).

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `domain/audio/AudioDecoder.kt` | utility (decode) | file-I/O / transform | **none** (MediaCodec new to repo) | no analog — framework-doc |
| `domain/audio/Resampler.kt` | utility (DSP) | transform | **none** (pure DSP; closest is `SysExProtocol.assembleGetPages` for pure-fn + unit-test shape) | partial (test shape only) |
| `domain/audio/WavEncoder.kt` | utility (encode) | transform | `SysExProtocol` byte-builders (`buildFilePutInitPayload`, BE byte packing) | role-match |
| `domain/midi/SampleImportManager.kt` | service (orchestration) | event-driven (Flow progress) | `domain/midi/ProjectBackupManager.kt` | exact |
| `domain/midi/MIDIRepository.kt` *(MODIFY: add `putSampleFile`)* | service (protocol) | streaming (paged PUT) | `MIDIRepository.putProjectArchive` (same file) | exact |
| `ui/import/SampleImportScreen.kt` | component (screen) | request-response (UI) | `ui/device/DeviceScreen.kt` composable | exact |
| `ui/import/SampleImportViewModel.kt` *(co-located)* | provider (ViewModel) | event-driven (StateFlow) | `DeviceViewModel` (in `DeviceScreen.kt`) | exact |
| `MainActivity.kt` *(MODIFY: register SAF launcher + wire VM)* | config (entry) | request-response | `MainActivity.kt` backup/restore launcher block | exact |
| `ui/EP133App.kt` *(MODIFY if adding a nav tab)* | route | request-response | `EP133App.kt` `NavRoute` enum + `composable()` | exact |
| `test/.../WavEncoderTest.kt` | test | transform | `test/.../SysExProtocolTest.kt` (frame/byte assertions) | exact |
| `test/.../ResamplerTest.kt` | test | transform | `test/.../ProjectProtocolTest.kt` (pure-fn byte assertions) | exact |
| `test/.../SampleImportTest.kt` | test | streaming | `test/.../ProjectProtocolTest.kt` (paged PUT frame layout) | exact |
| `test/.../SampleImportViewModelTest.kt` | test | event-driven | `test/.../ProjectsViewModelTest.kt` (fake repo + spy port) | exact |

---

## Pattern Assignments

### `domain/midi/SampleImportManager.kt` (service, event-driven Flow) — mirror verbatim

**Analog:** `domain/midi/ProjectBackupManager.kt` (whole file, 202 lines). Copy its shape: a
`sealed class` progress type, a per-item `Flow { ... }` builder with a **connected-device
guard first**, IO-dispatched reads, `CancellationException` rethrow before the generic catch,
and explicit `Progress → Done / Error` emissions. For a batch, wrap one sample's flow per
picked file.

**Progress sealed class + manager skeleton** (`ProjectBackupManager.kt:29-36`, `:67-101`):
```kotlin
sealed class SampleImportProgress {
    data class Progress(val current: Int, val total: Int) : SampleImportProgress()
    data class Done(val name: String) : SampleImportProgress()
    data class Error(val message: String) : SampleImportProgress()
}

class SampleImportManager(private val midi: MIDIRepository) {
    fun importSample(name: String, wavBytes: ByteArray): Flow<SampleImportProgress> = flow {
        if (midi.deviceState.value.outputPortId == null) {          // device guard FIRST
            emit(SampleImportProgress.Error("No EP-133 connected"))
            return@flow
        }
        emit(SampleImportProgress.Progress(0, wavBytes.size))
        val ok = try {
            midi.putSampleFile(name, wavBytes)
        } catch (e: CancellationException) {
            throw e                                                  // ALWAYS rethrow first
        } catch (e: Exception) {
            Log.e(TAG, "Sample import failed for $name", e)          // throwable, not msg
            emit(SampleImportProgress.Error("Upload failed: ${e.message ?: e}"))
            return@flow
        }
        if (ok) {
            emit(SampleImportProgress.Progress(wavBytes.size, wavBytes.size))
            emit(SampleImportProgress.Done(name))
        } else {
            emit(SampleImportProgress.Error("Device did not acknowledge the import"))
        }
    }
    private companion object { const val TAG = "EP133APP" }
}
```

**Filename validation/sanitization pattern** (mirror `ProjectBackupManager.kt:120-131`,
`:187-189`): `ProjectBackupManager` validates `file.name` against a regex and rejects
path-escaping names *before any device write* (threat T-04-06/08). Phase 5's V5 security
control (05-RESEARCH "Security Domain") is the same shape — sanitize the sample name to a
safe basename + `.wav`, reject `/`, `..`, control chars, **before** `putSampleFile`.

---

### `domain/midi/MIDIRepository.kt` — ADD `putSampleFile(name, wavBytes)` (service, streaming)

**Analog:** `putProjectArchive` in the **same file** (`MIDIRepository.kt:525-555`). Copy the
INIT → paged-DATA loop almost verbatim; the only difference is the target is a *new*
`/sounds/<name>.wav` rather than an existing `/projects` slot node.

**Paged PUT loop to copy** (`MIDIRepository.kt:525-555`):
```kotlin
suspend fun putProjectArchive(slotNodeId: Int, tarBytes: ByteArray): Boolean {
    val portId = _deviceState.value.outputPortId ?: throw IllegalStateException("no output port")
    if (transferInFlight) throw IllegalStateException("transfer already in flight")
    transferInFlight = true
    val ack = CompletableDeferred<Boolean>()
    pendingPutAckDeferred = ack
    return try {
        val initFrame = SysExProtocol.buildFilePutInitFrame(
            currentDeviceId, slotNodeId, tarBytes.size, requestId = 20,
        )
        midiManager.sendMidi(portId, initFrame)
        var page = 0; var offset = 0
        while (offset < tarBytes.size) {
            val end = minOf(offset + SysExProtocol.MAX_PAGE_BYTES, tarBytes.size)
            val chunk = tarBytes.copyOfRange(offset, end)
            val dataFrame = SysExProtocol.buildFilePutDataFrame(currentDeviceId, page, chunk, requestId = 21)
            midiManager.sendMidi(portId, dataFrame)
            offset = end
            page = (page + 1) and 0xFFFF
        }
        withTimeoutOrNull(PUT_ACK_TIMEOUT_MS) { ack.await() } ?: false
    } catch (e: CancellationException) {
        throw e
    } finally {
        pendingPutAckDeferred = null
        transferInFlight = false
    }
}
```

**Target-addressing decision (Landmine 4 — inherited Phase 4 open question):** For a *new*
`/sounds/<name>.wav`, `buildFilePutInitFrame` takes a **nodeId**, but the file doesn't exist
yet. Two candidates, both already in the codebase:
- **(i) path-string PUT (proven for `/sounds`)** — `BackupManager.kt:196-200` writes new
  `/sounds/$name` files via `SysExProtocol.buildFilePutFrame(deviceId, "/sounds/$name", data, chunkIndex, requestId)`. This is the demonstrated `/sounds`-create path. **But** it's the
  Phase 2 *single-chunk* builder, which Phase 4 documented as truncating multi-KB blobs
  (Landmine 5). So **path-string target + paged transfer** is needed — confirm the device
  accepts a path-string INIT, or:
- **(ii) resolve `/sounds` → nodeId** then use the paged node PUT exactly like
  `putProjectArchive`. Resolution analog: `MIDIRepository.listNodeBody` / `listProjects`
  (`MIDIRepository.kt:582-655`) enumerate a node and match by name.

Default to (i) per research; node-ID fallback (ii) exists. Hardware-verify.

---

### `domain/audio/WavEncoder.kt` (utility, transform) — pure RIFF/Int16 writer

**Analog (byte-layout idiom only):** `SysExProtocol`'s big-endian byte packers
(`buildFilePutInitPayload`, `SysExProtocol.kt:346-354`) — same "manual byte array, explicit
shifts, fixed offsets" style, but **WAV is little-endian** (Landmine 3). Use
`ByteBuffer.order(LITTLE_ENDIAN)`. Hard-code the target rate `46875` and assert it
(Landmine 2). This is one of the two "DO hand-roll" pieces per 05-RESEARCH.

**Encoder to copy** (05-RESEARCH "Code Examples", verified against RIFF spec + `data/index.js`):
```kotlin
fun encodeWav(pcm: ShortArray, sampleRate: Int = 46875, channels: Int = 1): ByteArray {
    val byteRate = sampleRate * channels * 2
    val dataSize = pcm.size * 2
    val buf = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray()); buf.putInt(36 + dataSize); buf.put("WAVE".toByteArray())
    buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1)        // PCM
    buf.putShort(channels.toShort()); buf.putInt(sampleRate); buf.putInt(byteRate)
    buf.putShort((channels * 2).toShort()); buf.putShort(16)             // block align, bits/sample
    buf.put("data".toByteArray()); buf.putInt(dataSize)
    for (s in pcm) buf.putShort(s)
    return buf.array()
}
```
**Pass-through fast path** (05-RESEARCH "Pattern 2", mirrors web-app `uploadSound`): before
decode, sniff the RIFF `fmt ` chunk; if already PCM/16-bit/46875/(1|2)ch, return raw bytes
unchanged.

---

### `domain/audio/Resampler.kt` (utility, DSP transform) — pure, no-op when already 46875

**No in-repo analog** for the DSP itself. Closest *structural* model is
`SysExProtocol.assembleGetPages` (`SysExProtocol.kt:443-458`) — a **pure, hardware-free
function** that's directly unit-testable, which is exactly the shape Resampler should take
(pure `ShortArray` in → `ShortArray` out, per-channel linear interp to 46875, early-return
when `srcRate == 46875`). The second "DO hand-roll" piece. Never upsample — cap at 46875
(`Math.min(src, 46875)` semantics from `data/index.js`, 05-RESEARCH A2).

---

### `domain/audio/AudioDecoder.kt` (utility, file-I/O) — **NO ANALOG — flag for planner**

**No MediaCodec/MediaExtractor usage exists anywhere in the codebase.** This is genuinely new
framework surface. Use the documented Android pattern (05-RESEARCH "Pattern 2", `[CITED:
developer.android.com]`): `MediaExtractor.setDataSource(fd)` → select audio track →
`MediaCodec.createDecoderByType(mime)` → drain output buffers → `ShortArray PCM + (srcRate,
channels)`. **SAF URI lifetime (Landmine 7):** read the `content://` bytes *inside the picker
callback's coroutine grant* (`contentResolver.openFileDescriptor` / `openInputStream`), under
`Dispatchers.IO`. Decode is the one piece that's hardware/instrumentation-bound — cover via
Robolectric/instrumented test or treat as manual-only (05-RESEARCH Wave 0).

---

### `ui/import/SampleImportViewModel.kt` (provider, StateFlow) — co-located in Screen file

**Analog:** `DeviceViewModel` (`DeviceScreen.kt:78-228`). Copy: private `MutableStateFlow`
backing fields with `_` prefix exposed as read-only `StateFlow` via `.asStateFlow()`; a
`_snackbarMessage` channel + `dismissSnackbar()`; per-operation `inProgress` + `progress`
floats; `viewModelScope.launch { ...collect { when(progress) { ... } } }` consuming the
manager's Flow. For the staged list, hold `MutableStateFlow<List<StagedSample>>` where each
item carries a per-file state (pending/converting/loading/done/error).

**SAF callback fields + Flow consumption** (`DeviceScreen.kt:105-162`):
```kotlin
private val _snackbarMessage = MutableStateFlow<String?>(null)
val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

// SAF callback — set by MainActivity (cannot register ActivityResult inside ViewModel)
var onRequestPick: (() -> Unit)? = null

fun onFilesPicked(uris: List<Uri>, context: Context) {
    viewModelScope.launch {
        // per uri: read bytes under Dispatchers.IO during the grant (Landmine 7),
        // convert under Dispatchers.Default, then collect SampleImportManager flow,
        // mapping Progress/Done/Error onto the staged-list item + _snackbarMessage.
    }
}
```

---

### `ui/import/SampleImportScreen.kt` (component, screen) — mirror DeviceScreen composable

**Analog:** `DeviceScreen` composable (`DeviceScreen.kt:230-303`). Copy: `collectAsState()`
for every VM `StateFlow`; a `remember { SnackbarHostState() }` + `LaunchedEffect(snackbarMessage)`
that shows then `dismissSnackbar()`s; `Scaffold(snackbarHost = ...)`; a `Column` with
`verticalScroll`; `LinearProgressIndicator` for progress (`DeviceScreen.kt:446`,
`ProjectsScreen.kt:419`). For the staged list, a `LazyColumn` of per-file rows each showing
its state + per-file progress.

**Snackbar + LaunchedEffect idiom to copy** (`DeviceScreen.kt:243-253`):
```kotlin
val snackbarMessage by viewModel.snackbarMessage.collectAsState()
val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
LaunchedEffect(snackbarMessage) {
    snackbarMessage?.let { msg ->
        snackbarHostState.showSnackbar(msg)
        viewModel.dismissSnackbar()
    }
}
```

---

### `MainActivity.kt` — MODIFY: register the multi-file SAF launcher (config)

**Analog:** the existing backup/restore launcher block in the **same file**
(`MainActivity.kt:78-88`). **SAF launchers must be registered before `setContent`**
(Activity-lifecycle constraint — comment is in the analog). Use `OpenMultipleDocuments`
(Phase 5 needs multi-select) and launch with an `audio/*` MIME filter.

**Launcher registration + VM wiring to copy** (`MainActivity.kt:78-88`):
```kotlin
// SAF launchers — MUST be registered before setContent (Activity lifecycle)
val importLauncher = registerForActivityResult(
    ActivityResultContracts.OpenMultipleDocuments(),
) { uris: List<Uri> -> sampleImportViewModel.onFilesPicked(uris, this) }

sampleImportViewModel.onRequestPick = { importLauncher.launch(arrayOf("audio/*")) }
```
Also instantiate `SampleImportManager(midiRepo)` and `SampleImportViewModel(...)` alongside the
existing `projectBackupManager` / `deviceViewModel` block (`MainActivity.kt:74-76`), and pass
the VM into `EP133App(...)` (`MainActivity.kt:92-100`).

---

### `ui/EP133App.kt` — MODIFY (only if adding a nav tab) (route)

**Analog:** the `NavRoute` enum + `NavHost` `composable()` block in the **same file**
(`EP133App.kt:56-67`, `:149-168`). Per CONTEXT "Claude's Discretion," import placement is open
(new tab vs. action within Sounds/Device). If a tab: add an enum entry (route/label/icon) and
a matching `composable(NavRoute.IMPORT.route) { SampleImportScreen(sampleImportViewModel) }`.

**Nav entry to copy** (`EP133App.kt:61-66`, `:156-167`):
```kotlin
enum class NavRoute(val route: String, val label: String, val icon: ImageVector) {
    // ...
    IMPORT("import", "IMPORT", Icons.Default.FileUpload),
}
// in NavHost:
composable(NavRoute.IMPORT.route) { SampleImportScreen(sampleImportViewModel) }
```

---

## Test Pattern Assignments (Wave 0)

All tests: JUnit 4 (`org.junit.Test` + `org.junit.Assert.*`), package
`com.ep133.sampletool`, no Robolectric for the pure pieces. Run:
`cd AndroidApp && ./gradlew :app:testDebugUnitTest`.

### `test/.../WavEncoderTest.kt`
**Analog:** `SysExProtocolTest.kt` (frame-byte assertions). Assert the RIFF header bytes for a
known `ShortArray`: `"RIFF"`/`"WAVE"`/`"fmt "`/`"data"` literals, `sampleRate == 46875` (LE),
`bitsPerSample == 16`, `channels`, and `dataSize == pcm.size * 2`. Add a **pass-through** test:
an already-46875/s16/mono WAV returns byte-identical (Landmine 2/3 regression guards).

### `test/.../ResamplerTest.kt`
**Analog:** `ProjectProtocolTest.kt` pure-fn tests (e.g. `pack7bit_roundTrips...`,
`SysExProtocolTest.kt:21-31`). Synthetic PCM (a sine at a known rate) → assert output length
(`44100→46875` ratio) and a few endpoint sample values; assert **no-op** (identical array)
when `srcRate == 46875`.

### `test/.../SampleImportTest.kt`
**Analog:** `ProjectProtocolTest.kt:66-98` (`putInitFrame_*`, `putDataFrame_*`). Use the same
`unpackPayload(frame) = unpack7bit(frame[9 .. size-1])` helper. Assert `putSampleFile` /
`buildFilePut*Frame` produce a correct INIT + multiple paged DATA frames for a multi-KB WAV
(page increments, chunk bytes survive 7-bit pack/unpack — Landmine 5 truncation guard).

**`unpackPayload` helper to copy** (`ProjectProtocolTest.kt:19-21`):
```kotlin
private fun unpackPayload(frame: ByteArray): ByteArray =
    SysExProtocol.unpack7bit(frame.copyOfRange(9, frame.size - 1))
```

### `test/.../SampleImportViewModelTest.kt`
**Analog:** `ProjectsViewModelTest.kt` (whole test-double harness). Copy the `SpyMIDIPort`
(records `sent` frames, fakes connected/disconnected) + the `open`-method `FakeMIDIRepo`
subclass, plus `StandardTestDispatcher` + `Dispatchers.setMain`/`resetMain` +
`advanceUntilIdle()`. Assert: picked URIs map to a staged list; progress states advance
pending → done/error; snackbar message set on completion.

**Test-double harness to copy** (`ProjectsViewModelTest.kt:8-44`, `:52-55`):
```kotlin
private class SampleImportSpyMIDIPort(private val connected: Boolean = false) : MIDIPort {
    val sent = mutableListOf<ByteArray>()
    override fun sendMidi(portId: String, data: ByteArray) { sent.add(data) }
    // ... other MIDIPort no-ops
}
private class SampleImportFakeMIDIRepo(initialConnected: Boolean = false)
    : MIDIRepository(SampleImportSpyMIDIPort(initialConnected)) { /* override open methods */ }
```

---

## Shared Patterns

### Connected-device guard (every device-write entry point)
**Source:** `ProjectBackupManager.kt:68-71` / `:132-135`.
**Apply to:** `SampleImportManager`, `MIDIRepository.putSampleFile`.
```kotlin
if (midi.deviceState.value.outputPortId == null) {
    emit(SampleImportProgress.Error("No EP-133 connected")); return@flow
}
```

### Coroutine + error-handling house rules (CLAUDE.md, enforced repo-wide)
**Source:** `ProjectBackupManager.kt:75-82`, `MIDIRepository.kt:508-515`.
**Apply to:** all new suspend/Flow code.
- Rethrow `CancellationException` **before** the generic `catch (e: Exception)`.
- `Log.e(TAG, msg, throwable)` — always pass the throwable. Tags: `EP133MIDI` (MIDI layer),
  `EP133APP` (repository/manager).
- `withContext(Dispatchers.IO)` for URI/file reads; `Dispatchers.Default` for the CPU-bound
  convert; `viewModelScope` in ViewModels, never `GlobalScope`.

### StateFlow exposure discipline (CLAUDE.md)
**Source:** `DeviceScreen.kt:93-110`.
**Apply to:** `SampleImportViewModel`.
- `_`-prefixed `private val _x = MutableStateFlow(...)` → public `val x: StateFlow<...> = _x.asStateFlow()`. Never expose `MutableStateFlow`.
- `MIDI`/`USB`/`EP133` acronym casing preserved.

### 7-bit SysEx packing (do NOT re-implement — Landmine, "Don't Hand-Roll")
**Source:** `SysExProtocol.pack7bit`/`unpack7bit` (`SysExProtocol.kt:66`), already wraps every
PUT payload via `buildFrame`. The WAV payload routes through it untouched; the
`SampleImportTest` round-trip assertion is the regression guard.

### Storage pre-flight (Landmine 6) — reuse existing FILE_METADATA read
**Source:** `MIDIRepository.queryDeviceStatsInner` (`MIDIRepository.kt:431-444`) already reads
`/sounds` `used_space_in_bytes` / `max_capacity` into `deviceState.storageUsedBytes` /
`storageTotalBytes`. **Apply to:** `SampleImportManager` — gate a batch on converted-size vs
free space before writing; no new metadata machinery needed.

---

## No Analog Found

| File | Role | Data Flow | Reason / Closest framework pattern |
|------|------|-----------|------------------------------------|
| `domain/audio/AudioDecoder.kt` | utility | file-I/O | No `MediaCodec`/`MediaExtractor` usage anywhere in repo. Use the documented `MediaExtractor → MediaCodec.createDecoderByType → drain buffers` pattern (05-RESEARCH "Pattern 2", developer.android.com). Decode is the only hardware/instrumentation-bound new piece — Robolectric/instrumented or manual-only. |
| `domain/audio/Resampler.kt` | utility | transform | No DSP/resampling code in repo. Pure-function + unit-test *shape* mirrors `SysExProtocol.assembleGetPages` (pure, hardware-free, directly testable), but the linear-interp algorithm itself is new (hand-write ~40 lines per 05-RESEARCH "DO hand-roll"). |

---

## Metadata

**Analog search scope:** `AndroidApp/app/src/main/java/com/ep133/sampletool/{domain/midi,domain/audio,ui}`, `AndroidApp/app/src/test/java/com/ep133/sampletool/`.
**Files scanned:** MIDIRepository.kt, SysExProtocol.kt, ProjectBackupManager.kt, BackupManager.kt, DeviceScreen.kt, ProjectsScreen.kt, EP133App.kt, MainActivity.kt, ProjectProtocolTest.kt, SysExProtocolTest.kt, ProjectsViewModelTest.kt.
**Pattern extraction date:** 2026-06-20

## PATTERN MAPPING COMPLETE
