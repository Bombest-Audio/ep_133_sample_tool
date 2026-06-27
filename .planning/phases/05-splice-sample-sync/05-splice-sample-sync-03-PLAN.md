---
phase: 05-splice-sample-sync
plan: 03
type: execute
wave: 2
depends_on: ["05-splice-sample-sync-01", "05-splice-sample-sync-02"]
files_modified:
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/AudioDecoder.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SampleImportManager.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt
  - .planning/phases/05-splice-sample-sync/05-HUMAN-UAT.md
autonomous: false
requirements: [SAMPLE-02, SAMPLE-03]
user_setup: []
must_haves:
  truths:
    - "MIDIRepository.putSampleFile(name, wavBytes) uploads a multi-KB WAV to /sounds via the paged INIT + paged-DATA loop (not single-chunk), mirroring putProjectArchive"
    - "AudioDecoder.decode(context, uri) returns (ShortArray pcm, srcRate, channels) decoded via MediaExtractor + MediaCodec"
    - "SampleImportManager.importSample emits Progress -> Done/Error per sample, with a connected-device guard first and a sanitized /sounds basename before any device write"
    - "The full convert pipeline (decode -> Resampler.toRate -> WavEncoder.encodeWav, with the pass-through fast path) produces 46875/s16 WAV bytes"
    - "SampleImportTest is GREEN"
  artifacts:
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/AudioDecoder.kt"
      provides: "MediaExtractor/MediaCodec decode of a content:// audio URI to PCM (SAMPLE-02 decode)"
      contains: "MediaCodec"
      min_lines: 60
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SampleImportManager.kt"
      provides: "Per-sample convert + /sounds upload orchestration with progress Flow (mirrors ProjectBackupManager)"
      contains: "class SampleImportManager"
      min_lines: 60
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt"
      provides: "putSampleFile paged PUT to /sounds (SAMPLE-03)"
      contains: "fun putSampleFile"
  key_links:
    - from: "MIDIRepository.putSampleFile"
      to: "SysExProtocol paged PUT builders"
      via: "INIT + paged DATA loop (mirror putProjectArchive)"
      pattern: "buildFilePutDataFrame"
    - from: "SampleImportManager"
      to: "AudioDecoder + Resampler + WavEncoder + MIDIRepository.putSampleFile"
      via: "convert-then-upload Flow"
      pattern: "putSampleFile"
    - from: "SampleImportManager"
      to: "deviceState.storageUsedBytes/storageTotalBytes"
      via: "free-space pre-flight"
      pattern: "storageTotalBytes"
---

<objective>
Build the device-bound load + decode layer: add MIDIRepository.putSampleFile to upload a converted WAV to /sounds over the Phase 4 paged FILE_PUT stack (re-targeting putProjectArchive's INIT+paged-DATA loop from a /projects slot to a new /sounds file), the AudioDecoder (the one genuinely new MediaCodec/MediaExtractor surface in the repo), and SampleImportManager mirroring ProjectBackupManager — a per-sample progress Flow that sniffs the pass-through fast path, decodes/resamples/encodes when needed, sanitizes the sample name, pre-flights /sounds free space, then uploads. Turns SampleImportTest GREEN. Decode and the real /sounds upload are hardware/instrumentation-bound, so this plan is non-autonomous: it records explicit UAT entries in 05-HUMAN-UAT.md and ships against the documented defaults.

Purpose: SAMPLE-03 (load to /sounds) + SAMPLE-02's decode half.
Output: AudioDecoder.kt, SampleImportManager.kt, MIDIRepository.putSampleFile; 05-HUMAN-UAT.md with the deferred hardware checks.
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
@.planning/phases/04-project-management/04-HUMAN-UAT.md

<interfaces>
<!-- Existing shipped symbols this plan reuses/extends. -->
MIDIRepository (open class, MIDIRepository.kt):
  suspend fun putProjectArchive(slotNodeId: Int, tarBytes: ByteArray): Boolean   // lines 525-555 — mirror this
  suspend fun resolveNodeId(path: String): Int?                                  // walks root->child by name
  open suspend fun listProjects(): List<ProjectSlot>
  private currentDeviceId, transferInFlight, pendingPutAckDeferred, PUT_ACK_TIMEOUT_MS=15_000L
  deviceState: StateFlow<DeviceState>   // outputPortId, storageUsedBytes: Long?, storageTotalBytes: Long?

SysExProtocol:
  fun buildFilePutInitFrame(deviceId, nodeId, fileSize, requestId): ByteArray   // nodeId addressing (paged)
  fun buildFilePutDataFrame(deviceId, page, chunk, requestId): ByteArray        // paged DATA
  fun buildFilePutFrame(deviceId, path, data, chunkIndex, requestId): ByteArray // path-string (BackupManager /sounds restore used this)
  const MAX_PAGE_BYTES = 4096

BackupManager.kt: writes new /sounds/$name via buildFilePutFrame(deviceId, "/sounds/$name", data, chunkIndex, requestId) (lines ~196-200) — the proven /sounds-create path (Landmine 4 default).

Wave 1 (now available):
  WavEncoder.encodeWav(pcm, 46875, channels); WavEncoder.isAlreadyDeviceFormat(wavBytes)
  Resampler.toRate(pcm, srcRate, 46875, channels)

ProjectBackupManager.kt (the orchestration shape to mirror): SampleImportProgress sealed class, connected-device guard FIRST, CancellationException rethrow before generic catch, Log.e(TAG, msg, throwable), TAG="EP133APP", filename sanitization before device write.
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: MIDIRepository.putSampleFile — paged FILE_PUT to a new /sounds file</name>
  <files>AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt</files>
  <read_first>
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt (lines 525-555 putProjectArchive — copy the INIT+paged-DATA loop; lines 81-91 currentDeviceId/transferInFlight/pendingPutAckDeferred; resolveNodeId ~613-630; PUT_ACK_TIMEOUT_MS ~793)
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt (buildFilePutInitFrame ~365, buildFilePutDataFrame ~372, buildFilePutFrame ~225, MAX_PAGE_BYTES ~461)
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/BackupManager.kt (lines ~196-200 — the path-string /sounds PUT that demonstrably worked for restore)
    - 05-RESEARCH.md Landmine 4 (node-ID vs path-string for a NEW /sounds file) + Landmine 5 (must be paged, not single-chunk) + Open Question 1
    - 05-PATTERNS.md "MIDIRepository.kt — ADD putSampleFile" (target-addressing decision)
  </read_first>
  <action>
    Add `suspend fun putSampleFile(name: String, wavBytes: ByteArray): Boolean` to MIDIRepository, structured exactly like putProjectArchive: grab outputPortId (throw IllegalStateException if null), guard transferInFlight, set up the CompletableDeferred<Boolean> ack in pendingPutAckDeferred, send INIT, loop MAX_PAGE_BYTES slices sending buildFilePutDataFrame with an incrementing 16-bit page, await STATUS_OK within PUT_ACK_TIMEOUT_MS, rethrow CancellationException, clear the deferred + transferInFlight in finally.
    Addressing — default to the path-string-target + paged-transfer hybrid (Landmine 4 default = path string, proven for /sounds; Landmine 5 = paged transfer required for multi-KB). Concretely: resolve the target via resolveNodeId("/sounds") to obtain the /sounds parent node, then issue buildFilePutInitFrame for the new file under that node (the device's INIT announces the new file by name+size). Mark the addressing choice with `// HARDWARE-VERIFY (Open Q1 / Landmine 4)` and, in a KDoc, document the one-line fallback: if the device rejects a node-ID INIT for a non-existent file, switch the INIT to SysExProtocol.buildFilePutFrame(currentDeviceId, "/sounds/$name", chunk, chunkIndex, requestId) path-string framing while keeping the paged DATA loop.
    Use requestId values distinct from putProjectArchive's 20/21 (e.g. 30 INIT / 31 DATA) to avoid ack-dispatch collisions; confirm dispatchPagedPutResponse already routes any in-flight PUT ack (it does — keyed on transferInFlight, not requestId). Do not change putProjectArchive.
  </action>
  <verify>
    <automated>cd AndroidApp && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home ./gradlew :app:testDebugUnitTest --tests "*.SampleImportTest"</automated>
  </verify>
  <acceptance_criteria>
    - SampleImportTest is green: a >4096-byte WAV produces 1 INIT + >1 paged DATA frame and the unpacked DATA payloads reassemble to the original bytes (Landmine 5 guard).
    - putSampleFile mirrors putProjectArchive's transferInFlight/ack/finally structure; CancellationException rethrown.
    - The addressing choice carries a `// HARDWARE-VERIFY (Open Q1 / Landmine 4)` marker and a documented path-string fallback.
  </acceptance_criteria>
  <done>`./gradlew :app:testDebugUnitTest --tests "*.SampleImportTest"` green; paged /sounds PUT in place with the hardware-verify marker + fallback.</done>
</task>

<task type="auto">
  <name>Task 2: AudioDecoder (MediaCodec) + SampleImportManager orchestration</name>
  <files>AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/AudioDecoder.kt, AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SampleImportManager.kt</files>
  <read_first>
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/ProjectBackupManager.kt (whole file — mirror the sealed progress class, device guard, IO dispatch, CancellationException rethrow, Log.e w/ throwable, filename sanitization at lines ~120-131/187-189)
    - 05-RESEARCH.md "Pattern 2" (MediaExtractor -> MediaCodec.createDecoderByType -> drain output buffers) + Landmine 7 (SAF URI lifetime — read inside the grant) + "Security Domain" (V5: bound buffers, sanitize basename, pre-flight size)
    - 05-PATTERNS.md "AudioDecoder.kt — NO ANALOG" + "SampleImportManager.kt mirror verbatim" + "Storage pre-flight (Landmine 6)"
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/WavEncoder.kt + Resampler.kt (the Wave 1 units this composes)
    - context7: resolve "android MediaCodec" / query "MediaExtractor MediaCodec decode PCM output buffer drain" for current API surface
  </read_first>
  <action>
    Create domain/audio/AudioDecoder.kt as `object AudioDecoder` with `suspend fun decode(context: Context, uri: Uri): DecodedPcm` (data class DecodedPcm(val pcm: ShortArray, val sampleRate: Int, val channels: Int)). Under Dispatchers.IO: open the content:// URI via contentResolver.openFileDescriptor(uri, "r") INSIDE the caller's grant (Landmine 7); MediaExtractor.setDataSource(fd.fileDescriptor); select the first track whose MIME starts with "audio/"; read sampleRate + channelCount from its MediaFormat; MediaCodec.createDecoderByType(mime), configure(format, null surface, null crypto, 0), start; drain the input/output buffer loop, accumulating 16-bit PCM into a ByteArrayOutputStream (bounded — see threat model), stop on BUFFER_FLAG_END_OF_STREAM; convert the accumulated little-endian bytes to ShortArray. Release codec + extractor in finally. Wrap MediaCodec exceptions and rethrow as a descriptive IOException; rethrow CancellationException. Mark `// HARDWARE/INSTRUMENTATION-VERIFY (UAT-DECODE)` — pure-JVM unit tests can't exercise MediaCodec.
    Create domain/midi/SampleImportManager.kt as `class SampleImportManager(private val midi: MIDIRepository)`. Define `sealed class SampleImportProgress { data class Progress(current, total); data class Done(name); data class Error(message) }`. Add `fun importSample(name: String, sourceWavOrPcm: ...): Flow<SampleImportProgress>` mirroring ProjectBackupManager.backupProject: device guard FIRST (emit Error "No EP-133 connected" + return@flow if outputPortId == null); sanitize the name to a safe basename + ".wav" (reject "/", "..", control chars — V5 / T-05-03 traversal) BEFORE any work; pre-flight converted size vs (storageTotalBytes - storageUsedBytes), emit Error if it would overflow (Landmine 6); then call midi.putSampleFile(safeName, wavBytes) inside try/catch (CancellationException rethrow first, then Log.e(TAG, ..., e) + emit Error); emit Progress then Done(safeName) on ok, else Error. TAG = "EP133APP".
    Provide a `suspend fun convert(context, uri): ByteArray` (Dispatchers.Default) composing the Wave 1 units: read source bytes (IO, in-grant); if WavEncoder.isAlreadyDeviceFormat(bytes) return bytes unchanged (fast path); else AudioDecoder.decode -> Resampler.toRate(pcm, srcRate, 46875, channels) -> WavEncoder.encodeWav(resampled, 46875, channels).
  </action>
  <verify>
    <automated>cd AndroidApp && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home ./gradlew :app:testDebugUnitTest :app:assembleDebug</automated>
  </verify>
  <acceptance_criteria>
    - :app:assembleDebug succeeds (AudioDecoder + SampleImportManager compile and link against Wave 1 + MIDIRepository.putSampleFile).
    - Full :app:testDebugUnitTest suite green (SampleImportTest + the Wave 1 tests still pass).
    - SampleImportManager sanitizes the name before the device write and pre-flights /sounds free space; device guard is the first emission path.
    - AudioDecoder releases MediaCodec/MediaExtractor in finally and carries the `// HARDWARE/INSTRUMENTATION-VERIFY (UAT-DECODE)` marker.
  </acceptance_criteria>
  <done>assembleDebug + full unit suite green; convert pipeline composes the Wave 1 units with the pass-through fast path; decode is wired but device-verified.</done>
</task>

<task type="checkpoint:human-verify" gate="blocking">
  <name>Task 3: Checkpoint — record deferred hardware UAT entries (decode, /sounds PUT, pitch)</name>
  <files>.planning/phases/05-splice-sample-sync/05-HUMAN-UAT.md</files>
  <action>Confirm 05-HUMAN-UAT.md carries the three deferred hardware-only entries (UAT-DECODE, UAT-SOUNDS-PUT, UAT-PITCH) in the 04-HUMAN-UAT.md format (Assumption shipped / Steps / Fallback / Status ☐), each with the shipped default and a documented fallback — no faked device checks. Then pause for human confirmation that the deferred items are recorded honestly.</action>
  <what-built>
    putSampleFile (paged /sounds upload), AudioDecoder (MediaCodec decode), and SampleImportManager (convert + upload orchestration) are implemented and unit-tested for everything that does NOT require hardware. Three behaviors cannot be exercised without a physical EP-133 + a USB-host Android device + MediaCodec on a real device: (1) real MediaCodec decode of a WAV/MP3, (2) the new-file /sounds PUT addressing (path-string vs node-ID, Landmine 4 / Open Q1), (3) on-device acceptance of the uploaded WAV. These are recorded as deferred UAT entries.
  </what-built>
  <how-to-verify>
    Confirm 05-HUMAN-UAT.md was written with three entries mirroring the 04-HUMAN-UAT.md format (Assumption shipped / Steps / Fallback / Status ☐):
    1. UAT-DECODE — import a real 44.1 kHz WAV and an MP3 via the (Wave 3) import screen; both decode + convert without error.
    2. UAT-SOUNDS-PUT — import a converted sample; confirm it appears in /sounds and on a pad. If the upload acks but nothing appears, apply the Landmine 4 fallback (switch INIT to the path-string buildFilePutFrame for "/sounds/$name", keep paged DATA).
    3. UAT-PITCH — play the imported sample; confirm correct pitch/length (the 46875 conversion is audibly right — no detune from a wrong-rate slip).
    No code change is required to approve — this checkpoint just confirms the deferred items are documented honestly (not faked) and the defaults + fallbacks are recorded.
  </how-to-verify>
  <resume-signal>Type "approved" once 05-HUMAN-UAT.md contains the three deferred entries with documented defaults + fallbacks, or describe what's missing.</resume-signal>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| content:// audio URI -> AudioDecoder (MediaCodec) | untrusted, possibly malformed/huge media file crosses here |
| user-supplied sample name -> /sounds path | tampering / path-traversal vector into the device filesystem |
| app -> EP-133 (paged FILE_PUT) | destructive device write |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-05-03-01 | DoS | Malformed/huge audio file OOMs MediaCodec/the PCM accumulator | mitigate | Stream-decode with bounded output buffers; cap accumulated PCM size and abort with an IOException past a ceiling; catch MediaCodec exceptions; Dispatchers.IO so a bad file can't block the main thread. |
| T-05-03-02 | Tampering | Crafted sample name writes outside /sounds (traversal) | mitigate | SampleImportManager sanitizes to a safe basename + ".wav" and rejects "/", "..", control chars BEFORE putSampleFile (mirrors ProjectBackupManager filename regex guard). |
| T-05-03-03 | DoS | Oversized batch overflows /sounds storage mid-write | mitigate | Pre-flight converted size vs (storageTotalBytes - storageUsedBytes) read from deviceState (FILE_METADATA already populated); emit Error before the write (Landmine 6). |
| T-05-03-04 | Information disclosure | SAF URI read after grant expiry throws SecurityException | mitigate | Read bytes inside the picker-callback grant under Dispatchers.IO (Landmine 7); never persist the URI for deferred reads. |
| T-05-03-05 | Legal/ToS | Any Splice GraphQL/network call | mitigate | None built — verdict-level: the only source is the SAF picker. No splice.com host, no OAuth, no GraphQL anywhere in this plan. |
| T-05-03-SC | Tampering | package installs | accept | Zero external packages (MediaCodec/MediaExtractor/SAF/midi are platform). |
</threat_model>

<verification>
cd AndroidApp && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
Hardware items (UAT-DECODE / UAT-SOUNDS-PUT / UAT-PITCH) DEFERRED to 05-HUMAN-UAT.md — not verifiable without a physical EP-133.
</verification>

<success_criteria>
putSampleFile uploads to /sounds via the paged loop (SampleImportTest green); AudioDecoder + SampleImportManager compile and the full unit suite + assembleDebug + lint pass; the convert pipeline composes the Wave 1 units with the pass-through fast path; name sanitization + storage pre-flight + SAF-grant reads are in place; the three hardware-only behaviors are documented in 05-HUMAN-UAT.md with defaults and fallbacks (not faked).
</success_criteria>

<output>
Create `.planning/phases/05-splice-sample-sync/05-03-SUMMARY.md` when done.
</output>
