---
phase: 01-midi-foundation
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIManagerThreadingTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIRepositoryTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/SequencerEngineScopeTest.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt
autonomous: true
requirements: [CONN-01, CONN-02]

must_haves:
  truths:
    - "MIDI receive callbacks never arrive on the MIDI thread (onMidiReceived always invoked on main thread)"
    - "SequencerEngine does not leak coroutines after destroy — close() cancels all running jobs"
    - "MainActivity does not hold a manual CoroutineScope that outlives the lifecycle"
  artifacts:
    - path: "AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIManagerThreadingTest.kt"
      provides: "Unit test scaffolding for MIDI thread dispatch verification"
    - path: "AndroidApp/app/src/test/java/com/ep133/sampletool/SequencerEngineScopeTest.kt"
      provides: "Unit test scaffolding for SequencerEngine scope cancellation"
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt"
      provides: "Threading-safe MidiReceiver.onSend callback dispatch"
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt"
      provides: "SupervisorJob scope + close() lifecycle method"
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt"
      provides: "lifecycleScope replacing manual CoroutineScope; sequencer.close() in onDestroy"
  key_links:
    - from: "MIDIManager.startListening().MidiReceiver.onSend"
      to: "mainHandler.post {}"
      via: "Handler dispatch"
      pattern: "mainHandler\\.post"
    - from: "SequencerEngine"
      to: "SupervisorJob"
      via: "scope constructor"
      pattern: "SupervisorJob"
    - from: "MainActivity.onDestroy"
      to: "sequencer.close()"
      via: "direct call"
      pattern: "sequencer\\.close"
---

<objective>
Fix Android MIDI threading bug and coroutine scope leaks. No new features.

Purpose: The MIDI receive callback currently fires directly on Android's MIDI thread. Any downstream consumer that calls evaluateJavascript (WebView MIDI bridge) or updates Compose snapshot state from this callback will crash. SequencerEngine holds a bare CoroutineScope that is never cancelled, leaking coroutines across screen navigation and after app destroy. MainActivity holds a manual CoroutineScope instead of lifecycleScope, duplicating lifecycle management. These are pre-ship stability blockers.

Output: Three stub unit test files, MIDIManager threading fix (one-line change at line 253), SequencerEngine SupervisorJob + close() (3-line change), and MainActivity lifecycleScope migration.
</objective>

<execution_context>
@/Users/thomasphillips/.claude/get-shit-done/workflows/execute-plan.md
@/Users/thomasphillips/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/ROADMAP.md
@.planning/REQUIREMENTS.md
@.planning/phases/01-midi-foundation/01-CONTEXT.md
@.planning/phases/01-midi-foundation/01-RESEARCH.md
@.planning/phases/01-midi-foundation/01-VALIDATION.md
</context>

<interfaces>
<!-- Key types and contracts the executor needs. Extracted from codebase. -->

From AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt (line 250-255):
The threading bug is inside the MidiReceiver inside startListening(). The CURRENT (broken) code:
```
outputPort.connect(object : MidiReceiver() {
    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
        val bytes = data.copyOfRange(offset, offset + count)
        onMidiReceived?.invoke(portId, bytes)   // LINE 253 — fires on MIDI thread
    }
})
```
The fix wraps the invoke in mainHandler.post { }, exactly like notifyDevicesChanged() at line 275-279.

From AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt (line 77):
The CURRENT (broken) scope declaration:
```kotlin
private val scope = CoroutineScope(Dispatchers.Default)
```
No close() method exists. The fix adds a SupervisorJob and a close() method.

From AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt (lines 39-40):
The CURRENT (broken) scope fields:
```kotlin
private val scope = CoroutineScope(Dispatchers.Main)
private var screenOnJob: Job? = null
```
And in onDestroy() (line 107): `screenOnJob?.cancel()` — this can be removed after migration.
observeScreenOnState() at line 93 uses `scope.launch { ... }` — this becomes `lifecycleScope.launch { ... }`.

From AndroidApp/app/src/androidTest/java/com/ep133/sampletool/TestMIDIRepository.kt:
TestMIDIRepository and NoOpMIDIPort are in androidTest. Unit tests in src/test/ need their own test doubles or a shared FakeMIDIPort — do NOT import from androidTest in unit tests.
</interfaces>

<tasks>

<task type="auto">
  <name>Task 1-01: Create unit test stubs (Wave 0 — tests must exist before implementation commits)</name>
  <files>
    AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIManagerThreadingTest.kt
    AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIRepositoryTest.kt
    AndroidApp/app/src/test/java/com/ep133/sampletool/SequencerEngineScopeTest.kt
  </files>
  <action>
    The directory AndroidApp/app/src/test/java/com/ep133/sampletool/ exists but is empty. Create three stub test files in it. Each file must compile and run (all tests @Ignore or passing trivially) before the implementation tasks begin — this is the Nyquist requirement.

    MIDIManagerThreadingTest.kt: Package `com.ep133.sampletool`. Import JUnit4, `org.junit.Ignore`, `org.junit.Test`. Define class `MIDIManagerThreadingTest`. Add one @Ignore("Wave 0 stub — implement after threading fix") @Test stub named `onMidiReceived_isInvokedOnMainThread`. Do NOT import anything from androidTest or android.* — this is a JVM unit test. The test body can be empty or have a comment.

    MIDIRepositoryTest.kt: Package `com.ep133.sampletool`. Import JUnit4, `org.junit.Ignore`, `org.junit.Test`, `kotlinx.coroutines.test.runTest`. Define class `MIDIRepositoryTest`. Add one @Ignore stub named `deviceState_emitsConnectedTrueWhenDeviceAdded`. Empty body.

    SequencerEngineScopeTest.kt: Package `com.ep133.sampletool`. Import JUnit4, `org.junit.Ignore`, `org.junit.Test`, `kotlinx.coroutines.test.runTest`. Define class `SequencerEngineScopeTest`. Add one @Ignore stub named `close_cancelsPendingNoteOffJobs`. Empty body.

    All three files must compile without error. JUnit 4 and kotlinx-coroutines-test:1.7.3 are already in testImplementation in app/build.gradle.kts — no new dependencies needed.

    PITFALL: Do NOT import `android.os.Looper` or any android.* class in these files. JVM unit tests have no Android runtime. If the real threading test needs Looper, use Robolectric (not in scope for Wave 0 stubs — just make them @Ignore).
  </action>
  <verify>
    <automated>cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp && ./gradlew :app:test 2>&1 | tail -20</automated>
  </verify>
  <done>
    ./gradlew :app:test exits 0. Three stub files exist in src/test/. All tests either @Ignore or pass trivially. No compilation errors.
  </done>
</task>

<task type="auto">
  <name>Task 1-02: Fix MIDIManager.kt MIDI thread dispatch (D-05)</name>
  <files>
    AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt
  </files>
  <action>
    In MIDIManager.kt, find startListening() at line 231. Inside the MidiReceiver.onSend() callback at line 253, wrap the onMidiReceived?.invoke() call in mainHandler.post { }.

    The change is surgical — modify only the MidiReceiver.onSend() body inside startListening(). Do NOT touch any other method.

    The pattern to follow is notifyDevicesChanged() at lines 275-279 which already uses mainHandler.post { onDevicesChanged?.invoke() }. Apply the identical pattern to onMidiReceived.

    Concretely: the existing two lines inside onSend (bytes assignment + invoke) become three lines: bytes assignment, then mainHandler.post { onMidiReceived?.invoke(portId, bytes) }.

    See RESEARCH.md "Android: Full startListening fix" section for the exact code example.

    PITFALL (from RESEARCH.md Pitfall B): MutableStateFlow.value is thread-safe — do NOT add any extra dispatching to MIDIRepository._deviceState mutations. The fix is only in MIDIManager.startListening().
  </action>
  <verify>
    <automated>cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp && ./gradlew :app:test 2>&1 | tail -20</automated>
  </verify>
  <done>
    ./gradlew :app:test passes. The onSend body in startListening() is wrapped in mainHandler.post { }. grep confirms: `grep -n "mainHandler.post" AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt` shows at least two hits (notifyDevicesChanged + the new startListening fix).
  </done>
</task>

<task type="auto">
  <name>Task 1-03: Add SupervisorJob + close() to SequencerEngine (D-07)</name>
  <files>
    AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt
  </files>
  <action>
    In SequencerEngine.kt, find the scope declaration at line 77:
      `private val scope = CoroutineScope(Dispatchers.Default)`

    Replace it with:
      `private val job = SupervisorJob()`
      `private val scope = CoroutineScope(Dispatchers.Default + job)`

    Then add a close() method after the existing public API functions (after stopLiveCapture() or after clearLiveGrid(), before the private internal loops section):
      `fun close() { job.cancel() }`

    Add `SupervisorJob` to the existing kotlinx.coroutines imports at the top of the file — it is already imported as a whole-package via `import kotlinx.coroutines.*` or add `import kotlinx.coroutines.SupervisorJob` explicitly if needed.

    Do NOT change anything else in this file. The note-off fire-and-forget scope.launch() calls at line 191 remain unchanged — they will be cleaned up when job.cancel() is called, which is the correct behavior.

    Why SupervisorJob: If any child coroutine (e.g., a note-off job) throws, SupervisorJob prevents the failure from propagating to cancel the entire scope. See RESEARCH.md "Android: SequencerEngine Scope (D-07)".
  </action>
  <verify>
    <automated>cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp && ./gradlew :app:test 2>&1 | tail -20</automated>
  </verify>
  <done>
    ./gradlew :app:test passes. SequencerEngine has a public close() method. grep confirms: `grep -n "SupervisorJob\|fun close" AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt`
  </done>
</task>

<task type="auto">
  <name>Task 1-04: Migrate MainActivity to lifecycleScope + call sequencer.close() (D-07, D-08)</name>
  <files>
    AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt
  </files>
  <action>
    Two changes in MainActivity.kt:

    CHANGE 1 — Replace manual CoroutineScope with lifecycleScope (D-08):
    - Remove the `private val scope = CoroutineScope(Dispatchers.Main)` field at line 39.
    - Remove the `private var screenOnJob: Job? = null` field at line 40.
    - In observeScreenOnState(), change `screenOnJob = scope.launch {` to `lifecycleScope.launch {`.
    - In onDestroy(), remove the `screenOnJob?.cancel()` call at line 107 — lifecycleScope handles its own cancellation.
    - Add import: `import androidx.lifecycle.lifecycleScope` (see RESEARCH.md Pitfall D — this import MUST be explicit).
    - Remove the now-unused imports: `import kotlinx.coroutines.CoroutineScope`, `import kotlinx.coroutines.Job` (only if no longer used elsewhere in the file — check before removing).

    CHANGE 2 — Call sequencer.close() in onDestroy() (D-07):
    - In onDestroy(), after `super.onDestroy()`, add `sequencer.close()`.
    - Position: before the try/catch that unregisters usbReceiver, after any removed screenOnJob?.cancel() line.
    - The final onDestroy() order should be: super.onDestroy() → sequencer.close() → try { unregisterReceiver(usbReceiver) } → midiRepo.close().

    PITFALL (from RESEARCH.md Pitfall D): Without `import androidx.lifecycle.lifecycleScope`, the compiler will not find lifecycleScope even though the dependency is on the classpath. Add the explicit import.

    Do NOT change usbReceiver, onCreate, or any other method.
  </action>
  <verify>
    <automated>cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp && ./gradlew :app:test 2>&1 | tail -20</automated>
  </verify>
  <done>
    ./gradlew :app:test passes. MainActivity has no `private val scope = CoroutineScope(...)` field. observeScreenOnState() uses lifecycleScope.launch. onDestroy() calls sequencer.close(). grep confirms: `grep -n "lifecycleScope\|sequencer.close" AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt`
  </done>
</task>

</tasks>

<verification>
Run after all tasks complete:
```bash
cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp && ./gradlew :app:test
```
All three stub test files exist and compile. Tests are @Ignore or pass. No android.* imports in unit test files.

```bash
grep -n "mainHandler.post" AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt
```
At least 2 results: notifyDevicesChanged (pre-existing) + onSend in startListening (new fix).

```bash
grep -n "SupervisorJob\|fun close" AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt
```
Both present.

```bash
grep -n "lifecycleScope\|sequencer\.close\|CoroutineScope" AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt
```
lifecycleScope present, sequencer.close() present, CoroutineScope absent.
</verification>

<success_criteria>
- MIDI receive callback (MidiReceiver.onSend) is wrapped in mainHandler.post — same pattern as notifyDevicesChanged
- SequencerEngine has SupervisorJob-backed scope and a public close() method
- MainActivity uses lifecycleScope (not a manual CoroutineScope)
- MainActivity.onDestroy() calls sequencer.close() before midiRepo.close()
- Three unit test stub files exist in src/test/ and compile without errors
- ./gradlew :app:test exits 0
</success_criteria>

<output>
After completion, create `.planning/phases/01-midi-foundation/01-midi-foundation-01-SUMMARY.md` with:
- Files modified and what changed in each
- Test stubs created and their future purpose
- Any deviations from this plan
- Commit hash
</output>

---
---
phase: 01-midi-foundation
plan: 02
type: execute
wave: 1
depends_on: []
files_modified:
  - iOSApp/EP133SampleTool/MIDI/MIDIManager.swift
  - iOSApp/EP133SampleTool/MIDI/MIDIPort.swift
  - iOSApp/EP133SampleTool/App/EP133SampleToolApp.swift
autonomous: true
requirements: [CONN-01]

must_haves:
  truths:
    - "iOS MIDI receive callback (onMIDIReceived) is always invoked on the main thread"
    - "sendRawBytes allocates enough memory for any payload size — no buffer overflow on SysEx"
    - "MIDIPort Swift protocol exists as a named contract that MIDIManager conforms to"
    - "MIDIManager is instantiated once at app root and injected via SwiftUI environment"
  artifacts:
    - path: "iOSApp/EP133SampleTool/MIDI/MIDIPort.swift"
      provides: "Swift protocol mirroring Kotlin MIDIPort interface"
      exports: ["MIDIPort protocol"]
    - path: "iOSApp/EP133SampleTool/MIDI/MIDIManager.swift"
      provides: "Threading-safe handleMIDIInput; correct sendRawBytes allocation; MIDIPort conformance"
    - path: "iOSApp/EP133SampleTool/App/EP133SampleToolApp.swift"
      provides: "MIDIManagerObservable @StateObject creation + .environmentObject injection"
  key_links:
    - from: "MIDIManager.handleMIDIInput"
      to: "DispatchQueue.main.async { [weak self] in }"
      via: "GCD dispatch"
      pattern: "DispatchQueue\\.main\\.async"
    - from: "MIDIManager"
      to: "MIDIPort"
      via: "protocol conformance"
      pattern: "MIDIManager:.*MIDIPort"
    - from: "EP133SampleToolApp"
      to: "MIDIManagerObservable"
      via: "@StateObject"
      pattern: "@StateObject.*midiManager"
---

<objective>
Fix two iOS CoreMIDI bugs in place, define the MIDIPort Swift protocol, and wire MIDIManager into the SwiftUI environment. No new screens, no MIDIKit, no deployment target change.

Purpose: The iOS MIDI callback fires on CoreMIDI's private thread. Any UI update triggered from onMIDIReceived? will hit the Main Thread Checker and crash in debug builds. The sendRawBytes allocation under-allocates for any SysEx payload, causing silent memory corruption on first SysEx send. The MIDIPort protocol establishes the contract Phase 3 will build against. MIDIManager singleton injection prevents duplicate instantiation across view recreations.

Output: One new file (MIDIPort.swift), two modified files (MIDIManager.swift, EP133SampleToolApp.swift). All changes build cleanly in Xcode.
</objective>

<execution_context>
@/Users/thomasphillips/.claude/get-shit-done/workflows/execute-plan.md
@/Users/thomasphillips/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/ROADMAP.md
@.planning/REQUIREMENTS.md
@.planning/phases/01-midi-foundation/01-CONTEXT.md
@.planning/phases/01-midi-foundation/01-RESEARCH.md
</context>

<interfaces>
<!-- Key types and contracts the executor needs. Extracted from codebase. -->

From iOSApp/EP133SampleTool/MIDI/MIDIManager.swift:

Current inner types (lines 7-15):
```swift
struct MIDIDevice {
    let id: String
    let name: String
}
struct DeviceList {
    let inputs: [MIDIDevice]
    let outputs: [MIDIDevice]
}
```
These will become nested types in the MIDIPort protocol. MIDIManager will reference them via protocol typealias or direct use.

Current threading BUG at line 177:
```swift
onMIDIReceived?(portId, bytes)   // fires on CoreMIDI thread
```
The fix is to capture portId and bytes as local constants, then dispatch to main. See RESEARCH.md "iOS: Full handleMIDIInput dispatch fix".

Current sendRawBytes BUG at lines 136-143:
```swift
let packetListSize = MemoryLayout<MIDIPacketList>.size + data.count
let packetListPtr = UnsafeMutablePointer<MIDIPacketList>.allocate(capacity: 1)
```
`capacity: 1` allocates space for one MIDIPacketList struct, NOT `packetListSize` bytes. Fix uses UnsafeMutableRawPointer.allocate(byteCount:alignment:). See RESEARCH.md "iOS: sendRawBytes Allocation Fix (D-12)" for exact code.

handleMIDINotification (lines 192-194) already uses the correct pattern:
```swift
DispatchQueue.main.async { [weak self] in
    self?.onDevicesChanged?()
}
```
The onMIDIReceived fix mirrors this exactly.

From iOSApp/EP133SampleTool/App/EP133SampleToolApp.swift (current — 11 lines):
```swift
@main
struct EP133SampleToolApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea()
        }
    }
}
```

From AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIPort.kt:
The Kotlin interface the Swift protocol must mirror:
  - var onMidiReceived: ((String, ByteArray) -> Unit)?
  - var onDevicesChanged: (() -> Unit)?
  - fun getUSBDevices(): Devices
  - fun sendMidi(portId: String, data: ByteArray)
  - fun requestUSBPermissions()
  - fun refreshDevices()
  - fun startListening(portId: String)
  - fun closeAllListeners()
  - fun prewarmSendPort(portId: String)
  - fun close()
</interfaces>

<tasks>

<task type="auto">
  <name>Task 2-01: Fix MIDIManager.handleMIDIInput thread dispatch (D-11)</name>
  <files>
    iOSApp/EP133SampleTool/MIDI/MIDIManager.swift
  </files>
  <action>
    In MIDIManager.swift, find handleMIDIInput() starting at line 147. Inside the for-loop body, locate the callback call at line 177:
      `onMIDIReceived?(portId, bytes)`

    Replace it with a main-thread dispatch. Capture portId and bytes as named local constants BEFORE the async block — this avoids capturing loop variables by reference (see RESEARCH.md Pitfall A):
      ```
      let capturedPortId = portId
      let capturedBytes = bytes
      DispatchQueue.main.async { [weak self] in
          self?.onMIDIReceived?(capturedPortId, capturedBytes)
      }
      ```

    The `portId` local is determined just above at line 175. `bytes` is populated in the withUnsafePointer block at lines 160-172. Both are fully resolved before line 177, so capture is safe.

    Do NOT touch any other method. The withUnsafePointer block that follows (line 179) for packet advancement is unchanged.

    PITFALL (RESEARCH.md Pitfall A): Do NOT write `DispatchQueue.main.async { [weak self] in self?.onMIDIReceived?(portId, bytes) }` without capturing as local constants first. The loop variable may have advanced by the time the async closure executes.

    PITFALL: Do NOT use `DispatchQueue.main.sync` — that deadlocks from the CoreMIDI callback thread.
  </action>
  <verify>
    <automated>MISSING — iOS has no CLI test runner configured. Verify: open iOSApp/EP133SampleTool.xcodeproj in Xcode → Product → Build (⌘B). Build must succeed with zero errors. Also verify: Xcode Main Thread Checker is enabled (Scheme → Run → Diagnostics → Main Thread Checker) and no violation fires on app launch.</automated>
  </verify>
  <done>
    Xcode build succeeds. onMIDIReceived dispatch in handleMIDIInput uses DispatchQueue.main.async with [weak self] and captured local constants. grep confirms: `grep -n "DispatchQueue.main.async" iOSApp/EP133SampleTool/MIDI/MIDIManager.swift` shows 2+ hits (handleMIDINotification pre-existing + handleMIDIInput new fix).
  </done>
</task>

<task type="auto">
  <name>Task 2-02: Fix sendRawBytes buffer allocation (D-12)</name>
  <files>
    iOSApp/EP133SampleTool/MIDI/MIDIManager.swift
  </files>
  <action>
    In MIDIManager.swift, find sendRawBytes() at line 135. The current implementation allocates using `UnsafeMutablePointer<MIDIPacketList>.allocate(capacity: 1)` at line 137 — this allocates space for one MIDIPacketList struct, which is NOT `packetListSize` bytes.

    Replace the entire sendRawBytes function body with the corrected allocation using UnsafeMutableRawPointer. The total allocation must cover:
      - MemoryLayout<MIDIPacketList>.size (list header: numPackets field)
      - MemoryLayout<MIDIPacket>.size (packet header: timeStamp + length fields, no data bytes)
      - data.count (the actual payload bytes)

    Use UnsafeMutableRawPointer.allocate(byteCount: packetListSize, alignment: MemoryLayout<MIDIPacketList>.alignment), then bind to MIDIPacketList.self with capacity: 1. The defer { rawPtr.deallocate() } must use rawPtr, not packetListPtr.

    See RESEARCH.md "iOS: sendRawBytes Allocation Fix (D-12)" for the exact corrected implementation. The function signature remains unchanged — only the body changes.

    PITFALL (RESEARCH.md Pitfall C): The size calculation must include BOTH the MIDIPacketList header AND the MIDIPacket header. `MemoryLayout<MIDIPacketList>.size + data.count` is still wrong — it omits the MIDIPacket header bytes. Must be `MemoryLayout<MIDIPacketList>.size + MemoryLayout<MIDIPacket>.size + data.count`.
  </action>
  <verify>
    <automated>MISSING — iOS has no CLI test runner configured. Verify: Xcode build succeeds (⌘B). Manual smoke test: On device with EP-133 connected, trigger any SysEx operation (Phase 2 will add real SysEx; for now, confirm the basic build succeeds and no crash on launch).</automated>
  </verify>
  <done>
    Xcode build succeeds. sendRawBytes uses UnsafeMutableRawPointer.allocate(byteCount:alignment:). grep confirms: `grep -n "UnsafeMutableRawPointer\|UnsafeMutablePointer" iOSApp/EP133SampleTool/MIDI/MIDIManager.swift` — only UnsafeMutableRawPointer present (no capacity:1 pointer).
  </done>
</task>

<task type="auto">
  <name>Task 2-03: Create MIDIPort Swift protocol (D-13, D-14)</name>
  <files>
    iOSApp/EP133SampleTool/MIDI/MIDIPort.swift
    iOSApp/EP133SampleTool/MIDI/MIDIManager.swift
  </files>
  <action>
    PART A — Create new file iOSApp/EP133SampleTool/MIDI/MIDIPort.swift:

    Define a Swift protocol named `MIDIPort` with `AnyObject` constraint (needed because we need reference semantics for the mutable callback properties). The protocol mirrors Kotlin's MIDIPort interface at AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIPort.kt.

    Include nested types Device and DeviceList inside the protocol (or as top-level types in the same file if Swift protocol nesting is awkward — either is acceptable). These replace MIDIManager's own MIDIDevice and DeviceList inner types.

    Required protocol members (see RESEARCH.md "iOS: MIDIPort Swift Protocol (D-13, D-14)" for the full code example):
      - `var onMIDIReceived: ((String, [UInt8]) -> Void)? { get set }`
      - `var onDevicesChanged: (() -> Void)? { get set }`
      - `func setup()`
      - `func close()`
      - `func getUSBDevices() -> DeviceList`
      - `func sendMIDI(to portId: String, data: [UInt8])`
      - `func startListening(portId: String)`
      - `func stopListening(portId: String)`

    Phase 1 does NOT include requestUSBPermissions or refreshDevices in the Swift protocol — iOS handles device enumeration differently (connectAllSources). Include only the methods listed above.

    startListening and stopListening are no-ops in Phase 1 — the protocol just defines the contract for Phase 3.

    PART B — Make MIDIManager conform to MIDIPort:

    In MIDIManager.swift, update the class declaration from:
      `final class MIDIManager {`
    to:
      `final class MIDIManager: MIDIPort {`

    Add stub implementations for the two new protocol methods that MIDIManager does not yet implement:
      - `func startListening(portId: String) {}` — no-op per D-13
      - `func stopListening(portId: String) {}` — no-op per D-13

    Update MIDIManager to use the protocol's Device/DeviceList types instead of its own MIDIDevice/DeviceList inner types IF they conflict. The simplest approach: rename MIDIManager's inner structs to match the protocol's nested types, or add typealiases. See RESEARCH.md note: "The cleanest approach: move MIDIDevice/DeviceList out of MIDIManager into the MIDIPort protocol."

    IMPORTANT: MIDIManager already has getUSBDevices(), sendMIDI(to:data:), setup(), close(), onMIDIReceived, and onDevicesChanged — these already satisfy the protocol. Only startListening and stopListening are new.
  </action>
  <verify>
    <automated>MISSING — iOS has no CLI test runner configured. Verify: Xcode build succeeds (⌘B) with zero errors and zero warnings related to protocol conformance. `grep -n "MIDIManager.*MIDIPort\|: MIDIPort" iOSApp/EP133SampleTool/MIDI/MIDIManager.swift` shows conformance declaration.</automated>
  </verify>
  <done>
    MIDIPort.swift exists with protocol definition. MIDIManager conforms to MIDIPort. Xcode build succeeds. No missing protocol member warnings.
  </done>
</task>

<task type="auto">
  <name>Task 2-04: Wire MIDIManagerObservable into SwiftUI environment (D-15)</name>
  <files>
    iOSApp/EP133SampleTool/MIDI/MIDIManager.swift
    iOSApp/EP133SampleTool/App/EP133SampleToolApp.swift
  </files>
  <action>
    PART A — Add MIDIManagerObservable class to MIDIManager.swift (or a new file — co-locate with MIDIManager.swift for simplicity):

    Create a final class `MIDIManagerObservable: ObservableObject` (per D-03: iOS 16 target requires ObservableObject, NOT @Observable). It wraps a MIDIManager instance and publishes connection state:
      - `let midi = MIDIManager()`
      - `@Published var isConnected: Bool = false`
      - In init(), set midi.onDevicesChanged callback to update isConnected from midi.getUSBDevices(), then call midi.setup().

    See RESEARCH.md "iOS: MIDIManager Singleton Injection (D-15)" for the full code example. The self-reference in the onDevicesChanged closure must use [weak self] to avoid a retain cycle.

    PART B — Update EP133SampleToolApp.swift:

    The current app struct has no state (5-line struct). Add:
      - `@StateObject private var midiManager = MIDIManagerObservable()`
      - In the WindowGroup body, add `.environmentObject(midiManager)` to ContentView.

    The final EP133SampleToolApp should look like the example in RESEARCH.md "iOS: MIDIManager Singleton Injection (D-15)". ContentView itself is unchanged in Phase 1.

    IMPORTANT: MIDIManagerObservable must import Foundation and SwiftUI (for ObservableObject). If adding to MIDIManager.swift, it already imports Foundation/CoreMIDI — also add `import Combine` if @Published requires it (it may be available via SwiftUI transitively, but explicit import is safer).

    PITFALL: Do NOT instantiate MIDIManager directly in a View or in multiple places. The @StateObject in the App struct creates it once for the app lifetime.
  </action>
  <verify>
    <automated>MISSING — iOS has no CLI test runner configured. Verify: Xcode build succeeds (⌘B). On simulator: app launches without crash, no "Main Thread Checker" violations in console.</automated>
  </verify>
  <done>
    Xcode build succeeds. EP133SampleToolApp has @StateObject private var midiManager. ContentView receives .environmentObject(midiManager). MIDIManagerObservable class exists with @Published isConnected. grep confirms: `grep -n "@StateObject\|environmentObject\|MIDIManagerObservable" iOSApp/EP133SampleTool/App/EP133SampleToolApp.swift`
  </done>
</task>

</tasks>

<verification>
Xcode build must succeed with zero errors (Product → Build, ⌘B).

```bash
grep -n "DispatchQueue.main.async" iOSApp/EP133SampleTool/MIDI/MIDIManager.swift
```
At least 2 hits: handleMIDINotification (pre-existing) + handleMIDIInput (new fix).

```bash
grep -n "UnsafeMutableRawPointer" iOSApp/EP133SampleTool/MIDI/MIDIManager.swift
```
1 hit in sendRawBytes. No `UnsafeMutablePointer.*capacity: 1` present.

```bash
ls iOSApp/EP133SampleTool/MIDI/MIDIPort.swift
```
File exists.

```bash
grep -n ": MIDIPort" iOSApp/EP133SampleTool/MIDI/MIDIManager.swift
```
MIDIManager class declaration shows conformance.

```bash
grep -n "@StateObject\|MIDIManagerObservable" iOSApp/EP133SampleTool/App/EP133SampleToolApp.swift
```
Both present.
</verification>

<success_criteria>
- Xcode build succeeds with zero errors
- handleMIDIInput dispatches onMIDIReceived? to main thread via DispatchQueue.main.async
- sendRawBytes allocates byteCount bytes (not capacity:1 struct instances)
- MIDIPort.swift protocol exists and MIDIManager conforms to it
- startListening(portId:) and stopListening(portId:) are no-ops in MIDIManager (Phase 3 implements them)
- MIDIManagerObservable is created once in EP133SampleToolApp and injected as environment object
</success_criteria>

<output>
After completion, create `.planning/phases/01-midi-foundation/01-midi-foundation-02-SUMMARY.md` with:
- Files created/modified and what changed in each
- The exact lines changed in MIDIManager.swift (threading fix, sendRawBytes fix)
- Protocol design choices (type locations, conformance approach)
- Any deviations from this plan
- Commit hash
</output>

---
---
phase: 01-midi-foundation
plan: 03
type: execute
wave: 2
depends_on: [01-midi-foundation-01]
files_modified:
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/EP133.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/beats/BeatsScreen.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/sounds/SoundsScreen.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt
  - AndroidApp/app/src/androidTest/java/com/ep133/sampletool/DeviceScreenTest.kt
  - AndroidApp/app/src/androidTest/java/com/ep133/sampletool/TestMIDIRepository.kt
autonomous: true
requirements: [CONN-01, CONN-02, CONN-03, CONN-04]

must_haves:
  truths:
    - "User sees a live connection dot on the DEVICE navigation tab on all screens (connected = Teal dot visible)"
    - "DeviceScreen shows one of three states when disconnected: no device / awaiting permission / permission denied"
    - "DeviceScreen 'no device' state has a Grant Permission button that calls requestUSBPermissions()"
    - "DeviceScreen 'denied' state has an Open Settings button"
    - "Pads, Beats, and Sounds screens show a non-intrusive overlay when no device is connected"
    - "Reconnect after cable replug requires no manual action (onDeviceAdded is the authoritative trigger)"
  artifacts:
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/EP133.kt"
      provides: "PermissionState enum + permissionState field in DeviceState"
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt"
      provides: "permissionState updates in usbPermissionReceiver; 500ms delay removed"
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt"
      provides: "Three-state DeviceConnectionState composable; PermissionState.AWAITING shows CircularProgressIndicator"
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt"
      provides: "BadgedBox connection dot on DEVICE tab"
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/ui/beats/BeatsScreen.kt"
      provides: "Disconnected overlay when deviceState.connected == false"
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/ui/sounds/SoundsScreen.kt"
      provides: "Disconnected overlay when deviceState.connected == false"
  key_links:
    - from: "MIDIManager.usbPermissionReceiver"
      to: "DeviceState.permissionState"
      via: "StateFlow update via MIDIRepository.updateDeviceStateOnly()"
      pattern: "permissionState.*PermissionState"
    - from: "EP133App.NavigationBar DEVICE item"
      to: "deviceState.connected"
      via: "BadgedBox with TEColors.Teal dot"
      pattern: "BadgedBox.*TEColors.Teal"
    - from: "BeatsScreen/SoundsScreen"
      to: "deviceState.connected"
      via: "Box overlay composable"
      pattern: "disconnected.*overlay|overlay.*disconnected"
---

<objective>
Harden Android USB connection flow and add connection status UI on all screens. This is the UI-visible half of Phase 1.

Purpose: The 500ms hardcoded delay in usbPermissionReceiver is a race condition — it sometimes fires before the MIDI service enumerates the device, and sometimes fires too late. Replacing it with onDeviceAdded as the authoritative trigger (D-09) removes the race. The missing permission state model (D-19) means the app currently shows "OFFLINE" with no guidance when permission is denied. The three-state error UI closes the gap between the current binary connected/disconnected state and what CONN-04 requires. The connection badge (D-16) is a non-intrusive persistent indicator that satisfies CONN-01 across all screens.

Output: PermissionState enum + DeviceState field, MIDIManager permission state mutations, 500ms delay removal, three-state DeviceScreen composable, DEVICE tab connection badge, disconnected overlays on Pads/Beats/Sounds screens. All in Android only.
</objective>

<execution_context>
@/Users/thomasphillips/.claude/get-shit-done/workflows/execute-plan.md
@/Users/thomasphillips/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/ROADMAP.md
@.planning/REQUIREMENTS.md
@.planning/phases/01-midi-foundation/01-CONTEXT.md
@.planning/phases/01-midi-foundation/01-RESEARCH.md
@.planning/phases/01-midi-foundation/01-VALIDATION.md
@.planning/phases/01-midi-foundation/01-midi-foundation-01-SUMMARY.md
</context>

<interfaces>
<!-- Key types and contracts the executor needs. Extracted from codebase. -->

From AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/EP133.kt (lines 54-61):
Current DeviceState — NO permissionState field yet:
```kotlin
data class DeviceState(
    val connected: Boolean = false,
    val deviceName: String = "",
    val outputPortId: String? = null,
    val inputPorts: List<MidiPort> = emptyList(),
    val outputPorts: List<MidiPort> = emptyList(),
)
```
Task 3-01 adds PermissionState enum + permissionState field to DeviceState.

From AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt (lines 57-72):
The usbPermissionReceiver with 500ms delay — the broken code at line 66-68:
```kotlin
mainHandler.postDelayed({
    notifyDevicesChanged()
}, 500)
```
Task 3-02 removes this delayed call and adds permissionState updates.

From AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt:
updateDeviceStateOnly() at lines 41-59 builds DeviceState. After Task 3-01 adds the field, this function must also set permissionState. The permission state is set by MIDIManager — MIDIManager needs a way to propagate it to the DeviceState. See task 3-02 for the approach.

From AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt (lines 73-113):
The NavigationBar iterates NavRoute.entries. The DEVICE item's icon block currently renders:
```kotlin
icon = {
    Icon(imageVector = item.icon, contentDescription = item.label)
}
```
Task 3-04 wraps the DEVICE icon in BadgedBox.

TEColors.Teal is confirmed as `Color(0xFF00A69C)` per RESEARCH.md sources. Import: `import com.ep133.sampletool.ui.theme.TEColors` (already imported in DeviceScreen.kt).

For EP133App.kt to read isConnected, it needs access to deviceState. EP133App currently receives ViewModels as params. Pass `isConnected: Boolean` as an additional parameter, derived from `deviceViewModel.deviceState.collectAsState()` in MainActivity before passing to EP133App.
</interfaces>

<tasks>

<task type="auto">
  <name>Task 3-01: Add PermissionState enum and permissionState field to DeviceState (D-19)</name>
  <files>
    AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/EP133.kt
  </files>
  <action>
    In EP133.kt, add a new enum class just before the DeviceState data class:
      `enum class PermissionState { UNKNOWN, AWAITING, GRANTED, DENIED }`

    Then add `permissionState: PermissionState = PermissionState.UNKNOWN` as the last field in DeviceState. The default value UNKNOWN means "not yet attempted." All existing call sites that construct DeviceState with named parameters will continue to compile because the new field has a default.

    Do NOT change any other definition in this file. The file contains EP133Pads, EP133Sounds, and Scale definitions below line 80 — do not touch those.

    After this change, all DeviceState(...) construction sites in MIDIRepository.kt will still compile because permissionState has a default. Task 3-02 will set explicit permissionState values in MIDIManager.
  </action>
  <verify>
    <automated>cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp && ./gradlew :app:compileDebugKotlin 2>&1 | tail -20</automated>
  </verify>
  <done>
    Project compiles. PermissionState enum exists. DeviceState has permissionState field with UNKNOWN default. grep confirms: `grep -n "PermissionState\|permissionState" AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/EP133.kt`
  </done>
</task>

<task type="auto">
  <name>Task 3-02: Remove 500ms delay; add permission state tracking to MIDIManager (D-09, D-19)</name>
  <files>
    AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt
    AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt
  </files>
  <action>
    CHANGE 1 — Remove the 500ms hardcoded delay in usbPermissionReceiver (MIDIManager.kt, lines 63-69):

    Currently, when `granted == true`, the receiver calls `mainHandler.postDelayed({ notifyDevicesChanged() }, 500)`. Remove this delayed call entirely. The authoritative trigger for device re-enumeration after permission grant is `deviceCallback.onDeviceAdded()` which is already registered at line 76. When the MIDI service enumerates the device after permission is granted, onDeviceAdded fires and calls notifyDevicesChanged(). No manual trigger needed.

    PITFALL (RESEARCH.md Pitfall E): On some Android versions, onDeviceAdded fires before the device is openable. This is a conditional concern that requires physical hardware testing. For Phase 1, implement the clean fix (remove the delay) and note in the summary if onDeviceAdded-based reconnect fails in testing.

    CHANGE 2 — Track permission state in MIDIManager (D-19):

    MIDIManager needs to expose the current permission state so MIDIRepository can include it in DeviceState. The cleanest approach for Phase 1: add a `var currentPermissionState: PermissionState = PermissionState.UNKNOWN` field to MIDIManager, and update it:
      - In requestUSBPermissions(), before calling usbManager.requestPermission(): set `currentPermissionState = PermissionState.AWAITING` then call the callback via `notifyDevicesChanged()` so MIDIRepository refreshes state.
      - In usbPermissionReceiver, when `granted == true`: set `currentPermissionState = PermissionState.GRANTED`, then call `notifyDevicesChanged()`.
      - In usbPermissionReceiver, when `granted == false`: set `currentPermissionState = PermissionState.DENIED`, then call `notifyDevicesChanged()`.

    CHANGE 3 — Propagate permissionState through MIDIRepository (MIDIRepository.kt):

    MIDIRepository.updateDeviceStateOnly() constructs DeviceState. After the MIDIManager change, access `(midiManager as? MIDIManager)?.currentPermissionState ?: PermissionState.UNKNOWN` and include it in the DeviceState construction. Similarly update refreshDeviceState() at lines 87-93.

    NOTE: The MIDIPort interface does not expose permissionState. The cast `(midiManager as? MIDIManager)?` is a pragmatic Phase 1 solution — clean interface will be added in Phase 2. If the cast feels brittle, an alternative is to add a `val permissionState: PermissionState` getter to the MIDIPort interface and implement it in both MIDIManager and NoOpMIDIPort. Either approach is acceptable.

    PITFALL (RESEARCH.md Pitfall F): Set `currentPermissionState = PermissionState.AWAITING` BEFORE calling `usbManager.requestPermission()`, not after. The system dialog appears asynchronously — the state must reflect "awaiting" while the dialog is showing.
  </action>
  <verify>
    <automated>cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp && ./gradlew :app:compileDebugKotlin 2>&1 | tail -20</automated>
  </verify>
  <done>
    Project compiles. 500ms delay is removed from usbPermissionReceiver. MIDIManager has currentPermissionState field that is set to AWAITING/GRANTED/DENIED. MIDIRepository.updateDeviceStateOnly() includes permissionState in DeviceState construction. grep confirms: `grep -n "postDelayed\|currentPermissionState\|PermissionState" AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt`
  </done>
</task>

<task type="auto">
  <name>Task 3-03: Add three-state connection UI to DeviceScreen (D-19) + add CONN-04 test cases</name>
  <files>
    AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt
    AndroidApp/app/src/androidTest/java/com/ep133/sampletool/DeviceScreenTest.kt
    AndroidApp/app/src/androidTest/java/com/ep133/sampletool/TestMIDIRepository.kt
  </files>
  <action>
    PART A — Update DeviceScreen.kt:

    The existing DeviceScreen() composable currently always shows the connected/offline state inline in DeviceCard, but does not show actionable states for AWAITING or DENIED. The change is to check `deviceState.permissionState` when `!deviceState.connected` and show a state-appropriate section.

    Add a new private composable `DeviceConnectionState(permissionState, onGrantPermission, onOpenSettings)` that uses a `when` expression on permissionState:
      - UNKNOWN, GRANTED (device present but no ports): "Connect your EP-133 via USB" with USB icon + "Grant Permission" button that calls onGrantPermission
      - AWAITING: CircularProgressIndicator + "Waiting for USB permission…" text
      - DENIED: "USB permission required. Go to Settings to allow USB access." + "Open Settings" Button that calls onOpenSettings

    In DeviceScreen(), when `!deviceState.connected`, render DeviceConnectionState() at the top of the Column (before DeviceCard), passing:
      - onGrantPermission: `{ viewModel.refreshDevices() }` — this calls requestUSBPermissions() via refreshDeviceState()
      - onOpenSettings: open Android Settings with `Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)` with the app's URI

    See RESEARCH.md "Android: DeviceScreen three-state empty state pattern" for the full composable code example.

    The OFFLINE/ONLINE indicator in DeviceCard remains unchanged — it shows connection status for the connected state. DeviceConnectionState only renders when disconnected.

    Import: `import com.ep133.sampletool.domain.model.PermissionState` (in the same package).
    Import for Settings intent: `import android.content.Intent`, `import android.provider.Settings`, `import android.net.Uri`.

    PART B — Add test cases to DeviceScreenTest.kt:

    DeviceScreenTest already has 4 tests. Add three new tests for CONN-04 coverage:

    1. `deviceScreen_showsGrantPermissionButtonWhenDisconnected()` — setUpDevice() with NoOpMIDIPort (default disconnected), assertIsDisplayed for "Grant Permission".

    2. `deviceScreen_showsAwaitingStateWhenPermissionPending()` — needs a TestMIDIRepository that emits DeviceState with permissionState = PermissionState.AWAITING. Create a helper method or overload setUpDevice() with a custom TestMIDIRepository subclass that overrides deviceState.

    3. `deviceScreen_showsDeniedStateWhenPermissionDenied()` — same pattern, DENIED state.

    PART C — Update TestMIDIRepository.kt to support permission states:

    Add helper factory methods or constructor params so tests can set up specific DeviceState values. The simplest approach: add a constructor parameter `initialState: DeviceState = DeviceState()` to TestMIDIRepository and override `deviceState` to emit it. Alternatively, expose a `_deviceState: MutableStateFlow` — since `MIDIRepository.deviceState` is private, a subclass approach or a new constructor overload in TestMIDIRepository works.

    Note: TestMIDIRepository is in androidTest (not unit test src/test). The new DeviceScreenTest test cases are instrumented tests.
  </action>
  <verify>
    <automated>cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp && ./gradlew :app:compileDebugAndroidTestKotlin 2>&1 | tail -20</automated>
  </verify>
  <done>
    androidTest compilation succeeds. DeviceScreen has a DeviceConnectionState composable with when (permissionState) branches. DeviceScreenTest has 3 new test methods for CONN-04. TestMIDIRepository supports custom initial DeviceState. grep confirms: `grep -n "DeviceConnectionState\|PermissionState\|AWAITING\|DENIED" AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt`
  </done>
</task>

<task type="auto">
  <name>Task 3-04: Add connection status badge to DEVICE tab in EP133App (D-16, D-17)</name>
  <files>
    AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt
    AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt
  </files>
  <action>
    PART A — Update EP133App.kt to accept and display connection state:

    Add a new parameter `isConnected: Boolean` to EP133App(). Inside the NavigationBar loop over NavRoute.entries, find the NavigationBarItem for the DEVICE route (NavRoute.DEVICE). Wrap its `icon = { ... }` in a `BadgedBox`:

    ```
    icon = {
        if (item == NavRoute.DEVICE) {
            BadgedBox(
                badge = {
                    if (isConnected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(TEColors.Teal, CircleShape)
                        )
                    }
                }
            ) {
                Icon(imageVector = item.icon, contentDescription = item.label)
            }
        } else {
            Icon(imageVector = item.icon, contentDescription = item.label)
        }
    }
    ```

    Required imports to add to EP133App.kt:
      - `import androidx.compose.foundation.background`
      - `import androidx.compose.foundation.shape.CircleShape`
      - `import androidx.compose.foundation.layout.Box`
      - `import androidx.compose.foundation.layout.size`
      - `import androidx.compose.material3.BadgedBox`
      - `import com.ep133.sampletool.ui.theme.TEColors`

    Note: `Box` and `size` modifier may already be imported — check before adding duplicate imports.

    PART B — Update MainActivity to pass isConnected to EP133App:

    In MainActivity.onCreate(), before calling setContent { EP133App(...) }, collect deviceState from midiRepo.deviceState. Since EP133App is called inside setContent (a Composable context), use `collectAsState()` inside the composable:

    ```kotlin
    setContent {
        val deviceState by midiRepo.deviceState.collectAsState()
        EP133App(
            ...existing params...,
            isConnected = deviceState.connected,
        )
    }
    ```

    Add `import androidx.compose.runtime.collectAsState` and `import androidx.compose.runtime.getValue` if not already present in MainActivity.kt.

    PITFALL: Do NOT use `midiRepo.deviceState.value` (reading StateFlow value outside Compose) — this won't recompose. Use `.collectAsState()` inside the setContent lambda.
  </action>
  <verify>
    <automated>cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp && ./gradlew :app:compileDebugKotlin 2>&1 | tail -20</automated>
  </verify>
  <done>
    Project compiles. EP133App has isConnected parameter. DEVICE tab icon is wrapped in BadgedBox showing TEColors.Teal dot when connected. MainActivity passes deviceState.connected to EP133App. grep confirms: `grep -n "BadgedBox\|TEColors.Teal\|isConnected" AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt`
  </done>
</task>

<task type="auto">
  <name>Task 3-05: Add disconnected overlays to Pads, Beats, Sounds screens (D-20)</name>
  <files>
    AndroidApp/app/src/main/java/com/ep133/sampletool/ui/beats/BeatsScreen.kt
    AndroidApp/app/src/main/java/com/ep133/sampletool/ui/sounds/SoundsScreen.kt
  </files>
  <action>
    When no device is connected, Pads/Beats/Sounds screens must not crash or show stale interactive state. The approach is a non-intrusive overlay that informs the user and prevents interaction when disconnected.

    Both BeatsScreen and SoundsScreen currently receive their ViewModel directly. The ViewModels (BeatsViewModel, SoundsViewModel) already have access to MIDIRepository's deviceState — add `val isConnected: StateFlow<Boolean>` to each ViewModel, derived from `midi.deviceState.map { it.connected }` or simply expose `midi.deviceState` and let the screen derive it.

    Simpler approach for Phase 1: Add `val deviceState = midi.deviceState` to BeatsViewModel and SoundsViewModel (both already have `midi: MIDIRepository`), then read `.collectAsState()` in the screen composable.

    In BeatsScreen() and SoundsScreen(), collect `deviceState` and when `!deviceState.connected`, render an overlay Box over the entire screen content:
      - Box with `fillMaxSize()`, background with `MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)`, centered Column with USB icon (Icons.Default.Usb) and text "Connect EP-133 to use [BEATS/SOUNDS]".
      - The overlay does NOT navigate away (D-18 — no auto-navigation).
      - Use `Box(modifier = Modifier.fillMaxSize()) { [existing screen content]; if (!isConnected) [overlay] }` to stack the overlay on top.

    For PadsScreen: PadsViewModel is in a separate file (src/main/java/com/ep133/sampletool/ui/pads/). Apply the same pattern — add deviceState exposure to PadsViewModel, add overlay in PadsScreen. However, since PadsScreen was not modified in git status, check whether it already shows a meaningful state when disconnected (it will send MIDI to a null portId which is guarded by `?: return` in MIDIRepository). The overlay is a UX improvement — add it to PadsScreen too.

    Per project conventions (CLAUDE.md): ViewModel co-located in the same file as the Screen. BeatsViewModel is in BeatsScreen.kt, SoundsViewModel is in SoundsScreen.kt, PadsViewModel is in PadsScreen.kt. Add deviceState to each ViewModel.

    Required imports for the overlay composable:
      - `import androidx.compose.foundation.layout.Box`
      - `import androidx.compose.material.icons.filled.Usb` (already imported in EP133App but may not be in BeatsScreen)
      - `import androidx.compose.ui.graphics.Color`

    IMPORTANT: This task modifies BeatsScreen.kt and SoundsScreen.kt which are listed in git status as modified (M). Read the current file contents carefully before making changes to avoid overwriting existing work.
  </action>
  <verify>
    <automated>cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp && ./gradlew :app:compileDebugKotlin 2>&1 | tail -20</automated>
  </verify>
  <done>
    Project compiles. BeatsViewModel and SoundsViewModel expose deviceState or isConnected. BeatsScreen and SoundsScreen show a disconnected overlay when !deviceState.connected. Overlay does not navigate. grep confirms: `grep -n "connected\|overlay\|Usb" AndroidApp/app/src/main/java/com/ep133/sampletool/ui/beats/BeatsScreen.kt`
  </done>
</task>

<task type="checkpoint:human-verify" gate="blocking">
  <what-built>
    Android connection status UI across all screens. What was automated:
    - PermissionState enum + DeviceState field (Task 3-01)
    - USB permission race fix (Task 3-02): 500ms delay removed, onDeviceAdded is authoritative
    - DeviceScreen three-state composable: no device / awaiting / denied (Task 3-03)
    - DEVICE tab connection badge using TEColors.Teal (Task 3-04)
    - Disconnected overlay on Beats and Sounds screens (Task 3-05)
    - Instrumented test cases for CONN-04 states (Task 3-03)
  </what-built>
  <how-to-verify>
    Run full test suite first:
    ```bash
    cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp
    ./gradlew :app:test
    ./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ep133.sampletool.DeviceScreenTest
    ```

    Then install on device or emulator and check manually:

    1. Open the app with no EP-133 connected. Navigate to Device tab. Confirm: "Connect your EP-133 via USB" message is visible with "Grant Permission" button. The DEVICE tab icon has NO teal dot.

    2. Navigate to Beats. Confirm: a "Connect EP-133 to use BEATS" overlay is visible. The beats grid is still visible underneath (dimmed). No crash.

    3. Navigate to Sounds. Confirm: similar disconnected overlay. No crash.

    4. Connect EP-133. Navigate to Device tab. Confirm: device name appears, ONLINE status. The DEVICE tab icon now shows a small teal dot.

    5. Navigate between all tabs. Confirm: teal dot persists on the DEVICE tab icon in all screens.

    6. If EP-133 is connected: unplug cable. Confirm: teal dot disappears from DEVICE tab. Beats and Sounds show overlay. Replug cable. Confirm: app reconnects automatically without any user action (CONN-02).

    Manual check for permission state (requires USB connected device without prior permission grant):
    7. Fresh install or permissions cleared. Connect EP-133. Confirm "Waiting for USB permission…" with progress indicator appears briefly before the system dialog.
    8. Deny permission. Confirm: "USB permission required. Go to Settings…" with Open Settings button.

    CONN-03 check:
    9. After granting permission once, disconnect and reconnect. Confirm: no USB permission dialog appears again (system has cached the grant).
  </how-to-verify>
  <resume-signal>Type "approved" when all screens look correct, or describe any issues found.</resume-signal>
</task>

</tasks>

<verification>
Unit tests:
```bash
cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp && ./gradlew :app:test
```

DeviceScreen instrumented tests:
```bash
cd /Users/thomasphillips/workspace/ep_133_sample_tool/AndroidApp && ./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ep133.sampletool.DeviceScreenTest
```

Grep checks:
```bash
grep -n "PermissionState" AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/EP133.kt
grep -n "AWAITING\|GRANTED\|DENIED" AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt
grep -n "postDelayed" AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt
grep -n "BadgedBox\|TEColors.Teal" AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt
grep -n "DeviceConnectionState" AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt
```

The `postDelayed` grep must show ZERO results for the 500ms permission receiver delay (the 1000ms usbReceiver.onReceive delay in MainActivity is separate and stays).
</verification>

<success_criteria>
- CONN-01: Teal dot on DEVICE tab visible when EP-133 is connected; dot absent when disconnected
- CONN-02: App reconnects after cable replug via onDeviceAdded — no manual action required; no 500ms hardcoded delay in usbPermissionReceiver
- CONN-03: hasPermission() gating preserved; no permission re-prompt on reconnect (existing behavior preserved per D-10)
- CONN-04: DeviceScreen shows three distinct states — no device (Grant Permission), awaiting (progress indicator), denied (Open Settings)
- D-18: No auto-navigation on connect/disconnect — user stays on current screen
- D-20: Pads, Beats, Sounds screens show a non-intrusive overlay when disconnected; no crashes
- ./gradlew :app:test passes
- Instrumented DeviceScreenTest passes (3 new CONN-04 test cases + existing 4 = 7 total)
</success_criteria>

<output>
After completion, create `.planning/phases/01-midi-foundation/01-midi-foundation-03-SUMMARY.md` with:
- Files modified and what changed in each
- PermissionState model design decisions
- How permissionState flows from MIDIManager → DeviceState → UI
- Any deviations from this plan (especially re: Pitfall E onDeviceAdded timing)
- Test results summary
- Commit hash
</output>
