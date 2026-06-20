# Phase 5: Sample Import (Android) — Context

**Gathered:** 2026-06-20
**Status:** Ready for planning (scope locked after feasibility research)
**Source:** Promoted from backlog 999.1 "Splice sample sync"; reshaped after 05-RESEARCH.md

<domain>
## Phase Boundary

Let an Android user import audio files from phone storage, convert them to the EP-133's
sample format, and load them onto a connected device — no desktop required.

**Reshaped from "Splice sync":** research (05-RESEARCH.md) found NO ToS-clean programmatic
way to reach a user's Splice library on Android (no public API; the internal GraphQL surface
violates Splice ToS and risks account termination; the local `~/Splice` folder is
desktop-only). So Phase 5 is user-driven file **import**, which is fully buildable. The real
Splice-folder *sync* is backlogged to the desktop/Electron target (ROADMAP Phase 999.2) —
**do not build any Splice API/GraphQL integration in this phase.**
</domain>

<decisions>
## Implementation Decisions (locked)

- **Source = SAF file picker.** User picks one or more audio files (`ACTION_OPEN_DOCUMENT` /
  `OpenMultipleDocuments`). No Splice integration of any kind.
- **Target format = the EP-133's:** 16-bit PCM WAV, **46875 Hz**, mono or stereo
  (`DEVICE_SAMPLE_RATE=46875`, `DEVICE_AUDIO_FORMAT="s16"`, from `data/index.js` per
  05-RESEARCH.md).
- **Conversion on Android:** `MediaCodec` decode → linear resample to 46875 Hz → hand-written
  RIFF/Int16 WAV encoder. No new third-party dependencies (per research).
- **Device load = reuse Phase 4.** Same paged INIT/DATA `FILE_PUT` (`buildFilePut*Frame`,
  `MIDIRepository.putProjectArchive` pattern), re-targeted from `/projects` to
  `/sounds/<name>.wav`. Mirror `ProjectBackupManager` as a new `SampleImportManager`.
- **UI:** an import screen (likely a new entry point / action) with per-file progress and a
  per-sample success/failure result.

### Hardware note
Actual on-device load (does the EP-133 accept the WAV at `/sounds`, does it appear on a pad)
needs a physical device — handle like Phase 4: implement against the documented format,
unit-test the conversion + frame building, and defer the on-device round-trip to a UAT entry.

### Claude's Discretion
- Import screen placement (new nav tab vs. action within Sounds/Device).
- Sample naming / collision handling on `/sounds`.
- Staging location for converted WAVs before load.
</decisions>

<canonical_refs>
## Canonical References
- `.planning/phases/05-splice-sample-sync/05-RESEARCH.md` — feasibility verdict, EP-133 WAV format, MediaCodec conversion approach, FILE_PUT reuse, validation architecture
- `.planning/phases/04-project-management/04-RESEARCH.md` — EP-133 file protocol, `/sounds` path, paged FILE_PUT
- `AndroidApp/.../domain/midi/SysExProtocol.kt` — multi-page FILE_PUT builders
- `AndroidApp/.../domain/midi/MIDIRepository.kt` — paged transfer dispatch (`putProjectArchive`)
- `AndroidApp/.../domain/midi/ProjectBackupManager.kt` — file-load orchestration to mirror
- `AndroidApp/.../ui/device/DeviceScreen.kt` — screen+ViewModel+SAF+progress+snackbar pattern
</canonical_refs>

<deferred>
## Deferred
- Desktop/Electron Splice-folder sync → ROADMAP Phase 999.2 backlog.
- iOS.
- On-device load verification → hardware UAT.
</deferred>

---

*Phase: 05-splice-sample-sync · scope locked 2026-06-20 (manual import; Splice API disqualified by ToS)*
