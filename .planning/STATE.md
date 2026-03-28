# Project State

**Project:** EP-133 Sample Tool — Mobile
**Milestone:** M1 — Native Mobile Apps
**Phase:** Not started
**Last updated:** 2026-03-28

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-28)

**Core value:** A connected EP-133 user can do everything on their phone that they can do on their desktop — no laptop required.
**Current focus:** Not started — run `/gsd:plan-phase 1` to begin

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | MIDI Foundation | Not started |
| 2 | Android Device Management | Not started |
| 3 | iOS Native UI | Not started |
| 4 | Project Management | Not started |

## Current Position

**Active phase:** None
**Active plan:** None
**Phase progress:** 0/4 phases complete

```
[          ] 0%
```

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
