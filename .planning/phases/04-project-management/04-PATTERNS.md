# Phase 4: Project Management (Android) - Pattern Map

**Mapped:** 2026-06-20
**Files analyzed:** 11 (5 new, 4 modified, 2 new test files)
**Analogs found:** 11 / 11 (every file has a strong in-repo analog)

All analog paths below are relative to the repo root:
`/Users/thomasphillips/workspace/ep_133_sample_tool/.claude/worktrees/elated-torvalds-b407d5/`

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt` (MODIFY) | protocol/util | request-response (framing) | itself — extend `buildFileGetFrame`/`buildFilePutFrame` | exact (in-file) |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt` (MODIFY) | service (device dispatch) | streaming / paged request-response | itself — `dispatchFileResponse` + `queryDeviceStatsInner` | exact (in-file) |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/ProjectBackupManager.kt` (NEW) | service (orchestration) | streaming/file-I/O over protocol | `domain/midi/BackupManager.kt` | role+flow match |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/projects/ProjectsScreen.kt` (NEW) | component (Compose screen) | request-response + file-I/O (progress) | `ui/device/DeviceScreen.kt` | exact |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/projects/ProjectsViewModel.kt` (NEW, may co-locate in Screen) | viewmodel/store | event-driven (StateFlow) | `DeviceViewModel` (in `DeviceScreen.kt`) + `ChordsViewModel.kt` | exact |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt` (MODIFY) | route registration | request-response (nav) | itself — `NavRoute` enum + `composable()` | exact (in-file) |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt` (MODIFY) | entry/wiring | event-driven | itself — VM construction + SAF launcher wiring | exact (in-file) |
| `AndroidApp/app/src/main/AndroidManifest.xml` (MODIFY) | config | config | itself — `<provider>` block | role match (new provider) |
| `AndroidApp/app/src/main/res/xml/file_paths.xml` (NEW) | config | config | `res/xml/usb_device_filter.xml` (only other res/xml) | structural only |
| `AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt` (NEW) | test (protocol) | request-response | `test/.../SysExProtocolTest.kt` + `BackupRestoreTest.kt` | exact |
| `AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectsViewModelTest.kt` (NEW) | test (VM) | event-driven | `test/.../ChordsViewModelTest.kt` | exact |

> Research's `MultiChunkGetTest.kt`, `SysExDispatchTest.kt`, `BackupLibraryTest.kt`, `ShareIntentTest.kt` are folded into `ProjectProtocolTest.kt` / `ProjectsViewModelTest.kt` here, or split at the planner's discretion. The test patterns below cover all of them. Note: dispatch/multi-chunk tests that need virtual-time/timeout simulation are currently `@Ignore`d in the repo (see `MIDIRepositoryStatsTest.kt`, `BackupRestoreTest.kt`) and validated on hardware — match that convention; don't invent a flaky timeout test.

---

## Pattern Assignments

### `SysExProtocol.kt` — ADD GET_INIT/DATA + PUT_INIT/DATA builders (protocol, request-response)

**Analog:** itself. The existing `buildFileGetFrame`/`buildFilePutFrame` (lines 194-233) use a single 2-byte `chunkIndex` and are the **wrong** model per RESEARCH Pitfall 1 — do NOT extend them for projects. Add NEW builders alongside, reusing `buildFrame` (which 7-bit-packs the whole payload) exactly like the existing file builders do.

**Constants to add** — mirror the existing constant block (lines 33-43):
```kotlin
// existing, keep:
const val TE_SYSEX_FILE = 5
const val TE_SYSEX_FILE_PUT = 2
const val TE_SYSEX_FILE_GET = 3
const val STATUS_OK = 0
const val STATUS_SPECIFIC_SUCCESS_START = 64
// ADD:
const val TE_SYSEX_FILE_GET_TYPE_INIT = 0
const val TE_SYSEX_FILE_GET_TYPE_DATA = 1
const val TE_SYSEX_FILE_PUT_TYPE_INIT = 0
const val TE_SYSEX_FILE_PUT_TYPE_DATA = 1
```

**Builder pattern to mirror** — the existing private `buildFileSystemFrame` + public wrapper (lines 173-212). New builders prepend `[TE_SYSEX_FILE, TE_SYSEX_FILE_GET, type, ...]` then call `buildFrame(deviceId, CMD_PRODUCT_SPECIFIC, requestId, payload)`. The INIT request carries `nodeId` (uint16 BE) + `offset` (uint32 BE); DATA carries `page` (uint16 BE). RESEARCH "Code Examples" (lines 437-463) gives the exact byte layout. Implement these as `private fun build…Payload(): ByteArray` + a public `build…Frame(deviceId, …, requestId)` that wraps via `buildFrame`, matching the file-builder shape.

**7-bit codec is reused as-is** (lines 56-99) — `pack7bit`/`unpack7bit` already round-trip arbitrary 8-bit data (proven by `SysExProtocolTest.pack7bitRoundtrip_preservesAllBytes`). The `.tar` archive bytes route through these. RESEARCH Pitfall 5.

---

### `MIDIRepository.kt` — ADD project enumeration + paged GET/PUT dispatch (service, streaming)

**Analog:** itself, two existing mechanisms to extend.

**Dispatch pattern** (lines 237-271, `dispatchFileResponse`). The current `TE_SYSEX_FILE_GET` branch (lines 263-269) emits to `_fileChunks` once per response. For paged transfer it MUST keep the request alive across `STATUS_SPECIFIC_SUCCESS_START` (64) responses and complete only on `STATUS_OK` (0) — exactly the status discrimination the `TE_SYSEX_FILE_LIST` branch already does (lines 245-262):
```kotlin
SysExProtocol.TE_SYSEX_FILE_LIST -> {
    val status = payload.getOrNull(0)?.toInt()?.and(0xFF) ?: return
    if (status == SysExProtocol.STATUS_OK || status == SysExProtocol.STATUS_SPECIFIC_SUCCESS_START) {
        fileListEntryCount++
        // ...parse + emit entry...
    }
    if (status == SysExProtocol.STATUS_OK) {            // <-- terminator
        pendingFileListCountDeferred?.complete(fileListEntryCount)
        pendingFileListCountDeferred = null
    }
}
```
For paged GET, model the transfer as a **Channel/SharedFlow of pages** (not a single `CompletableDeferred`) per RESEARCH Pitfall 3 — keep the pending handler registered while `status >= SUCCESS_START`, resolve on `STATUS_OK`.

**Request-response orchestration** to mirror — `queryDeviceStatsInner` (lines 319-357): set a pending deferred field, send the frame via `midiManager.sendMidi(portId, frame)`, `withTimeoutOrNull(...) { deferred.await() }`, null-out the pending field on completion/timeout. The new `listProjects()` and `getProjectArchive(nodeId)` follow this skeleton. Note the existing `statsQueryInFlight` guard (lines 310-316) against overlapping queries racing on shared pending fields — replicate that guard for project transfers.

**Pending-deferred field pattern** (lines 75-89): declare `private var pendingX: CompletableDeferred<…>? = null` and the file flows (`_fileChunks`, `_fileListEntries`) as `MutableSharedFlow` with a public `asSharedFlow()`. Never expose the mutable.

**Node-ID resolution (NEW capability, RESEARCH Pitfall 2):** FILE_LIST currently takes a path string (`buildFileListFrame(deviceId, "/sounds", …)`). The reference impl resolves `/projects` → nodeId first by listing root (nodeId 0). Plan `resolveNodeId(path)` here; verify on hardware whether the path string also works (Phase 2's `/sounds` may have relied on a firmware convenience).

**Logging convention:** `Log.d("EP133APP", …)` in the repository layer (used throughout, e.g. line 218); MIDI-frame layer uses `"EP133MIDI"`. Match per CLAUDE.md.

---

### `ProjectBackupManager.kt` (NEW) — single-project archive backup (service, file-I/O over protocol)

**Analog:** `domain/midi/BackupManager.kt`.

**Constructor + progress sealed classes** (lines 21-54): mirror exactly. Define `sealed class ProjectBackupProgress { data class Progress(current, total); data class Done(val tarBytes: ByteArray); data class Error(message) }` matching `BackupProgress` (lines 21-28). Class takes `(private val midi: MIDIRepository)`.

**Flow-emitting backup method** (lines 70-150, `createBackup`): mirror the `flow { … }` structure — early `emit(Progress(0,0))`, guard on `midi.deviceState.value.outputPortId == null` → `emit(Error(...))`, then the transfer loop emitting `Progress`, ending with `emit(Done(bytes))`. The KEY difference: this is an **opaque single-archive download** (RESEARCH "Don't Hand-Roll"), NOT a per-file ZIP assembly — so the inner loop is the paged INIT/DATA GET (accumulate into `ByteArrayOutputStream`), and there is no `ZipOutputStream` re-archiving. RESEARCH Pattern 2 (lines 298-317) gives the exact loop:
```kotlin
val init = sendGetInit(nodeId)              // → fileSize, fileName
val out = ByteArrayOutputStream(init.fileSize)
var page = 0
while (out.size() < init.fileSize) {
    val resp = sendGetData(page)
    require(resp.page == page) { "unexpected page ${resp.page}, expected $page" }
    if (resp.data.isEmpty()) break
    out.write(resp.data)
    page = resp.nextPage                    // (page + 1) & 0xFFFF
}
```

**Filename convention** (lines 59-62, `suggestedBackupFilename`): mirror — `"EP133-P0$slot-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())}.tar"`. Reuse the same date format string.

**File persistence (NEW, not in BackupManager):** BackupManager hands bytes back to the VM which writes via SAF `contentResolver.openOutputStream` (see `DeviceViewModel.onBackupUriSelected`). For Phase 4, RESEARCH Pattern 3 recommends app-specific storage instead: `context.getExternalFilesDir("backups")` + `file.writeBytes(tarBytes)`, null-checked (RESEARCH Pitfall 6). Do file I/O under `withContext(Dispatchers.IO)` (CLAUDE.md) — see `DeviceViewModel.onBackupUriSelected` lines 140-148 for the `withContext(Dispatchers.IO)` write wrapper to copy.

**Backup library enumeration (NEW)** — RESEARCH Pattern 3 (lines 322-330):
```kotlin
val backups = dir.listFiles { f -> f.extension == "tar" }
    ?.sortedByDescending { it.lastModified() }
    ?.map { BackupItem(it.name, it.lastModified()) } ?: emptyList()
```

---

### `ProjectsScreen.kt` (NEW) — 9-slot browser + backup library (component, request-response + progress)

**Analog:** `ui/device/DeviceScreen.kt` — copy its whole shape.

**ViewModel co-located in the same file** (DeviceScreen.kt lines 78-228 define `DeviceViewModel` above the `@Composable`). CLAUDE.md mandates co-location; `ChordsViewModel` is the documented exception. Put `ProjectsViewModel` here unless the planner splits it.

**StateFlow + progress field pattern** (DeviceViewModel lines 92-110): private `MutableStateFlow` backing field with underscore prefix, public `asStateFlow()`:
```kotlin
private val _isBackupInProgress = MutableStateFlow(false)
val isBackupInProgress: StateFlow<Boolean> = _isBackupInProgress.asStateFlow()
private val _backupProgress = MutableStateFlow(0f)
val backupProgress: StateFlow<Float> = _backupProgress.asStateFlow()
private val _snackbarMessage = MutableStateFlow<String?>(null)
```

**Flow-collection in viewModelScope** (lines 127-162, `onBackupUriSelected`): collect the `ProjectBackupManager` flow, map `Progress` → `_backupProgress.value = current/total`, on `Done` write the file + set snackbar, on `Error` set snackbar. Copy this `viewModelScope.launch { manager.flow().collect { when(it) {...} } }` structure.

**Screen scaffold + progress UI** (lines 230-324): `collectAsState()` for every flow, `Scaffold(snackbarHost = …)`, `LaunchedEffect(snackbarMessage)` to show+dismiss (lines 248-253), `verticalScroll`. The 9-slot list and library list replace the device cards. For the slot list use a `Column`/`LazyColumn` of cards; copy `ElevatedCard`/`OutlinedCard` + `LinearProgressIndicator(progress = { backupProgress })` from `BackupRestoreSection` (lines 660-725) and `DeviceCard` (lines 393-467).

**Active-slot marker + connection gating:** reuse `DeviceConnectionState` (lines 333-391) for the not-connected case (`if (!deviceState.connected)`), and the teal-dot online indicator idiom (lines 401-413) to mark the active project slot. Brand colors via `TEColors.Teal` etc. (CLAUDE.md).

**Restore confirmation dialog** (lines 256-272): if single-project restore ships (RESEARCH Open Question 2), copy this `AlertDialog` verbatim for the destructive-PUT confirm (Security Domain requirement).

**Share action (NEW)** — RESEARCH Pattern 4 / Code Examples (lines 480-490). Triggered from the library row; uses `LocalContext.current` (already imported in DeviceScreen line 52):
```kotlin
val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
ShareCompat.IntentBuilder(context)
    .setType("application/octet-stream")
    .setStream(uri)
    .setChooserTitle("Share EP-133 project backup")
    .startChooser()
```
NEVER share a `file://` URI (RESEARCH Pitfall 4 → `FileUriExposedException`).

---

### `EP133App.kt` — register the Projects tab (route registration)

**Analog:** itself.

**NavRoute enum** (lines 53-63): add an entry, e.g. `PROJECTS("projects", "PROJECTS", Icons.Default.FolderOpen)`. Pick an icon already importable (DeviceScreen already imports `Icons.Filled.FolderOpen`).

**VM param + composable registration** (lines 65-73 signature; lines 151-162 `composable()` block): add `projectsViewModel: ProjectsViewModel` to `EP133App(...)` params and:
```kotlin
composable(NavRoute.PROJECTS.route) { ProjectsScreen(projectsViewModel) }
```
Mirror the `DEVICE` registration (lines 155-161) if the screen needs `LocalContext` (it will, for share + storage). The `NavRoute.entries.forEach` bottom-bar loop (lines 83-140) picks up the new entry automatically — no change needed there.

---

### `MainActivity.kt` — construct + wire the ViewModel (entry/wiring)

**Analog:** itself (lines 57-96).

**VM construction** (lines 68-72): add `val projectsViewModel = ProjectsViewModel(midiRepo)` (or with a `ProjectBackupManager`/`context`) next to the others, and pass it into `EP133App(...)` (lines 88-95).

**SAF launcher wiring is the model for any picker** (lines 75-84): if backup uses app-specific storage (recommended), NO SAF launcher is needed for backup — simpler than Device. If restore-import or export-to-SAF is added, copy the `registerForActivityResult(ActivityResultContracts.CreateDocument(...)) { uri -> vm.onUriSelected(it, this) }` + `vm.onRequestBackup = { backupLauncher.launch(name) }` pattern verbatim (the callback-injection seam that keeps `registerForActivityResult` out of the ViewModel). Lifecycle constraint: register before `setContent`.

---

### `AndroidManifest.xml` — declare the FileProvider (config)

**Analog:** itself — add inside `<application>` (alongside the existing `<activity>` blocks, lines 23-47). RESEARCH Pattern 4 (lines 334-343):
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```
`applicationId` is `com.ep133.sampletool` (build.gradle.kts line 11) → authority `com.ep133.sampletool.fileprovider`. `exported="false"` per Security Domain.

---

### `res/xml/file_paths.xml` (NEW) — FileProvider path config (config)

**Analog:** structural sibling `res/xml/usb_device_filter.xml` (only other file in `res/xml/`). RESEARCH Pattern 4 (lines 345-350):
```xml
<paths>
    <external-files-path name="backups" path="backups/" />
</paths>
```
The `path="backups/"` must match `getExternalFilesDir("backups")`. If a `filesDir` fallback is used (RESEARCH Pitfall 6), add a `<files-path>` entry too.

---

### `ProjectProtocolTest.kt` (NEW) — protocol unit tests (test, request-response)

**Analogs:** `test/.../SysExProtocolTest.kt` + the `fileGetProtocol_buildsCorrectFrame` test in `BackupRestoreTest.kt`.

**Frame-byte assertion pattern** (SysExProtocolTest lines 42-70, `fileListFrame_commandByteIsCorrect`): build a frame, assert `frame[8]` command byte, then unpack the packed payload and assert the subcommand bytes:
```kotlin
assertEquals(SysExProtocol.CMD_PRODUCT_SPECIFIC, frame[8].toInt() and 0x7F)
val packedPayload = frame.copyOfRange(9, frame.size - 1)
val unpacked = SysExProtocol.unpack7bit(packedPayload)
assertEquals(SysExProtocol.TE_SYSEX_FILE, unpacked[0].toInt() and 0xFF)
assertEquals(SysExProtocol.TE_SYSEX_FILE_GET, unpacked[1].toInt() and 0xFF)
// then assert unpacked[2] == GET_TYPE_INIT, nodeId BE bytes, offset BE bytes
```
Cover: GET_INIT/DATA + PUT_INIT/DATA byte layout; FILE_LIST response parsing into `{nodeId, flags, size, name}`; a `pack7bit`/`unpack7bit` round-trip on a binary blob (mirror lines 20-31). Plain JUnit 4: `import org.junit.Test; import org.junit.Assert.*`.

**Multi-chunk loop / dispatch tests:** the page-mismatch-throws / empty-data-terminates logic is pure and testable. But anything needing virtual-time timeout simulation or a SysEx response simulator is `@Ignore`d in this repo and validated on hardware — see `MIDIRepositoryStatsTest.kt` lines 16-37 and `BackupRestoreTest.kt` lines 85-89. Match that: test the deterministic parsing/loop logic, `@Ignore` the timing-dependent paths with the same justification-string style.

---

### `ProjectsViewModelTest.kt` (NEW) — ViewModel unit test (test, event-driven)

**Analog:** `test/.../ChordsViewModelTest.kt`.

**Test doubles** (ChordsViewModelTest lines 28-59): copy `SpyMIDIPort : MIDIPort` (records `sent: mutableList`, returns a controllable `getUSBDevices()`) and `FakeMIDIRepo : MIDIRepository(SpyMIDIPort(...))` that overrides `deviceState` with a settable `MutableStateFlow<DeviceState>`. This is the established seam for VM tests without hardware.

**Coroutine test harness** (lines 14-26 imports): `StandardTestDispatcher` + `Dispatchers.setMain`/`resetMain` in `@Before`/`@After`, `runTest { }` for suspend assertions.

Cover: `listProjects()` maps 9 entries + marks the active slot (RESEARCH PROJ-01 test row); library enumeration sorts `.tar` by mtime desc (use a temp dir); share-intent construction can be a Robolectric/intent assertion or deferred to hardware per repo convention.

---

## Shared Patterns

### SysEx framing + 7-bit codec
**Source:** `domain/midi/SysExProtocol.kt` (`buildFrame` lines 119-135; `pack7bit`/`unpack7bit` lines 56-99)
**Apply to:** every new protocol builder and the archive byte path.
All frames go through `buildFrame(deviceId, CMD_PRODUCT_SPECIFIC, requestId, payload)`, which 7-bit-packs the full payload. Never hand-roll framing or packing; the codec round-trips arbitrary 8-bit data.

### Pending-deferred + SharedFlow dispatch
**Source:** `domain/midi/MIDIRepository.kt` (pending fields lines 74-89; `dispatchFileResponse` 237-271; `queryDeviceStatsInner` 319-357)
**Apply to:** `listProjects`, `getProjectArchive`, project PUT.
Set a `CompletableDeferred`/Channel field, send the frame, `withTimeoutOrNull { await() }`, clear the field. Discriminate `STATUS_SPECIFIC_SUCCESS_START` (keep alive) vs `STATUS_OK` (terminate). Guard overlapping in-flight transfers (`statsQueryInFlight` idiom, lines 310-316).

### StateFlow encapsulation
**Source:** `DeviceViewModel` (DeviceScreen.kt lines 92-110), `ChordsViewModel.kt` (throughout)
**Apply to:** `ProjectsViewModel`.
`private val _x = MutableStateFlow(...)` + public `val x: StateFlow<…> = _x.asStateFlow()`; never expose the mutable (CLAUDE.md). Underscore-prefixed backing fields. `viewModelScope` for launches; rethrow `CancellationException`.

### Flow-driven progress + snackbar UI
**Source:** `DeviceViewModel.onBackupUriSelected` (lines 127-162) + `DeviceScreen` Scaffold/snackbar/LaunchedEffect (lines 245-324) + `BackupRestoreSection` LinearProgressIndicator (lines 707-723)
**Apply to:** project backup in `ProjectsScreen`.
Collect the manager's progress `Flow` in `viewModelScope`, push to `_backupProgress`/`_snackbarMessage`, render with `LinearProgressIndicator(progress = { backupProgress })` and a `LaunchedEffect`-driven snackbar.

### IO dispatch + logging
**Source:** `DeviceViewModel` `withContext(Dispatchers.IO)` (lines 140-148); `MIDIRepository` `Log.d("EP133APP", …)` (line 218)
**Apply to:** all backup file writes/enumeration (`Dispatchers.IO`); repository-layer logs (`EP133APP`), MIDI-frame logs (`EP133MIDI`). `Log.e(TAG, msg, throwable)` always includes the throwable (CLAUDE.md).

### Test doubles
**Source:** `ChordsViewModelTest.kt` `SpyMIDIPort` + `FakeMIDIRepo` (lines 30-59)
**Apply to:** both new test files. The `MIDIPort` interface is the testability seam; `FakeMIDIRepo` overriding `deviceState` controls connection state without hardware.

---

## No Analog Found

None. Every file maps to a strong in-repo analog. Two capabilities are genuinely new to the codebase but have a clear external reference and a partial local analog:

| Capability | Local analog (partial) | External reference |
|------------|------------------------|--------------------|
| FileProvider + ShareCompat share | `MainActivity` SAF launcher wiring (callback-injection seam) | RESEARCH Pattern 4 (developer.android.com sharing guide) |
| App-specific external storage backup library | `BackupManager` filename convention; `DeviceViewModel` IO-write wrapper | RESEARCH Pattern 3 |
| Paged INIT/DATA multi-chunk transfer | `dispatchFileResponse` FILE_LIST status discrimination; `queryDeviceStatsInner` deferred orchestration | RESEARCH Pattern 2 / `data/index.js` `iterGet` |

---

## Metadata

**Analog search scope:** `AndroidApp/app/src/main/java/com/ep133/sampletool/{domain/midi,ui/device,ui/chords,ui}/`, `AndroidApp/app/src/test/java/com/ep133/sampletool/`, `AndroidApp/app/src/main/{AndroidManifest.xml,res/xml/}`, `AndroidApp/app/build.gradle.kts`
**Files scanned:** 11 source + 12 test files enumerated; 9 read in full/targeted
**Pattern extraction date:** 2026-06-20
