# EP-133 Sample Tool — Mobile

## What This Is

A native iOS and Android app for managing Teenage Engineering EP-133 K.O. II hardware from your phone. Connects via USB (OTG/Lightning) and provides full feature parity with the desktop Electron app — sample management, pattern editing, project library, and device configuration — in a purpose-built mobile UI.

Built for EP-133 users who want to manage their device on the go, and designed to ship publicly to the broader Teenage Engineering community.

## Core Value

A connected EP-133 user can do everything on their phone that they can do on their desktop — no laptop required.

## Requirements

### Validated

- ✓ Electron desktop app with full sample management, MIDI-Sysex, audio processing — existing
- ✓ iOS WKWebView wrapper app with CoreMIDI USB MIDI bridge — existing
- ✓ Android WebView wrapper app with android.media.midi USB MIDI bridge — existing
- ✓ Shared MIDI polyfill routing Web MIDI API through native bridges (iOS, Android, JUCE) — existing
- ✓ Android native Compose UI: Pads, Beats, Sounds, Chords, Device screens — mostly working
- ✓ Android domain layer: MIDIRepository, SequencerEngine, ChordPlayer — existing
- ✓ Android MIDI layer: MIDIManager, MIDIPort abstraction — existing
- ✓ Shared EP-133 protocol data: pad mappings, 999 factory sounds, musical scales — existing

### Active

- [ ] Android: Full pattern management — browse, edit, create, and organize patterns across banks
- [ ] Android: Project management — save/load to device, browse library, backup/restore, share
- [ ] Android: Device settings configuration from the app
- [ ] Android: Complete native Compose UI feature parity with desktop app
- [ ] iOS: Native SwiftUI screens replacing WKWebView wrapper (pads, beats, sounds, projects, device)
- [ ] iOS: Full pattern management parity with Android
- [ ] iOS: Project management (save/load, browse, backup, share)
- [ ] iOS: Device settings configuration
- ✓ Both platforms: Robust USB connection handling (connect/disconnect, permissions, error states) — validated Phase 1

### Out of Scope

- BLE MIDI — USB-only for v1; wireless adds complexity and latency not worth for this milestone
- Cloud sync — local device management only; no server infrastructure in scope
- Audio recording on phone — the EP-133 handles audio; the app handles control and file management
- JUCE plugin mobile support — plugin is desktop-only by nature

## Context

The codebase is a monorepo with four platform targets (Electron, JUCE plugin, iOS, Android), all wrapping a shared compiled web app (`data/index.js`, ~1.75MB React bundle). The source for the web app is not in this repo — only the compiled output ships.

The Android app has diverged from the pure-wrapper model: it has a native Compose UI layer that talks directly to the MIDI domain layer, bypassing the WebView for primary screens. This is the architectural direction for mobile: native UI, direct MIDI, shared EP-133 protocol data from `shared/`.

iOS currently uses WKWebView + CoreMIDI. The iOS native UI build is the larger lift of this milestone.

Key technical context:
- EP-133 communicates via MIDI and SysEx over USB
- Android MIDI: `android.media.midi` (API 29+), min SDK is already set to 29
- iOS MIDI: CoreMIDI with USB MIDI session (`MIDINetworkSession` not needed for wired)
- Shared JSON protocol data in `shared/` (pads, sounds, scales) — already used by Android native UI

## Constraints

- **Compatibility (Android)**: Min API 29 (Android 10) — required for `android.media.midi` USB MIDI
- **Compatibility (iOS)**: iOS 16+ deployment target — set in Xcode project
- **Tech Stack**: Kotlin + Jetpack Compose for Android; Swift + SwiftUI for iOS — no cross-platform framework
- **Architecture**: Native UI talks directly to MIDI layer; WebView is fallback only, not primary UX
- **Web app source**: Compiled `data/index.js` only — cannot modify web app source as part of this milestone

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Native UI over WebView-first on mobile | WebView delivers desktop UX on mobile form factor — touch targets, navigation, and mobile conventions require native | — Pending |
| USB-only connection (no BLE) | BLE MIDI adds latency and pairing complexity; USB is reliable and already partially implemented | — Pending |
| Shared EP-133 protocol data from `shared/` JSON | Single source of truth for pad mappings and sounds across platforms — prevents drift | ✓ Good |
| Android Compose + iOS SwiftUI (no cross-platform) | Each platform has deep native MIDI APIs that benefit from native integration; RN/Flutter adds a bridging layer over an already-bridged system | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd:transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-03-28 after initialization*
