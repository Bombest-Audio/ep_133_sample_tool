---
phase: 05-splice-sample-sync
plan: "02"
subsystem: android-audio
tags: [wav-encoder, resampler, tdd-green, sample-import, pure-jvm, riff, linear-interp, android]
dependency_graph:
  requires: [05-splice-sample-sync-01]
  provides: [SAMPLE-02-pure]
  affects: [05-03, 05-04]
tech_stack:
  added: []
  patterns:
    - RIFF/PCM-16 WAV encoding via ByteBuffer LITTLE_ENDIAN (pure JVM, no Android imports)
    - Defensive RIFF header sniffing with try/catch returning false on malformed input
    - Per-channel linear interpolation resampler with fast-path identity at device rate
    - TDD GREEN — test contracts locked by Wave 0 (Plan 01); production code makes them pass
key_files:
  created:
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/WavEncoder.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/Resampler.kt
  modified: []
decisions:
  - "encodeWav accepts any sampleRate parameter (not require==46875) — Test C creates a 44100 WAV as a fixture for isAlreadyDeviceFormat; the plan's action text conflict was resolved in favor of the test contract (tests are the authority)"
  - "isAlreadyDeviceFormat wraps all parsing in try/catch returning false — covers all malformed-buffer paths including BufferUnderflowException without verbose per-field guards"
  - "Resampler identity path returns the exact input array (referential), not a copy — Test D calls assertArrayEquals so content equality works, but referential return also avoids an unnecessary allocation"
  - "Linear interpolation chosen over sinc: EP-133 effective audio bandwidth ~8-12 kHz; sinc overkill per 05-RESEARCH; documented in KDoc"
  - "Test files (WavEncoderTest.kt, ResamplerTest.kt) staged in this worktree alongside production files since Wave 0 commits live on a sibling branch and are not yet merged"
metrics:
  duration: "~20 minutes"
  completed: "2026-06-21"
  tasks_completed: 2
  tasks_total: 2
  files_changed: 4
---

# Phase 05 Plan 02: WavEncoder + Resampler (Wave 1 GREEN) Summary

**One-liner:** Pure JVM RIFF/Int16-LE WAV encoder locked to 46875 Hz + per-channel linear resampler with identity fast-path — WavEncoderTest A/B/C and ResamplerTest D/E/F all GREEN.

## Tasks Completed

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | WavEncoder — RIFF/Int16 LE writer + pass-through sniffer | cd74f35 | WavEncoder.kt, WavEncoderTest.kt |
| 2 | Resampler — per-channel linear interp to 46875, no-op at rate | b93ab6d | Resampler.kt, ResamplerTest.kt |

## What Was Built

### WavEncoder.kt (`domain/audio/WavEncoder`)

`object WavEncoder` with two functions:

**`encodeWav(pcm: ShortArray, sampleRate: Int = 46875, channels: Int = 1): ByteArray`**

Builds a 44-byte RIFF/fmt/data header + PCM body via `java.nio.ByteBuffer` with `ByteOrder.LITTLE_ENDIAN`. All fields match the layout in the plan's `<interfaces>` block exactly: `RIFF` chunk descriptor, `WAVE` marker, `fmt ` sub-chunk (subchunk1Size=16, audioFormat=1, channels, sampleRate, byteRate, blockAlign, bitsPerSample=16), `data` sub-chunk with dataSize, then `putShort` loop over the input array. Channel count is passed through unchanged — no downmixing.

Note: the parameter accepts any `sampleRate` value (not hard-guarded at 46875). This is intentional: Test C creates a 44100 Hz WAV via `encodeWav` to use as a fixture for `isAlreadyDeviceFormat`. The Landmine 2 guard lives in `isAlreadyDeviceFormat` (which rejects non-46875 rates) and in `WavEncoderTest` itself (which hard-codes the literal 46875 in assertions).

**`isAlreadyDeviceFormat(wavBytes: ByteArray): Boolean`**

Defensive RIFF parser: returns `false` immediately for buffers shorter than 44 bytes. Wraps all sequential `ByteBuffer` reads in a `try/catch` that returns `false` on any exception (covers `BufferUnderflowException` from truncated data). Verifies `RIFF`/`WAVE`/`fmt ` four-CC markers, then checks `audioFormat==1 && bitsPerSample==16 && sampleRate==46875 && channels in 1..2`. Satisfies T-05-02-01 (no OOB reads, no throws on malformed input).

### Resampler.kt (`domain/audio/Resampler`)

`object Resampler` with one function:

**`toRate(pcm: ShortArray, srcRate: Int, dstRate: Int = 46875, channels: Int = 1): ShortArray`**

Fast-path identity: if `srcRate == dstRate`, returns `pcm` unchanged (referential equality — no copy, no computation). This guards Landmine 2: zero resample artifact when source is already at the device rate.

For all other cases: deinterleaves `pcm` by channel count, computes `dstFrames = round(srcFrames * dstRate / srcRate)`, then per channel performs linear interpolation — `srcPos = i * srcRate / dstRate`, floor index `lo`, fractional `frac`, interpolated value clamped to Short range. Re-interleaves per-channel results into a single output array.

KDoc documents the linear-interp algorithm choice (audibly fine for EP-133's ~8-12 kHz effective bandwidth; sinc is overkill).

## Test Results

| Test | Status | What it proved |
|------|--------|----------------|
| WavEncoderTest A — `riffHeader_carries46875_s16_channels` | GREEN | Full RIFF header byte assertions at 46875/s16/mono |
| WavEncoderTest B — `stereo_channelsAndByteRate` | GREEN | channels=2, byteRate=46875×4, blockAlign=4 |
| WavEncoderTest C — `passThrough_identity_whenAlready46875` | GREEN | `isAlreadyDeviceFormat` true for 46875, false for 44100 |
| ResamplerTest D — `noOp_whenSrcEqualsDst` | GREEN | identity return with no resample artifact at 46875 |
| ResamplerTest E — `length_44100_to_46875` | GREEN | 44100-frame input → 46875±1 output frames |
| ResamplerTest F — `downsample_neverUpsamplesBeyond46875` | GREEN | 48000→46875 output shorter than input |

## Deviations from Plan

### Auto-adjusted — Test contract takes precedence over action text

**Found during:** Task 1 implementation

**Issue:** The plan's `<action>` text says `require(sampleRate == DEVICE_SAMPLE_RATE)` in `encodeWav`, but `WavEncoderTest C` calls `WavEncoder.encodeWav(samplePcm, sampleRate = 44100, channels = 1)` to build a 44100 Hz fixture. A hard `require()` would throw and fail Test C.

**Resolution:** Removed the `require()` guard from `encodeWav`. The test file is the locked contract (Wave 0 decision); the action text is advisory. The Landmine 2 guard is enforced through `isAlreadyDeviceFormat` rejecting non-46875 rates, and through `WavEncoderTest` asserting the literal `46875` in header assertions.

**Rule:** Rule 1 (Bug) — plan action text conflicted with the locked test contract.

## Known Stubs

None — all functions are fully implemented and tested.

## Deferred Items

**Pre-existing lint error (out of scope):**

`AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt:157` — `MutableImplicitPendingIntent`: `PendingIntent.getBroadcast` uses `FLAG_MUTABLE` with an implicit intent (Android 14+ violation). This error pre-existed before Wave 1 and was not introduced by `WavEncoder.kt` or `Resampler.kt`. The `lintDebug` task fails because of it. Fix: change to `FLAG_IMMUTABLE` or make the intent explicit — should be addressed in a subsequent wave or chore task.

## Threat Surface Scan

No new threat surface introduced:
- No network endpoints
- No auth paths
- No file I/O (pure in-memory transforms)
- No schema changes
- `isAlreadyDeviceFormat` is a pure reader of bytes passed in by the caller; the trust boundary was already declared in `<threat_model>` as T-05-02-01, mitigated by the defensive parsing implemented here

## Self-Check: PASSED

Files created:
- `/Users/thomasphillips/workspace/ep_133_sample_tool/.claude/worktrees/agent-a7da4ee08e9fed85b/AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/WavEncoder.kt` — exists
- `/Users/thomasphillips/workspace/ep_133_sample_tool/.claude/worktrees/agent-a7da4ee08e9fed85b/AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/Resampler.kt` — exists

Commits exist:
- cd74f35 (Task 1: WavEncoder GREEN) — verified
- b93ab6d (Task 2: Resampler GREEN) — verified

Tests: `./gradlew :app:testDebugUnitTest --tests "*.WavEncoderTest" --tests "*.ResamplerTest"` — BUILD SUCCESSFUL
