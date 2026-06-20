---
phase: 04-project-management
plan: 04
type: execute
wave: 3
depends_on: ["04-project-management-03", "04-project-management-01"]
files_modified:
  - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/projects/ProjectsScreen.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectsViewModelTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/BackupLibraryTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/ShareIntentTest.kt
autonomous: false
requirements: [PROJ-01, PROJ-03, PROJ-04]
must_haves:
  truths:
    - "A Projects tab appears in the bottom nav and opens a screen listing the 9 slots with names + active marker"
    - "Tapping backup on a slot shows progress and writes a backup, which then appears in the backup library list with name + timestamp"
    - "The library list is scrollable, sorted newest-first, showing file name and timestamp"
    - "Sharing a backup launches the Android share sheet with a FileProvider content:// URI (never file://)"
  artifacts:
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/ui/projects/ProjectsScreen.kt"
      provides: "Projects browser + backup library Compose screen and co-located ProjectsViewModel"
      contains: "fun ProjectsScreen"
      min_lines: 120
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt"
      provides: "Projects nav destination registration"
      contains: "PROJECTS"
  key_links:
    - from: "ProjectsScreen library row"
      to: "FileProvider.getUriForFile + ShareCompat.IntentBuilder"
      via: "share action"
      pattern: "FileProvider.getUriForFile"
    - from: "EP133App"
      to: "ProjectsScreen"
      via: "composable(NavRoute.PROJECTS.route)"
      pattern: "NavRoute.PROJECTS"
    - from: "ProjectsViewModel"
      to: "MIDIRepository.listProjects + ProjectBackupManager"
      via: "viewModelScope flow collection"
      pattern: "listProjects"
---

<objective>
Build the user-facing layer: a Projects Compose screen mirroring DeviceScreen — the 9-slot browser (PROJ-01) with active marker and connection gating, a scrollable backup library with timestamps (PROJ-03), and a FileProvider + ShareCompat share action (PROJ-04). Register the Projects tab in nav and wire the ViewModel in MainActivity. Restore stays behind the existing restore-confirm AlertDialog and is enabled only after the Wave 2 hardware round-trip passes (Open Q2 gate).

Purpose: PROJ-01 browser UI, PROJ-03 library, PROJ-04 share.
Output: ProjectsScreen.kt with co-located ProjectsViewModel; EP133App nav entry; MainActivity wiring; filled ProjectsViewModelTest/BackupLibraryTest/ShareIntentTest.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/phases/04-project-management/04-RESEARCH.md
@.planning/phases/04-project-management/04-PATTERNS.md
@.planning/phases/04-project-management/04-03-SUMMARY.md

<interfaces>
From domain layer (Waves 1-2):
- MIDIRepository.listProjects(): List<ProjectSlot>; ProjectSlot(nodeId, name, sizeBytes, isActive); deviceState: StateFlow<DeviceState> (DeviceState.connected)
- ProjectBackupManager(midi): backupProject(slot, context): Flow<ProjectBackupProgress>; restoreProject(file, context): Flow<…>; listBackups(context): List<BackupItem>; BackupItem(file, name, timestamp); sealed ProjectBackupProgress{ Progress(current,total); Done(file); Error(message) }

UI analogs to mirror (read, do not break):
- ui/device/DeviceScreen.kt: DeviceViewModel co-located above the @Composable (lines 78-228); StateFlow encapsulation _x/MutableStateFlow + asStateFlow() (92-110); onBackupUriSelected flow-collect→progress/snackbar (127-162); Scaffold + LaunchedEffect(snackbar) (245-324); DeviceCard/BackupRestoreSection cards + LinearProgressIndicator(progress={backupProgress}) (393-467, 660-725); restore AlertDialog (256-272); DeviceConnectionState not-connected gate (333-391); teal-dot online indicator (401-413); LocalContext.current import (line 52); TEColors.Teal
- ui/EP133App.kt: NavRoute enum (53-63), EP133App(...) params (65-73), composable() registration (151-162), bottom-bar NavRoute.entries.forEach loop (83-140 — picks up new entry automatically)
- MainActivity.kt: VM construction (68-72), EP133App(...) call (88-95), registerForActivityResult SAF launcher + callback-injection seam (75-84), register-before-setContent constraint

Share (RESEARCH Pattern 4 / Code Examples 480-490): FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file); ShareCompat.IntentBuilder(context).setType("application/octet-stream").setStream(uri).setChooserTitle("Share EP-133 project backup").startChooser(). Authority com.ep133.sampletool.fileprovider declared in Wave 0.
Icon: Icons.Filled.FolderOpen (already importable; DeviceScreen imports it).
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: Build ProjectsScreen + co-located ProjectsViewModel (browser, library, share)</name>
  <read_first>
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt (entire file — copy its shape: VM co-location, StateFlow encapsulation 92-110, flow-collect 127-162, Scaffold/snackbar/LaunchedEffect 245-324, cards + LinearProgressIndicator 660-725, restore AlertDialog 256-272, connection gate 333-391, teal-dot 401-413)
    - .planning/phases/04-project-management/04-PATTERNS.md (ProjectsScreen.kt + ProjectsViewModel sections)
    - .planning/phases/04-project-management/04-RESEARCH.md (Pattern 3 storage 319-330; Pattern 4 share 332-360; Pitfall 4 file:// 418-421; Open Question 3 summary depth 531-534)
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectsViewModelTest.kt, BackupLibraryTest.kt, ShareIntentTest.kt (Wave 0 stubs)
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ChordsViewModelTest.kt (SpyMIDIPort/FakeMIDIRepo doubles + coroutine harness)
  </read_first>
  <files>AndroidApp/app/src/main/java/com/ep133/sampletool/ui/projects/ProjectsScreen.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectsViewModelTest.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/BackupLibraryTest.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/ShareIntentTest.kt</files>
  <action>
    Create ui/projects/ProjectsScreen.kt with a co-located ProjectsViewModel (CLAUDE.md co-location rule) constructed with (midi: MIDIRepository, backupManager: ProjectBackupManager). ViewModel state: `_slots: MutableStateFlow<List<ProjectSlot>>`, `_backups: MutableStateFlow<List<BackupItem>>`, `_isBackupInProgress`, `_backupProgress: MutableStateFlow<Float>`, `_snackbarMessage: MutableStateFlow<String?>` — all with public asStateFlow() accessors, underscore-prefixed private backing fields, never expose mutable. Methods: `loadProjects()` (viewModelScope.launch → midi.listProjects() → _slots), `loadBackups(context)` (backupManager.listBackups → _backups), `backupSlot(slot, context)` (collect backupProgress flow, map Progress→_backupProgress=current/total, Done→refresh library + snackbar, Error→snackbar — copy onBackupUriSelected structure), `restoreBackup(file, context)` gated behind confirmation. viewModelScope launches; rethrow CancellationException.

    Composable ProjectsScreen(viewModel): Scaffold with snackbarHost + LaunchedEffect(snackbarMessage). If !deviceState.connected → DeviceConnectionState-style not-connected panel for the slot section (library still browsable offline). Slot list: a LazyColumn of cards (copy ElevatedCard/OutlinedCard), each showing slot.name, a content-summary line ("Group A–D" / size), the teal-dot active indicator when slot.isActive (RESEARCH Open Q3: v1 = name + active marker + lightweight summary, no full-archive download), and a Backup button driving a LinearProgressIndicator(progress={backupProgress}). Backup library section: a scrollable LazyColumn of BackupItem rows showing name + formatted timestamp + a Share button. Share button: val context = LocalContext.current; FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file) → ShareCompat.IntentBuilder(...).setType("application/octet-stream").setStream(uri).setChooserTitle("Share EP-133 project backup").startChooser(). NEVER Uri.fromFile / file:// (Pitfall 4). Restore action (Open Q2): copy DeviceScreen's restore AlertDialog verbatim for the destructive confirm; keep the restore button present but disabled/hidden behind a flag until the hardware round-trip passes — add a `// HARDWARE-GATE (Open Q2): enable restore button after hardware backup→restore round-trip` comment.

    Fill the three tests: ProjectsViewModelTest — using FakeMIDIRepo (override deviceState + listProjects to return 9 entries with one active), assert _slots maps 9 entries and exactly one isActive==true. BackupLibraryTest — create two temp .tar files with distinct mtimes, assert listBackups returns them sorted newest-first. ShareIntentTest — assert the share builder uses MIME "application/octet-stream"; `@Ignore` any test requiring a real FileProvider/Context with the repo's hardware/Robolectric-absent justification string.

    Conventions: val over var; MIDI/EP133/USB casing; TEColors.Teal for the active dot; Dispatchers.IO already handled in the manager.
  </action>
  <verify>
    <automated>JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home bash -c 'cd AndroidApp && ./gradlew :app:testDebugUnitTest --tests "*.ProjectsViewModelTest" --tests "*.BackupLibraryTest" --tests "*.ShareIntentTest"'</automated>
  </verify>
  <acceptance_criteria>
    - ProjectsViewModelTest: 9 slots mapped, exactly one active.
    - BackupLibraryTest: temp .tar files sorted newest-first.
    - ShareIntentTest: MIME constant asserted; Context-dependent test @Ignore'd with justification.
    - ProjectsScreen uses FileProvider.getUriForFile (no Uri.fromFile / "file://" anywhere in the file).
    - ProjectsViewModel exposes only StateFlow (no public MutableStateFlow); HARDWARE-GATE (Open Q2) comment at the restore button.
  </acceptance_criteria>
  <done>Browser + library + share screen built and unit-asserted; restore present but hardware-gated.</done>
</task>

<task type="auto">
  <name>Task 2: Register the Projects nav destination and wire the ViewModel in MainActivity</name>
  <read_first>
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt (NavRoute enum 53-63; EP133App params 65-73; composable registration 151-162; bottom-bar loop 83-140)
    - AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt (VM construction 68-72; EP133App call 88-95; SAF launcher seam 75-84; register-before-setContent)
    - .planning/phases/04-project-management/04-PATTERNS.md (EP133App.kt + MainActivity.kt sections)
  </read_first>
  <files>AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt, AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt</files>
  <action>
    In EP133App.kt: add NavRoute.PROJECTS("projects", "PROJECTS", Icons.Filled.FolderOpen) to the enum; add `projectsViewModel: ProjectsViewModel` to the EP133App(...) signature; add `composable(NavRoute.PROJECTS.route) { ProjectsScreen(projectsViewModel) }` mirroring the DEVICE registration. The NavRoute.entries.forEach bottom-bar loop picks up the new tab automatically — do not modify it. In MainActivity.kt: construct `val projectBackupManager = ProjectBackupManager(midiRepo)` and `val projectsViewModel = ProjectsViewModel(midiRepo, projectBackupManager)` alongside the existing VMs (before setContent), and pass projectsViewModel into the EP133App(...) call. Backup uses app-specific storage so NO SAF launcher is needed (simpler than Device). Trigger projectsViewModel.loadProjects()/loadBackups(context) on screen entry (LaunchedEffect in the screen or on VM init). Keep acronym casing; val over var.
  </action>
  <verify>
    <automated>JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home bash -c 'cd AndroidApp && ./gradlew :app:assembleDebug :app:lintDebug'</automated>
  </verify>
  <acceptance_criteria>
    - assembleDebug succeeds — the Projects screen, nav entry, and MainActivity wiring all compile together.
    - NavRoute.PROJECTS registered with a composable mapping to ProjectsScreen.
    - ProjectsViewModel + ProjectBackupManager constructed in MainActivity before setContent and passed to EP133App.
    - lintDebug reports no new errors.
  </acceptance_criteria>
  <done>Projects tab is reachable in the running app; full debug APK builds.</done>
</task>

</tasks>

<hardware_checkpoints>
Phase-gate UAT on a physical EP-133 (cannot be automated — no USB-MIDI emulator):
1. Open Projects screen → confirm 9 slots show real names + correct active marker (PROJ-01; validates Open Q1 addressing).
2. Back up one slot → confirm a `.tar` is written and appears in the library with name + timestamp (PROJ-02/PROJ-03), and the tar opens to a valid project tree.
3. Restore the backed-up `.tar` → confirm the device reloads the project (PROJ-02; enables the Open Q2 restore button only on success).
4. Share a backup → confirm it reaches Files/Drive/AirDrop targets (PROJ-04).
</hardware_checkpoints>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| app → arbitrary receiving app (share) | A content:// URI is granted to whichever app the user picks in the share sheet |
| user tap → destructive restore PUT | A library row can trigger an overwrite of a live device slot |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-04-09 | Information disclosure | sharing a file:// URI or over-broad grant | mitigate | FileProvider.getUriForFile only (no Uri.fromFile); ShareCompat grants FLAG_GRANT_READ_URI_PERMISSION scoped to the single backup file; provider exported=false (Wave 0) |
| T-04-10 | Tampering | accidental destructive restore | mitigate | Restore button behind the DeviceScreen-style AlertDialog confirmation; button disabled until the hardware round-trip gate (Open Q2) passes |
| T-04-11 | DoS | UI blocks while listing/backing up large projects | mitigate | Enumeration + backup run in viewModelScope off the main thread; file I/O on Dispatchers.IO (Wave 2); progress surfaced via LinearProgressIndicator |
| T-04-SC | Tampering | npm/pip/cargo installs | accept | No package installs this phase (RESEARCH Package Legitimacy Audit: zero new packages) |
</threat_model>

<verification>
- `--tests "*.ProjectsViewModelTest" "*.BackupLibraryTest" "*.ShareIntentTest"` green.
- `cd AndroidApp && ./gradlew :app:assembleDebug :app:lintDebug` green.
- Phase-gate hardware UAT (4 steps above) before `/gsd:verify-work`.
</verification>

<success_criteria>
- Projects tab + 9-slot browser with active marker (PROJ-01).
- Scrollable backup library with name + timestamp (PROJ-03).
- Share via FileProvider + ShareCompat content:// URI (PROJ-04).
- Restore present, confirmed, and hardware-gated.
</success_criteria>

<output>
Create `.planning/phases/04-project-management/04-04-SUMMARY.md` when done. List the 4-step hardware UAT as the phase-gate checklist.
</output>
