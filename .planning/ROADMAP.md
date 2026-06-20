# Roadmap: EP-133 Sample Tool — Mobile

**Milestone:** M1 — Native Mobile Apps
**Created:** 2026-03-28
**Granularity:** Standard
**Coverage:** 20/20 requirements mapped

## Phases

- [x] **Phase 1: MIDI Foundation** — Fix threading bugs, harden USB connection, establish iOS MIDI layer (completed 2026-03-28)
- [x] **Phase 2: Android Device Management** — Real device stats, full backup/restore, performance screen hardening (completed 2026-03-30)
- [ ] **Phase 3: iOS Native UI** — Build all four SwiftUI screens mirroring the Android Compose screens
- [ ] **Phase 4: Project Management** — Project browser, project-level backup, backup library, share sheet
- [ ] **Phase 5: Splice Sample Sync** — Pull samples from a user's Splice library and load them onto the EP-133 (research-gated on the Splice access path)

## Backlog

Unscheduled ideas (999.x). Promote into a milestone via `/gsd:review-backlog`.

_(none — Phase 999.1 "Splice sample sync" promoted to Phase 5 on 2026-06-20)_

## Phase Details

### Phase 1: MIDI Foundation
**Goal**: Users on both platforms get a reliable USB connection with correct permission flow and error states — no crashes from threading bugs.
**Depends on**: Nothing (first phase)
**Requirements**: CONN-01, CONN-02, CONN-03, CONN-04

### Success Criteria
1. User sees a live connection status indicator that updates immediately when the EP-133 cable is plugged in or removed, on both Android and iOS.
2. User does not need to restart the app after a cable replug — the app reconnects automatically.
3. User is prompted for USB permission once; subsequent launches and replug events do not re-prompt.
4. User sees an actionable error screen (not a blank state or crash) when the EP-133 is missing, permission is denied, or the device is unrecognized — with a clear step to resolve it.

**Plans:** 3/1 plans complete

Plans:
- [x] 01-midi-foundation-01-PLAN.md — Fix Android MIDI threading + SequencerEngine scope leak + lifecycleScope migration
- [x] 01-midi-foundation-02-PLAN.md — Fix iOS CoreMIDI threading + sendRawBytes buffer + MIDIPort Swift protocol + app environment injection
- [x] 01-midi-foundation-03-PLAN.md — Harden Android USB connection + PermissionState model + three-state DeviceScreen + connection badge + disconnected overlays

**UI hint**: yes
**Dependencies**: none

---

### Phase 2: Android Device Management
**Goal**: Android users can view real device stats, configure the device, and save or restore a full backup from phone storage.
**Depends on**: Phase 1
**Requirements**: DEV-01, DEV-02, DEV-03, DEV-04, PERF-01, PERF-02, PERF-03, PERF-04

### Success Criteria
1. User can see real sample count, storage used, and firmware version on the Device screen — not hardcoded placeholders.
2. User can change the EP-133 MIDI channel and scale/root note from the Device screen and have the change take effect immediately.
3. User can save a full EP-133 backup to a named file on phone storage using the OS file picker.
4. User can restore the EP-133 from a backup file selected from phone storage, with a confirmation step before overwrite.
5. User can trigger any pad with multi-touch and velocity, preview a sound before assigning it, program a 16-step beat synced to device transport, and see pads highlighted by scale membership.

**Plans:** 1/1 plans complete

Plans:
- [ ] 02-android-device-management-02-PLAN.md — Wave 0 test stubs + SysEx protocol + accumulation buffer + device stats + PAK backup/restore + multi-touch + scale lock + sound preview + MIDI transport

**UI hint**: yes
**Dependencies**: Phase 1

---

### Phase 3: iOS Native UI
**Goal**: iOS users have native SwiftUI screens for Pads, Beats, Sounds, and Device — no more full-screen WKWebView as the primary interface.
**Depends on**: Phase 1
**Requirements**: IOS-01, IOS-02, IOS-03, IOS-04

### Success Criteria
1. iOS user can open the app and navigate between Pads, Beats, Sounds, and Device screens via a native bottom tab bar.
2. iOS user can trigger EP-133 sounds by tapping pads on the native SwiftUI Pads screen with multi-touch support.
3. iOS user can program a 16-step beat sequence on the native SwiftUI Beats screen and start/stop playback synced to EP-133 hardware transport.
4. iOS user can browse factory sounds on the native SwiftUI Sounds screen and preview a sound on the EP-133 before assigning it to a pad.
5. iOS user can see live connection status and real device stats (where available) on the native SwiftUI Device screen.

### Plans
- Build iOS domain layer — `MIDIRepository.swift`, `SequencerEngine.swift` (using `ContinuousClock`), `ChordPlayer.swift`; `@Observable`/`@MainActor` ViewModel base; iOS 16 `ObservableObject` fallback strategy
- Build SwiftUI Pads and Sounds screens — `PadsViewModel`, `SoundsViewModel`; multi-touch pad grid; factory sound browser with preview; pad assignment
- Build SwiftUI Beats screen — `BeatsViewModel`; 16-step grid; BPM control; device transport sync (MIDI Start/Stop/Continue)
- Build SwiftUI Device screen — `DeviceViewModel`; connection status; real device stats from SysEx (reuse protocol map from Phase 2); settings controls; replace `ContentView` WKWebView entrypoint with tab navigation

**UI hint**: yes
**Dependencies**: Phase 1

---

### Phase 4: Project Management (Android slice)
**Goal**: Android users can browse the EP-133's 9 project slots, back up a single project to phone storage, manage a backup library, and share backup files via the system share sheet.
**Scope note**: Narrowed to the **Android slice only** per 04-CONTEXT.md. The iOS half (SwiftUI Projects screen, fileExporter/fileImporter, ShareLink, security-scoped resources, custom UTType) is deferred to a later phase.
**Depends on**: Phase 2
**Requirements**: PROJ-01, PROJ-02, PROJ-03, PROJ-04

### Success Criteria
1. User can open a Projects screen and see all 9 EP-133 project slots with their names and a content summary.
2. User can back up a single project (not a full device dump) to a named file on phone storage.
3. User can open a backup library and see all previously saved backups as a scrollable list with file names and timestamps.
4. User can share any backup file from the library via the Android share intent — including to AirDrop, Files, Google Drive, or the desktop Electron app.

**Plans:** 4 plans (4 waves)

Plans:
- [x] 04-project-management-01-PLAN.md — Wave 0: six unit-test scaffolds + FileProvider manifest/path config
- [x] 04-project-management-02-PLAN.md — Wave 1 (gate): real multi-page INIT/DATA FILE_GET/PUT in SysExProtocol + MIDIRepository (replaces Phase 2's broken single-chunk model)
- [x] 04-project-management-03-PLAN.md — Wave 2: /projects node resolution + 9-slot enumeration + ProjectBackupManager single-project backup/restore (hardware-gated)
- [ ] 04-project-management-04-PLAN.md — Wave 3: ProjectsScreen browser + backup library + FileProvider/ShareCompat share + nav registration

**UI hint**: yes
**Dependencies**: Phase 2

---

### Phase 5: Splice Sample Sync
**Goal**: An Android user can pull samples from their Splice library and load them onto a connected EP-133 — no desktop required.
**Status**: RESEARCH-GATED. Programmatic Splice access is unconfirmed. Research must resolve the access path — official Splice API (does one exist + does ToS allow it?) vs. reading the local Splice desktop-app sample folder vs. manual import — before requirements and plans are locked. If no viable programmatic path exists, the phase narrows to a manual-import fallback (and a desktop/Electron path may be flagged as more appropriate than Android).
**Depends on**: Phase 4 (reuses the file-transfer SysEx stack — `ProjectBackupManager` / multi-page `FILE_PUT`)
**Requirements**: TBD pending feasibility research. Provisional: SPLICE-01 discover + auth the Splice source; SPLICE-02 fetch selected samples; SPLICE-03 convert to the EP-133's expected WAV; SPLICE-04 load onto the device via `FILE_PUT`.

### Success Criteria
1. User can point the app at their Splice samples via whatever access path research validates.
2. User can browse and select Splice samples to sync.
3. Selected samples are converted to the EP-133's expected WAV format.
4. Selected samples are loaded onto the connected EP-133 over the existing file-transfer SysEx protocol.

**Plans:** TBD (planning follows feasibility research)

**UI hint**: yes
**Dependencies**: Phase 4

---

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. MIDI Foundation | 3/1 | Complete   | 2026-03-28 |
| 2. Android Device Management | 1/1 | Complete   | 2026-03-30 |
| 3. iOS Native UI | 0/4 | Not started | — |
| 4. Project Management | 4/4 | Complete (Android slice) — hardware UAT pending | 2026-06-20 |
| 5. Splice Sample Sync | 0/? | Research-gated | — |

---
*Roadmap created: 2026-03-28*
*Last updated: 2026-06-20 — Phase 4 plan 04-03 (Wave 2 enumerate + ProjectBackupManager) executed*
