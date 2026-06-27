---
phase: 04-project-management
plan: 02
subsystem: android-midi-protocol
tags: [sysex, file-transfer, paging, dispatch, tdd]
requires: [04-project-management-01]
provides:
  - "SysExProtocol paged GET/PUT INIT/DATA frame builders + response parsers"
  - "SysExProtocol.assembleGetPages pure page-assembly loop"
  - "SysExProtocol.classifyTransferStatus continuation predicate"
  - "MIDIRepository.getProjectArchive / putProjectArchive paged transfer"
affects:
  - "Wave 2 ProjectBackupManager (consumes getProjectArchive)"
  - "Wave 3 ProjectsScreen restore (consumes putProjectArchive)"
tech-stack:
  added: []
  patterns:
    - "Channel-backed multi-response transfer (not single CompletableDeferred)"
    - "Pure-function extraction for hardware-free unit testing"
    - "Status-keyed continuation: keep request alive >= SUCCESS_START, complete on OK"
key-files:
  created: []
  modified:
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/MultiChunkGetTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/SysExDispatchTest.kt
decisions:
  - "Pure assembleGetPages + classifyTransferStatus extracted so MultiChunkGetTest/SysExDispatchTest run without a device or coroutine timing"
  - "Pages flow through Channel(UNLIMITED) keyed by transferInFlight; INIT uses a one-shot CompletableDeferred"
  - "Buffer cap = fileSize + MAX_PAGE_BYTES (one-page slack) aborts on oversized/runaway stream (T-04-03)"
metrics:
  duration: "~25m"
  completed: "2026-06-20"
  tasks: 2
  files: 5
---

# Phase 4 Plan 02: Multi-page transfer GATE Summary

Replaced Phase 2's broken single-byte `chunkIndex` FILE_GET/PUT with the device's real two-phase INIT/DATA paging protocol — the gate every downstream project-management feature (backup, restore, library, share) depends on. Pure protocol + dispatch, fully unit-tested on the deterministic paths; the timing-dependent coroutine dispatch path is `@Ignore`d for hardware UAT.

## What was built

**Task 1 — SysExProtocol paged builders + parsers (commit 4e7f573)**
- New constants `TE_SYSEX_FILE_GET_TYPE_INIT/DATA` and `..._PUT_TYPE_INIT/DATA`.
- `buildFileGetInitFrame`, `buildFileGetDataFrame`, `buildFilePutInitFrame`, `buildFilePutDataFrame` — added alongside the broken Phase 2 builders (lines 194–233 untouched). nodeId uint16 BE, offset/fileSize uint32 BE, page uint16 BE; PUT DATA carries the 7-bit-packed archive chunk.
- `parseGetInitResponse` (fileId, flags, fileSize uint32 BE, null-terminated fileName) and `parseGetDataResponse` (page, data, `nextPage = (page+1)&0xFFFF`).
- `ProjectProtocolTest` rewritten with real frame-byte assertions for all four builders + both parsers + the 256-byte binary round-trip.

**Task 2 — MIDIRepository paged dispatch state machine (commit 706a682)**
- `getProjectArchive(nodeId)`: sends GET_INIT, awaits parsed `{fileSize, fileName}`, then loops GET_DATA(page) accumulating into a `ByteArrayOutputStream` until fileSize or empty-data terminator; requires `resp.page == expectedPage`; outer + per-page `withTimeoutOrNull` (T-04-04).
- `putProjectArchive(slotNodeId, tarBytes)`: PUT_INIT announcing size, then PUT_DATA(page, chunk) slices, awaiting a STATUS_OK ack.
- `dispatchPagedGetResponse` / `dispatchPagedPutResponse`: keep the request registered while `status >= STATUS_SPECIFIC_SUCCESS_START`, complete on `STATUS_OK`, drop on error. Pages stream through `Channel(UNLIMITED)` per RESEARCH Pitfall 3. `transferInFlight` guard (mirrors `statsQueryInFlight`) prevents overlapping transfers racing shared pending fields. `CancellationException` rethrown.
- Pure `assembleGetPages` and `classifyTransferStatus` extracted into SysExProtocol for hardware-free tests.
- `MultiChunkGetTest`: paged assembly reaches fileSize, partial last page, page-mismatch throws, empty-data terminates, oversized-page aborts (T-04-03), CRC32 of assembled blob == 0xF32EA407. `SysExDispatchTest`: PENDING/COMPLETE/ERROR discrimination as a pure function; the coroutine timing path `@Ignore`d with the established hardware-justification string.

## Verification

- `--tests "*.ProjectProtocolTest"` — PASS
- `--tests "*.MultiChunkGetTest" "*.SysExDispatchTest"` — PASS
- Full `:app:testDebugUnitTest` — PASS (no regression to Phase 2 backup/restore/protocol tests)

## Deviations from Plan

None — plan executed as written. The plan permitted extracting the page-assembly and status-discrimination logic into pure functions; both were extracted into `SysExProtocol` (`assembleGetPages`, `classifyTransferStatus`) so the Wave 0 tests turn GREEN without a device.

## Open Hardware-Verification Items

- **A3 (response byte offset):** `parseGetInitResponse` / `parseGetDataResponse` are written against the documented offsets after the `[FILE, GET, TYPE]` header is stripped. Both carry a `// HARDWARE-VERIFY (A3)` comment. Must be confirmed on a physical EP-133 during Wave 2/3 UAT before shipping.
- The full coroutine dispatch lifecycle (Channel receive + per-page timeout reset under real device timing) is `@Ignore`d in SysExDispatchTest pending hardware.

## Deferred / Out of Scope

- Pre-existing `MIDIManager.kt:159` MutableImplicitPendingIntent lint error still fails `:app:lintDebug` (documented in `04 deferred-items.md`, not introduced here). Unit tests are unaffected.

## Self-Check: PASSED
- SysExProtocol.kt, MIDIRepository.kt, ProjectProtocolTest.kt, MultiChunkGetTest.kt, SysExDispatchTest.kt — all present and modified.
- Commits 4e7f573 and 706a682 present in git log.
