---
phase: 05-splice-sample-sync
plan: "01"
subsystem: android-test
tags: [tdd, red-scaffold, wav-encoder, resampler, sample-import, paged-put, android]
dependency_graph:
  requires: []
  provides: [SAMPLE-01, SAMPLE-02, SAMPLE-03, SAMPLE-04]
  affects: [05-02, 05-03, 05-04]
tech_stack:
  added: []
  patterns:
    - RED test scaffold (Wave 0 TDD gate) — locks production API shapes before implementation
    - SysEx paged PUT frame assertion via SpyMIDIPort (mirrors ProjectProtocolTest pattern)
    - ViewModel state-machine testing via FakeMIDIRepo + StandardTestDispatcher (mirrors ProjectsViewModelTest pattern)
key_files:
  created:
    - AndroidApp/app/src/test/java/com/ep133/sampletool/WavEncoderTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ResamplerTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportViewModelTest.kt
  modified: []
decisions:
  - "SampleImportViewModelTest uses importStagedBytes(name, bytes) as the testability seam instead of onFilesPicked(uris) — SAF URI reads are hardware/instrumentation-only; the seam accepts pre-read bytes"
  - "SampleImportTest uses SampleImportFakeMIDIRepo that sets protected _deviceState directly — this is the only way to give putSampleFile an outputPortId without hardware; same pattern as putProjectArchive"
  - "WavEncoderTest hard-codes literal 46875 and 16 in every assertion — intentional Landmine 2/3 regression guards; a format change breaks the test loudly"
  - "Test doubles renamed (SampleImportSpyPort, SampleImportFakeRepo) to avoid top-level redeclaration clashes with ProjectsViewModelTest (same shared test source set — see STATE.md Phase 4 note)"
metrics:
  duration: "~15 minutes"
  completed: "2026-06-20"
  tasks_completed: 2
  tasks_total: 2
  files_changed: 4
---

# Phase 05 Plan 01: Wave 0 RED Scaffold Summary

**One-liner:** Four JUnit 4 RED test files locking WavEncoder/Resampler/putSampleFile/SampleImportViewModel API shapes as format-exact regression guards before Wave 1-3 implementation.

## Tasks Completed

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | WavEncoderTest + ResamplerTest (RED) | 3d9e686 | WavEncoderTest.kt, ResamplerTest.kt |
| 2 | SampleImportTest + SampleImportViewModelTest (RED) | 090ab79 | SampleImportTest.kt, SampleImportViewModelTest.kt |

## What Was Built

### WavEncoderTest.kt
Three tests covering the `WavEncoder` public contract:
- **Test A** (`riffHeader_carries46875_s16_channels`): RIFF header byte assertions for a known `ShortArray`. Hard-codes `46875` (Landmine 2 guard) and `16` (Landmine 3 guard) in explicit `LITTLE_ENDIAN` field reads. Total length, four-CC markers, audioFormat=1, channels, sampleRate, bitsPerSample, and dataSize all asserted.
- **Test B** (`stereo_channelsAndByteRate`): channels=2, byteRate=46875×2×2, blockAlign=4.
- **Test C** (`passThrough_identity_whenAlready46875`): `isAlreadyDeviceFormat` returns `true` for a 46875/s16/mono WAV and `false` for a 44100 WAV (the common Splice export rate).

### ResamplerTest.kt
Three tests covering the `Resampler` public contract:
- **Test D** (`noOp_whenSrcEqualsDst`): `toRate(input, srcRate=46875, dstRate=46875)` returns `contentEquals` input — no resample artifact at the device rate (Landmine 2 guard).
- **Test E** (`length_44100_to_46875`): 44100-sample input → output length within ±1 of `round(44100 × 46875/44100) = 46875`.
- **Test F** (`downsample_neverUpsamplesBeyond46875`): 48000 → 46875 length ratio + asserts output shorter than input (device never upsamples beyond 46875, per 05-RESEARCH A2).

### SampleImportTest.kt
Three tests covering `MIDIRepository.putSampleFile` paged transfer:
- **Landmine 5 guard** (`putSampleFile_sendsInitPlusPagedDataFrames`): 10,000-byte payload produces exactly 1 INIT frame + `ceil(10000/4096) = 3` DATA frames via a `SampleImportSpyPort`. Asserts `TE_SYSEX_FILE` + `TE_SYSEX_FILE_PUT` in both INIT and DATA payloads.
- **Payload integrity** (`putSampleFile_chunkPayloadsSurvive7bitPackUnpack`): Concatenating unpacked DATA chunk payloads (stripping the 5-byte frame header) reassembles the original `wavBytes` byte-for-byte — proves no truncation through 7-bit pack/unpack.
- **Disconnected** (`putSampleFile_whenDisconnected_sendsNoFrames`): no frames sent when `outputPortId == null`.

Uses `SampleImportFakeMIDIRepo` subclass that sets the protected `_deviceState` directly (the only way to give `putSampleFile` an `outputPortId` without hardware).

### SampleImportViewModelTest.kt
Four tests covering `SampleImportViewModel` state machine via the `importStagedBytes(name, bytes)` testability seam:
- **Test 1**: `importStagedBytes` adds an entry to `stagedSamples` StateFlow with the correct filename.
- **Test 2** (connected): item advances to `Done` state after successful import.
- **Test 3** (disconnected): item advances to `Error` state + `snackbarMessage` mentions "EP-133" or "connected".
- **Test 4**: three `importStagedBytes` calls produce three entries in order.

## Deviations from Plan

None — plan executed exactly as written with one minor implementation detail noted below.

**Design note (not a deviation):** The `SampleImportViewModelTest` uses `importStagedBytes(name, bytes)` as the test entry point (a VM testability seam for pre-read bytes) rather than `onFilesPicked(uris)`. This is per the plan's action text ("design the VM test against a content-free seam") and 05-VALIDATION (SAF URI read is manual-only). The `StagedSample` items expose `isDone()`, `isError()`, and `name` — these are part of the locked contract Wave 3 must satisfy.

## RED State Verification

`./gradlew :app:compileDebugUnitTestKotlin` fails with exactly these unresolved references — no other compile errors:

| Symbol | File | Wave that fixes it |
|--------|------|--------------------|
| `WavEncoder`, `audio` package | WavEncoderTest.kt | Wave 1 |
| `Resampler`, `audio` package | ResamplerTest.kt | Wave 1 |
| `putSampleFile` | SampleImportTest.kt | Wave 2 |
| `SampleImportManager` | SampleImportTest.kt, SampleImportViewModelTest.kt | Wave 2 |
| `SampleImportViewModel`, `import` package | SampleImportViewModelTest.kt | Wave 3 |

## Known Stubs

None — this is a pure test scaffold (Wave 0). No production code was written.

## Threat Flags

None — Wave 0 is test-only code. No new network endpoints, auth paths, file access patterns, or schema changes. The T-05-00-01 threat (test asserting wrong format) is now mitigated by `WavEncoderTest` hard-coding `46875`/`16`.

## Self-Check: PASSED

Files created:
- /Users/thomasphillips/workspace/ep_133_sample_tool/.claude/worktrees/agent-a520b5c05b1a4e72d/AndroidApp/app/src/test/java/com/ep133/sampletool/WavEncoderTest.kt ✓
- /Users/thomasphillips/workspace/ep_133_sample_tool/.claude/worktrees/agent-a520b5c05b1a4e72d/AndroidApp/app/src/test/java/com/ep133/sampletool/ResamplerTest.kt ✓
- /Users/thomasphillips/workspace/ep_133_sample_tool/.claude/worktrees/agent-a520b5c05b1a4e72d/AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportTest.kt ✓
- /Users/thomasphillips/workspace/ep_133_sample_tool/.claude/worktrees/agent-a520b5c05b1a4e72d/AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportViewModelTest.kt ✓

Commits exist:
- 3d9e686 (Task 1: WavEncoderTest + ResamplerTest) ✓
- 090ab79 (Task 2: SampleImportTest + SampleImportViewModelTest) ✓
