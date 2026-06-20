---
phase: 4
slug: project-management
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-06-20
---

# Phase 4 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Seeded from 04-RESEARCH.md "Validation Architecture". Android slice only.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4.13.2 + `kotlinx-coroutines-test` 1.7.3 (unit); Compose UI test (instrumented) |
| **Config file** | None — standard Android unit test runner |
| **Quick run command** | `cd AndroidApp && ./gradlew :app:testDebugUnitTest` |
| **Full suite command** | `cd AndroidApp && ./gradlew :app:testDebugUnitTest :app:lintDebug` |
| **Estimated runtime** | ~45 seconds (quick unit); ~5-8 minutes (full instrumented) |

---

## Sampling Rate

- **After every task commit:** Run `cd AndroidApp && ./gradlew :app:testDebugUnitTest`
- **After every plan wave:** Run `cd AndroidApp && ./gradlew :app:testDebugUnitTest :app:lintDebug`
- **Before `/gsd:verify-work`:** Full unit suite green + manual UAT on physical EP-133 (enumerate 9 slots, back up one project, restore it, share the file)
- **Max feedback latency:** 45 seconds (unit)

---

## Per-Task Verification Map

> Seeded from research. Plan/Task IDs finalized by the planner; wave-0 creates the missing test files.

| Requirement | Behavior | Wave | Test Type | Automated Command | File Exists | Status |
|-------------|----------|------|-----------|-------------------|-------------|--------|
| PROJ-02 | GET INIT payload bytes correct (nodeId/offset BE) | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.ProjectProtocolTest"` | ❌ W0 | ⬜ pending |
| PROJ-02 | GET DATA loop assembles fileSize bytes across pages | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.MultiChunkGetTest"` | ❌ W0 | ⬜ pending |
| PROJ-02 | Page mismatch throws; empty data terminates | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.MultiChunkGetTest"` | ❌ W0 | ⬜ pending |
| PROJ-02 | SUCCESS_START keeps request pending; OK completes | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.SysExDispatchTest"` | ❌ W0 | ⬜ pending |
| PROJ-01 | FILE_LIST response parses {nodeId,flags,size,name} per entry | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.ProjectProtocolTest"` | ❌ W0 | ⬜ pending |
| PROJ-01 | listProjects() maps 9 entries + marks active slot | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.ProjectsViewModelTest"` | ❌ W0 | ⬜ pending |
| PROJ-03 | Library enumerates `.tar` files sorted by mtime desc | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.BackupLibraryTest"` | ❌ W0 | ⬜ pending |
| PROJ-04 | Share builds FileProvider content:// URI + ACTION_SEND | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.ShareIntentTest"` | ❌ W0 | ⬜ pending |
| — | 7-bit pack/unpack round-trips a binary archive blob | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.SysExProtocolTest"` | ⚠️ partial (Phase 2) | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `ProjectProtocolTest.kt` — GET/PUT INIT/DATA frame bytes, FILE_LIST response parsing
- [ ] `MultiChunkGetTest.kt` — paged assembly, page-mismatch, empty-terminator, CRC32
- [ ] `SysExDispatchTest.kt` — multi-response request lifecycle (SUCCESS_START vs OK)
- [ ] `ProjectsViewModelTest.kt` — slot list mapping + active marker
- [ ] `BackupLibraryTest.kt` — directory enumeration + timestamp sort
- [ ] `ShareIntentTest.kt` — ShareCompat/FileProvider intent construction
- [ ] FileProvider manifest entry + `res/xml/file_paths.xml`

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Real slot names + content summary | PROJ-01 | Needs a connected EP-133 with named projects | Connect device, open Projects screen, confirm 9 slots show real names + active marker |
| Single-project download integrity | PROJ-02 | Requires hardware round-trip; tar must be valid | Back up one slot, verify the `.tar` opens and contains the project tree |
| Restore round-trip | PROJ-02 | Overwrites a live slot | Restore the backed-up `.tar`, confirm device reloads the project |
| Share sheet | PROJ-04 | OS share UI not unit-testable end-to-end | Share a saved backup; confirm it reaches Files/Drive/AirDrop targets |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 45s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
