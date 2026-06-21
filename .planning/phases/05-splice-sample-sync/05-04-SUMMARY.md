---
phase: 05-splice-sample-sync
plan: "04"
subsystem: android-audio-import-ui
tags: [sample-import, compose, saf, nav, viewmodel, wave-3, android, checkpoint]
dependency_graph:
  requires: [05-splice-sample-sync-01, 05-splice-sample-sync-03]
  provides: [SAMPLE-01, SAMPLE-04]
  affects: []
tech_stack:
  added: []
  patterns:
    - SampleImportScreen Composable with Scaffold + SnackbarHost + LaunchedEffect (mirrors DeviceScreen)
    - LazyColumn of StagedSampleRow with LinearProgressIndicator (indeterminate/determinate) + state glyphs
    - SAF OpenMultipleDocuments launcher registered before setContent in MainActivity (Activity lifecycle)
    - SampleImportViewModel.onFilesPicked: seeded staged rows + importSample() per URI under IO grant
    - Nav IMPORT entry in NavRoute enum + composable(NavRoute.IMPORT.route) in EP133App NavHost
key_files:
  created: []
  modified:
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/import/SampleImportScreen.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt
decisions:
  - "IMPORT NavRoute added as the 7th bottom-nav entry (FileUpload icon from material-icons-extended)"
  - "onFilesPicked derives display names from URI lastPathSegment to avoid a ContentResolver.query() round-trip (name only needs to display in the staged list; manager.sanitizeName() does the authoritative sanitization before any device write)"
  - "StagedSample gained progress: Float = 0f field (non-breaking: test constructs no StagedSample directly)"
  - "StagedSampleRow uses indeterminate LinearProgressIndicator for CONVERTING and LOADING (duration unknown), determinate for DONE (1f) and ERROR (sample.progress at failure point)"
  - "UAT-IMPORT-UI entry was seeded at Wave 0 planning time (present in 05-HUMAN-UAT.md); no new entry needed — existing entry correctly describes the deferred end-to-end flow"
metrics:
  duration: "~20 minutes"
  completed: "2026-06-21"
  tasks_completed: 2
  tasks_total: 3
  files_changed: 3
---

# Phase 05 Plan 04: Import UI + Nav Wiring (Wave 3) Summary

**One-liner:** SampleImportScreen composable + co-located ViewModel SAF wiring; Import bottom-nav entry in EP133App; OpenMultipleDocuments launcher registered before setContent in MainActivity — full test + build + lint green.

## Tasks Completed

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | SampleImportScreen composable + VM additions (triggerPick, onFilesPicked, dismissSnackbar) | adeee14 | SampleImportScreen.kt (106 → 443 lines) |
| 2 | Import nav entry + MainActivity SAF launcher (OpenMultipleDocuments before setContent) | f760181 | EP133App.kt, MainActivity.kt |
| 3 | Checkpoint:human-verify — Import UI review + UAT-IMPORT-UI confirmation | — | UAT entry already present in 05-HUMAN-UAT.md |

## What Was Built

### SampleImportScreen.kt (Wave 3 expansion)

Expanded from the 106-line Wave 2 skeleton to 443 lines. Additive — no skeleton behavior removed.

**StagedSample** gained `progress: Float = 0f` (non-breaking; tests pass unchanged).

**SampleImportViewModel** additions:
- `var onRequestPick: (() -> Unit)? = null` — SAF callback set by MainActivity.
- `fun triggerPick()` — delegates to the callback.
- `fun dismissSnackbar()` — clears `_snackbarMessage`.
- `fun onFilesPicked(uris, context)`: derives display names from `uri.lastPathSegment`; seeds one `StagedSample(Pending)` per URI; launches one coroutine per file that sets CONVERTING then collects `importSample()` events (Progress → Loading+pct, Done → Done, Error → Error+snackbar). Reads happen inside the picker-callback grant (Landmine 7). `CancellationException` rethrown; `Log.e` on other failures.

**SampleImportScreen composable**:
- `Scaffold(snackbarHost=...)` with `LaunchedEffect(snackbarMessage)` + `dismissSnackbar()` (mirrors DeviceScreen).
- Header card with `FileUpload` icon, descriptive text, and "Pick Files" button calling `triggerPick()`.
- `LazyColumn` of `StagedSampleRow` items.

**StagedSampleRow**:
- Row: state glyph (`CheckCircle`/`Error`/empty Box) + filename (`maxLines=1, overflow=Ellipsis`) + state label.
- `LinearProgressIndicator`: indeterminate for CONVERTING/LOADING; `progress = { 1f }` (Teal) for DONE; `progress = { sample.progress }` (error color) for ERROR.
- Error message text beneath the bar when `state == Error`.

### EP133App.kt

- `IMPORT("import", "IMPORT", Icons.Default.FileUpload)` added to `NavRoute` enum.
- `sampleImportViewModel: SampleImportViewModel` parameter added to `EP133App()`.
- `composable(NavRoute.IMPORT.route) { SampleImportScreen(sampleImportViewModel) }` registered in NavHost.

### MainActivity.kt

- `SampleImportManager(midiRepo)` + `SampleImportViewModel(midiRepo, sampleImportManager)` instantiated.
- `importLauncher = registerForActivityResult(OpenMultipleDocuments()) { uris -> sampleImportViewModel.onFilesPicked(uris, this) }` registered **before `setContent`** (Activity lifecycle constraint per STATE.md decision).
- `sampleImportViewModel.onRequestPick = { importLauncher.launch(arrayOf("audio/*")) }`.
- `sampleImportViewModel` passed to `EP133App(...)`.

## Test Results

All 4 `SampleImportViewModelTest` tests GREEN (unchanged from Wave 2):
- `importStagedBytes_mapsToStagedList_pendingInitially`
- `importStagedBytes_whenConnected_advancesToDone`
- `importStagedBytes_whenDisconnected_advancesToErrorWithMessage`
- `importStagedBytes_multipleFiles_producesMultipleEntries`

Wave 1 (WavEncoderTest A/B/C, ResamplerTest D/E/F) + Wave 2 (SampleImportTest x3) — still GREEN.

`:app:assembleDebug` — BUILD SUCCESSFUL  
`:app:lintDebug` — BUILD SUCCESSFUL (no new lint issues)  
No test files modified (verified via `git status --short`)

## Deviations from Plan

### Auto-adjusted — UAT-IMPORT-UI already present in 05-HUMAN-UAT.md

**Found during:** Task 3 execution

**Context:** The plan's checkpoint says to "Confirm 05-HUMAN-UAT.md carries a UAT-IMPORT-UI entry." The entry was seeded at Wave 0 planning time (visible in the file from Wave 2). It fully describes the live end-to-end flow (pick → per-file progress → DONE/ERROR, sample lands in /sounds) with all three verification steps. No new entry needed.

**Rule:** Rule 3 (non-issue — pre-existing artifact satisfies the criterion).

## Hardware-Deferred UAT

| UAT ID | Status | Description |
|--------|--------|-------------|
| UAT-IMPORT-UI | ☐ not verified | End-to-end: Import tab → pick → per-file progress → DONE (sample on /sounds + pad) or ERROR with message. Needs physical EP-133 + USB-host Android device. |
| UAT-DECODE | ☐ not verified | Real MediaCodec decode of WAV/MP3 via AudioDecoder (from Wave 2). |
| UAT-SOUNDS-PUT | ☐ not verified | Paged /sounds PUT addressing (nodeId=0 placeholder vs path-string fallback, from Wave 2). |
| UAT-PITCH | ☐ not verified | 46875 Hz playback pitch/duration correctness (from Wave 2). |

## Known Stubs

- `MIDIRepository.putSampleFile` uses `nodeId=0` as the `/sounds` parent placeholder — hardware-dependent; UAT-SOUNDS-PUT tracks the verification and fallback.
- End-to-end import (picker → decode → upload → pad playback) is hardware-only; UAT-IMPORT-UI is the gate for SAMPLE-01 + SAMPLE-04 live verification.

## Threat Surface Scan

No new network endpoints, auth paths, file access patterns, or schema changes introduced in this plan. The only new trust boundary interaction is the SAF `OpenMultipleDocuments` picker — OS-mediated, URI-only, no new permissions. Byte reads happen inside `onFilesPicked` under `Dispatchers.IO` within the grant (T-05-04-01 mitigated as specified). All T-05-04-* threat IDs from the plan's `<threat_model>` are mitigated:

| Threat ID | Disposition | Status |
|-----------|-------------|--------|
| T-05-04-01 | mitigate | onFilesPicked reads under IO inside the picker grant; no URI persisted |
| T-05-04-02 | mitigate | Per-file sequential coroutines; Wave 2 storage pre-flight guards device writes |
| T-05-04-03 | mitigate | Errors mapped to per-row messages + snackbar; Log.e keeps detail in logcat |
| T-05-04-04 | mitigate | Only SAF audio/* picker — no Splice browser, no network surface |
| T-05-04-SC | accept | Zero new external packages |

## Self-Check: PASSED

Files modified:
- `SampleImportScreen.kt` — exists (443 lines, min_lines=120 criterion met)
- `EP133App.kt` — contains "IMPORT" ✓
- `MainActivity.kt` — contains "OpenMultipleDocuments" ✓

Commits exist:
- `adeee14` (Task 1: SampleImportScreen expansion) — verified
- `f760181` (Task 2: Nav + MainActivity wiring) — verified

Tests: `testDebugUnitTest` — BUILD SUCCESSFUL, 0 failures  
assembleDebug: BUILD SUCCESSFUL  
lintDebug: BUILD SUCCESSFUL  
No test files modified: confirmed via `git status --short`
