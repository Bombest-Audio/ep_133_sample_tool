# Phase 5: Splice Sample Sync — Context

**Gathered:** 2026-06-20
**Status:** RESEARCH-GATED — feasibility must be resolved before planning
**Source:** Promoted from backlog 999.1; direct scoping with Thomas

<domain>
## Phase Boundary

Let an Android user pull samples from their **Splice** library and load them onto a
connected EP-133 over the existing file-transfer SysEx stack — no desktop required.

**Target:** Android app, reusing the Phase 4 stack (`ProjectBackupManager`, multi-page
`FILE_PUT` in `SysExProtocol`/`MIDIRepository`). Flag if a desktop/Electron path is
actually more appropriate (e.g. if Splice access only works on desktop).
</domain>

<decisions>
## The gate — resolve in research BEFORE any planning

**KEY FEASIBILITY UNKNOWN.** The entire phase shape depends on how (or whether) we can
reach a user's Splice samples programmatically:
- (a) **Official Splice API** — does a public/usable one exist for listing/downloading the
  user's samples? Does Splice's ToS permit programmatic sample pulls from a third-party app?
- (b) **Local Splice desktop-app sample folder** — Splice's desktop app downloads samples to
  a known folder. On Android that folder isn't present, but a desktop/companion path or a
  user-pointed sync folder might be. Assess.
- (c) **Manual import** — user exports/selects sample files; app converts + loads them.

If **no viable programmatic path exists** (API absent or ToS-disallowed), say so plainly and
recommend the best fallback rather than forcing a plan around a nonexistent API. A clear
"infeasible via API → fallback X" verdict is a SUCCESSFUL research outcome.

Also resolve: auth model (if API); conversion of fetched samples to the EP-133's expected
**WAV** format (bit depth / sample rate / mono-stereo constraints); and how loading reuses
the Phase 4 `FILE_PUT` path (target path on device, e.g. `/sounds`).

## Reuse
- Device loading: the Phase 4 multi-page `FILE_PUT` transfer + `ProjectBackupManager` pattern.
- Do NOT re-solve the SysEx transfer; build on what Phase 4 shipped.

## Claude's Discretion (post-feasibility)
- UI for browsing/selecting Splice samples.
- Where converted WAVs are staged before load.
</decisions>

<canonical_refs>
## Canonical References
- `.planning/phases/04-project-management/04-RESEARCH.md` — the EP-133 file protocol (FILE_PUT, paths, WAV handling under `/sounds`)
- `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt` — multi-page FILE_PUT builders
- `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt` — `putProjectArchive` / paged transfer dispatch
- `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/ProjectBackupManager.kt` — file-load orchestration pattern
</canonical_refs>

<deferred>
## Deferred until feasibility is known
- Formal requirements (SPLICE-01..0N) — lock after research picks the access path.
- iOS.
</deferred>

---

*Phase: 05-splice-sample-sync · Context captured 2026-06-20 (research-gated)*
