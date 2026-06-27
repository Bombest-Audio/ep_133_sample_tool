---
phase: 04-project-management
plan: 02
type: execute
wave: 1
depends_on: ["04-project-management-01"]
files_modified:
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/MultiChunkGetTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/SysExDispatchTest.kt
autonomous: true
requirements: [PROJ-02]
must_haves:
  truths:
    - "A multi-page GET assembles fileSize bytes across pages and stops on the empty-data terminator"
    - "A page-number mismatch in a GET response is detected and surfaced as an error, not silently accepted"
    - "An intermediate response with STATUS_SPECIFIC_SUCCESS_START keeps the transfer pending; STATUS_OK completes it"
    - "GET/PUT INIT and DATA request frames carry the correct subcommand, type, nodeId (uint16 BE) and offset/page bytes"
  artifacts:
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt"
      provides: "GET_INIT/GET_DATA + PUT_INIT/PUT_DATA frame builders and INIT/DATA response parsers"
      contains: "TE_SYSEX_FILE_GET_TYPE_INIT"
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt"
      provides: "Paged GET/PUT dispatch keeping the request alive across SUCCESS_START"
      contains: "getProjectArchive"
  key_links:
    - from: "MIDIRepository.getProjectArchive"
      to: "SysExProtocol.buildFileGetInitFrame / buildFileGetDataFrame"
      via: "INIT then paged DATA loop"
      pattern: "buildFileGetDataFrame"
    - from: "MIDIRepository dispatch"
      to: "STATUS_SPECIFIC_SUCCESS_START continuation"
      via: "keep pending while status >= SUCCESS_START, complete on STATUS_OK"
      pattern: "STATUS_SPECIFIC_SUCCESS_START"
---

<objective>
THE GATE. Replace Phase 2's broken single-byte `chunkIndex` FILE_GET/PUT model with the device's real two-phase INIT/DATA paging protocol. Project archives are multi-kilobyte `.tar` blobs; nothing downstream (project backup, restore, library, share) works until a full multi-page transfer assembles correctly. This is pure protocol + dispatch, fully unit-testable without hardware for the deterministic parts; timing-dependent paths are `@Ignore`d per repo convention and validated on hardware.

Purpose: Correct protocol foundation for PROJ-02 (single-project backup).
Output: New GET/PUT INIT/DATA builders + response parsers in SysExProtocol; a `getProjectArchive(nodeId)` paged-download and a `putProjectArchive(...)` paged-upload dispatch in MIDIRepository that keep the request registered across `STATUS_SPECIFIC_SUCCESS_START` and complete on `STATUS_OK`.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/phases/04-project-management/04-RESEARCH.md
@.planning/phases/04-project-management/04-PATTERNS.md

<interfaces>
From SysExProtocol.kt (existing, extend — do NOT modify the broken buildFileGetFrame/buildFilePutFrame, add new builders alongside):
- const TE_SYSEX_FILE=5, TE_SYSEX_FILE_GET=3, TE_SYSEX_FILE_PUT=2, CMD_PRODUCT_SPECIFIC=127, STATUS_OK=0, STATUS_SPECIFIC_SUCCESS_START=64
- fun buildFrame(deviceId, command, requestId, payload): ByteArray — 7-bit packs the full payload
- private fun buildFileSystemFrame(deviceId, fileCmd, requestId, pathBytes, extraPayload) — pattern to mirror (lines 173-183)
- fun pack7bit / unpack7bit — reuse as-is for archive bytes

From MIDIRepository.kt (existing, extend):
- open class MIDIRepository(midiManager: MIDIPort); deviceState: StateFlow<DeviceState>
- pending-deferred fields pattern (lines 74-89): `private var pendingX: CompletableDeferred<…>? = null`
- @Volatile statsQueryInFlight guard (line 80) — replicate for project transfers
- dispatchFileResponse (lines 237-271): TE_SYSEX_FILE_LIST branch already discriminates STATUS_OK vs STATUS_SPECIFIC_SUCCESS_START (lines 245-262) — the model for paged GET
- queryDeviceStatsInner (lines 319-357): send frame via midiManager.sendMidi(portId, frame); withTimeoutOrNull{ deferred.await() }; null out pending field
- _fileChunks / _fileListEntries as MutableSharedFlow with asSharedFlow() — never expose mutable
- Log tag "EP133APP" (repository), "EP133MIDI" (frame layer)
</interfaces>

Byte layouts (RESEARCH "EP-133 Project Protocol" + "Code Examples"):
- GET INIT request payload: [TE_SYSEX_FILE, TE_SYSEX_FILE_GET, GET_TYPE_INIT(0), nodeId(uint16 BE), offset(uint32 BE)]
- GET DATA request payload: [TE_SYSEX_FILE, TE_SYSEX_FILE_GET, GET_TYPE_DATA(1), page(uint16 BE)]
- GET INIT response body (after unpack7bit, header stripped): fileId=b[0]<<8|b[1]; flags=b[2]; fileSize=b[3..6] uint32 BE; fileName=null-term from b[7]
- GET DATA response body: page=b[0]<<8|b[1]; data=b[2..]; nextPage=(page+1)&0xFFFF; empty data => EOF
- PUT mirrors GET under TE_SYSEX_FILE_PUT with PUT_TYPE_INIT(0)/PUT_TYPE_DATA(1)
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Add GET/PUT INIT/DATA frame builders + response parsers to SysExProtocol</name>
  <read_first>
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt (constant block lines 32-43; buildFileSystemFrame lines 173-183; buildFileGetFrame/buildFilePutFrame lines 194-233 — the WRONG model, do not extend)
    - .planning/phases/04-project-management/04-RESEARCH.md (Code Examples lines 437-464; Multi-chunk FILE_GET lines 159-208)
    - .planning/phases/04-project-management/04-PATTERNS.md (SysExProtocol.kt section — constants to add, builder pattern)
    - AndroidApp/app/src/test/java/com/ep133/sampletool/SysExProtocolTest.kt (frame-byte assertion idiom)
  </read_first>
  <files>AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt</files>
  <behavior>
    - buildFileGetInitFrame(deviceId, nodeId, offset=0, requestId): packed payload unpacks to [5,3,0, nodeId hi, nodeId lo, offset b3..b0]; frame[8] == CMD_PRODUCT_SPECIFIC
    - buildFileGetDataFrame(deviceId, page, requestId): unpacks to [5,3,1, page hi, page lo]
    - buildFilePutInitFrame / buildFilePutDataFrame: same shape under TE_SYSEX_FILE_PUT with type 0/1; DATA carries page + the 7-bit-packed archive chunk bytes
    - parseGetInitResponse(body): returns (fileId, flags, fileSize, fileName) with fileSize decoded as uint32 BE from b[3..6] and fileName as the null-terminated ASCII string from b[7]
    - parseGetDataResponse(body): returns (page, data) where page=b[0]<<8|b[1], data=b.copyOfRange(2, size); nextPage computed as (page+1)&0xFFFF by the caller or a helper
    - A binary blob round-trips through pack7bit→unpack7bit unchanged (regression guard for archive bytes)
  </behavior>
  <action>
    Add constants TE_SYSEX_FILE_GET_TYPE_INIT=0, TE_SYSEX_FILE_GET_TYPE_DATA=1, TE_SYSEX_FILE_PUT_TYPE_INIT=0, TE_SYSEX_FILE_PUT_TYPE_DATA=1 to the existing constant block. Add private `buildXxxPayload(): ByteArray` helpers and public `buildFileGetInitFrame`, `buildFileGetDataFrame`, `buildFilePutInitFrame`, `buildFilePutDataFrame` that prepend the [TE_SYSEX_FILE, subcommand, type, ...] header and call `buildFrame(deviceId, CMD_PRODUCT_SPECIFIC, requestId, payload)` — mirror buildFileSystemFrame, do NOT route through the broken chunkIndex builders. nodeId is uint16 BE, offset is uint32 BE, page is uint16 BE. Add `parseGetInitResponse` and `parseGetDataResponse` that operate on the already-unpacked response body (the dispatcher unpacks). Per RESEARCH Assumption A3, the exact response-body offset after the [FILE,GET,TYPE] header needs hardware confirmation — write the parsers against the documented offsets and add a `// HARDWARE-VERIFY (A3): response body offset after [FILE,GET,TYPE] header` comment at the parse site.

    Fill the corresponding real assertions in ProjectProtocolTest (replacing the Wave 0 placeholder): build each frame, assert frame[8]==CMD_PRODUCT_SPECIFIC, unpack frame.copyOfRange(9, size-1), assert the subcommand/type/nodeId/offset/page bytes. Keep the pack7bit binary-blob round-trip.

    `val` over `var`; acronym casing MIDI/EP133.
  </action>
  <verify>
    <automated>JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home bash -c 'cd AndroidApp && ./gradlew :app:testDebugUnitTest --tests "*.ProjectProtocolTest"'</automated>
  </verify>
  <acceptance_criteria>
    - ProjectProtocolTest passes with real frame-byte assertions for GET_INIT, GET_DATA, PUT_INIT, PUT_DATA covering subcommand, type, nodeId BE, offset/page BE.
    - parseGetInitResponse decodes fileSize as uint32 BE and a null-terminated fileName.
    - The original buildFileGetFrame/buildFilePutFrame are untouched (no diff to lines 194-233).
    - HARDWARE-VERIFY (A3) comment present at the response-parse site.
  </acceptance_criteria>
  <done>New paged GET/PUT builders + parsers exist and are unit-asserted; broken model left intact for Phase 2 callers.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Add paged GET/PUT dispatch to MIDIRepository (the continuation state machine)</name>
  <read_first>
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt (pending fields lines 74-89; dispatchFileResponse lines 237-271, esp. FILE_LIST status discrimination 245-262 and the FILE_GET branch 263-269; queryDeviceStatsInner lines 319-357; statsQueryInFlight guard 310-316)
    - .planning/phases/04-project-management/04-RESEARCH.md (Pattern 2 lines 298-317; Pitfall 3 lines 412-416; Multi-chunk FILE_GET continuation lines 196-208)
    - .planning/phases/04-project-management/04-PATTERNS.md (MIDIRepository.kt section — dispatch + orchestration)
    - AndroidApp/app/src/test/java/com/ep133/sampletool/MultiChunkGetTest.kt, SysExDispatchTest.kt (Wave 0 stubs to fill)
  </read_first>
  <files>AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/MultiChunkGetTest.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/SysExDispatchTest.kt</files>
  <behavior>
    - Page-assembly loop: given INIT fileSize N and a sequence of DATA pages, the loop accumulates exactly N bytes then stops (extract the loop into a pure, testable function operating over an injected page-supplier so it runs without a device)
    - Page mismatch: a DATA response whose page != expected page throws/returns error "unexpected page" (do not silently accept)
    - Empty-data terminator: a DATA response with zero-length data ends the loop even if fewer than fileSize bytes (defensive)
    - Continuation: status STATUS_SPECIFIC_SUCCESS_START keeps the pending transfer registered; STATUS_OK completes it; status < SUCCESS_START with non-OK is an error and clears the pending handler
  </behavior>
  <action>
    Add a `getProjectArchive(nodeId: Int): ByteArray` suspend function that: sends the GET_INIT frame, awaits the parsed INIT (fileSize, fileName), then loops sending GET_DATA(page) frames, accumulating into a `ByteArrayOutputStream(fileSize)` until `out.size() >= fileSize` or empty-data terminator, advancing `page = (page+1)&0xFFFF`, requiring `resp.page == expectedPage`. Add the mirror `putProjectArchive(parentNodeId, slotNodeId, tarBytes)` suspend function. Model the paged transfer with a Channel/SharedFlow of pages (not a single CompletableDeferred) per Pitfall 3; in dispatchFileResponse, route the new paged-GET branch to keep the pending handler alive while `status >= STATUS_SPECIFIC_SUCCESS_START` and resolve on `STATUS_OK`, resetting the per-chunk timeout on each SUCCESS_START. Replicate the statsQueryInFlight guard as a `transferInFlight` flag to prevent overlapping transfers racing shared pending fields. Route archive DATA payloads through unpack7bit (GET) / pack7bit (PUT). Declare new pending fields with underscore-prefixed/private convention; expose nothing mutable. Use Dispatchers.IO is NOT needed here (MIDI send is the bottleneck); rethrow CancellationException. Log via "EP133APP".

    Extract the page-assembly + page-mismatch + empty-terminator logic into a pure internal function so MultiChunkGetTest can drive it with a fake page supplier (no device, no timing). Fill MultiChunkGetTest with: assembles fileSize across pages, page-mismatch throws, empty-data terminates. Fill SysExDispatchTest with the SUCCESS_START-keeps-pending / OK-completes status discrimination as a pure function test where possible; `@Ignore` any test that needs virtual-time timeout simulation with the repo's hardware-justification string.
  </action>
  <verify>
    <automated>JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home bash -c 'cd AndroidApp && ./gradlew :app:testDebugUnitTest --tests "*.MultiChunkGetTest" --tests "*.SysExDispatchTest"'</automated>
  </verify>
  <acceptance_criteria>
    - MultiChunkGetTest passes: paged assembly reaches fileSize, page-mismatch throws, empty-data terminates — all driven by a fake page supplier (no device).
    - SysExDispatchTest passes: status discrimination tested as a pure function; any timing-dependent test is `@Ignore`d with the hardware justification string.
    - getProjectArchive and putProjectArchive exist; the paged branch in dispatchFileResponse keeps the request alive across SUCCESS_START.
    - No MutableSharedFlow/MutableStateFlow exposed publicly; CancellationException rethrown; transferInFlight guard present.
  </acceptance_criteria>
  <done>Real INIT/DATA paged transfer + continuation dispatch implemented and unit-asserted on the deterministic paths; the gate is open for project backup.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| EP-133 device → app (SysEx in) | Device responses are length/structure-untrusted input parsed into buffers |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-04-03 | Tampering / DoS | malformed or oversized SysEx GET DATA response corrupts the accumulation buffer | mitigate | Length-check every response field before read; require resp.page == expectedPage; cap the accumulation buffer at the INIT-declared fileSize (+ a small slack) and abort on overflow (RESEARCH Security Domain V5) |
| T-04-04 | DoS | a never-terminating page stream hangs the transfer | mitigate | Per-chunk timeout reset on SUCCESS_START with an absolute outer `withTimeoutOrNull`; empty-data and fileSize both terminate the loop |
| T-04-05 | Tampering | CRC mismatch on assembled archive | mitigate | Accumulate CRC32 across chunks; on mismatch surface an error rather than writing a corrupt backup (consumed by Wave 2) |
| T-04-SC | Tampering | npm/pip/cargo installs | accept | No package installs this phase (RESEARCH Package Legitimacy Audit: zero new packages) |
</threat_model>

<verification>
- `--tests "*.ProjectProtocolTest" "*.MultiChunkGetTest" "*.SysExDispatchTest"` all green.
- `cd AndroidApp && ./gradlew :app:testDebugUnitTest :app:lintDebug` clean at wave merge.
- HARDWARE-VERIFY (A3) comment flags the byte-offset assumption for the Wave 2/3 hardware UAT.
</verification>

<success_criteria>
- Multi-page GET assembles full archives across pages with page-chaining, page-mismatch detection, and SUCCESS_START continuation.
- PUT mirror exists for restore.
- Phase 2's broken single-chunk model untouched; no regression to existing backup/restore tests.
</success_criteria>

<output>
Create `.planning/phases/04-project-management/04-02-SUMMARY.md` when done. Record the A3 byte-offset assumption as an open hardware-verification item.
</output>
