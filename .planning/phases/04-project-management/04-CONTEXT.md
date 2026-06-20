# Phase 4: Project Management — Context

**Gathered:** 2026-06-19
**Status:** Ready for research (Android slice only)
**Source:** Direct scoping with Thomas (no discuss-phase interview — decisions captured below)

<domain>
## Phase Boundary

Phase 4 as written in ROADMAP.md bundles Android **and** iOS project management. This
context narrows it to the **Android slice only**. The iOS half is explicitly deferred to a
later phase.

**In scope (Android):**
1. **Project browser** — show the EP-133's 9 project slots with names + a content summary,
   enumerated over SysEx.
2. **Single-project backup** — back up one project (not a full-device dump) to a named file
   on phone storage.
3. **Backup library** — a scrollable list of saved backups with file names + timestamps.
4. **Share** — share any backup file via an Android share intent (`ShareCompat`).

**Out of scope (deferred):** all iOS work — SwiftUI Projects screen, `fileExporter`/
`fileImporter`, `ShareLink`, security-scoped resource handling, custom `UTType`.
</domain>

<decisions>
## Implementation Decisions

### Scope
- Android only this phase. Do not touch `iOSApp/`.
- Build on the existing Phase 2 device-management stack (`MIDIRepository`, `BackupManager`,
  `SysExProtocol`, `DeviceScreen`), not a parallel implementation.

### KEY UNKNOWN — resolve in research before planning
- The EP-133 **project-level SysEx boundary is undocumented.** The existing protocol is
  filesystem-based (`FILE_LIST`/`FILE_GET`/`FILE_PUT` on paths, e.g. `/sounds`). The open
  question: **how are the 9 projects stored on-device, and can a single project be
  enumerated / backed up / restored independently?**
  - Is there a per-project path or node (e.g. `/projects/N`, a project directory, a slot
    index) that `FILE_LIST`/`FILE_GET` can target?
  - What is the on-disk unit of "one project" in the dump format?
  - Is partial / single-project **restore** supported, or is restore whole-device only?
- **If single-project backup proves infeasible** (the device only supports whole-device
  dumps), surface that clearly and propose a fallback (e.g. label the existing full backup
  as the project-backup unit, or back up the project's referenced samples as a bundle).

### Reuse / patterns
- Project browser is a new Compose screen + ViewModel, mirroring the existing
  Pads/Beats/Sounds/Device screen+ViewModel pattern.
- Backup library is local file enumeration (app's storage dir / SAF), with timestamps from
  file metadata or the existing `EP133-YYYY-MM-DD-HHmm` naming convention in `BackupManager`.

### Claude's Discretion
- Exact Compose layout of the project browser and library list.
- File storage location (app-specific dir vs SAF tree) — pick what makes share + library
  enumeration simplest.
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### EP-133 SysEx protocol (the core unknown lives here)
- `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt` — current frame/command map (FILE_LIST/GET/PUT/METADATA on paths)
- `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/BackupManager.kt` — current full-device backup/restore flow over the file protocol
- `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt` — SysEx dispatch, file-list/chunk flows, device-stats query
- `data/index.js` — compiled web app; the upstream reference implementation of the full protocol. **Reverse-engineer project/slot handling from here** (grep for project, slot, /projects, scene, pattern, dump, FILE_LIST targets).
- `.planning/phases/02-android-device-management/02-RESEARCH.md` — Phase 2's reverse-engineering of the file protocol; the starting point, don't redo it.

### UI / screen patterns to mirror
- `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt` — screen+ViewModel+SAF+backup-progress pattern
- `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt` — nav registration for a new tab/screen

### Shared data
- `shared/ep133-pads.json`, `shared/ep133-sounds.json` — EP-133 content model reference
</canonical_refs>

<deferred>
## Deferred Ideas
- All iOS project management (SwiftUI Projects screen, fileExporter/fileImporter, ShareLink,
  security-scoped resources, custom UTType) — later phase.
- Project-level restore hardening / multi-chunk transfer — already flagged as "Phase 4
  hardening" in BackupManager; gate on the feasibility research outcome.
</deferred>

---

*Phase: 04-project-management*
*Context captured: 2026-06-19 (Android-only scoping, direct with Thomas)*
