---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: Phase 05 complete
last_updated: "2026-06-21T04:30:00.000Z"
progress:
  total_phases: 5
  completed_phases: 5
  total_plans: 14
  completed_plans: 14
  percent: 100
---

# Project State

**Project:** EP-133 Sample Tool — Mobile
**Milestone:** M1 — Native Mobile Apps
**Phase:** 5
**Last updated:** 2026-06-21

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-28)

**Core value:** A connected EP-133 user can do everything on their phone that they can do on their desktop — no laptop required.
**Current focus:** Phase 05 — splice-sample-sync

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | MIDI Foundation | Complete (2026-03-28) |
| 2 | Android Device Management | Complete (2026-03-30) |
| 3 | iOS Native UI | Not started |
| 4 | Project Management | Code-complete (Android slice) — 4/4 plans; hardware UAT pending |
| 5 | Sample Import (Android) | Code-complete (Android slice) — 4/4 plans; SAMPLE-01..04 verified; hardware UAT pending |

## Current Position

Phase: 05 — COMPLETE (Android slice; hardware UAT pending)
Plan: 4 of 4 complete
**Active artifacts:** 05-CONTEXT / 05-RESEARCH / 05-VALIDATION / 05-PATTERNS / 4 PLAN.md files / 05-01..04-SUMMARY / 05-HUMAN-UAT / 05-VERIFICATION
**Plans:** 05-01 ✅ (wave 0: RED test scaffold — WavEncoder/Resampler/SampleImport/SampleImportViewModel) → 05-02 ✅ (wave 1: pure WavEncoder @ 46875/s16 + Resampler no-op-at-rate/no-upsample, GREEN) → 05-03 ✅ (wave 2: AudioDecoder MediaCodec + MIDIRepository.putSampleFile additive paged /sounds PUT + SampleImportManager; Phase 4 code intact) → 05-04 ✅ (wave 3: SampleImportScreen + ViewModel + SAF OpenMultipleDocuments + Import nav/MainActivity wiring). Full unit suite GREEN (22 test classes, 0 failures), :app:assembleDebug SUCCESS.
**Hardware checkpoints (need physical EP-133 + USB-host Android):** recorded in 05-HUMAN-UAT.md — UAT-DECODE (MediaCodec decode), UAT-SOUNDS-PUT (new /sounds file addressing: node-ID vs path string, Open Q1/Landmine 4), UAT-PITCH (46875 Hz conversion pitch correctness), UAT-IMPORT-UI (end-to-end import through the UI). All DEFERRED, NOT verified; documented with assumptions + fallbacks.
**Verification:** 05-VERIFICATION.md = passed (4/4 SAMPLE-01..04 verified code-complete), status human_needed for hardware UAT. Independently confirmed: `:app:testDebugUnitTest` green, `:app:assembleDebug` SUCCESS.
**Codex adversarial-review fixes (quick task 260620-tng):** Two correctness findings fixed post-verification. (1) `putSampleFile` now uses path-string framing — `buildFilePutFrame(deviceId, "/sounds/$name", chunk, chunkIndex)` on every chunk so the destination name is actually transmitted (was a name-less `buildFilePutInitFrame(nodeId=0)` that could never create `/sounds/<name>`); mirrors BackupManager's proven /sounds write. (2) `SampleImportManager` now fails closed — emits Error on PUT no-ack (ok=false) instead of Done. Changes additive/local; Phase 4 + SysExProtocol byte-for-byte intact. Full suite still green.
**Execution note:** Wave 2's first worktree attempt forked from a stale pre-Phase-4 base and regressed Phase 4 (MIDIRepository/SysExProtocol deletions); it was discarded and re-run in sequential mode, yielding additive-only changes (MIDIRepository +70, 0 deletions).
**Next step:** run the hardware UAT (UAT-DECODE/SOUNDS-PUT/PITCH/IMPORT-UI) on a physical EP-133, then /gsd:verify-work for Phase 5.

```
[██████████] Phases 1–2, 4, 5 complete (Android); Phase 3 (iOS) not started
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
- [Phase 04-project-management]: ProjectsViewModel co-located in ProjectsScreen.kt (CLAUDE.md rule); MIDIRepository.listProjects() made `open` so the VM test fake returns canned 9-slot enumeration without the real SysEx cycle
- [Phase 04-project-management]: restore disabled by a compile-time RESTORE_ENABLED=false const (Open Q2) — AlertDialog + restoreProject validation fully wired; flip one boolean after UAT-3. Project backup writes to app-specific storage (no SAF launcher, unlike DeviceScreen)

## Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260620-tng | Fix Codex adversarial-review findings in Phase 5 sample import (path-string /sounds PUT addressing + fail-closed on no-ack) | 2026-06-21 | 7a5c22b | [260620-tng-codex-import-fixes](./quick/260620-tng-codex-import-fixes/) |
| 260621-cu5 | Harden Android sample import — ASCII-safe/length-capped sanitizeName (prevents US_ASCII filename corruption) + Resampler input guards | 2026-06-21 | e13cd3c | [260621-cu5-harden-sample-import](./quick/260621-cu5-harden-sample-import/) |
| 260621-czf | Serialize batch sample uploads via uploadMutex — fixes multi-pick imports failing with "transfer already in flight" | 2026-06-21 | 2808491 | [260621-czf-serialize-batch-uploads](./quick/260621-czf-serialize-batch-uploads/) |
| 260621-lg4 | Rework /sounds upload to device's node-ID INIT protocol (ground truth from data/index.js) + AudioDecoder KEY_PCM_ENCODING fix + 20s/rate guards | 2026-06-21 | 9f907e8 | [260621-lg4-sounds-upload-protocol](./quick/260621-lg4-sounds-upload-protocol/) |
| 260621-mju | Remove wrong A0/D0 note-60 pad special-case (hardware capture: A0 = note 37 ch 0, not 60 ch 6) — fixes A0 triggering a group C pad | 2026-06-21 | b4c6eb4 | [260621-mju-fix-pad0-mapping](./quick/260621-mju-fix-pad0-mapping/) |
| 260625-iug | Make repo a proper public OSS gift from Bombest Audio — MIT LICENSE + NOTICE (TE-asset carve-out, garrettjwilke credit), CoC/SECURITY/CHANGELOG, GitHub templates, README credits, package.json fixes, and the Claude Design brief (landing + protocol page + app concept) | 2026-06-25 | 256291b | [260625-iug-oss-gift-setup](./quick/260625-iug-oss-gift-setup/) |

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
