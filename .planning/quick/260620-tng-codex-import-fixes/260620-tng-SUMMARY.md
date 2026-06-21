---
quick_id: 260620-tng
slug: codex-import-fixes
title: Fix Codex adversarial-review findings in Phase 5 sample import
completed: 2026-06-21
duration_minutes: 25
tasks_completed: 3
files_modified: 5
commits: 3
---

# Quick Task 260620-tng: Fix Codex adversarial-review findings in Phase 5 sample import

## One-liner

Path-string `/sounds/$name` on every PUT chunk frame + fail-closed no-ack Error in SampleImportManager.

## Tasks

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | putSampleFile transmits destination name via buildFilePutFrame | 6060d5c | MIDIRepository.kt |
| 2 | Import fails closed on no-ack (putSampleFile false → Error) | f6e4d16 | SampleImportManager.kt |
| 3 | Update tests + UAT note | 7a5c22b | SampleImportViewModelTest.kt, SampleImportTest.kt, 05-HUMAN-UAT.md |

## What changed

### Task 1 — MIDIRepository.putSampleFile

Replaced the `buildFilePutInitFrame(nodeId=0) + buildFilePutDataFrame` loop with
`buildFilePutFrame(path, chunk, chunkIndex, requestId)` per chunk. Every frame now
carries `/sounds/$name` as its path string (the same framing BackupManager.restore proved
for `/sounds` writes). Large WAVs still page — one `buildFilePutFrame` call per
`MAX_PAGE_BYTES` slice. Made `putSampleFile` `open` to enable test-fake overrides.
Empty-bytes edge handled: single zero-length path frame.

Phase 4 code (`getProjectArchive`, `putProjectArchive`, `resolveNodeId`, `listProjects`,
`buildFilePutInitFrame`, `buildFilePutDataFrame`) is byte-for-byte unchanged. Diff:
+39 / -41, all changes confined to the `putSampleFile` method and doc comment.

### Task 2 — SampleImportManager

Both `importSample` and `importSampleBytes` now emit:
- `Error("Upload not confirmed by EP-133 (no STATUS_OK) — reconnect and retry $name")` when `ok == false`
- `Progress(1,1) + Done(name)` only when `ok == true`

No code path emits `Done` after a `false` return.

### Task 3 — Tests + UAT note

`SampleImportViewModelTest.SampleImportFakeRepo` now overrides `putSampleFile` returning
`_state.value.connected`. The `importStagedBytes_whenConnected_advancesToDone` test reaches
`Done` via a real success path; the disconnected test stays on `Error` as expected.

`SampleImportTest` rewritten for path-string frame layout:
- All 3 frames (ceil(10000/4096)) carry `TE_SYSEX_FILE + TE_SYSEX_FILE_PUT + pathBytes`
- Reassembly drops header = 2 + pathLength(17) + 2 = 21 bytes per frame
- Paging assertion unchanged (>1 frame for >4096-byte payload)
- Disconnected test unchanged

`05-HUMAN-UAT.md` UAT-SOUNDS-PUT note appended: hardware UAT now only needs to confirm
multi-chunk path-string PUT creates the file and triggers STATUS_OK.

## Verification

- `./gradlew :app:testDebugUnitTest` — BUILD SUCCESSFUL, full suite green
- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL
- `git diff --numstat HEAD~3 HEAD -- MIDIRepository.kt` — changes additive/local to putSampleFile only

## Deviations from Plan

None — plan executed exactly as written. The only unplanned additive change was making
`putSampleFile` `open` (required for the test-fake override the plan specified in Task 3;
plan did not call this out explicitly but it is a necessary precondition).

## Self-Check

- [x] MIDIRepository.kt modified — exists
- [x] SampleImportManager.kt modified — exists
- [x] SampleImportViewModelTest.kt modified — exists
- [x] SampleImportTest.kt modified — exists
- [x] 05-HUMAN-UAT.md modified — exists
- [x] Commit 6060d5c exists
- [x] Commit f6e4d16 exists
- [x] Commit 7a5c22b exists

## Self-Check: PASSED
