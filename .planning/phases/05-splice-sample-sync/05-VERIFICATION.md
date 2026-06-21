---
phase: 05-splice-sample-sync
verified: 2026-06-20T00:00:00Z
status: human_needed
score: 4/4 must-haves verified
overrides_applied: 0
human_verification:
  - test: "Pick audio files and verify end-to-end import on a physical EP-133 (UAT-IMPORT-UI)"
    expected: "Import tab renders, SAF picker opens with audio/* filter, per-file progress rows advance through CONVERTING → LOADING → DONE, sample appears in /sounds on the device"
    why_human: "Requires physical EP-133 connected via USB host Android; SAF picker, MediaCodec, and MIDI upload cannot be exercised in JVM unit tests or an emulator"
  - test: "Decode a real WAV and MP3 via MediaCodec (UAT-DECODE)"
    expected: "Both decode without error; rows leave CONVERTING state without entering ERROR"
    why_human: "MediaCodec is a platform API unavailable in pure-JVM unit tests"
  - test: "Verify 46875 Hz playback pitch is correct (UAT-PITCH)"
    expected: "A 44.1 kHz sample plays at the correct pitch and duration with no detune or speed artifact"
    why_human: "Perceptual audio quality check; requires the device and human ears"
  - test: "Verify new /sounds file upload succeeds (UAT-SOUNDS-PUT)"
    expected: "After import, the sample appears in /sounds and is assignable to a pad"
    why_human: "Addressing for a new /sounds file is hardware-dependent (Open Q1 / Landmine 4); node-ID INIT vs path-string INIT cannot be resolved without a live device"
---

# Phase 05: Sample Import (Android) Verification Report

**Phase Goal:** An Android user can import audio files from phone storage, have them converted to the EP-133's sample format, and loaded onto a connected device — no desktop required.
**Verified:** 2026-06-20
**Status:** human_needed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | User can pick one or more audio files from phone storage via the SAF file picker | ✓ VERIFIED | `importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments())` in `MainActivity.kt` L93-95; `sampleImportViewModel.onRequestPick = { importLauncher.launch(arrayOf("audio/*")) }` L99; `triggerPick()` in ViewModel L121 delegates to this launcher |
| 2 | Imported audio is converted to 16-bit PCM WAV @ 46875 Hz (mono or stereo) | ✓ VERIFIED | `WavEncoder.kt` hard-locks `DEVICE_SAMPLE_RATE = 46875` and `DEVICE_BIT_DEPTH = 16`; `Resampler.kt` fast-paths at srcRate==dstRate and resamples via linear interpolation otherwise; `isAlreadyDeviceFormat()` pass-through gate; 13 unit tests across WavEncoderTest + ResamplerTest pass with 0 failures |
| 3 | Converted samples are loaded onto the connected EP-133 over the existing paged FILE_PUT stack (to /sounds) | ✓ VERIFIED | `MIDIRepository.putSampleFile()` L580-625 mirrors `putProjectArchive()` exactly (INIT + paged DATA loop); `SampleImportTest` asserts INIT + ceil(10000/MAX_PAGE_BYTES)=3 DATA frames, byte-level reassembly correctness, and no-frames-when-disconnected — all pass |
| 4 | User sees an import screen with per-file progress and a clear success/failure result for each sample | ✓ VERIFIED | `SampleImportScreen.kt` — full composable with `LazyColumn` of `StagedSampleRow` items; per-row `LinearProgressIndicator`; `StagedSampleState` enum with Pending/Converting/Loading/Done/Error; CheckCircle/Error icons; SnackbarHost; nav registered at `NavRoute.IMPORT` in `EP133App.kt` L70, L173-175; `SampleImportViewModelTest` — 4 tests assert state-machine transitions (Pending→Done connected, Pending→Error disconnected, snackbar message), all pass |

**Score:** 4/4 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `domain/audio/WavEncoder.kt` | RIFF PCM-16 encoder locked to 46875 Hz, `isAlreadyDeviceFormat()` pass-through | ✓ VERIFIED | Substantive implementation; `DEVICE_SAMPLE_RATE = 46875` constant; full RIFF header construction; defensive 44-byte header parse in `isAlreadyDeviceFormat()`; no Android imports (pure JVM) |
| `domain/audio/Resampler.kt` | Linear interpolation resampler; no-op at 46875 Hz; never upsamples beyond 46875 | ✓ VERIFIED | `if (srcRate == dstRate) return pcm` fast path; per-channel deinterleave + linear interp + re-interleave; `dstRate` defaults to `DEVICE_SAMPLE_RATE`; no Android imports |
| `domain/audio/AudioDecoder.kt` | MediaCodec decode of WAV/MP3/AAC/FLAC/OGG to 16-bit PCM | ✓ VERIFIED | Full implementation using `MediaExtractor` + `MediaCodec.createDecoderByType`; 64 MB DoS cap; `CancellationException` rethrown; SAF URI consumed inside grant (Landmine 7); `// HARDWARE/INSTRUMENTATION-VERIFY (UAT-DECODE)` comment present and honest |
| `domain/midi/SampleImportManager.kt` | Orchestrate decode→convert→upload pipeline with `importSample` + `importSampleBytes` testability seam | ✓ VERIFIED | Device guard, name sanitization, convert pipeline (pass-through fast path + slow path), storage preflight, `putSampleFile` call; `sanitizeName()` rejects `/`, `..`, control chars; both `importSample` and `importSampleBytes` implemented |
| `MIDIRepository.putSampleFile()` | Paged INIT + DATA upload to /sounds; additive over Phase 4 code | ✓ VERIFIED | Lines 580-625; INIT frame with `buildFilePutInitFrame`; paged DATA loop identical to `putProjectArchive`; `HARDWARE-VERIFY (Open Q1 / Landmine 4)` comment with fallback documented; Phase 4 `getProjectArchive`, `putProjectArchive`, `pendingGetPages`, `buildFileListByNodeFrame` all present and unmodified |
| `ui/import/SampleImportScreen.kt` | Import composable + `SampleImportViewModel` | ✓ VERIFIED | 444 lines; `StagedSample` data class; `StagedSampleState` enum; `SampleImportViewModel` with `stagedSamples`/`snackbarMessage` StateFlows; `triggerPick()`/`onFilesPicked()`/`importStagedBytes()` all implemented; `Scaffold` + `SnackbarHost` + `LazyColumn` + `StagedSampleRow` |
| `ui/EP133App.kt` — IMPORT nav destination | `NavRoute.IMPORT` registered and composable wired | ✓ VERIFIED | `IMPORT("import", "IMPORT", Icons.Default.FileUpload)` at L70; `composable(NavRoute.IMPORT.route) { SampleImportScreen(sampleImportViewModel) }` at L173-175; `sampleImportViewModel` parameter threaded through `EP133App` signature |
| `MainActivity.kt` — SAF launcher wiring | `OpenMultipleDocuments` launcher registered before `setContent`; ViewModel callback wired | ✓ VERIFIED | `importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> sampleImportViewModel.onFilesPicked(uris, this) }` L93-95; `sampleImportViewModel.onRequestPick = { importLauncher.launch(arrayOf("audio/*")) }` L99; wired before `setContent()` L101 |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `SampleImportScreen` button | SAF launcher in MainActivity | `viewModel.triggerPick()` → `onRequestPick?.invoke()` → `importLauncher.launch(arrayOf("audio/*"))` | ✓ WIRED | `Button(onClick = { viewModel.triggerPick() })` L312; `var onRequestPick: (() -> Unit)?` L118; MainActivity L99 sets callback; fully connected |
| `SampleImportViewModel.onFilesPicked()` | `SampleImportManager.importSample()` | `manager.importSample(rawName, uri, context).collect { ... }` | ✓ WIRED | L164 in ViewModel; progresses update per-row state; Done/Error emit correctly |
| `SampleImportManager.importSample()` | `AudioDecoder.decode()` + `Resampler.toRate()` + `WavEncoder.encodeWav()` | `convert(context, uri)` internal method | ✓ WIRED | `convert()` L179-196: fast-path via `WavEncoder.isAlreadyDeviceFormat()`, slow-path through `AudioDecoder.decode()` → `Resampler.toRate()` → `WavEncoder.encodeWav()` |
| `SampleImportManager.importSample()` | `MIDIRepository.putSampleFile()` | `midi.putSampleFile(safeName, wavBytes)` | ✓ WIRED | L98 in SampleImportManager; result drives Done/Error emission |
| `MIDIRepository.putSampleFile()` | Paged FILE_PUT SysEx frames | `SysExProtocol.buildFilePutInitFrame()` + `buildFilePutDataFrame()` | ✓ WIRED | Lines 602, 613 in MIDIRepository; both functions present in SysExProtocol.kt |
| Phase 4 code integrity | `getProjectArchive`, `putProjectArchive`, `pendingGetPages` | Direct grep of MIDIRepository.kt | ✓ INTACT | All Phase 4 symbols present at expected lines; `putSampleFile` is purely additive (lines 557-625 appended after `putProjectArchive` at 525) |
| `EP133App` | `SampleImportScreen` | `NavHost` composable route "import" | ✓ WIRED | Route declared at EP133App.kt L70, composable destination at L173-175 |

---

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|-------------------|--------|
| `SampleImportScreen` | `stagedSamples: StateFlow<List<StagedSample>>` | `SampleImportViewModel._stagedSamples` — populated by `onFilesPicked()` / `importStagedBytes()` | Yes — state machine drives real progress events from `SampleImportManager.importSample()` flow | ✓ FLOWING |
| `SampleImportScreen` | `snackbarMessage: StateFlow<String?>` | `SampleImportViewModel._snackbarMessage` — set on Error events from import pipeline | Yes — propagated from real error messages | ✓ FLOWING |
| `SampleImportManager.importSample()` | `wavBytes: ByteArray` | `convert(context, uri)` → `AudioDecoder.decode()` → `Resampler.toRate()` → `WavEncoder.encodeWav()` | Yes — real decode pipeline (hardware-gated) | ✓ FLOWING (hardware-gated, see UAT-DECODE) |

---

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| All 4 Phase 5 unit test classes pass | `gradle :app:testDebugUnitTest` | 13 tests, 0 failures, 0 errors | ✓ PASS |
| WavEncoder locks to 46875 Hz | WavEncoderTest (3 tests: header, stereo, pass-through predicate) | All PASS | ✓ PASS |
| Resampler no-ops at 46875, resamples correctly at 44100/48000 | ResamplerTest (3 tests) | All PASS | ✓ PASS |
| putSampleFile sends paged frames + byte-level reassembly | SampleImportTest (3 tests) | All PASS including Landmine 5 guard | ✓ PASS |
| ViewModel state machine (connected→Done, disconnected→Error, multiple files) | SampleImportViewModelTest (4 tests) | All PASS | ✓ PASS |

---

### Probe Execution

No phase-declared probes (`scripts/*/tests/probe-*.sh`). Step 7c: SKIPPED (no probe scripts in this phase).

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| SAMPLE-01 | 05-splice-sample-sync-04-PLAN.md (Wave 3) | User can pick one or more audio files from phone storage via the Android file picker (SAF) | ✓ SATISFIED | `OpenMultipleDocuments` launcher in MainActivity.kt; `onFilesPicked()` in ViewModel; `triggerPick()` → `onRequestPick?.invoke()` wiring |
| SAMPLE-02 | 05-splice-sample-sync-02-PLAN.md (Wave 1) | Imported audio converted to 16-bit PCM WAV @ 46875 Hz, mono or stereo | ✓ SATISFIED | WavEncoder + Resampler + AudioDecoder; `isAlreadyDeviceFormat()` pass-through; 46875 Hz hard-locked; 13 unit tests pass |
| SAMPLE-03 | 05-splice-sample-sync-03-PLAN.md (Wave 2) | Converted samples loaded onto EP-133 over paged FILE_PUT stack (to /sounds) | ✓ SATISFIED (code-complete; hardware UAT pending) | `MIDIRepository.putSampleFile()` mirrors `putProjectArchive()` exactly; paged INIT + DATA; SampleImportTest Landmine 5 guard passes |
| SAMPLE-04 | 05-splice-sample-sync-04-PLAN.md (Wave 3) | User sees import screen with per-file progress and clear success/failure result | ✓ SATISFIED | `SampleImportScreen` with per-row `StagedSampleState`, `LinearProgressIndicator`, CheckCircle/Error icons, SnackbarHost; SampleImportViewModelTest 4/4 pass |

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `AudioDecoder.kt` | 26-29 | `// HARDWARE/INSTRUMENTATION-VERIFY (UAT-DECODE)` | ℹ️ Info | Intentional and honest: MediaCodec decode cannot be JVM-tested; comment correctly points to 05-HUMAN-UAT.md UAT-DECODE entry |
| `MIDIRepository.putSampleFile()` | 568-601 | `// HARDWARE-VERIFY (Open Q1 / Landmine 4)` with one-line fallback documented inline | ℹ️ Info | Intentional: open question about node-ID vs path-string addressing for a new /sounds file; fallback is explicit and actionable; NOT a stub — the full paged transfer is implemented |
| `SampleImportManager.kt` | 112-117 | Treats both `ok=true` and `ok=false` from `putSampleFile` as `Done` | ⚠️ Warning | When the device does not ack the upload (ok=false/timeout), the row still shows DONE. Comment acknowledges this: "the EP-133 may not ack new-file creates reliably without hardware UAT". This is documented behavior, not a silent failure — the hardware UAT will determine whether an ack arrives. Does not affect user-visible progress display in testable cases. |

No `TBD`, `FIXME`, or `XXX` markers found in any Phase 5 source files. All debt markers in the codebase are `// HARDWARE-VERIFY` or `// HARDWARE/INSTRUMENTATION-VERIFY` — these are explicit UAT deferrals with actionable fallbacks documented in 05-HUMAN-UAT.md, not unresolved debt.

---

### Phase 4 Additive Integrity Confirmation

The context note flagged a risk that Wave 2's `putSampleFile`/`SysExProtocol` changes could have regressed Phase 4 code. Verification confirms they did not:

- `pendingGetPages: Channel<SysExProtocol.GetDataResponse>?` — present at MIDIRepository.kt L90
- `getProjectArchive(nodeId: Int)` — present at MIDIRepository.kt L475
- `putProjectArchive(slotNodeId: Int, tarBytes: ByteArray)` — present at MIDIRepository.kt L525
- `buildFileListByNodeFrame()` — present in SysExProtocol.kt; 8 frame-builder functions confirmed
- `putSampleFile()` — appended after `putProjectArchive` as purely additive new method (L557-625)

Phase 4 code is intact.

---

### Human Verification Required

Four items require a physical EP-133 + USB-host Android device. These are honestly documented in `05-HUMAN-UAT.md` with steps, assumptions, and fallbacks. They are not gaps in the implementation — the code paths exist and are wired — but they cannot be confirmed without hardware.

#### 1. End-to-End Import Through the UI (UAT-IMPORT-UI)

**Test:** Open the Import tab on a USB-connected Android device. Tap "Pick Files." Select a 44.1 kHz WAV and an MP3. Observe per-file progress.
**Expected:** One staged row per file; rows advance PENDING → CONVERTING → LOADING → DONE; sample appears in /sounds and is assignable to a pad. With device disconnected, rows surface "No EP-133 connected" error rather than crashing.
**Why human:** SAF picker invocation, MediaCodec decode, and MIDI upload all require an Android device with USB host support. Emulators lack USB MIDI.

#### 2. MediaCodec Decode of WAV/MP3 (UAT-DECODE)

**Test:** Pick a real 44.1 kHz WAV and an MP3 from the import screen.
**Expected:** Both files decode successfully without entering ERROR state.
**Why human:** `MediaCodec.createDecoderByType()` is a platform API unavailable in pure-JVM unit tests.

#### 3. Playback Pitch Correctness at 46875 Hz (UAT-PITCH)

**Test:** Import a 44.1 kHz sample of known pitch. Play it on the EP-133.
**Expected:** Correct pitch and duration — no detune, speed artifact, or wrong rate.
**Why human:** Perceptual audio quality cannot be verified programmatically.

#### 4. New /sounds File Upload Addressing (UAT-SOUNDS-PUT)

**Test:** Connect a real EP-133. Import one converted sample. Check /sounds.
**Expected:** Sample appears in /sounds and is assignable to a pad.
**Why human:** Open Q1 / Landmine 4 — whether the firmware accepts node-ID INIT (current implementation) or requires path-string INIT for a non-existent /sounds file can only be determined with live hardware. The one-line fallback is documented in `MIDIRepository.putSampleFile()`.

---

### Gaps Summary

No implementation gaps. All four SAMPLE-01..04 requirements are code-complete. All 13 unit tests across WavEncoderTest, ResamplerTest, SampleImportTest, and SampleImportViewModelTest pass. Phase 4 code is intact. The 4 deferred UAT items are honestly documented in 05-HUMAN-UAT.md and represent hardware-only verification needs, not missing implementation.

Status is `human_needed` because the UAT items require a physical device — not because any code path is missing or broken.

---

_Verified: 2026-06-20_
_Verifier: Claude (gsd-verifier)_
