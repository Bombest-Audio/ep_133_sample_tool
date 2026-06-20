---
phase: 04-project-management
plan: 01
subsystem: testing
tags: [junit, kotlin, android, fileprovider, sharecompat, sysex, nyquist]

# Dependency graph
requires:
  - phase: 02-android-device-management
    provides: SysExProtocol (7-bit codec, FILE constants), MIDIPort/MIDIRepository test seam, ChordsViewModelTest doubles
provides:
  - Six executing Wave 0 unit test classes (ProjectProtocol, MultiChunkGet, SysExDispatch, ProjectsViewModel, BackupLibrary, ShareIntent)
  - Concrete --tests targets for every Wave 1-3 automated verify (Nyquist substrate)
  - FileProvider declaration with authority com.ep133.sampletool.fileprovider
  - res/xml/file_paths.xml scoped to backups/ (external + internal fallback)
affects: [04-project-management-02, 04-project-management-03, 04-project-management-04]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Wave 0 executing-stub tests: real placeholders against today-available symbols + TODO naming the implementing plan"
    - "Per-file renamed test doubles (ProjectsSpyMIDIPort/ProjectsFakeMIDIRepo) to avoid top-level redeclaration across the shared test source set"
    - "FileProvider path config scoped to a single subdir per STRIDE threat register"

key-files:
  created:
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/MultiChunkGetTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/SysExDispatchTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectsViewModelTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/BackupLibraryTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ShareIntentTest.kt
    - AndroidApp/app/src/main/res/xml/file_paths.xml
  modified:
    - AndroidApp/app/src/main/AndroidManifest.xml

key-decisions:
  - "Renamed Wave 0 VM test doubles to ProjectsSpyMIDIPort/ProjectsFakeMIDIRepo — private top-level decls collide with ChordsViewModelTest across the module test source set"
  - "BackupLibraryTest is a real passing test now (temp .tar files + sort), not a stub — enumeration logic is pure and hardware-free"
  - "Timing/hardware-dependent paths @Ignore'd with the MIDIRepositoryStatsTest justification-string convention, not left failing"
  - "MIME constant pinned to application/octet-stream for the future share builder"

patterns-established:
  - "Executing-stub test: at least one green assertion against existing symbols, real assertions deferred to the named implementing plan via TODO(04-project-management-NN)"
  - "FileProvider paths scoped to backups/ only — no root-path/external-path wildcard (threat register T-04-01/T-04-02)"

requirements-completed: [PROJ-01, PROJ-02, PROJ-03, PROJ-04]

# Metrics
duration: 14min
completed: 2026-06-20
---

# Phase 4 Plan 01: Wave 0 Test Scaffold + FileProvider Summary

**Six executing JUnit 4 stub classes establishing the Nyquist verify substrate for Phase 4, plus a backups/-scoped FileProvider with authority com.ep133.sampletool.fileprovider for the Wave 3 share intent.**

## Performance

- **Duration:** ~14 min
- **Started:** 2026-06-20T00:00:00Z (approx)
- **Completed:** 2026-06-20
- **Tasks:** 2
- **Files modified:** 8 (7 created, 1 modified)

## Accomplishments
- Six Phase 4 test classes that compile, RUN (not "No tests found"), and pass — each carrying a TODO naming the plan (02/03/04) that fills its real assertions.
- Two tests carry one `@Ignore`'d hardware/timing path each (SysExDispatchTest, ShareIntentTest), matching the repo's `MIDIRepositoryStatsTest` justification-string convention; the rest are fully green.
- `BackupLibraryTest` is a genuine passing test of `.tar` enumeration sorted by mtime descending (temp dir, no hardware).
- FileProvider registered and resolvable at authority `com.ep133.sampletool.fileprovider`; `processDebugManifest` confirms the merged manifest.

## Task Commits

1. **Task 1: Six Phase 4 unit test classes as executing stubs** — `dfe4240` (test)
2. **Task 2: Declare the FileProvider and its path config** — `edf5eb0` (feat)

## Files Created/Modified
- `AndroidApp/.../ProjectProtocolTest.kt` — GET subcommand constant + 256-byte 7-bit codec round-trip; Wave 1 fills frame-byte + FILE_LIST parse
- `AndroidApp/.../MultiChunkGetTest.kt` — real CRC32 assertion (verified 0xF32EA407 for "EP133"); Wave 1 fills paged-loop assertions
- `AndroidApp/.../SysExDispatchTest.kt` — status-constant assertion + @Ignore'd SUCCESS_START/OK lifecycle (hardware)
- `AndroidApp/.../ProjectsViewModelTest.kt` — FakeMIDIRepo connection-state exercise + coroutine harness; Wave 2/3 fills slot mapping
- `AndroidApp/.../BackupLibraryTest.kt` — real temp-dir `.tar` enumeration sorted desc; Wave 2 swaps in the manager helper
- `AndroidApp/.../ShareIntentTest.kt` — MIME constant assertion + @Ignore'd FileProvider intent (Robolectric absent)
- `AndroidApp/app/src/main/res/xml/file_paths.xml` — `external-files-path` + `files-path` both rooted at `backups/`
- `AndroidApp/app/src/main/AndroidManifest.xml` — `<provider>` block inside `<application>`, exported=false, grantUriPermissions=true

## Decisions Made
- **Renamed test doubles** (`ProjectsSpyMIDIPort`/`ProjectsFakeMIDIRepo`): the `private` top-level `SpyMIDIPort`/`FakeMIDIRepo` in `ChordsViewModelTest.kt` collided with copies in `ProjectsViewModelTest.kt` at compile time (Kotlin redeclaration across the module test source set). Renaming is cleaner than extracting a shared test-double file in a scaffold-only plan.
- **BackupLibraryTest written as a real test now**: enumeration + mtime sort is pure, so it asserts real behavior immediately (per the plan's instruction) rather than deferring.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Renamed Wave 0 VM test doubles to break a redeclaration clash**
- **Found during:** Task 1 (test scaffold)
- **Issue:** `:app:compileDebugUnitTestKotlin` failed — `SpyMIDIPort`/`FakeMIDIRepo` declared `private` at top level in both `ChordsViewModelTest.kt` and the new `ProjectsViewModelTest.kt` are treated as redeclarations across the shared Android test source set, blocking compilation of all six new classes.
- **Fix:** Renamed the new file's doubles to `ProjectsSpyMIDIPort`/`ProjectsFakeMIDIRepo` and updated the doc comment to explain why.
- **Files modified:** AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectsViewModelTest.kt
- **Verification:** `:app:testDebugUnitTest` for all six classes builds and reports them RUN/green.
- **Committed in:** `dfe4240` (Task 1 commit)

**2. [Rule 1 - Bug] Corrected a hardcoded CRC32 placeholder value**
- **Found during:** Task 1 (MultiChunkGetTest authoring)
- **Issue:** Initial draft asserted a made-up CRC32 constant for "EP133" — would have failed at runtime.
- **Fix:** Computed the real CRC32 (`0xF32EA407`) via jshell and asserted that, dropping the redundant self-comparison.
- **Files modified:** AndroidApp/app/src/test/java/com/ep133/sampletool/MultiChunkGetTest.kt
- **Verification:** Test passes (skipped=0, failures=0).
- **Committed in:** `dfe4240` (Task 1 commit)

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 bug)
**Impact on plan:** Both necessary to make the scaffold compile and assert truthfully. No scope creep.

## Issues Encountered
- **`:app:lintDebug` fails on a single PRE-EXISTING error** in `MIDIManager.kt:159` (`MutableImplicitPendingIntent`), present at the Task 1 commit and untouched by this plan. Out of scope per the scope boundary — logged to `.planning/phases/04-project-management/deferred-items.md`, not fixed. `processDebugManifest` (the provider acceptance criterion) succeeds independently. The plan's lint acceptance is "no NEW errors introduced by the provider/xml"; that holds.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Wave 1 (04-02, the multi-chunk GET/PUT gate) has concrete `--tests "*.ProjectProtocolTest"` / `"*.MultiChunkGetTest"` / `"*.SysExDispatchTest"` targets to fill.
- Wave 3 (04-04 share) has a registered, grantable FileProvider authority.
- Recommend a separate chore commit to fix the pre-existing `MIDIManager.kt` lint error so `:app:lintDebug` (the per-wave full-suite command) is green before the phase gate.

## Self-Check: PASSED

All 7 created files present on disk; both task commits (`dfe4240`, `edf5eb0`) present in git history.

---
*Phase: 04-project-management*
*Completed: 2026-06-20*
