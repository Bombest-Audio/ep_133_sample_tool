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
- [ ] **Phase 5: Sample Import (Android)** — Import audio files via SAF, convert to EP-133 WAV, load onto the device over the Phase 4 FILE_PUT stack

## Backlog

Unscheduled ideas (999.x). Promote into a milestone via `/gsd:review-backlog`.

- [ ] **Phase 999.2: Desktop Splice-folder sync (Electron)** — On the desktop/Electron target, watch the user's local Splice folder (`~/Splice` macOS, `C:\Documents\Splice` Windows) and sync new samples onto the EP-133. Desktop-only and ToS-clean (reads the user's own local files — no Splice API). This is where a true "Splice sync" belongs; Android can't reach Splice without violating ToS (see 05-RESEARCH.md). Captured 2026-06-20.

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

### Phase 5: Sample Import (Android)
**Goal**: An Android user can import audio files from phone storage, have them converted to the EP-133's sample format, and loaded onto a connected device — no desktop required.
**Origin**: Reshaped from "Splice Sample Sync" after research (05-RESEARCH.md) found no ToS-clean programmatic Splice path on Android. The literal Splice-folder *sync* is backlogged to the desktop/Electron target (Phase 999.2). Phase 5 ships the user-driven import that IS buildable on Android.
**Depends on**: Phase 4 (reuses the file-transfer SysEx stack — `ProjectBackupManager` / multi-page `FILE_PUT`, re-targeted from `/projects` to `/sounds`)
**Requirements**: SAMPLE-01, SAMPLE-02, SAMPLE-03, SAMPLE-04

### Success Criteria
1. User can pick one or more audio files from phone storage via the SAF file picker.
2. Imported audio is converted to 16-bit PCM WAV @ 46875 Hz (mono or stereo), the EP-133's expected format.
3. Converted samples are loaded onto the connected EP-133 over the existing paged FILE_PUT stack (to `/sounds`).
4. User sees an import screen with per-file progress and a clear success/failure result for each sample.

**Plans:** 4 plans (4 waves)

Plans:
- [x] 05-splice-sample-sync-01-PLAN.md — Wave 0: four unit-test scaffolds (WavEncoder/Resampler/SampleImport/SampleImportViewModel)
- [x] 05-splice-sample-sync-02-PLAN.md — Wave 1: pure conversion core — WavEncoder (16-bit RIFF @ 46875) + Resampler + pass-through
- [x] 05-splice-sample-sync-03-PLAN.md — Wave 2: AudioDecoder (MediaCodec) + MIDIRepository.putSampleFile (paged /sounds PUT) + SampleImportManager (hardware-gated)
- [x] 05-splice-sample-sync-04-PLAN.md — Wave 3: SampleImportScreen + ViewModel + SAF multi-pick + Import nav/MainActivity wiring (hardware-gated)

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
| 5. Sample Import (Android) | 4/4 | Complete (Android slice) — hardware UAT pending | 2026-06-20 |

---
*Roadmap created: 2026-03-28*
*Last updated: 2026-06-20 — Phase 5 complete (Android slice; SAMPLE-01..04 verified, hardware UAT pending)*
