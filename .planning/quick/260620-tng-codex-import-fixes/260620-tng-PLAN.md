---
quick_id: 260620-tng
slug: codex-import-fixes
title: Fix Codex adversarial-review findings in Phase 5 sample import
created: 2026-06-21
---

# Quick Task 260620-tng: Fix Codex adversarial-review findings in Phase 5 sample import

Two correctness fixes in the Android sample-import path, found by the Codex adversarial
review of Phase 5. Both let the import path report success for an upload that never
addressed a named file and was never confirmed by the device.

## Background (verified facts)

- `MIDIRepository.putSampleFile(name, wavBytes)` currently emits `buildFilePutInitFrame(deviceId, nodeId=0, size)` + paged `buildFilePutDataFrame()`. The sanitized `name` is transmitted in **no** frame, so the device cannot deterministically create `/sounds/<name>`.
- `SysExProtocol.buildFilePutFrame(deviceId, path, data, chunkIndex, requestId)` exists and emits `[TE_SYSEX_FILE(5), TE_SYSEX_FILE_PUT(2), <path ASCII>, chunkHi, chunkLo, <data>]`. `BackupManager.restore` uses exactly this for proven `/sounds/$name` writes (Landmine 4 default).
- `SampleImportManager.importSample` and `importSampleBytes` both emit `Done` when `putSampleFile` returns `false` (timeout / no STATUS_OK), masking real upload failures.
- `SampleImportViewModelTest.SampleImportFakeRepo` does NOT override `putSampleFile`, so the connected test hits the real method against a spy that never sends STATUS_OK → returns `false`. The current `false→Done` behavior is the only reason that test passes.

## Task 1 — putSampleFile transmits the destination name (Codex finding #1)

**Files:** `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt`

- Replace the `buildFilePutInitFrame(deviceId, 0, size)` + paged `buildFilePutDataFrame()` loop in `putSampleFile` with the path-string framing `BackupManager.restore` proved: build `val path = "/sounds/$name"`, chunk `wavBytes` over `SysExProtocol.MAX_PAGE_BYTES`, and for each chunk emit `SysExProtocol.buildFilePutFrame(currentDeviceId, path, chunk, chunkIndex, requestId = 30 + (chunkIndex and 0xFF))`. Increment `chunkIndex` per frame. The full `/sounds/$name` path rides on every frame, and large WAVs still page.
- Keep the existing ack flow unchanged: `pendingPutAckDeferred = ack` before sending, `withTimeoutOrNull(PUT_ACK_TIMEOUT_MS) { ack.await() } ?: false` after, `transferInFlight` guards, and the `finally` cleanup. Path frames are still `TE_SYSEX_FILE_PUT`, so the dispatcher's PUT-ack routing is unchanged.
- Handle the empty-bytes edge: if `wavBytes` is empty, do not enter an infinite loop — either send a single zero-length path frame or return early; pick whichever keeps the ack contract sane (a real WAV is always multi-KB, but be defensive).
- Update the doc comment: the "name never sent" concern is resolved (the name is now in every frame). Narrow the remaining `HARDWARE-VERIFY (UAT-SOUNDS-PUT)` note to: does multi-chunk path-string PUT actually create the file and ack on real hardware.
- **Do NOT touch any other method in MIDIRepository.** Changes must be additive/local to `putSampleFile`. Phase 4 paged GET/PUT, node listing, and `putProjectArchive` must remain byte-for-byte intact.

**Verify:** `git diff` shows changes confined to `putSampleFile` + its doc comment.

## Task 2 — Import fails closed on no-ack (Codex finding #2)

**Files:** `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SampleImportManager.kt`

- In BOTH `importSample` and `importSampleBytes`, when `putSampleFile` returns `false`, emit `SampleImportProgress.Error("Upload not confirmed by EP-133 (no STATUS_OK) — reconnect and retry $safeName")` and return. Only emit `Progress(1,1)` + `Done(safeName)` when `ok == true`. Remove the "treat false as Done" comment blocks.

**Verify:** no code path emits `Done` after a `false` return from `putSampleFile`.

## Task 3 — Update tests to the corrected contracts + UAT note

**Files:** `AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportViewModelTest.kt`, `AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportTest.kt`, `.planning/phases/05-splice-sample-sync/05-HUMAN-UAT.md`

- **SampleImportViewModelTest.kt:** Add an override of `putSampleFile` to `SampleImportFakeRepo` returning `connected` (true when connected, false otherwise), e.g. `override suspend fun putSampleFile(name: String, wavBytes: ByteArray): Boolean = _state.value.connected`. This makes `importStagedBytes_whenConnected_advancesToDone` reach `Done` by exercising a real success ack instead of the no-ack path, and keeps the disconnected test on the Error path.
- **SampleImportTest.kt:** `putSampleFile` now emits path-string frames, not a separate INIT + paged DATA. Update both tests:
  - Assert every emitted frame's unpacked payload starts `[TE_SYSEX_FILE (5), TE_SYSEX_FILE_PUT (2)]`.
  - Assert the ASCII bytes of `/sounds/kick.wav` appear in the frames (prove the destination name is transmitted — Codex's explicit ask).
  - Assert paging still occurs: a 10,000-byte payload → `ceil(10000 / MAX_PAGE_BYTES)` = 3 frames, and `> 1`.
  - Fix the chunk-reassembly test: data now starts after `[5, 2]` + `pathBytes` + `chunkIndex(2)` in each frame's unpacked payload. Compute the header offset from the path length (`"/sounds/kick.wav".length`) and reassemble the chunks to equal the original `wavBytes`.
- **05-HUMAN-UAT.md:** Under `UAT-SOUNDS-PUT`, append a short note that the default is now path-string framing (`/sounds/$name` on every frame) and that `SampleImportManager` now fails closed (emits Error) when the device sends no STATUS_OK.

**Verify after all tasks:** `./gradlew :app:testDebugUnitTest` (full suite green) and `./gradlew :app:assembleDebug` both BUILD SUCCESSFUL. No test file other than the two named is modified. Phase 4 code in MIDIRepository/SysExProtocol unchanged.
