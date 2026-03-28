# Roadmap: EP-133 Sample Tool — Mobile

**Milestone:** M1 — Native Mobile Apps
**Created:** 2026-03-28
**Granularity:** Standard
**Coverage:** 20/20 requirements mapped

## Phases

- [ ] **Phase 1: MIDI Foundation** — Fix threading bugs, harden USB connection, establish iOS MIDI layer
- [ ] **Phase 2: Android Device Management** — Real device stats, full backup/restore, performance screen hardening
- [ ] **Phase 3: iOS Native UI** — Build all four SwiftUI screens mirroring the Android Compose screens
- [ ] **Phase 4: Project Management** — Project browser, project-level backup, backup library, share sheet

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

**Plans:** 3 plans

Plans:
- [ ] 01-midi-foundation-01-PLAN.md — Fix Android MIDI threading + SequencerEngine scope leak + lifecycleScope migration
- [ ] 01-midi-foundation-02-PLAN.md — Fix iOS CoreMIDI threading + sendRawBytes buffer + MIDIPort Swift protocol + app environment injection
- [ ] 01-midi-foundation-03-PLAN.md — Harden Android USB connection + PermissionState model + three-state DeviceScreen + connection badge + disconnected overlays

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

### Plans
- Reverse-engineer EP-133 SysEx protocol — map device stat query, full backup dump, and restore messages from `data/index.js`; document findings in `.planning/research/SYSEX_PROTOCOL.md`
- Implement SysEx accumulation buffer — handle fragmented `onSend()` delivery on Android before any backup work begins
- Build `ProjectManager` domain component — SAF file I/O with `ActivityResultContracts.CreateDocument`/`OpenDocument`; typed project file model via `kotlinx-serialization-json`
- Wire real device stats to DeviceScreen — SysEx query on connect; replace all hardcoded values
- Implement full backup/restore on Android — SysEx dump to file, restore from file with confirmation; fix `SampleManagerPanel` WebView bridge gap

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

### Phase 4: Project Management
**Goal**: Users on both platforms can browse EP-133 project slots, save individual project backups, manage a backup library, and share backup files.
**Depends on**: Phase 2, Phase 3
**Requirements**: PROJ-01, PROJ-02, PROJ-03, PROJ-04

### Success Criteria
1. User can open a Projects screen and see all 9 EP-133 project slots with their names and a content summary.
2. User can back up a single project (not a full device dump) to a named file on phone storage.
3. User can open a backup library and see all previously saved backups as a scrollable list with file names and timestamps.
4. User can share any backup file from the library via the iOS Share Sheet or Android share intent — including to AirDrop, Files, Google Drive, or the desktop Electron app.

### Plans
- Research EP-133 project-level SysEx boundary — determine what constitutes one project in the dump format and whether partial restore is supported; document in `.planning/research/SYSEX_PROTOCOL.md`
- Build project browser on Android — enumerate 9 project slots via SysEx; `ProjectsScreen` Compose UI with slot cards
- Implement project-level backup/restore on Android — single-project SysEx dump to file; restore with slot targeting
- Build iOS project management — `ProjectManager.swift`; SwiftUI Projects screen; `fileExporter`/`fileImporter` with custom `UTType` (`com.ep133sampletool.project`); security-scoped resource `defer` pattern
- Build backup library and share sheet on both platforms — scrollable backup list with timestamps; Android `ShareCompat`; iOS `ShareLink`

**UI hint**: yes
**Dependencies**: Phase 2, Phase 3

---

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. MIDI Foundation | 0/3 | In progress | — |
| 2. Android Device Management | 0/5 | Not started | — |
| 3. iOS Native UI | 0/4 | Not started | — |
| 4. Project Management | 0/5 | Not started | — |

---
*Roadmap created: 2026-03-28*
*Last updated: 2026-03-28 — Phase 1 plans created*
