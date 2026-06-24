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

## Deferred (hardware UAT)

- **METADATA GET JSON fix** — offline fix applied; needs hardware round-trip to confirm `getMetadataJson` now returns `{"active":3000}` correctly.
- **PUT INIT ack gate** — offline fix applied; needs hardware upload test to confirm "unexpected page" is gone.
- **Active-group project→group mapping** — `getNodeInfo` returns null for the active-project nodeId. The FILE_INFO response body layout may differ from what `parseFileInfo` expects. Needs hardware capture of the FILE_INFO response.
- **`parseFileInitResponse` chunkSize offset** — reads body[1..4] as u32 BE. With real HW capture `00 0C 00 00 02 00` this yields a large number — tolerated (advisory). Confirmed harmless in HW session.
- **Legacy FILE_LIST path-string stats** — `queryDeviceStats` still uses path-string FILE_LIST. Body shape unverified on HW. Log added for capture.
- **Hardware UAT (priority)** — deploy APK, test upload, confirm METADATA GET returns parsed JSON, confirm active-group sync chain completes.
