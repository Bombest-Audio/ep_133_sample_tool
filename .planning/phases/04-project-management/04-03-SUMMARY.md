---
phase: 04-project-management
plan: 03
subsystem: android-midi-projects
tags: [sysex, file-list, node-resolution, project-backup, enumeration]
requires: [04-project-management-02]
provides:
  - "MIDIRepository.resolveNodeId + listProjects 9-slot enumeration with active marker"
  - "SysExProtocol.parseFileListEntries pure directory-entry decoder"
  - "SysExProtocol.buildFileListByNodeFrame node-ID FILE_LIST request"
  - "ProjectBackupManager: opaque .tar backup/restore + library enumeration"
  - "ProjectBackupManager.enumerateBackups pure Context-free library helper"
affects:
  - "Wave 3 ProjectsScreen + ViewModel (consumes listProjects / ProjectSlot)"
  - "Wave 3 backup-library list + share (consumes enumerateBackups / BackupItem)"
  - "Wave 3 restore-confirm AlertDialog (gates ProjectBackupManager.restoreProject)"
tech-stack:
  added: []
  patterns:
    - "Node-ID FILE_LIST with one-line path-string fallback (Open Q1)"
    - "Pure parser extraction (parseFileListEntries / enumerateBackups) for hardware-free tests"
    - "Opaque device-archive backup — no ZIP re-archiving, never inspect the .tar"
key-files:
  created:
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/ProjectBackupManager.kt
    - .planning/phases/04-project-management/04-HUMAN-UAT.md
  modified:
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/BackupLibraryTest.kt
decisions:
  - "FILE_LIST node body accumulates across SUCCESS_START on a CompletableDeferred keyed by pendingNodeListDeferred; resolves on STATUS_OK — separate from the Phase 2 path-string /sounds count path"
  - "parseFileListEntries returns sizeBytes as Long (uint32 BE exceeds Int range) and stops on a truncated trailing entry rather than overrunning (T-04-07)"
  - "enumerateBackups extracted to a Context-free companion helper so BackupLibraryTest runs as a pure JVM test against a temp dir"
  - "restoreProject constrains the source file to the app backups dir (canonicalPath match) on top of the P(NN).tar regex — defends T-04-08 traversal"
metrics:
  duration: "~30m"
  completed: "2026-06-20"
  tasks: 2
  files: 6
---

# Phase 4 Plan 03: Slot enumeration + ProjectBackupManager Summary

Built the project-management domain layer on top of the Wave 1 paged transfer: enumerate the 9 EP-133 project slots (PROJ-01) and back up / restore a single project as an opaque `.tar` device blob (PROJ-02). Two hardware-only behaviors (FILE_LIST addressing, restore round-trip) ship against documented assumptions with explicit markers and are deferred to the phase-gate UAT — no physical device was attached.

## What was built

**Task 1 — resolveNodeId + listProjects enumeration (commit 8635c6b)**
- `SysExProtocol.parseFileListEntries(body)` — pure decoder for concatenated FILE_LIST entries (nodeId u16 BE, flags, fileSize u32 BE as `Long`, null-terminated ASCII name). Bounds-checks every field, stops on a truncated trailing entry (T-04-07), tolerates a final entry with no NUL terminator. Plus `FileEntry` and `TE_SYSEX_FILE_FILE_TYPE_FILE/DIR` flag constants.
- `SysExProtocol.buildFileListByNodeFrame(deviceId, nodeId, page, requestId)` — node-ID FILE_LIST request (`[FILE, LIST, page u16, nodeId u16]`), the device's real addressing model.
- `MIDIRepository.ProjectSlot(nodeId, name, sizeBytes, isActive)`; `parseFileListEntries` delegating accessor.
- `MIDIRepository.resolveNodeId(path)` — walks segments from root (nodeId 0), matching child names; `listProjects()` resolves `/projects`, reads its metadata `active` pointer, lists that node, maps entries to `ProjectSlot` with the active marker. Node-list dispatch accumulates the unpacked body across `STATUS_SPECIFIC_SUCCESS_START` and resolves on `STATUS_OK`, kept distinct from the Phase 2 path-string `/sounds` count path. `statsQueryInFlight` guards overlapping queries; `CancellationException` rethrown; `EP133APP` logging; no mutable flow exposed.
- `// HARDWARE-VERIFY (Open Q1)` markers with a one-line path-string fallback switch.
- `ProjectProtocolTest`: multi-entry parse (names/flags/sizes incl. a uint32 size exceeding Int range), truncated-entry stop, no-NUL final entry.

**Task 2 — ProjectBackupManager (commit 5399cec)**
- `ProjectBackupProgress` sealed class (Progress/Done(file)/Error) mirroring `BackupProgress`; `BackupItem(file, name, timestamp)`.
- `backupProject(slot, context)` — guards no-device, downloads via `midi.getProjectArchive` (opaque blob, **no ZipOutputStream**), writes to `getExternalFilesDir("backups")` with a `filesDir/backups` fallback (Pitfall 6) under `Dispatchers.IO`, emits Progress → Done(file).
- `restoreProject(file, context)` — validates `P(\d{2})\.tar` filename and slot range before any PUT (T-04-06), constrains the source to the backups dir via canonical-path match (T-04-08), reads under `Dispatchers.IO`, resolves the target slot node via `listProjects`, uploads via `midi.putProjectArchive`. `// HARDWARE-GATE (Open Q2)` at the restore path — destructive PUT requires the Wave 3 AlertDialog confirmation.
- `suggestedProjectFilename(slot)` → `EP133-P{NN}-{ts}.tar` reusing BackupManager's date format.
- `enumerateBackups(dir)` pure companion helper (`.tar` newest-first) + `listBackups(context)` delegating to it.
- `BackupLibraryTest` rewritten to assert against `enumerateBackups` (newest-first, timestamp, file identity, empty-dir).

## Verification

- `--tests "*.ProjectProtocolTest"` — PASS (FILE_LIST multi-entry parse + Wave 1 frame/parse coverage)
- `--tests "*.BackupLibraryTest"` — PASS (real enumerateBackups helper)
- Full `:app:testDebugUnitTest` — PASS (no regression to Wave 1 MultiChunkGet/SysExDispatch or Phase 2 suites)
- `:app:lintDebug` — fails ONLY on the pre-existing `MIDIManager.kt:159` MutableImplicitPendingIntent error (out of scope, documented in `deferred-items.md` / 04-02-SUMMARY). No new lint errors introduced.

## Deviations from Plan

None affecting scope. Two plan-permitted extractions for hardware-free testing:
- `parseFileListEntries` extracted into `SysExProtocol` (plan asked for it pure/testable).
- `enumerateBackups` extracted to a Context-free companion helper (plan: "pure enough to test against a temp dir") so `BackupLibraryTest` runs as a pure JVM test.

One Rule-2 hardening beyond the literal plan: `restoreProject` additionally constrains the source file to the app backups dir (canonical-path match) on top of the filename regex — directly mitigates threat T-04-08 (path traversal). The plan's `context` parameter was otherwise unused.

## Open Hardware-Verification Items (deferred to phase-gate UAT)

Recorded in `.planning/phases/04-project-management/04-HUMAN-UAT.md` — NOT verified, NOT approved:
1. **UAT-1 (Open Q1 / A2):** FILE_LIST nodeId-walk vs path string for `/projects`. One-line fallback documented.
2. **UAT-2 (A3):** INIT/DATA/LIST response body byte offsets after the header strip. Adjust the leading-byte skip + re-run ProjectProtocolTest if garbled.
3. **UAT-3 (Open Q2 / A6):** single-project restore round-trip; restore button stays gated until a clean backup→restore pass.

## Deferred / Out of Scope

- Pre-existing `MIDIManager.kt:159` lint error still fails `:app:lintDebug` (not introduced here; see `deferred-items.md`). Unit tests unaffected.
- `ProjectsViewModelTest` / `ProjectsScreen` / FileProvider share remain Wave 3 (04-project-management-04).

## Requirements

- PROJ-01: slot enumeration domain logic complete (resolveNodeId + listProjects + ProjectSlot, parse-tested). UI in Wave 3.
- PROJ-02: opaque-archive backup path wired and tested; restore implemented + validated but hardware-gated.

## Self-Check: PASSED
- ProjectBackupManager.kt, MIDIRepository.kt, SysExProtocol.kt, ProjectProtocolTest.kt, BackupLibraryTest.kt, 04-HUMAN-UAT.md — all present and changed.
- Commits 8635c6b and 5399cec present in git log.
