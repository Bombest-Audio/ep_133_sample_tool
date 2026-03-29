---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: Ready to plan
last_updated: "2026-03-29T04:25:43.907Z"
progress:
  total_phases: 4
  completed_phases: 1
  total_plans: 1
  completed_plans: 3
---

# Project State

**Project:** EP-133 Sample Tool — Mobile
**Milestone:** M1 — Native Mobile Apps
**Phase:** 2
**Last updated:** 2026-03-28

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-28)

**Core value:** A connected EP-133 user can do everything on their phone that they can do on their desktop — no laptop required.
**Current focus:** Phase 01 — midi-foundation

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | MIDI Foundation | Executed — checkpoint:human-verify pending |
| 2 | Android Device Management | Not started |
| 3 | iOS Native UI | Not started |
| 4 | Project Management | Not started |

## Current Position

Phase: 01 (midi-foundation) — AWAITING VERIFICATION (checkpoint:human-verify)
Plan: Not started
**Active phase:** Phase 1: MIDI Foundation
**Active plan:** 03 complete — Task 3-06 is checkpoint:human-verify (blocking)
**Phase progress:** Phase 1 tasks done, verification pending

```
[██████████] 100%
```

## Decisions Made

- **Android MIDI dispatch:** mainHandler.post{} in MidiReceiver.onSend — mirrors notifyDevicesChanged() pattern
- **SequencerEngine scope:** SupervisorJob prevents child failure from cancelling entire scope
- **PermissionState:** Separate enum (not folded into DeviceState.connected) for clean three-state UI
- **MIDIRepository.currentPermissionState:** Cast via (midiManager as? MIDIManager) — pragmatic Phase 1; clean in Phase 2
- **iOS ObservableObject:** iOS 16 target requires ObservableObject + @Published (not @Observable which needs iOS 17)
- **MIDIDevice/MIDIDeviceList:** Top-level types in MIDIPort.swift (not nested in protocol)

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
