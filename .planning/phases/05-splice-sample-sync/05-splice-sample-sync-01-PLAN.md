---
phase: 05-splice-sample-sync
plan: 01
type: execute
wave: 0
depends_on: []
files_modified:
  - AndroidApp/app/src/test/java/com/ep133/sampletool/WavEncoderTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/ResamplerTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportViewModelTest.kt
autonomous: true
requirements: [SAMPLE-01, SAMPLE-02, SAMPLE-03, SAMPLE-04]
must_haves:
  truths:
    - "Four unit-test files exist and compile against the not-yet-written production APIs (WavEncoder, Resampler, MIDIRepository.putSampleFile/SampleImportManager, SampleImportViewModel)"
    - "WavEncoderTest asserts a known ShortArray encodes to a RIFF header with sampleRate 46875 (LE), 16 bits/sample, correct channel count, and dataSize == pcm.size*2; plus a pass-through identity case"
    - "ResamplerTest asserts 44100->46875 output length ratio + endpoint samples and a no-op identity when srcRate==46875"
    - "SampleImportTest asserts a multi-KB WAV produces one INIT frame + multiple paged DATA frames whose 7-bit-unpacked chunks reassemble to the original bytes"
    - "SampleImportViewModelTest asserts picked URIs map to a staged list and progress states advance to done/error"
    - "Every test references the real (target) production symbol so Wave 1-3 turn them GREEN with no test edits"
  artifacts:
    - path: "AndroidApp/app/src/test/java/com/ep133/sampletool/WavEncoderTest.kt"
      provides: "RIFF header + pass-through assertions (SAMPLE-02)"
      contains: "class WavEncoderTest"
      min_lines: 40
    - path: "AndroidApp/app/src/test/java/com/ep133/sampletool/ResamplerTest.kt"
      provides: "Resample length/value + no-op assertions (SAMPLE-02)"
      contains: "class ResamplerTest"
      min_lines: 30
    - path: "AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportTest.kt"
      provides: "Paged /sounds PUT frame-layout assertions (SAMPLE-03)"
      contains: "class SampleImportTest"
      min_lines: 40
    - path: "AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportViewModelTest.kt"
      provides: "URI -> staged-list + progress mapping (SAMPLE-01/04)"
      contains: "class SampleImportViewModelTest"
      min_lines: 50
  key_links:
    - from: "SampleImportTest"
      to: "SysExProtocol.buildFilePut* + unpack7bit"
      via: "frame unpack helper"
      pattern: "unpack7bit"
    - from: "SampleImportViewModelTest"
      to: "MIDIRepository fake + spy port"
      via: "ProjectsViewModelTest-style harness"
      pattern: "MIDIPort"
---

<objective>
Wave 0 RED scaffold. Create the four unit-test files that define the contracts for Phase 5's pure conversion core, the paged /sounds PUT, and the import ViewModel — before any production code exists. These tests fail to compile/run now (the symbols they reference don't exist yet) and are turned GREEN by Waves 1-3 with zero test edits. This locks the WavEncoder/Resampler/putSampleFile/ViewModel public shapes up front so downstream waves implement against fixed contracts, not a moving target.

Purpose: Nyquist compliance — every later task has an automated verify that already exists. Establish the byte-exact format guards (46875 Hz, s16 LE) as regression tests against Landmines 2 and 3.
Output: WavEncoderTest.kt, ResamplerTest.kt, SampleImportTest.kt, SampleImportViewModelTest.kt.
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
@.planning/phases/05-splice-sample-sync/05-VALIDATION.md

<interfaces>
<!-- Contracts the tests must reference. These are the public APIs Waves 1-3 will implement.
     Defining them here is the whole point of Wave 0 — downstream waves implement to match. -->

Target production symbols (do NOT implement them in this plan — only write tests that compile against them):

WavEncoder (object, domain/audio/WavEncoder.kt — Wave 1):
  fun encodeWav(pcm: ShortArray, sampleRate: Int = 46875, channels: Int = 1): ByteArray
  fun isAlreadyDeviceFormat(wavBytes: ByteArray): Boolean   // pass-through sniffer: PCM/16-bit/46875/(1|2)ch

Resampler (object, domain/audio/Resampler.kt — Wave 1):
  fun toRate(pcm: ShortArray, srcRate: Int, dstRate: Int = 46875, channels: Int = 1): ShortArray
  // never upsamples beyond 46875; returns the input array unchanged when srcRate == dstRate

MIDIRepository.putSampleFile(name: String, wavBytes: ByteArray): Boolean   // Wave 2, added to MIDIRepository.kt

Existing symbols already shipped (reference directly):
  SysExProtocol.buildFilePutInitFrame(deviceId, nodeId, fileSize, requestId): ByteArray
  SysExProtocol.buildFilePutDataFrame(deviceId, page, chunk, requestId): ByteArray
  SysExProtocol.buildFilePutFrame(deviceId, path, data, chunkIndex, requestId): ByteArray   // path-string variant
  SysExProtocol.unpack7bit(bytes): ByteArray
  SysExProtocol.MAX_PAGE_BYTES = 4096
  SysExProtocol.CMD_PRODUCT_SPECIFIC, TE_SYSEX_FILE, TE_SYSEX_FILE_PUT
  MIDIRepository(port: MIDIPort)  // open class; deviceState: StateFlow<DeviceState>; listProjects() is open
  DeviceState(connected, outputPortId, storageUsedBytes, storageTotalBytes, ...)
  MIDIPort interface (see ProjectsViewModelTest for the full no-op double)
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: WavEncoderTest + ResamplerTest — pure conversion contracts (RED)</name>
  <read_first>
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt (byte-assertion idiom; the `unpackPayload` helper and LE/BE assertions)
    - AndroidApp/app/src/test/java/com/ep133/sampletool/SysExProtocolTest.kt (frame-byte assertion style)
    - 05-RESEARCH.md "EP-133 Sample Format" + "Code Examples" (the encodeWav reference, the 46875/s16 target, the pass-through predicate)
    - 05-RESEARCH.md Landmines 2 (wrong sample rate) and 3 (bit depth/endianness) — these tests are the regression guards for both
    - 05-PATTERNS.md "WavEncoder" and "Resampler" sections
  </read_first>
  <action>
    Create WavEncoderTest.kt (package com.ep133.sampletool, JUnit 4: org.junit.Test + org.junit.Assert.*).
    Test A (riffHeader_carries46875_s16_channels): build a small known ShortArray (e.g. shortArrayOf(0, 1, -1, 32767, -32768)); call WavEncoder.encodeWav(pcm, sampleRate=46875, channels=1). Assert: bytes[0..3]=="RIFF", bytes[8..11]=="WAVE", bytes[12..15]=="fmt ", bytes[36..39]=="data"; audioFormat (LE int16 at offset 20)==1 (PCM); channels (offset 22)==1; sampleRate (LE int32 at offset 24)==46875; bitsPerSample (LE int16 at offset 34)==16; dataSize (LE int32 at offset 40)==pcm.size*2; total length==44+pcm.size*2. Read multi-byte fields with java.nio.ByteBuffer wrap + ByteOrder.LITTLE_ENDIAN so the test fails loudly on any endianness slip.
    Test B (stereo_channelsAndByteRate): encodeWav(pcm, 46875, channels=2); assert channels==2 and byteRate (LE int32 at offset 28)==46875*2*2 and blockAlign (LE int16 at offset 32)==4.
    Test C (passThrough_identity_whenAlready46875): construct an already-46875/s16/mono WAV (call encodeWav to build it), assert WavEncoder.isAlreadyDeviceFormat(thatWav)==true; build a 44100 WAV header variant and assert isAlreadyDeviceFormat==false.

    Create ResamplerTest.kt (same package + imports).
    Test D (noOp_whenSrcEqualsDst): Resampler.toRate(pcm, srcRate=46875, dstRate=46875, channels=1) returns an array contentEquals the input (identity — Landmine 2 guard, no resample artifact at the device rate).
    Test E (length_44100_to_46875): a mono ShortArray of length 44100 resampled 44100->46875 yields a length within +/-1 of round(44100 * 46875.0/44100.0)==46875; assert the ratio relationship, not an exact off-by-one.
    Test F (downsample_never_upsamples): assert toRate with srcRate=48000 produces output at the 46875 target length ratio (the device caps at 46875 — RESEARCH A2).
    Use only synthetic in-memory PCM. No Android/Robolectric imports — these are pure JVM tests.
  </action>
  <verify>
    <automated>cd AndroidApp && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home ./gradlew :app:compileDebugUnitTestKotlin 2>&1 | grep -E "WavEncoder|Resampler|unresolved reference: (encodeWav|toRate|isAlreadyDeviceFormat)" | head</automated>
  </verify>
  <acceptance_criteria>
    - WavEncoderTest.kt and ResamplerTest.kt exist in app/src/test/java/com/ep133/sampletool/.
    - Each references the exact target symbols (WavEncoder.encodeWav / .isAlreadyDeviceFormat, Resampler.toRate).
    - `./gradlew :app:compileDebugUnitTestKotlin` fails ONLY on unresolved references to the not-yet-created WavEncoder/Resampler (proving the tests are wired to the real contract, RED state). No other compile errors.
    - Hard-coded literal 46875 and 16 appear in WavEncoderTest assertions (Landmine 2/3 guard).
  </acceptance_criteria>
  <done>Both test files compile-fail solely on the missing WavEncoder/Resampler symbols; all assertions target the locked contract.</done>
</task>

<task type="auto">
  <name>Task 2: SampleImportTest + SampleImportViewModelTest — load + UI contracts (RED)</name>
  <read_first>
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt (lines ~19-21 `unpackPayload(frame)` helper; putInitFrame_*/putDataFrame_* assertions to copy for the /sounds case)
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectsViewModelTest.kt (the full ProjectsSpyMIDIPort + ProjectsFakeMIDIRepo harness, StandardTestDispatcher + setMain/resetMain + advanceUntilIdle)
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt (lines 525-555 putProjectArchive — the paged loop putSampleFile will mirror; MAX_PAGE_BYTES=4096)
    - 05-RESEARCH.md Landmine 5 (single-chunk vs paged; the truncation guard) and Landmine 4 (path-string vs nodeId target)
    - 05-PATTERNS.md "SampleImportViewModel" + "Test Pattern Assignments"
  </read_first>
  <action>
    Create SampleImportTest.kt (package com.ep133.sampletool, JUnit 4). Copy ProjectProtocolTest's private `unpackPayload(frame) = SysExProtocol.unpack7bit(frame.copyOfRange(9, frame.size - 1))` helper.
    Because putSampleFile is a suspend method on MIDIRepository that sends over a port, drive it through a spy port (copy ProjectsSpyMIDIPort which records `sent: MutableList<ByteArray>`). Construct a connected fake repo, call putSampleFile("kick.wav", wavBytes) inside runTest where wavBytes is a synthetic byte array > MAX_PAGE_BYTES (e.g. 10_000 bytes of a known pattern).
    Assert: the spy recorded exactly 1 INIT-shaped frame followed by ceil(10000/4096)==3 DATA frames; concatenating the unpacked DATA chunk payloads (stripping the page header) reconstructs the original wavBytes byte-for-byte (Landmine 5 truncation guard — proves paged, not single-chunk). If putSampleFile uses the path-string builder (Landmine 4 default), assert the INIT/first frame carries the ASCII path "/sounds/kick.wav"; structure the assertion so either the path-string OR nodeId addressing passes only the frame-count + payload-reassembly invariant (the addressing choice is hardware-verified, so assert the transfer integrity, not the addressing mode).

    Create SampleImportViewModelTest.kt (package com.ep133.sampletool). Copy the ProjectsViewModelTest harness verbatim, renaming doubles to SampleImportSpyMIDIPort / SampleImportFakeMIDIRepo (avoid top-level redeclaration clashes across the shared test source set — see STATE.md Phase 4 note). Add StandardTestDispatcher + Dispatchers.setMain/resetMain (@Before/@After) + advanceUntilIdle.
    Instantiate SampleImportViewModel(midiRepo, sampleImportManager) where SampleImportManager(midiRepo) is the Wave 2 class. Tests:
    - onFilesPicked maps a list of fake StagedSample inputs to the stagedSamples StateFlow with one entry per input, each starting in a Pending state.
    - a connected import advances an item Pending -> Done (collect the stagedSamples StateFlow); a disconnected repo advances it to Error with a "No EP-133 connected"-style message and sets snackbarMessage.
    Since onFilesPicked needs a real Context/contentResolver to read URIs, design the VM test against a content-free seam: assert the staged-list state machine and progress mapping using a directly-invokable internal entry (e.g. importStagedBytes(name, bytes) the VM exposes for testability, or by injecting pre-read bytes) rather than a live content:// read. Document in a test comment that the actual SAF URI read is hardware/instrumentation-only (05-VALIDATION Manual-Only).
  </action>
  <verify>
    <automated>cd AndroidApp && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home ./gradlew :app:compileDebugUnitTestKotlin 2>&1 | grep -E "unresolved reference: (putSampleFile|SampleImportManager|SampleImportViewModel|stagedSamples)" | head</automated>
  </verify>
  <acceptance_criteria>
    - SampleImportTest.kt and SampleImportViewModelTest.kt exist with the renamed test doubles (no clash with ProjectsViewModelTest's private top-level classes).
    - SampleImportTest asserts >1 DATA frame for a >4096-byte payload AND payload reassembly equality (the Landmine 5 regression guard).
    - `./gradlew :app:compileDebugUnitTestKotlin` fails ONLY on unresolved references to putSampleFile / SampleImportManager / SampleImportViewModel / stagedSamples — RED, contract-locked.
    - Test comments mark the SAF URI read and real decode as hardware/instrumentation-only per 05-VALIDATION.
  </acceptance_criteria>
  <done>Both files compile-fail solely on missing Wave 2/3 symbols; the paged-transfer integrity and staged-list state-machine contracts are asserted.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| test harness -> production API | Wave 0 only references contracts; no untrusted input crosses (pure test code) |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-05-00-SC | Tampering | npm/pip/cargo installs | accept | No package installs this phase (05-RESEARCH: zero external packages). Package Legitimacy Audit N/A. |
| T-05-00-01 | Tampering | Test asserting wrong format | mitigate | WavEncoderTest hard-codes 46875/16 LE so a later format regression (Landmine 2/3) fails the suite, not just hardware. |

No runtime trust boundary in Wave 0 — test-only code, no device write, no file input.
</threat_model>

<verification>
cd AndroidApp && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home ./gradlew :app:compileDebugUnitTestKotlin
(Expected: compile failures limited to the not-yet-created production symbols. This is the RED gate.)
</verification>

<success_criteria>
Four test files exist, each references its locked production contract, and `compileDebugUnitTestKotlin` fails only on the missing Wave 1-3 symbols (WavEncoder, Resampler, putSampleFile, SampleImportManager, SampleImportViewModel). No test references a Splice API of any kind.
</success_criteria>

<output>
Create `.planning/phases/05-splice-sample-sync/05-01-SUMMARY.md` when done.
</output>
