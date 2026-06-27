---
phase: 04-project-management
verified: 2026-06-20T00:00:00Z
status: human_needed
score: 4/4 must-haves verified (code+test); 4 hardware-UAT items deferred
overrides_applied: 0
human_verification:
  - test: "UAT-1 — Projects screen shows 9 real slot names + correct active marker over SysEx"
    expected: "9 slots P00..P08 with device names + active slot marked teal"
    why_human: "FILE_LIST nodeId-vs-path addressing only resolvable against physical EP-133 firmware"
  - test: "UAT-2 — Single-slot backup writes a valid multi-KB .tar; names/sizes not garbled"
    expected: "EP133-P{NN}-{ts}.tar is the full archive; slot summaries sane"
    why_human: "INIT/DATA response byte offsets (A3) need physical device confirmation"
  - test: "UAT-3 — backup→restore round-trip; then flip RESTORE_ENABLED=true"
    expected: "Device accepts the PUT and the slot matches the backed-up project"
    why_human: "Destructive FILE_PUT framing only verifiable with hardware; button stays gated until pass"
  - test: "UAT-4 — Share reaches Files/Drive/AirDrop with content:// URI, no FileUriExposedException"
    expected: "Share sheet opens; backup delivered to target via FileProvider URI"
    why_human: "Needs real Context + registered FileProvider + share targets"
---

# Phase 4: Project Management (Android slice) Verification Report

**Phase Goal:** Android users can browse the EP-133's 9 project slots, back up a single project to phone storage, manage a backup library, and share backup files via the system share sheet.
**Verified:** 2026-06-20
**Status:** human_needed (all code+test landed; 4 hardware-UAT items deferred by user decision)
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | User can open a Projects screen and see all 9 EP-133 slots with names + content summary (PROJ-01) | ✓ VERIFIED (real-name render → UAT-1) | `MIDIRepository.listProjects/resolveNodeId/parseFileListEntries` (real walk + parse, MIDIRepository.kt:613-662), `ProjectsScreen.SlotCard` renders name + teal active marker + `Groups A–D · {KB}` summary; `ProjectsViewModelTest` asserts 9 slots, exactly one active; `ProjectProtocolTest` (12 tests, 63 assertions) covers the entry parser. Nav tab registered (EP133App.kt:65,160), VM wired (MainActivity.kt:74-75,92). |
| 2 | User can back up a single project (not full device) to a named file (PROJ-02) | ✓ VERIFIED | `ProjectBackupManager.backupProject` downloads via `MIDIRepository.getProjectArchive` (real two-phase INIT/DATA paged transfer, MIDIRepository.kt:475-516) and writes `EP133-P{NN}-{ts}.tar` to app storage as an opaque blob. `MultiChunkGetTest` (6 green) verifies paged assembly, page-mismatch throw, oversized abort, CRC32 round-trip. Restore implemented + validated, correctly gated. |
| 3 | User can open a backup library showing saved backups newest-first with names + timestamps (PROJ-03) | ✓ VERIFIED | `ProjectBackupManager.enumerateBackups` (pure, `.tar` sorted by mtime desc) + `ProjectsScreen.BackupRow` (name + `yyyy-MM-dd HH:mm`). `BackupLibraryTest` (2 green) asserts real enumeration against a temp dir. No hardware needed — fully testable, fully tested. |
| 4 | User can share any backup file via the Android share intent (PROJ-04) | ✓ VERIFIED (share-target reach → UAT-4) | `ProjectsScreen.shareBackup` uses `FileProvider.getUriForFile(...".fileprovider"...)` + `ShareCompat.IntentBuilder.setType(SHARE_MIME).setStream(uri)` — no `file://` anywhere. FileProvider registered in manifest (exported=false, grantUriPermissions=true, authority `${applicationId}.fileprovider`), `file_paths.xml` scoped to `backups/`. `ShareIntentTest` asserts the real `SHARE_MIME` constant. |

**Score:** 4/4 truths verified at code+test level. Three carry a genuinely device-only runtime tail (UAT-1/2/4); restore is a fourth gated item (UAT-3).

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `SysExProtocol.kt` | Paged INIT/DATA builders + FILE_LIST entry parser | ✓ VERIFIED | `buildFileGet/PutInit/DataFrame`, `parseGetInit/DataResponse`, `parseFileListEntries`, `buildFileListByNodeFrame`, `assembleGetPages`, `classifyTransferStatus` — all real, bounds-checked. |
| `MIDIRepository.kt` | listProjects/resolveNodeId + paged get/put + dispatch wiring | ✓ VERIFIED | Real node walk, paged transfer, and full receive-path dispatch (`dispatchPagedGetResponse/PutResponse`, node-list accumulation, metadata). `transferInFlight` guard. |
| `ProjectBackupManager.kt` | single-project backup/restore + library | ✓ VERIFIED | Opaque-blob backup, traversal-defended restore (regex + canonical-path constraint), pure `enumerateBackups`. |
| `ProjectsScreen.kt` | 9-slot browser + library + share + VM | ✓ VERIFIED | Full Compose UI + co-located ViewModel, StateFlow encapsulation, restore behind AlertDialog + `RESTORE_ENABLED` gate. |
| `EP133App.kt` / `MainActivity.kt` | Nav tab + VM wiring | ✓ VERIFIED | `NavRoute.PROJECTS` + `composable(...)`; VM constructed and threaded in. |
| `AndroidManifest.xml` / `file_paths.xml` | FileProvider scoped to backups/ | ✓ VERIFIED | Provider block present, scoped, not exported. |

### Key Link Verification

| From | To | Via | Status |
|------|----|----|--------|
| ProjectsScreen | ProjectsViewModel | collectAsState on slots/backups/progress | ✓ WIRED |
| ProjectsViewModel | ProjectBackupManager | backupProject/restoreProject/listBackups collect | ✓ WIRED |
| ProjectBackupManager | MIDIRepository | getProjectArchive/putProjectArchive/listProjects | ✓ WIRED |
| MIDIRepository.dispatch | paged GET/PUT/node-list deferreds | onMidiReceived → dispatchPaged* | ✓ WIRED |
| shareBackup | FileProvider/manifest | getUriForFile(".fileprovider") | ✓ WIRED |
| EP133App nav | ProjectsScreen | NavRoute.PROJECTS composable | ✓ WIRED |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Unit suite | `:app:testDebugUnitTest` | BUILD SUCCESSFUL — 18 suites, 0 failures, 0 errors | ✓ PASS |
| APK assembles | `:app:assembleDebug` | BUILD SUCCESSFUL | ✓ PASS |
| Lint | `:app:lintDebug` | BUILD SUCCESSFUL (pre-existing MIDIManager lint fixed in commit 5b8f81c) | ✓ PASS |
| Phase-4 test coverage | per-class results | ProjectProtocol 12, MultiChunkGet 6, SysExDispatch 5(1 ign), ProjectsViewModel 2, BackupLibrary 2, ShareIntent 2(1 ign) | ✓ PASS |

### Requirements Coverage

| Requirement | Description | Status | Evidence |
|-------------|-------------|--------|----------|
| PROJ-01 | Browse 9 slots with names + overview | ✓ SATISFIED (real-name render = UAT-1) | listProjects + ProjectsScreen + tests |
| PROJ-02 | Backup single project to named file | ✓ SATISFIED | backupProject + getProjectArchive + MultiChunkGetTest |
| PROJ-03 | Library list with timestamps | ✓ SATISFIED | enumerateBackups + BackupRow + BackupLibraryTest |
| PROJ-04 | Share via Android share intent | ✓ SATISFIED (target reach = UAT-4) | shareBackup + FileProvider + ShareIntentTest |

### Anti-Patterns Found

| File | Pattern | Severity | Impact |
|------|---------|----------|--------|
| (none) | No TBD/FIXME/XXX in any phase-4 source file | — | Clean |
| ShareIntentTest / SysExDispatchTest | `@Ignore` on 2 tests | ℹ️ Info | Correctly limited to genuine device-timing + Context/FileProvider needs; justification strings present. Not hand-waved logic. |

### Scope Check

- **iOS/Swift leakage:** NONE. `git diff HEAD~6..HEAD` touches no iOS/Swift files. Android-only as scoped in 04-CONTEXT.md.
- **False hardware claims:** NONE. All four SUMMARYs explicitly state no device was attached and defer device-facing behavior to 04-HUMAN-UAT.md. No SUMMARY claims a hardware pass.

### Deferred-Set Audit

The deferred set is correctly limited to genuinely device-only behaviors:
- **UAT-1** (FILE_LIST nodeId-vs-path addressing) — depends on undocumented firmware behavior; one-line fallback documented. Legitimate.
- **UAT-2** (response byte offsets / A3) — exact post-header offset only confirmable against real device responses. Legitimate.
- **UAT-3** (restore round-trip) — destructive PUT; button gated behind `RESTORE_ENABLED=false`. Legitimate and conservatively gated.
- **UAT-4** (share-target reach) — needs real Context + FileProvider + share targets. Legitimate.

No testable logic was hand-waved into UAT. Everything JVM-testable (frame bytes, parsers, page assembly, library enumeration, VM slot mapping, MIME) is implemented and has a passing test.

### Gaps Summary

No blocking gaps. All four requirements have real, wired, substantive code with passing automated tests for everything not strictly hardware-dependent. The only outstanding work is the four hardware-UAT items, which require a physical EP-133 and were deferred by explicit user decision — not failures. Restore stays correctly gated behind `RESTORE_ENABLED=false` until UAT-3 passes (single boolean flip documented). Status is `human_needed` because the phase goal's runtime tail (real device enumeration, archive validity, share delivery) cannot be confirmed without hardware.

---

_Verified: 2026-06-20_
_Verifier: Claude (gsd-verifier)_
