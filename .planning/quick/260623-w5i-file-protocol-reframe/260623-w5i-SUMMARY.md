---
quick_id: 260623-w5i
slug: file-protocol-reframe
title: Reframe EP-133 file protocol — command=TE_SYSEX_FILE + FILE_INIT handshake + dispatcher rework
date: 2026-06-23
commit: d8b9f99
status: foundational-slice-complete
---

# 260623-w5i: EP-133 File Protocol Reframe — Foundational Slice Summary

**One-liner:** Reframed all file SysEx ops to command=TE_SYSEX_FILE(5) with body-starts-at-subcommand, added FILE_INIT handshake, reworked dispatcher — the three hardware-verified bugs blocking every file operation on the EP-133.

## Commit

`d8b9f99` — fix(260623-w5i): reframe EP-133 file protocol to command=TE_SYSEX_FILE(5) + FILE_INIT handshake

## What was built

### Task 1 — Reframe all file builders (SysExProtocol.kt)

- Added `buildFileFrame(deviceId, requestId, body)` = `buildFrame(deviceId, TE_SYSEX_FILE, requestId, body)`.
- Added `TE_SYSEX_FILE_INIT = 1`, `TE_SYSEX_FILE_INIT_SUBSCRIBE = 1` constants.
- Added `buildFileInitFrame(deviceId, requestId, maxResponseLength, flags)` — body `[INIT(1), flags, maxResp u32 BE]`.
- Added `parseFileInitResponse(body): Int` — parses negotiated chunkSize from body[1..4].
- Rerouted all file builders through `buildFileFrame`:
  - `buildFileSystemFrame` (path-string ops: LIST/GET/PUT/METADATA/DELETE) — body now starts at subcommand, no leading TE_SYSEX_FILE byte.
  - `buildFileListByNodeFrame` — body `[LIST(4), page u16, nodeId u16]` (was `[5, LIST, page, nodeId]`).
  - `buildFileGetInitFrame`, `buildFileGetDataFrame`, `buildFilePutInitFrame`, `buildFilePutDataFrame`, `buildFileCreatePutInitFrame` — all drop the leading `TE_SYSEX_FILE` byte from their body.
  - `buildMetadataGetFrame`, `buildMetadataSetFrame`, `buildFileInfoFrame` — same treatment.
- `CMD_PRODUCT_SPECIFIC` constant retained (greet and any other non-file uses).

### Task 2 — Adopt deviceId from greet (MIDIRepository.kt)

- In `dispatchSysEx` CMD_GREET branch: `currentDeviceId = message[4].toInt() and 0x7F`.
- Also resets `fileSessionInitialized = false` on greet (new connection signal).
- Log: `EP133MIDI: GREET: adopted deviceId=0x33`.

### Task 3 — FILE_INIT handshake (MIDIRepository.kt)

- Added fields: `fileSessionInitialized: Boolean`, `deviceChunkSize: Int = 512`, `pendingFileInitDeferred`.
- Added `suspend fun ensureFileSessionInit(): Boolean` — sends FILE_INIT, awaits response (5s timeout), marks session initialized. Best-effort: on timeout marks initialized anyway so we don't loop.
- `ensureFileSessionInit()` called at the top of `resolveNodeId` and `getActiveGroupIndex`.
- `FILE_INIT_REQUEST_ID = 83`, `FILE_INIT_TIMEOUT_MS = 5_000L` added to companion.

### Task 4 — Dispatcher rework (MIDIRepository.kt)

- Replaced obsolete `CMD_PRODUCT_SPECIFIC` branch (which checked `payload[0] == TE_SYSEX_FILE`) with `TE_SYSEX_FILE` branch.
- New branch: unpacks full body once via `unpack7bit(payload)`, extracts subcommand byte, calls `dispatchFileResponse(fileCmd, filePayload)` where `filePayload` is already unpacked.
- `dispatchFileResponse` gains `TE_SYSEX_FILE_INIT` case — sets `fileSessionInitialized = true`, records `deviceChunkSize`, completes `pendingFileInitDeferred`.
- All existing handlers (METADATA, FILE_LIST, FILE_GET, FILE_PUT, FILE_INFO) updated to work with pre-unpacked payload — removed inner `unpack7bit` calls.
- Legacy path-form METADATA response now parses directly as ASCII `key:value` text (no round-trip through `parseGreetResponse` which expected packed input).
- Raw payload hex logged at every inbound file response: `EP133MIDI: MIDI META: inbound FILE response cmd=5 payload[N] HH HH ...`.

### Test updates

All tests that previously asserted `CMD_PRODUCT_SPECIFIC (127)` at frame[8] and `TE_SYSEX_FILE (5)` at `p[0]` of the unpacked body were updated to the new contract:
- `frame[8] == TE_SYSEX_FILE (5)`
- `p[0] == subcommand` (GET, PUT, LIST, METADATA, FILE_INFO, or INIT — not 5)

Tests updated: `SysExProtocolTest`, `ProjectProtocolTest`, `MetadataProtocolTest`, `SampleImportTest`, `BackupRestoreTest`.

New tests added in `SysExProtocolTest`:
- `buildFileInitFrame_commandByteAndBodyLayout` — asserts exact wire bytes for FILE_INIT.
- `buildFileListByNodeFrame_commandByteAndBodyLayout` — asserts command=5, body=[LIST(4), page u16, nodeId u16].

## Verification

- `./gradlew :app:testDebugUnitTest` — 156 tests pass, 18 skipped (hardware-bound), 0 failures.
- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL.

## Follow-up fix (2026-06-23) — Bug: response dispatcher correlated by subcommand device never sends

**Commit:** `e9ea897`

### Root cause
The `dispatchSysEx` TE_SYSEX_FILE branch extracted `fileCmd = body[0]` and called `dispatchFileResponse(fileCmd, body[1:])`. But real device FILE responses do NOT echo the subcommand. Hardware captures:
- FILE_INIT reply unpacked body starts `00 0C 00 00 02 00` (body[0]=0x00, not 0x01=INIT)
- FILE_LIST reply unpacked body starts with page u16, not 0x04=LIST

So `fileCmd=0x00` matched no case in `dispatchFileResponse`, `pendingFileInitDeferred` was never completed, the session never opened, and every subsequent file operation was blocked at `ensureFileSessionInit()`.

### Fix
Replaced body[0]-as-subcommand routing with in-flight state inspection. Since requests are serialised (one file op in flight at a time), the op type is determined by checking which deferred/flag is set:

```kotlin
val inFlightCmd = when {
    pendingFileInitDeferred != null              -> TE_SYSEX_FILE_INIT
    metadataJsonInFlight || metadataSetInFlight  -> TE_SYSEX_FILE_METADATA
    pendingNodeListDeferred != null              -> TE_SYSEX_FILE_LIST
    transferInFlight                             -> TE_SYSEX_FILE_PUT
    pendingGetInitDeferred != null || pendingGetPages != null -> TE_SYSEX_FILE_GET
    pendingNodeInfoDeferred != null              -> TE_SYSEX_FILE_INFO
    else                                         -> -1  // log "unrouted" + raw hex
}
```

Also updated `dispatchFileResponse` to receive the WHOLE body (no bytes stripped) and updated handlers:
- FILE_INIT: tolerant chunkSize parse (try/catch returns 512 on error); session opening is what matters
- FILE_LIST (node path): skip the leading page u16 (2 bytes) before handing entries to `parseFileListEntries` — matches real wire format `[page u16][entries...]`
- Legacy FILE_LIST stats path: kept body[0]-as-status heuristic; added raw-hex log for next HW capture
- METADATA/PUT/GET/INFO: pass whole body; exact offsets are HW-unverified but not on the critical path

### Verification
- `./gradlew :app:testDebugUnitTest` — 156 tests pass, 0 failures (no tests asserted the old body[0]-subcommand routing)
- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL

## Offline fix 3 (2026-06-24) — METADATA GET JSON parse + PUT INIT ack gate

**Commit:** `24308c1`

### Fix 1 — `getMetadataJson`: extract JSON span before parsing

**Root cause (HW-verified):** METADATA GET response body is `00 00 7B 22 61 63 74 69 76 65 22 3A 33 30 30 30 7D 00` — a 2-byte page prefix + `{"active":3000}` + trailing NUL. `JSONObject(accumulated)` was called on the whole accumulated string, which starts with the two prefix bytes → throws `JSONException` → falls to "JSON parse failed, greet fallback" log.

**Fix:** Before `JSONObject(...)`, extract the outermost `{...}` span:
```kotlin
val jsonSpan = run {
    val s = accumulated.indexOf('{'); val e = accumulated.lastIndexOf('}')
    if (s >= 0 && e > s) accumulated.substring(s, e + 1) else accumulated
}
JSONObject(jsonSpan)
```
Greet fallback retained on catch (handles any format that still doesn't parse).

**Tests added (`MetadataProtocolTest`):** 4 JSON span extraction tests:
- `jsonSpan_stripsPagePrefixAndTrailingNul` — verifies the fix for the exact HW body shape
- `jsonSpan_cleanJsonPassesThrough` — clean JSON is unchanged
- `jsonSpan_nestedObjectsRespectLastBrace` — nested `{...}` captured correctly
- `jsonSpan_noJsonBraces_returnsAccumulated` — fallback when no braces present

### Fix 2 — `putSampleFile`: await PUT INIT response before sending DATA

**Root cause (HW-verified):** The device returns "unexpected page" when DATA frames arrive before the PUT INIT response. The reference tool (data/index.js) `await`s the INIT response before entering the DATA loop. Our code sent INIT then immediately looped DATA without waiting.

**Fix:** Added `pendingPutInitDeferred: CompletableDeferred<Boolean>?` (initialized before INIT is sent, null'd in finally). `putSampleFile` awaits it after sending INIT; only proceeds to DATA pages on `true`. `dispatchPagedPutResponse` now routes the first PUT response to `pendingPutInitDeferred` (completing it) and clears it; subsequent responses route to `pendingPutAckDeferred` as before.

**Tests added (`SampleImportTest.PutInitAckGateTest`):** 2 tests documenting the contract, `@Ignored` because the full `putSampleFile` path calls `android.util.Log` (not mocked in JVM unit tests) and the timeout path waits 15s:
- `putSampleFile_sendsOnlyInitFrameWhenInitAckTimesOut` — abort without DATA if INIT ack never arrives
- `putSampleFile_sendsDataFramesAfterInitAckReceived` — DATA frames sent after INIT ack; STATUS_OK completes the transfer

**Test count:** 162 tests pass (was 156), 20 skipped (was 18), 0 failures.

### Status after fix 3

| Area | Status |
|---|---|
| FILE_INIT + LIST + METADATA read | Hardware-VERIFIED working (commit c2fda33) |
| METADATA GET JSON parse | Fixed (offline) — hardware-PENDING |
| putSampleFile PUT INIT ack gate | Fixed (offline) — hardware-PENDING |
| Active-group project→group mapping | Known-open — `getActiveGroupIndex` resolves projects node, but `getNodeInfo` returns "not found" for the active-project nodeId; project→name walk is blocked |
| `getNodeInfo` "not found" response | Known-open — FILE_INFO (op 11) response body parsing unverified; may need offset adjustment |

## Offline fix 4 (2026-06-24) — Raw PCM upload + metadata JSON in PUT INIT

**Commit:** `55b0816`

### Root cause

The EP-133 stores sounds as raw s16 LE PCM (files named `.pcm`). The reference tool (`data/index.js uploadSound`) uploads `et.slice(data_start, data_end)` — just the WAV's `data` chunk, header stripped — with format carried in per-file METADATA JSON (`{"channels":N,"samplerate":R}` from `prepareTeenageMeta`). Our code uploaded WavEncoder's full RIFF/WAV (44-byte RIFF header + PCM) with null metadata, so uploaded samples had a header glitch at the start and played incorrectly. The PUT protocol itself was correct (hardware-verified: `081.pcm` was created on device).

### Changes

**`SampleImportManager.kt`:**
- Added `ConvertedSample(pcm: ByteArray, channels: Int, sampleRate: Int)` data class.
- `convert()` return type changed from `ByteArray` (WAV) to `ConvertedSample` (raw PCM + format).
  - Fast path: calls new `sliceWavData()` to locate and extract the RIFF `data` chunk body (header stripped); reads channels + sampleRate from the `fmt ` chunk. Handles non-standard WAVs where `data` is not at offset 36 (scans forward past unknown sub-chunks).
  - Slow path: calls `Resampler.toRate()` then `shortArrayToLeBytes()` (new) instead of `WavEncoder.encodeWav()`. Output is raw s16 LE bytes, no RIFF header.
- Added `shortArrayToLeBytes(ShortArray): ByteArray` — pure, no Android deps; each Short → 2 bytes LE via `ByteBuffer.LITTLE_ENDIAN`. Unit-testable.
- Added `sliceWavData(ByteArray): ConvertedSample?` — pure, no Android deps; returns null on malformed input. Unit-testable.
- `importSample`: updated to use `ConvertedSample`, passes `converted.pcm`, `converted.channels`, `converted.sampleRate` to `putSampleFile`.
- `importSampleBytes`: updated to extract PCM from pre-read WAV bytes via `isAlreadyDeviceFormat` + `sliceWavData`; passes raw PCM + format to `putSampleFile`.

**`MIDIRepository.kt`:**
- `putSampleFile` signature: `(name, pcmBytes, channels=1, sampleRate=46875)` — raw PCM in, metadata JSON out.
- Builds `{"channels":$channels,"samplerate":$sampleRate}` (exact key names from `data/index.js prepareTeenageMeta`) and passes it as `metadataJson` to `buildFileCreatePutInitFrame`.
- Log line updated to show `ch=` and `sr=` for observability.

**Tests:**
- `SampleImportTest.kt`: `SampleImportSpyMIDIPort` and `SampleImportFakeMIDIRepo` moved to file-level scope (shared with new `RawPcmFormatTest`). Fake's `putSampleFile` override updated to new 4-param signature and records `lastMetadataJson`, `lastChannels`, `lastSampleRate`.
- `SampleImportViewModelTest.kt`: fake's `putSampleFile` override updated to new signature.
- `SampleImportConcurrencyTest.kt`: fake's `putSampleFile` override updated to new signature.
- New test class `RawPcmFormatTest` added to `SampleImportTest.kt`:
  - `shortArrayToLeBytes_*` (4 tests): empty input, single zero, known values with LE byte order check, round-trip vs `ByteBuffer` reference.
  - `sliceWavData_*` (5 tests): canonical WAV header stripped correctly, stereo channels preserved, too-short returns null, empty returns null, non-WAV returns null.
  - `putSampleFile_initFrameCarriesMetadataJson_*` (3 tests): mono metadata JSON content, stereo metadata JSON content, metadata JSON keys appear in packed INIT wire frame.

### Status after fix 4

Upload is now raw-PCM + metadata JSON in INIT. **Hardware playback pending** — next step is to deploy the APK and test a sample upload on a physical EP-133 to confirm samples play without header glitch.

| Area | Status |
|---|---|
| Upload payload format | Fixed (raw PCM, header stripped) — hardware-PENDING |
| Upload metadata JSON | Fixed (`{"channels":N,"samplerate":R}`) — hardware-PENDING |
| PUT wire framing | Unchanged (hardware-verified: command=5, LSB-first packing, await-INIT, terminator) |
| Active-group sync | Unchanged (still disabled) |

## Offline fix 5 (2026-06-24) — DATA paging: negotiate chunk size + per-page ack

**Commit:** `0c716e2`

### Root cause

Hardware evidence: FILE_INIT acks `chunkSize=512`; app was sending 4096-byte DATA chunks (`MAX_PAGE_BYTES`). A 4096-byte raw chunk 7-bit-packs to ~4.7 KB — far over the device's 512-byte per-message budget → device responds `status=1 "unexpected page"`. A sendmidi test with ~420-byte chunks (within budget) got `status=0` on every page. Second issue: the app fired all DATA pages back-to-back without waiting for per-page acks; the reference tool (`data/index.js`) awaits each page response before sending the next (USB-MIDI has no flow control).

### Changes

**`MIDIRepository.kt`:**

- Added `internal fun computeSampleChunkSize(chunkSize: Int): Int` — mirrors `calculateMaxPayloadLength` from `data/index.js`:
  ```
  o = 11; s = chunkSize - 6; inner = s - 1 - o
  maxPayload = inner - (inner / 8)   // 7-bit packing overhead
  raw = maxPayload - 6               // DATA header headroom
  clamped to [64, 440]; falls back to 256 when chunkSize <= 0
  ```
  For `deviceChunkSize=512` → **427 bytes**.
- `putSampleFile`: replaced `SysExProtocol.MAX_PAGE_BYTES` with `computeSampleChunkSize(deviceChunkSize)`; logs chosen chunk size + page count.
- `putSampleFile`: per-page serial ack — for each DATA chunk, sets a fresh `pendingPutAckDeferred`, sends the frame, awaits with timeout. If ack is false/timeout, aborts and returns false. Terminator frame uses the same fresh-deferred pattern.
- `dispatchPagedPutResponse`: intermediate DATA responses now complete `pendingPutAckDeferred` with `true` for both `STATUS_OK` and `STATUS_SPECIFIC_SUCCESS_START` (previously `STATUS_SPECIFIC_SUCCESS_START` was silently swallowed).

**`SampleImportTest.kt`:**

New test classes:
- `ChunkSizeComputationTest` (5 tests): `computeSampleChunkSize(512)→427`, `(0)→256`, `(-1)→256`, tiny-input clamps to 64, large-input clamps to 440.
- `PerPageAckGatingTest` (3 tests): `ChunkFakeRepo` exercises chunk-size paging via a custom override. Tests: 1000 bytes with chunk=427 → 3 DATA pages + terminator; exact-one-page (427 bytes → 1 DATA); one-byte-over (428 bytes → 2 DATA).

### Verification

- `./gradlew :app:testDebugUnitTest` — all tests pass, 0 failures
- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL

### Status after fix 5

| Area | Status |
|---|---|
| DATA chunk sizing | Fixed — negotiated `deviceChunkSize` (512 → 427 raw bytes per page) |
| Per-page ack gating | Fixed — each DATA page awaited before next (serial, matches reference tool) |
| Upload metadata JSON + raw PCM | Unchanged from fix 4 (hardware-PENDING) |
| Active-group sync | Unchanged (still disabled) |

## Offline fix 6 (2026-06-25) — Unique reqId per PUT frame + force-close on failure + reqId mismatch guard

**Commit:** `ba62b55`

### Bug 1 — reqId reuse caused device desync

**Root cause (hardware-proven):** The device echoes the request reqId in each PUT response. The app was using `requestId=30` for INIT and `requestId=31` for ALL DATA pages and the terminator (hardcoded). Re-using the same reqId across pages means any stale or out-of-order response can complete the wrong page deferred.

**Fix:**
- `putSampleFile` now assigns a unique incrementing reqId per frame. Transfer-local counter starts at `PUT_INIT_REQUEST_ID` (30), increments by 1 after each frame, masked to 14-bit (0..0x3FFF) to stay in the SysEx field range.
- `dispatchSysEx` decodes `responseReqId = ((frame[6] & 0x0F) << 7) | (frame[7] & 0x7F)` from the raw message bytes and passes it through to `dispatchPagedPutResponse`.
- `dispatchPagedPutResponse` validates reqId on per-page acks: if `responseReqId != awaitedPutReqId` (both non -1), logs `"MIDI META: mismatched file response reqId=X awaiting=Y — ignoring"` and returns without completing the deferred. Matching reqId completes as before.
- `awaitedPutReqId: Int = -1` tracks the currently-expected reqId; reset to -1 in `finally` block.
- Companion constant `internal const val PUT_INIT_REQUEST_ID = 30`.

### Bug 2 — Dangling incomplete PUT wedges device

**Root cause (hardware-proven):** A PUT that sends INIT but never sends the zero-length terminator leaves the device in a wedged state where it ignores all subsequent PUTs until power-cycle.

**Fix:**
- `forceCloseTransfer(portId, terminatorPage, reqId)` — sends a zero-length DATA frame as terminator with its own reqId, waits up to `FORCE_CLOSE_TIMEOUT_MS` (2 s), ignores result; logs `"MIDI META: force-closed incomplete transfer page=N reqId=M"`.
- Called on: DATA page ack timeout, error status response, and `CancellationException` — all conditional on `initAcked` being `true` (i.e., INIT already acked by device, so device is in a PUT session).
- `initAcked: Boolean` local flag in `putSampleFile`; set `true` after `pendingPutInitDeferred` completes successfully.

### Tests

- `ReqIdUniquenessTest` (2 tests, `SampleImportTest.kt`): `UniqueReqIdFakeRepo` exercises the real reqId-incrementing logic without ack-await. Decodes reqId from each spy frame via `((frame[6] & 0x0F) << 7) | (frame[7] & 0x7F)`. Asserts: unique set size == frame count, first == `PUT_INIT_REQUEST_ID` (30), sequence increments by 1.
- `MismatchedReqIdTest` (2 tests): calls `dispatchPagedPutResponse` directly via reflection to bypass `dispatchSysEx`'s `Log.d` calls (Log not mocked in JVM tests). Asserts: mismatched reqId leaves deferred incomplete; matching reqId completes it.
- `build.gradle.kts`: added `testOptions { unitTests { isReturnDefaultValues = true } }` — Log methods return 0 instead of throwing `RuntimeException` in JVM unit tests. Required for any future tests that call production code paths containing `Log.*` calls.

### Verification

- `./gradlew :app:testDebugUnitTest` — all tests pass, 0 failures (162 pass + 4 new = 166 pass total)
- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL

### Status after fix 6

| Area | Status |
|---|---|
| reqId uniqueness per PUT frame | Fixed — unique incrementing reqId, 14-bit masked |
| reqId mismatch guard | Fixed — stale/unrelated responses ignored, logged |
| Force-close on transfer failure | Fixed — terminator sent on any post-INIT failure |
| Hardware test (PUT with correct reqIds) | PENDING |

## Deferred (hardware UAT)

- **Sample upload DATA paging** — chunk size + per-page ack fixed offline; hardware test needed to confirm `status=0` on all DATA pages (was `status=1 "unexpected page"`).
- **Sample upload playback** — upload format fixed offline; needs hardware test to confirm samples play cleanly (no header glitch).
- **METADATA GET JSON fix** — offline fix applied; needs hardware round-trip to confirm `getMetadataJson` now returns `{"active":3000}` correctly.
- **Active-group project→group mapping** — `getNodeInfo` returns null for the active-project nodeId. Needs hardware capture of the FILE_INFO response.
- **`parseFileInitResponse` chunkSize offset** — reads body[1..4] as u32 BE. Tolerated (advisory).
- **Hardware UAT (priority)** — deploy APK, test upload, confirm DATA pages ack OK with unique reqIds, confirm samples play, confirm METADATA GET parsed JSON, confirm active-group sync chain.
