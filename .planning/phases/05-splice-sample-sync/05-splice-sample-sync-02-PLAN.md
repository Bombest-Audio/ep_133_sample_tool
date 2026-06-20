---
phase: 05-splice-sample-sync
plan: 02
type: execute
wave: 1
depends_on: ["05-splice-sample-sync-01"]
files_modified:
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/WavEncoder.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/Resampler.kt
autonomous: true
requirements: [SAMPLE-02]
must_haves:
  truths:
    - "WavEncoder.encodeWav produces a valid RIFF/PCM-16 little-endian WAV at 46875 Hz with the input channel count preserved"
    - "WavEncoder.isAlreadyDeviceFormat returns true only for PCM/16-bit/46875/(1 or 2)ch WAV bytes (the pass-through fast path)"
    - "Resampler.toRate returns the input unchanged when srcRate==46875 and linearly resamples per channel to 46875 otherwise, never upsampling beyond 46875"
    - "WavEncoderTest and ResamplerTest are GREEN"
  artifacts:
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/WavEncoder.kt"
      provides: "Pure RIFF/Int16 WAV writer + pass-through format sniffer (SAMPLE-02)"
      contains: "fun encodeWav"
      min_lines: 40
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/Resampler.kt"
      provides: "Pure linear resampler to 46875 Hz, per-channel, no-op at target rate (SAMPLE-02)"
      contains: "fun toRate"
      min_lines: 40
  key_links:
    - from: "WavEncoder.encodeWav"
      to: "RIFF byte layout @ 46875/s16 LE"
      via: "ByteBuffer LITTLE_ENDIAN"
      pattern: "LITTLE_ENDIAN"
    - from: "Resampler.toRate"
      to: "46875 target rate"
      via: "DEVICE_SAMPLE_RATE constant"
      pattern: "46875"
---

<objective>
Implement the two pure, hardware-free conversion units that turn Wave 0's WavEncoderTest and ResamplerTest GREEN: a RIFF/Int16 little-endian WAV encoder hard-locked to the EP-133's 46875 Hz / s16 format (with a pass-through sniffer for already-conformant input), and a per-channel linear resampler to 46875 that is a no-op when the source is already at the device rate and never upsamples. These are the only "DO hand-roll" pieces in the phase (05-RESEARCH "Don't Hand-Roll"). No MediaCodec, no device I/O — both are pure functions over ShortArray/ByteArray, fully unit-tested with synthetic PCM.

Purpose: SAMPLE-02 (conversion to the EP-133 format) — the pure half. The device-bound decode half lands in Wave 2.
Output: WavEncoder.kt, Resampler.kt; WavEncoderTest + ResamplerTest pass.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md
@.planning/phases/05-splice-sample-sync/05-RESEARCH.md
@.planning/phases/05-splice-sample-sync/05-PATTERNS.md

<interfaces>
<!-- Contracts fixed by Wave 0 tests. Implement to match exactly. -->
WavEncoder (object):
  fun encodeWav(pcm: ShortArray, sampleRate: Int = 46875, channels: Int = 1): ByteArray
  fun isAlreadyDeviceFormat(wavBytes: ByteArray): Boolean

Resampler (object):
  fun toRate(pcm: ShortArray, srcRate: Int, dstRate: Int = 46875, channels: Int = 1): ShortArray

Device constants (define as private const in domain/audio, value-matched to data/index.js):
  DEVICE_SAMPLE_RATE = 46875
  DEVICE_BIT_DEPTH = 16

RIFF byte layout (verified against 05-RESEARCH "Code Examples" + RIFF spec) — little-endian throughout:
  offset 0  "RIFF" | 4 chunkSize=36+dataSize | 8 "WAVE"
  offset 12 "fmt " | 16 subchunk1Size=16 | 20 audioFormat=1 | 22 channels | 24 sampleRate | 28 byteRate=sampleRate*channels*2 | 32 blockAlign=channels*2 | 34 bitsPerSample=16
  offset 36 "data" | 40 dataSize=pcm.size*2 | 44.. Int16 LE samples (interleaved for stereo)
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: WavEncoder — RIFF/Int16 LE writer + pass-through sniffer</name>
  <behavior>
    - encodeWav(knownPcm, 46875, 1) writes "RIFF"/"WAVE"/"fmt "/"data" magic, audioFormat=1, sampleRate=46875 (LE), bits=16, dataSize=pcm.size*2, total=44+dataSize (WavEncoderTest A)
    - encodeWav(pcm, 46875, 2) sets channels=2, byteRate=46875*4, blockAlign=4 (Test B)
    - isAlreadyDeviceFormat(46875/s16/mono wav) == true; isAlreadyDeviceFormat(44100 header) == false (Test C)
  </behavior>
  <read_first>
    - 05-RESEARCH.md "Code Examples" (the encodeWav reference implementation) + "EP-133 Sample Format" + "Pattern 2" pass-through sniff
    - 05-RESEARCH.md Landmine 3 (bit depth / endianness — explicit LITTLE_ENDIAN)
    - 05-PATTERNS.md "WavEncoder" section (byte-layout idiom; note WAV is LE unlike SysExProtocol's BE packers)
    - AndroidApp/app/src/test/java/com/ep133/sampletool/WavEncoderTest.kt (the assertions this must satisfy — created in Plan 01)
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt (lines ~346-354 byte-packer idiom for reference only — do NOT reuse BE order)
  </read_first>
  <action>
    Create domain/audio/WavEncoder.kt as an `object WavEncoder`. Define private const val DEVICE_SAMPLE_RATE = 46875 and DEVICE_BIT_DEPTH = 16.
    encodeWav(pcm, sampleRate, channels): require(sampleRate == DEVICE_SAMPLE_RATE) (assert the target rate — Landmine 2). Allocate java.nio.ByteBuffer of (44 + pcm.size*2) with ByteOrder.LITTLE_ENDIAN. Write the RIFF/fmt /data chunks per the layout in <interfaces>, then loop putShort over pcm. Return buf.array(). Channels passed through (mono stays mono, stereo stays stereo — do NOT downmix, RESEARCH A1).
    isAlreadyDeviceFormat(wavBytes): parse the RIFF header defensively — return false if length < 44 or magic bytes 0..3 != "RIFF" or 8..11 != "WAVE". Locate the "fmt " chunk; read audioFormat (LE u16), channels (LE u16), sampleRate (LE u32), bitsPerSample (LE u16). Return true iff audioFormat==1 && bitsPerSample==16 && sampleRate==46875 && channels in 1..2. Wrap parsing so a malformed/short buffer returns false rather than throwing (V5 input validation — untrusted file bytes).
    No Android imports; pure JVM. Use Charsets.US_ASCII for the chunk magic.
  </action>
  <verify>
    <automated>cd AndroidApp && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home ./gradlew :app:testDebugUnitTest --tests "*.WavEncoderTest"</automated>
  </verify>
  <acceptance_criteria>
    - WavEncoderTest passes (all of A/B/C green).
    - encodeWav uses ByteOrder.LITTLE_ENDIAN and a require()/check on sampleRate==46875.
    - isAlreadyDeviceFormat returns false (not an exception) for buffers shorter than 44 bytes or with bad magic.
  </acceptance_criteria>
  <done>`./gradlew :app:testDebugUnitTest --tests "*.WavEncoderTest"` is green; encoder is LE, 46875-locked, pass-through sniffer validates defensively.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Resampler — per-channel linear interpolation to 46875, no-op at rate</name>
  <behavior>
    - toRate(pcm, 46875, 46875, 1) returns the input array unchanged (identity — ResamplerTest D)
    - toRate(mono44100, 44100, 46875, 1) yields length ~= round(44100 * 46875/44100) within +/-1 (Test E)
    - toRate(pcm, 48000, 46875, 1) targets the 46875 ratio length, never exceeding the source rate (Test F)
  </behavior>
  <read_first>
    - 05-RESEARCH.md "Pattern 2" + "Don't Hand-Roll" (linear interpolation, ~40 lines; A2 cap-at-46875)
    - 05-PATTERNS.md "Resampler" (pure-fn shape mirrors SysExProtocol.assembleGetPages — hardware-free, directly testable)
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ResamplerTest.kt (assertions to satisfy — created in Plan 01)
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt (assembleGetPages ~443-458 — the pure-function shape to mirror, not the algorithm)
  </read_first>
  <action>
    Create domain/audio/Resampler.kt as `object Resampler`. toRate(pcm, srcRate, dstRate=46875, channels):
    - Early return: if srcRate == dstRate, return pcm unchanged (referential identity — Test D depends on it; avoids any artifact at the device rate).
    - Cap rule (Landmine A2): the device never upsamples; if srcRate < dstRate the resampler still resamples up to dstRate=46875 (that is the device's required rate), but treat dstRate as the fixed 46875 target. Do NOT produce output above 46875.
    - Deinterleave by channel: for c in 0 until channels, take samples at stride `channels` starting at c. For each channel, compute outLen = round(srcLen * dstRate.toDouble() / srcRate). For each output index i, srcPos = i * srcRate.toDouble() / dstRate; floor index lo, frac = srcPos - lo; linearly interpolate sample = pcm[lo]*(1-frac) + pcm[lo+1]*frac (clamp lo+1 to last index at the tail). Round to Short with coercion into Short.MIN..MAX.
    - Re-interleave the per-channel results back into a single ShortArray of length outLenPerChannel*channels.
    Pure JVM, no Android imports. Document the linear-interp choice (RESEARCH: audibly fine for EP-133; sinc is overkill) in a KDoc comment.
  </action>
  <verify>
    <automated>cd AndroidApp && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home ./gradlew :app:testDebugUnitTest --tests "*.ResamplerTest"</automated>
  </verify>
  <acceptance_criteria>
    - ResamplerTest passes (D/E/F green).
    - srcRate==dstRate path returns the input array unchanged (no copy, no resample work).
    - Stereo input round-trips channel count (interleave preserved); output never sampled above 46875.
  </acceptance_criteria>
  <done>`./gradlew :app:testDebugUnitTest --tests "*.ResamplerTest"` is green; no-op identity and linear resample both correct.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| picked file bytes -> WavEncoder.isAlreadyDeviceFormat | untrusted RIFF header parsed here (V5 input validation) |
| decoded PCM -> Resampler/encoder | bounded in-memory transform; no I/O |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-05-02-01 | Tampering | isAlreadyDeviceFormat parsing a malformed/short WAV header | mitigate | Defensive bounds checks: length<44 or bad magic returns false; no out-of-bounds read, no throw (V5). |
| T-05-02-02 | DoS | Huge ShortArray into encodeWav (44 + size*2 allocation) | mitigate | Caller (Wave 2/3) pre-flights converted size vs /sounds free space (Landmine 6); encoder itself stays O(n) single-pass. |
| T-05-02-03 | Tampering | Wrong output sample rate (44100 leaks through) | mitigate | encodeWav require()s sampleRate==46875; WavEncoderTest hard-codes 46875 (Landmine 2 regression guard). |
| T-05-02-SC | Tampering | package installs | accept | No package installs (zero external deps this phase). |

No network, no Splice surface, no device write in this plan.
</threat_model>

<verification>
cd AndroidApp && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home ./gradlew :app:testDebugUnitTest --tests "*.WavEncoderTest" --tests "*.ResamplerTest" :app:lintDebug
</verification>

<success_criteria>
WavEncoderTest and ResamplerTest are green; the encoder is little-endian and 46875-locked; the resampler is a no-op at the device rate and linearly resamples otherwise without upsampling above 46875; lint clean. SAMPLE-02's pure half is complete.
</success_criteria>

<output>
Create `.planning/phases/05-splice-sample-sync/05-02-SUMMARY.md` when done.
</output>
