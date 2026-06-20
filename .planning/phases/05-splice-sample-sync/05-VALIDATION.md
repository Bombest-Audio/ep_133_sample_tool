---
phase: 5
slug: splice-sample-sync
status: planned
nyquist_compliant: true
wave_0_complete: false
created: 2026-06-20
---

# Phase 5 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Seeded from 05-RESEARCH.md "Validation Architecture". Android Sample Import.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4.13.2 + `kotlinx-coroutines-test` 1.7.3 (unit); Compose UI test (instrumented) |
| **Config file** | None — standard Android unit test runner |
| **Quick run command** | `cd AndroidApp && ./gradlew :app:testDebugUnitTest` |
| **Full suite command** | `cd AndroidApp && ./gradlew :app:testDebugUnitTest :app:lintDebug` |
| **Estimated runtime** | ~45 seconds (quick unit) |

---

## Sampling Rate

- **After every task commit:** `cd AndroidApp && ./gradlew :app:testDebugUnitTest`
- **After every plan wave:** `cd AndroidApp && ./gradlew :app:testDebugUnitTest :app:lintDebug`
- **Before `/gsd:verify-work`:** full unit suite green + manual UAT on a physical EP-133 (import a 44100 Hz file → it appears in `/sounds` and plays at correct pitch)
- **Max feedback latency:** 45 seconds (unit)

---

## Per-Requirement Verification Map

| Requirement | Behavior | Wave | Test Type | Automated Command | File Exists | Status |
|-------------|----------|------|-----------|-------------------|-------------|--------|
| SAMPLE-02 | WAV encoder emits correct RIFF header (s16/46875/ch) for known PCM | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.WavEncoderTest"` | ❌ W0 | ⬜ pending |
| SAMPLE-02 | Resampler: 44100→46875 length + endpoint samples correct | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.ResamplerTest"` | ❌ W0 | ⬜ pending |
| SAMPLE-02 | Pass-through: already-46875/s16 WAV returned byte-identical | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.WavEncoderTest"` | ❌ W0 | ⬜ pending |
| SAMPLE-03 | `putSampleFile` builds INIT + paged DATA frames for a multi-KB WAV to /sounds | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.SampleImportTest"` | ❌ W0 | ⬜ pending |
| SAMPLE-03 | 7-bit pack/unpack round-trips the WAV payload | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.SysExProtocolTest"` | ⚠️ partial (Phase 2) | ⬜ pending |
| SAMPLE-01/04 | ViewModel maps picked URIs → staged list + progress states | 0 | unit | `./gradlew :app:testDebugUnitTest --tests "*.SampleImportViewModelTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `WavEncoderTest.kt` — RIFF header bytes + pass-through identity
- [ ] `ResamplerTest.kt` — resample length/values; no-op when already 46875
- [ ] `SampleImportTest.kt` — paged PUT frame building for `/sounds`
- [ ] `SampleImportViewModelTest.kt` — URI → staged-list + progress mapping

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Real decode of a WAV/MP3 source | SAMPLE-02 | `MediaCodec` decode needs device/instrumentation | Import a real 44.1 kHz WAV + an MP3; confirm both decode + convert without error |
| On-device `/sounds` upload | SAMPLE-03 | Needs a connected EP-133 | Import a sample; confirm it appears in `/sounds` and on a pad |
| Playback-pitch correctness | SAMPLE-02 | Audible check on hardware | Play the imported sample; confirm correct pitch/length (no resample artifact) |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 45s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** planned 2026-06-20 — every task carries an <automated> verify or a Wave 0 dependency; Wave 0 (05-01) creates WavEncoderTest/ResamplerTest/SampleImportTest/SampleImportViewModelTest.
