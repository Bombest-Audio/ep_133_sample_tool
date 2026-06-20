---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: "Phase 4 in progress — plan 04-03 (Wave 2: enumerate + ProjectBackupManager) complete"
last_updated: "2026-06-20T17:00:00.000Z"
progress:
  total_phases: 4
  completed_phases: 2
  total_plans: 9
  completed_plans: 8
  percent: 89
---

# Project State

**Project:** EP-133 Sample Tool — Mobile
**Milestone:** M1 — Native Mobile Apps
**Phase:** 4
**Last updated:** 2026-06-19

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-28)

**Core value:** A connected EP-133 user can do everything on their phone that they can do on their desktop — no laptop required.
**Current focus:** Phase 04 — project-management (Android slice; research done, planning not started)

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | MIDI Foundation | Complete (2026-03-28) |
| 2 | Android Device Management | Complete (2026-03-30) |
| 3 | iOS Native UI | Not started |
| 4 | Project Management | In progress (Android slice) — 3/4 plans complete |

## Current Position

Phase: 04 (project-management) — IN PROGRESS, scoped to Android only
**Active artifacts:** 04-CONTEXT / 04-RESEARCH (FEASIBLE) / 04-VALIDATION / 04-PATTERNS / 4 PLAN.md files / 04-01-SUMMARY / 04-02-SUMMARY / 04-03-SUMMARY / 04-HUMAN-UAT
**Plans:** 04-01 ✅ (wave 0: test scaffold + FileProvider — DONE) → 04-02 ✅ (wave 1 GATE: multi-page GET/PUT — DONE) → 04-03 ✅ (wave 2: enumerate + ProjectBackupManager — DONE; resolveNodeId/listProjects/parseFileListEntries + ProjectBackupManager opaque backup/restore, ProjectProtocolTest/BackupLibraryTest GREEN; restore hardware-gated) → 04-04 (wave 3: ProjectsScreen + library + share). Plan-check: PASS.
**Hardware checkpoints (need physical EP-133):** recorded in 04-HUMAN-UAT.md — UAT-1 FILE_LIST nodeId-vs-path (Open Q1), UAT-2 A3 response-body byte-offsets, UAT-3 single-project restore round-trip (Open Q2). All DEFERRED, NOT verified; restore button stays gated until UAT-3 passes. Plans 03/04 are autonomous:false for this reason.
**Next step:** execute 04-04 (Wave 3 ProjectsScreen + backup library + FileProvider share).
**Deferred:** pre-existing `MIDIManager.kt:159` MutableImplicitPendingIntent lint error blocks `:app:lintDebug` — see 04 deferred-items.md (not introduced by 04-01).
**Note:** iOS slice of Phase 4 deferred to a later phase. Out-of-band fixes landed in commit d42ffd5 (backup/sequencer/MIDI bug fixes) — not tracked as a GSD phase.

```
[████████░░] Phases 1–2 complete, Phase 4 — Wave 2 done (3/4 plans)
```

## Decisions Made

- **Android MIDI dispatch:** mainHandler.post{} in MidiReceiver.onSend — mirrors notifyDevicesChanged() pattern
- **SequencerEngine scope:** SupervisorJob prevents child failure from cancelling entire scope
- **PermissionState:** Separate enum (not folded into DeviceState.connected) for clean three-state UI
- **MIDIRepository.currentPermissionState:** Cast via (midiManager as? MIDIManager) — pragmatic Phase 1; clean in Phase 2
- **iOS ObservableObject:** iOS 16 target requires ObservableObject + @Published (not @Observable which needs iOS 17)
- **MIDIDevice/MIDIDeviceList:** Top-level types in MIDIPort.swift (not nested in protocol)
- [Phase 02-android-device-management]: SysEx accumulation uses ByteArrayOutputStream — avoids fixed-size caps for large FILE_GET responses
- [Phase 02-android-device-management]: Scale and channel state live in MIDIRepository as single source of truth (not duplicated in ViewModels)
- [Phase 02-android-device-management]: SAF launchers must be registered before setContent() in MainActivity — Activity lifecycle constraint
- [Phase 02-android-device-management]: MIDI-dependent unit tests @Ignore — android.util.Log not available in JVM tests; validated via instrumented tests
- [Phase 04-project-management]: Wave 0 VM test doubles renamed (ProjectsSpyMIDIPort/ProjectsFakeMIDIRepo) — private top-level decls collide with ChordsViewModelTest across the shared module test source set
- [Phase 04-project-management]: FileProvider paths scoped to backups/ only (no root/external wildcard) per STRIDE threat register T-04-01/T-04-02
- [Phase 04-project-management]: FILE_LIST uses node-ID addressing (buildFileListByNodeFrame) with a one-line path-string fallback — node-list dispatch accumulates across SUCCESS_START on a deferred, resolves on STATUS_OK, separate from the Phase 2 /sounds path-string count
- [Phase 04-project-management]: Project backup is an opaque .tar device blob — download, write, done; no ZIP re-archiving and never inspect the tar (contrast Phase 2's /sounds .pak)
- [Phase 04-project-management]: restoreProject double-guards the destructive PUT — P(NN).tar filename regex + source-must-be-in-backups-dir canonical-path check (T-04-06/T-04-08), on top of the Wave 3 AlertDialog gate

## Notes

**Critical pre-work for Phase 1:**
Two latent bugs must be fixed before any MIDI-driven screen is wired: (1) Android `MidiReceiver.onSend()` fires on a MIDI thread — `MutableStateFlow` mutations from that thread cause non-deterministic crashes; (2) iOS `onMIDIReceived` is not dispatched to main thread — will crash when SwiftUI screens start consuming MIDI events. Both fixes are clearly specified in `.planning/codebase/CONCERNS.md` and `.planning/research/SUMMARY.md` (Pitfalls 1 and 2).

**Critical unknown for Phase 2:**
EP-133 SysEx protocol for backup/restore and device stat queries is not publicly documented. Must be reverse-engineered from `data/index.js` before implementation. First plan of Phase 2 is dedicated to this research. Findings go in `.planning/research/SYSEX_PROTOCOL.md`.

**iOS deployment target decision pending:**
Minimum iOS target is 16; `@Observable` requires iOS 17. A dual-path (`ObservableObject` fallback) adds boilerplate. Evaluate raising minimum to iOS 17 before starting Phase 3 — simplifies all ViewModels significantly.

**MIDIKit vs. raw CoreMIDI decision pending:**
STACK.md recommends MIDIKit 0.11.0 (requires Xcode 16); ARCHITECTURE.md notes existing `MIDIManager.swift` may be sufficient. Audit `MIDIManager.swift` capability at Phase 3 kickoff before adding the SPM dependency. If MIDIKit is adopted, update CLAUDE.md Xcode requirement from 15+ to 16+.

**Android stack upgrade deferred:**
Kotlin 1.9.22 → 2.0.21 upgrade, Hilt DI, Navigation 2.8 typed routes, and targetSdk 35 migration are deferred to after Phase 2 (or to a chore commit before Phase 3). Do not introduce build churn during the highest-risk SysEx implementation phases.

---
*State initialized: 2026-03-28*
