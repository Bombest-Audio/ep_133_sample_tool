---
phase: 05-splice-sample-sync
plan: 04
type: execute
wave: 3
depends_on: ["05-splice-sample-sync-03", "05-splice-sample-sync-01"]
files_modified:
  - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/import/SampleImportScreen.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportViewModelTest.kt
autonomous: false
requirements: [SAMPLE-01, SAMPLE-04]
user_setup: []
must_haves:
  truths:
    - "An Import destination is reachable from the bottom nav and opens a screen with a pick-files action"
    - "Tapping pick launches the SAF OpenMultipleDocuments picker filtered to audio/*; selecting files adds one staged row per file"
    - "Each staged row shows per-file state (pending/converting/loading/done/error) and a per-file progress indicator"
    - "On completion each row shows a clear success or failure result, and a snackbar surfaces batch-level messages"
    - "SampleImportViewModelTest is GREEN"
  artifacts:
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/ui/import/SampleImportScreen.kt"
      provides: "Import Compose screen + co-located SampleImportViewModel (SAF pick, staged list, per-file progress) (SAMPLE-01/04)"
      contains: "fun SampleImportScreen"
      min_lines: 120
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt"
      provides: "Import nav destination registration"
      contains: "IMPORT"
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt"
      provides: "OpenMultipleDocuments launcher registered before setContent + VM/manager wiring"
      contains: "OpenMultipleDocuments"
  key_links:
    - from: "MainActivity"
      to: "SampleImportViewModel.onFilesPicked"
      via: "registerForActivityResult(OpenMultipleDocuments) before setContent"
      pattern: "OpenMultipleDocuments"
    - from: "EP133App"
      to: "SampleImportScreen"
      via: "composable(NavRoute.IMPORT.route)"
      pattern: "NavRoute.IMPORT"
    - from: "SampleImportViewModel"
      to: "SampleImportManager flow"
      via: "viewModelScope collect -> staged-list state"
      pattern: "importSample"
---

<objective>
Build the user-facing import layer: a SampleImportScreen (mirroring DeviceScreen) with a SAF multi-file picker, a staged list of per-file rows each showing state + per-file progress, and a clear success/failure result per sample plus snackbar; the co-located SampleImportViewModel that maps picked URIs to staged items and drives the SampleImportManager Flow; an Import nav destination; and MainActivity wiring that registers the OpenMultipleDocuments launcher before setContent (repo lifecycle convention) and instantiates SampleImportManager + SampleImportViewModel. Turns SampleImportViewModelTest GREEN. The end-to-end import (real picker + decode + on-device upload) needs hardware, so this plan is non-autonomous and the live flow is recorded as a UAT entry.

Purpose: SAMPLE-01 (SAF multi-pick) + SAMPLE-04 (import screen with per-file progress + result).
Output: SampleImportScreen.kt (+ co-located VM); EP133App Import nav entry; MainActivity launcher + wiring; SampleImportViewModelTest green.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md
@.planning/phases/05-splice-sample-sync/05-RESEARCH.md
@.planning/phases/05-splice-sample-sync/05-PATTERNS.md

<interfaces>
<!-- Existing patterns to mirror + the Wave 2/3 symbols to wire. -->
DeviceViewModel (DeviceScreen.kt ~78-228) — copy the StateFlow exposure: private _x = MutableStateFlow(...); val x = _x.asStateFlow(); _snackbarMessage + dismissSnackbar(); per-op inProgress + progress floats; SAF callback var set by MainActivity (var onRequestBackup); viewModelScope.launch { flow.collect { when(progress){...} } }.

DeviceScreen composable (DeviceScreen.kt ~230-303 + snackbar idiom ~243-253) — collectAsState; remember { SnackbarHostState() }; LaunchedEffect(snackbarMessage){ showSnackbar then dismissSnackbar() }; Scaffold(snackbarHost=...); Column verticalScroll; LinearProgressIndicator.

EP133App.kt NavRoute enum (~56-67) + NavHost composable() block (~149-168) — add IMPORT entry + composable(NavRoute.IMPORT.route){ SampleImportScreen(sampleImportViewModel) }.

MainActivity.kt (~74-100) — instantiate alongside projectBackupManager/deviceViewModel; register SAF launcher BEFORE setContent (Activity lifecycle); pass VM into EP133App(...).

Wave 2 symbols: SampleImportManager(midiRepo).importSample(name, ...): Flow<SampleImportProgress>; SampleImportProgress.{Progress,Done,Error}; SampleImportManager.convert(context, uri).

SampleImportViewModel contract locked by Plan 01's SampleImportViewModelTest: stagedSamples: StateFlow<List<StagedSample>> (StagedSample carries name + a per-file state pending/converting/loading/done/error); snackbarMessage + dismissSnackbar(); var onRequestPick: (() -> Unit)?; fun onFilesPicked(uris: List<Uri>, context: Context); plus the content-free test seam (importStagedBytes / injectable bytes) the VM test drives.
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: SampleImportViewModel + SampleImportScreen (SAF pick, staged list, per-file progress)</name>
  <files>AndroidApp/app/src/main/java/com/ep133/sampletool/ui/import/SampleImportScreen.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportViewModelTest.kt</files>
  <read_first>
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt (lines 78-303 — VM StateFlow discipline, SAF callback var, snackbar+LaunchedEffect idiom, Scaffold/Column/LinearProgressIndicator)
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/projects/ProjectsScreen.kt (LazyColumn row + progress row layout, line ~419 LinearProgressIndicator)
    - AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportViewModelTest.kt (the contract this VM must satisfy — created in Plan 01)
    - 05-PATTERNS.md "SampleImportViewModel" + "SampleImportScreen" + "StateFlow exposure discipline" + "Coroutine + error-handling house rules"
    - 05-RESEARCH.md Landmine 7 (read URI bytes inside the grant under Dispatchers.IO)
  </read_first>
  <action>
    Create ui/import/SampleImportScreen.kt. Co-locate `class SampleImportViewModel(private val midi: MIDIRepository, private val importManager: SampleImportManager) : ViewModel()` in the same file (CLAUDE.md rule).
    Define `data class StagedSample(val name: String, val state: StagedState, val progress: Float = 0f, val message: String? = null)` and `enum class StagedState { PENDING, CONVERTING, LOADING, DONE, ERROR }`.
    VM state: private _stagedSamples = MutableStateFlow<List<StagedSample>>(emptyList()); val stagedSamples = _stagedSamples.asStateFlow(); _snackbarMessage + dismissSnackbar(); var onRequestPick: (() -> Unit)? = null.
    fun triggerPick() = onRequestPick?.invoke().
    fun onFilesPicked(uris: List<Uri>, context: Context): seed _stagedSamples with one PENDING StagedSample per uri (derive display name from the URI's last path segment), then viewModelScope.launch over each uri: read bytes inside the grant under Dispatchers.IO (Landmine 7); set the row CONVERTING; importManager.convert(context, uri) under Dispatchers.Default; set LOADING; collect importManager.importSample(name, wavBytes), mapping Progress -> row.progress, Done -> row DONE + per-file success message, Error -> row ERROR + message + _snackbarMessage. Rethrow CancellationException; Log.e on failure. Update rows immutably (copy the list, replace the indexed item) so StateFlow emits.
    Expose the content-free test seam the VM test uses (e.g. internal fun importStagedBytes(name, bytes) that runs the LOADING+collect half without a content:// read), so SampleImportViewModelTest drives the staged-list state machine without a real Context.
    Create the @Composable fun SampleImportScreen(viewModel: SampleImportViewModel): collectAsState on stagedSamples + snackbarMessage; remember { SnackbarHostState() } + LaunchedEffect(snackbarMessage){ show then dismiss }; Scaffold(snackbarHost=...) with a top "Import samples" action button calling viewModel.triggerPick(); a LazyColumn of staged rows, each showing the name, a state label, and a LinearProgressIndicator (indeterminate for CONVERTING/LOADING, value=progress otherwise) plus a check/error glyph + message on DONE/ERROR. Use TEColors per the theme. Disconnected device: show an inline hint (mirror DeviceScreen's connection gating) but still allow picking (conversion works offline; only upload needs the device).
    Also extend the Plan 01 SampleImportViewModelTest if any assertion targeted a symbol whose final name shifted (keep the test the source of truth; the VM must satisfy it, not the reverse).
  </action>
  <verify>
    <automated>cd AndroidApp && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home ./gradlew :app:testDebugUnitTest --tests "*.SampleImportViewModelTest"</automated>
  </verify>
  <acceptance_criteria>
    - SampleImportViewModelTest is green: picked inputs map to a staged list; rows advance pending -> done on a connected repo and pending -> error on a disconnected one; snackbar set on completion.
    - VM exposes only read-only StateFlow (no public MutableStateFlow); _-prefixed backing fields; MIDI/EP133 acronym casing preserved.
    - URI byte reads happen under Dispatchers.IO inside the picker callback (Landmine 7); CancellationException rethrown.
    - SampleImportViewModel is co-located in SampleImportScreen.kt.
  </acceptance_criteria>
  <done>`./gradlew :app:testDebugUnitTest --tests "*.SampleImportViewModelTest"` green; screen + VM built to the locked contract.</done>
</task>

<task type="auto">
  <name>Task 2: Wire Import nav + MainActivity SAF launcher (OpenMultipleDocuments before setContent)</name>
  <files>AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt, AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt</files>
  <read_first>
    - AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt (whole file — lines 74-100: VM/manager instantiation block; 78-88: SAF launchers registered before setContent; 90-101: setContent + EP133App call)
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt (NavRoute enum 56-67; EP133App signature + NavHost 69-168)
    - 05-PATTERNS.md "MainActivity.kt" + "EP133App.kt" (launcher registration before setContent; nav entry to copy)
    - STATE.md decision: "SAF launchers must be registered before setContent() in MainActivity"
  </read_first>
  <action>
    EP133App.kt: add `IMPORT("import", "IMPORT", Icons.Default.FileUpload)` to the NavRoute enum; add a `sampleImportViewModel: SampleImportViewModel` parameter to EP133App(...); add `composable(NavRoute.IMPORT.route) { SampleImportScreen(sampleImportViewModel) }` to the NavHost. Import the new symbols.
    MainActivity.kt: in onCreate, alongside the existing projectBackupManager/deviceViewModel block (~74-76), instantiate `val sampleImportManager = SampleImportManager(midiRepo)` and `val sampleImportViewModel = SampleImportViewModel(midiRepo, sampleImportManager)`. BEFORE setContent (Activity-lifecycle constraint), register `val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> sampleImportViewModel.onFilesPicked(uris, this) }` and set `sampleImportViewModel.onRequestPick = { importLauncher.launch(arrayOf("audio/*")) }`. Pass sampleImportViewModel into the EP133App(...) call. Add the imports (com.ep133.sampletool.domain.midi.SampleImportManager, com.ep133.sampletool.ui.import.SampleImportViewModel). Do not disturb the existing backup/restore launchers or the usbReceiver wiring.
  </action>
  <verify>
    <automated>cd AndroidApp && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug</automated>
  </verify>
  <acceptance_criteria>
    - :app:assembleDebug succeeds with the Import destination wired and the OpenMultipleDocuments launcher registered before setContent.
    - Full :app:testDebugUnitTest suite green; :app:lintDebug clean (no MutableImplicitPendingIntent or new lint regressions).
    - The launcher uses OpenMultipleDocuments (multi-select) and launches with arrayOf("audio/*"); EP133App receives sampleImportViewModel and registers composable(NavRoute.IMPORT.route).
  </acceptance_criteria>
  <done>assembleDebug + full unit suite + lint green; Import tab reachable and the multi-file SAF picker is wired before setContent.</done>
</task>

<task type="checkpoint:human-verify" gate="blocking">
  <name>Task 3: Checkpoint — review the Import UI + record the end-to-end UAT entry</name>
  <files>.planning/phases/05-splice-sample-sync/05-HUMAN-UAT.md</files>
  <action>Confirm 05-HUMAN-UAT.md carries a UAT-IMPORT-UI entry covering the live end-to-end flow (pick -> per-file progress -> DONE/ERROR, sample lands in /sounds and on a pad). Pause for human review of the Import UI behavior in code and confirmation that the deferred end-to-end check is documented honestly.</action>
  <what-built>
    The Import screen, staged list with per-file progress/results, SAF OpenMultipleDocuments multi-pick, and MainActivity/nav wiring are built and unit-tested. The end-to-end live flow (real picker selection -> real MediaCodec decode -> on-device /sounds upload -> sample appears + plays) needs a physical EP-133 + USB-host Android device and is deferred to UAT.
  </what-built>
  <how-to-verify>
    Confirm 05-HUMAN-UAT.md has a UAT-IMPORT-UI entry (added/updated this plan) covering: open the Import tab -> tap pick -> select one or more audio files (44.1 kHz WAV + an MP3) -> confirm one staged row per file, per-file progress advances, and each row ends in a clear DONE (with the sample landing in /sounds and on a pad) or ERROR with a readable message. This subsumes UAT-DECODE/UAT-SOUNDS-PUT/UAT-PITCH end-to-end through the UI (mirror 04-HUMAN-UAT UAT-4's "validates the lower UATs through the UI" framing). No code change needed to approve — confirm the UI behaves as built in code review and the UAT entry is documented.
  </how-to-verify>
  <resume-signal>Type "approved" once the Import UI is reviewed and 05-HUMAN-UAT.md records the end-to-end UAT-IMPORT-UI entry, or describe issues.</resume-signal>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| SAF picker -> app (content:// URIs) | OS-mediated; app receives untrusted file references |
| content:// bytes -> convert pipeline | untrusted file content (decode/convert handled in Wave 2, guarded there) |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-05-04-01 | Information disclosure | SAF URI read outside its grant (process death / deferred read) | mitigate | onFilesPicked reads bytes inside the picker callback's coroutine under Dispatchers.IO; no URI persisted (Landmine 7). |
| T-05-04-02 | DoS | A large multi-file batch ties up the UI / memory | mitigate | Per-file sequential processing on viewModelScope off the main thread; Wave 2 pre-flights /sounds free space before each write; rows stream state so the UI stays responsive. |
| T-05-04-03 | Tampering | A bad file surfaces a raw stack trace to the user | mitigate | Errors mapped to readable per-row messages + snackbar; Log.e(TAG, msg, throwable) keeps the detail in logcat, not the UI. |
| T-05-04-04 | Legal/ToS | Any Splice browser/network surface in the UI | mitigate | None built — the only source is the SAF audio/* picker. No Splice browser, no splice.com host. |
| T-05-04-SC | Tampering | package installs | accept | Zero external packages (SAF/Compose/Nav already on classpath). |
</threat_model>

<verification>
cd AndroidApp && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
End-to-end import (real picker + decode + /sounds upload + pad playback) DEFERRED to 05-HUMAN-UAT.md UAT-IMPORT-UI — needs a physical EP-133.
</verification>

<success_criteria>
SampleImportViewModelTest green; the Import screen + staged list + per-file progress + SAF multi-pick are built and wired into nav and MainActivity (launcher before setContent); assembleDebug + full unit suite + lint pass; the live end-to-end flow is documented in 05-HUMAN-UAT.md (UAT-IMPORT-UI). SAMPLE-01 + SAMPLE-04 complete in code.
</success_criteria>

<output>
Create `.planning/phases/05-splice-sample-sync/05-04-SUMMARY.md` when done.
</output>
