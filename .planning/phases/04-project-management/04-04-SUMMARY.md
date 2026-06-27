---
phase: 04-project-management
plan: 04
subsystem: android-ui-projects
tags: [compose, viewmodel, fileprovider, share, navigation, project-backup]
requires: [04-project-management-03, 04-project-management-01]
provides:
  - "ProjectsScreen Compose UI + co-located ProjectsViewModel (9-slot browser, backup library, share)"
  - "Projects nav tab registered in EP133App + wired in MainActivity"
  - "FileProvider + ShareCompat content:// share action for backups (PROJ-04)"
affects:
  - "Phase-gate hardware UAT (UAT-1/2/3/4) — restore button stays gated until UAT-3 passes"
tech-stack:
  added: []
  patterns:
    - "ViewModel co-located in Screen file with StateFlow encapsulation (mirrors DeviceViewModel)"
    - "FileProvider.getUriForFile + ShareCompat.IntentBuilder for content:// share (never file://)"
    - "Compile-time RESTORE_ENABLED gate for a destructive action behind a confirm dialog"
key-files:
  created:
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/projects/ProjectsScreen.kt
  modified:
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectsViewModelTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ShareIntentTest.kt
    - .planning/phases/04-project-management/04-HUMAN-UAT.md
decisions:
  - "MIDIRepository.listProjects() made `open` so the VM test fake can return canned 9-slot enumeration without driving the real SysEx FILE_LIST cycle"
  - "Restore gated by a compile-time RESTORE_ENABLED=false constant (Open Q2) — AlertDialog + restoreProject validation fully wired; flip one boolean after UAT-3"
  - "No SAF launcher for project backup — backup writes to app-specific storage (getExternalFilesDir), simpler than DeviceScreen's CreateDocument flow"
  - "SHARE_MIME extracted as a public top-level const so ShareIntentTest asserts the real constant the share builder uses"
metrics:
  duration: "~25m"
  completed: "2026-06-20"
  tasks: 2
  files: 7
---

# Phase 4 Plan 04: ProjectsScreen browser + backup library + share Summary

Built the user-facing project-management layer on top of the Wave 2 domain: a Projects Compose tab with a 9-slot browser + active marker (PROJ-01), a scrollable backup library sorted newest-first with name + timestamp (PROJ-03), and a FileProvider + ShareCompat `content://` share action (PROJ-04). Registered the tab in nav and wired the ViewModel in MainActivity. Restore is wired and confirmed behind an AlertDialog but stays disabled behind a compile-time gate until the hardware backup→restore round-trip (Open Q2 / UAT-3) passes. No physical EP-133 was attached — device-facing and share-target checks are deferred to the phase-gate UAT.

## What was built

**Task 1 — ProjectsScreen + co-located ProjectsViewModel (commit e941324)**
- `ProjectsViewModel(midi, backupManager)` mirroring `DeviceViewModel`: underscore-prefixed `MutableStateFlow` backing fields (`_slots`, `_backups`, `_isBackupInProgress`, `_backupProgress`, `_snackbarMessage`, `_showRestoreConfirm`) with public `asStateFlow()` accessors only — never exposes a mutable flow. `loadProjects()` / `loadBackups(context)` / `backupSlot(slot, context)` (collects `ProjectBackupProgress` → maps Progress/Done/Error, refreshes library on Done) / `requestRestore` + `confirmRestore` (gated). All in `viewModelScope`; `CancellationException` rethrown.
- `ProjectsScreen(viewModel)`: `Scaffold` + `SnackbarHost` + `LaunchedEffect(snackbarMessage)`; `LaunchedEffect` triggers `loadProjects()`/`loadBackups()` on entry and re-loads slots on reconnect. `LazyColumn` with a slot section (offline → `NotConnectedPanel`; connected → `SlotCard` per slot with the `TEColors.Teal` active dot + "ACTIVE" label + lightweight `Groups A–D · {KB}` summary, RESEARCH Open Q3) and a backup-library section (`BackupRow` per `.tar` with name + `yyyy-MM-dd HH:mm` timestamp, a Restore icon button gated by `RESTORE_ENABLED`, and a Share icon button).
- `shareBackup()`: `FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)` → `ShareCompat.IntentBuilder(...).setType(SHARE_MIME).setStream(uri).setChooserTitle(...).startChooser()`. No `Uri.fromFile` / `file://` anywhere (T-04-09 / Pitfall 4).
- Restore confirmation `AlertDialog` copied from DeviceScreen; `// HARDWARE-GATE (Open Q2)` comment at the restore button + a documented `RESTORE_ENABLED` constant.
- Made `MIDIRepository.listProjects()` `open` (test seam — the fake repo overrides it).
- `ProjectsViewModelTest`: `ProjectsFakeMIDIRepo` overrides `deviceState` + `listProjects()` returning 9 canned slots (one active) → asserts 9 mapped, exactly one active (P03), plus an empty-when-offline case. `ShareIntentTest`: asserts the real `SHARE_MIME` constant; the FileProvider/Context intent path stays `@Ignore`'d (Robolectric not in dep set). `BackupLibraryTest` already GREEN against the Wave 2 `enumerateBackups` helper.

**Task 2 — Nav registration + MainActivity wiring (commit 5c97a60)**
- `EP133App.kt`: added `NavRoute.PROJECTS("projects", "PROJECTS", Icons.Default.FolderOpen)` (the `NavRoute.entries.forEach` bottom-bar loop picks it up automatically), threaded `projectsViewModel` through the `EP133App(...)` signature, and registered `composable(NavRoute.PROJECTS.route) { ProjectsScreen(projectsViewModel) }`.
- `MainActivity.kt`: constructed `ProjectBackupManager(midiRepo)` + `ProjectsViewModel(midiRepo, projectBackupManager)` before `setContent` and passed the VM into `EP133App(...)`. No SAF launcher needed (app-specific storage).

## Verification

- `--tests "*.ProjectsViewModelTest" "*.BackupLibraryTest" "*.ShareIntentTest"` — PASS.
- Full `:app:testDebugUnitTest` — PASS (no regression).
- `:app:assembleDebug` — SUCCESS (ProjectsScreen + nav + MainActivity wiring compile into the debug APK).
- `:app:lintDebug` — fails ONLY on the pre-existing `MIDIManager.kt:159` MutableImplicitPendingIntent error (out of scope, see `deferred-items.md`). Zero new lint errors.

## Deviations from Plan

- **[Rule 3 — Blocking] `MIDIRepository.listProjects()` made `open`.** The plan's test seam (`ProjectsFakeMIDIRepo` overriding the repo) cannot return a canned 9-slot enumeration without an overridable `listProjects()` — it was `suspend fun` (final). Made it `open suspend fun`; no behavior change. Commit e941324.
- Restore gate implemented as a documented `RESTORE_ENABLED` const (default `false`) rather than a runtime flag — flipping one boolean after UAT-3 is the entire enable step, recorded in 04-HUMAN-UAT UAT-3.

## Hardware UAT (phase-gate checklist — physical EP-133 required, NOT verified)

Recorded/extended in `.planning/phases/04-project-management/04-HUMAN-UAT.md`:
1. **UAT-1** — Projects screen shows 9 slots with real names + correct active marker (PROJ-01 / Open Q1 addressing).
2. **UAT-2** — Back up a slot → a valid multi-KB `.tar` is written and appears in the library (PROJ-02/03).
3. **UAT-3** — Restore the `.tar` → device reloads the project; flip `RESTORE_ENABLED=true` on success (PROJ-02 / Open Q2).
4. **UAT-4** (new) — Share a backup → reaches Files/Drive/AirDrop with a `content://` URI, no `FileUriExposedException` (PROJ-04).

All four are DEFERRED and NOT marked verified — no device attached.

## Requirements

- PROJ-01: 9-slot browser UI with active marker + connection gating — complete (domain enumeration in Wave 2; UI here). Real-name render is UAT-1.
- PROJ-03: scrollable backup library, newest-first, name + timestamp — complete and tested.
- PROJ-04: FileProvider + ShareCompat `content://` share — complete and MIME-tested; share-target reach is UAT-4.

## Deferred / Out of Scope

- Pre-existing `MIDIManager.kt:159` lint error still fails `:app:lintDebug` (not introduced here).
- Restore button enablement (UAT-3) and all device/share-target behaviors (UAT-1/2/3/4) — physical EP-133 required.

## Self-Check: PASSED
- ProjectsScreen.kt, EP133App.kt, MainActivity.kt, MIDIRepository.kt, ProjectsViewModelTest.kt, ShareIntentTest.kt, 04-HUMAN-UAT.md — all present and changed.
- Commits e941324 and 5c97a60 present in git log.
