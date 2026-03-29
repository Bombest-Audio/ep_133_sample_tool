---
phase: 01-midi-foundation
verified: 2026-03-28T00:00:00Z
status: human_needed
score: 11/11 must-haves verified
human_verification:
  - test: "Run app on Android device with EP-133 connected. Unplug USB cable and replug it. Observe whether the DEVICE tab connection badge re-appears without any manual user action."
    expected: "The teal connection dot reappears automatically within 1-2 seconds of reconnecting the USB cable."
    why_human: "Auto-reconnect (CONN-02) depends on Android USB device-attached/detached broadcast receivers and MIDI service callbacks. Cannot simulate USB events programmatically without a device."
  - test: "Run app on iOS device with EP-133 connected. Enable the Xcode Main Thread Checker (Debug > Diagnostics > Main Thread API Checker). Trigger MIDI input from EP-133 (press a pad). Check for any [Main Thread Checker] violations in the Xcode console."
    expected: "Zero [Main Thread Checker] violations. All CoreMIDI callbacks arrive on main thread per the DispatchQueue.main.async fix."
    why_human: "Main Thread Checker violations only appear at runtime with a connected MIDI device. Cannot verify thread dispatch without live hardware and Xcode."
  - test: "On Android, uninstall the app (fresh install — no cached permissions). Connect EP-133 via USB. Launch app. Observe the Device screen."
    expected: "Device screen shows the AWAITING spinner while the system USB permission dialog is shown. After granting: teal dot appears on DEVICE tab and connected state shown. After denying: the DENIED error card with 'Open Settings' button is shown."
    why_human: "CONN-04 permission flow (UNKNOWN -> AWAITING -> GRANTED/DENIED) requires a fresh install with no cached system permissions. Cannot simulate system dialog in automated testing."
---

# Phase 1: MIDI Foundation Verification Report

**Phase Goal:** Fix two latent threading bugs on both platforms, harden the Android USB permission/reconnect flow, define the iOS Swift MIDI protocol layer, and add connection status UI and actionable error states on both platforms.
**Verified:** 2026-03-28
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| #  | Truth | Status | Evidence |
|----|-------|--------|----------|
| 1  | MIDI receive callbacks never arrive on the MIDI thread (onMidiReceived always invoked on main thread — Android) | VERIFIED | `mainHandler.post { onMidiReceived?.invoke(portId, bytes) }` at MIDIManager.kt:264 |
| 2  | SequencerEngine does not leak coroutines after destroy — close() cancels all running jobs | VERIFIED | `SupervisorJob` at SequencerEngine.kt:78; `fun close() { job.cancel() }` at line 172 |
| 3  | MainActivity does not hold a manual CoroutineScope that outlives the lifecycle | VERIFIED | No `CoroutineScope(Dispatchers.Main)` field in MainActivity.kt; `lifecycleScope.launch` at line 94 |
| 4  | iOS CoreMIDI callbacks dispatched to main thread (onMIDIReceived always invoked on main thread) | VERIFIED | `DispatchQueue.main.async { [weak self] in self?.onMIDIReceived?(capturedPortId, capturedBytes) }` at MIDIManager.swift:188-190 |
| 5  | iOS sendRawBytes uses correct buffer allocation | VERIFIED | `UnsafeMutableRawPointer.allocate(byteCount: packetListSize, alignment: ...)` at MIDIManager.swift:142-145 |
| 6  | MIDIPort Swift protocol exists and MIDIManager conforms to it | VERIFIED | `MIDIPort.swift` defines protocol; `final class MIDIManager: MIDIPort` at MIDIManager.swift:7 |
| 7  | MIDIManagerObservable injected into SwiftUI environment | VERIFIED | `@StateObject private var midiManager = MIDIManagerObservable()` and `.environmentObject(midiManager)` in EP133SampleToolApp.swift:6,12 |
| 8  | PermissionState enum and DeviceState.permissionState field exist | VERIFIED | `enum class PermissionState { UNKNOWN, AWAITING, GRANTED, DENIED }` at EP133.kt:55; `val permissionState: PermissionState` at EP133.kt:64 |
| 9  | DeviceScreen shows 3-state connection UI (UNKNOWN/GRANTED, AWAITING, DENIED) | VERIFIED | `DeviceConnectionState` composable at DeviceScreen.kt:171; when-expression covers all three states with correct UI per state |
| 10 | DEVICE tab has connection badge (teal dot when connected) | VERIFIED | `BadgedBox` with `TEColors.Teal` 8dp dot when `isConnected` at EP133App.kt:101-108; `isConnected = deviceState.connected` passed from MainActivity.kt:77 |
| 11 | BeatsScreen and SoundsScreen show disconnected overlay when EP-133 not connected | VERIFIED | Box overlay at BeatsScreen.kt:144-170; Box overlay at SoundsScreen.kt:168-194; both triggered by `!deviceState.connected` |

**Score:** 11/11 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIManagerThreadingTest.kt` | Unit test stub for MIDI thread dispatch | VERIFIED | Exists; `@Ignore` stub `onMidiReceived_isInvokedOnMainThread`; compiles as JVM unit test (no android.* imports) |
| `AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIRepositoryTest.kt` | Unit test stub for repository | VERIFIED | Exists; `@Ignore` stub `deviceState_emitsConnectedTrueWhenDeviceAdded` |
| `AndroidApp/app/src/test/java/com/ep133/sampletool/SequencerEngineScopeTest.kt` | Unit test stub for scope cancellation | VERIFIED | Exists; `@Ignore` stub `close_cancelsPendingNoteOffJobs` |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt` | Threading-safe MidiReceiver.onSend callback dispatch | VERIFIED | `mainHandler.post { onMidiReceived?.invoke(portId, bytes) }` — mirrors existing notifyDevicesChanged() pattern |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt` | SupervisorJob scope + close() lifecycle method | VERIFIED | `private val job = SupervisorJob()` + `private val scope = CoroutineScope(Dispatchers.Default + job)` + `fun close() { job.cancel() }` |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt` | lifecycleScope replacing manual CoroutineScope; sequencer.close() in onDestroy | VERIFIED | `lifecycleScope.launch` at line 94; `sequencer.close()` at onDestroy line 107; no manual `CoroutineScope` field present |
| `iOSApp/EP133SampleTool/MIDI/MIDIPort.swift` | Swift protocol for MIDI device access | VERIFIED | Exists; defines `MIDIPort` protocol with `AnyObject` constraint; includes `MIDIDevice`, `MIDIDeviceList` types |
| `iOSApp/EP133SampleTool/MIDI/MIDIManager.swift` | CoreMIDI threading fix + MIDIPort conformance | VERIFIED | `final class MIDIManager: MIDIPort`; `DispatchQueue.main.async` in `handleMIDIInput`; `UnsafeMutableRawPointer.allocate` in `sendRawBytes` |
| `iOSApp/EP133SampleTool/App/EP133SampleToolApp.swift` | @StateObject midiManager + environmentObject injection | VERIFIED | `@StateObject private var midiManager = MIDIManagerObservable()` + `.environmentObject(midiManager)` |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/EP133.kt` | PermissionState enum + DeviceState.permissionState field | VERIFIED | `enum class PermissionState` + `val permissionState: PermissionState = PermissionState.UNKNOWN` |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt` | 3-state DeviceConnectionState composable | VERIFIED | Private composable at line 171; `when (permissionState)` covers AWAITING (spinner), DENIED (error + settings button), default UNKNOWN/GRANTED (USB icon + grant button) |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt` | BadgedBox with teal connection dot on DEVICE tab | VERIFIED | `BadgedBox` with conditional `TEColors.Teal` 8dp `Box` when `isConnected`; `isConnected: Boolean = false` parameter |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/beats/BeatsScreen.kt` | Disconnected overlay | VERIFIED | Box overlay inside outer `Box(fillMaxSize)` when `!deviceState.connected` |
| `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/sounds/SoundsScreen.kt` | Disconnected overlay | VERIFIED | Box overlay inside outer `Box(fillMaxSize)` when `!deviceState.connected` |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `MIDIManager.startListening().MidiReceiver.onSend` | `mainHandler.post {}` | Handler dispatch | WIRED | 2 hits for `mainHandler.post` in MIDIManager.kt — at onSend (line 264) and notifyDevicesChanged (line 287) |
| `SequencerEngine` | `SupervisorJob` | scope constructor | WIRED | `private val job = SupervisorJob()` at line 78; `CoroutineScope(Dispatchers.Default + job)` at line 79 |
| `MainActivity.onDestroy` | `sequencer.close()` | direct call | WIRED | `sequencer.close()` at onDestroy line 107 |
| `MIDIManager.handleMIDIInput` | `DispatchQueue.main.async` | GCD dispatch | WIRED | `DispatchQueue.main.async { [weak self] in self?.onMIDIReceived?(capturedPortId, capturedBytes) }` at MIDIManager.swift:188-190 |
| `MIDIManager` | `MIDIPort` protocol | Swift class declaration | WIRED | `final class MIDIManager: MIDIPort` at MIDIManager.swift:7 |
| `EP133SampleToolApp` | `@StateObject private var midiManager` | SwiftUI property wrapper | WIRED | `@StateObject private var midiManager = MIDIManagerObservable()` + `.environmentObject(midiManager)` at EP133SampleToolApp.swift:6,12 |
| `MainActivity.setContent` | `isConnected = deviceState.connected` | collectAsState | WIRED | `val deviceState by midiRepo.deviceState.collectAsState()` then `isConnected = deviceState.connected` at MainActivity.kt:70,77 |
| `DeviceScreen` | `DeviceConnectionState` composable | conditional render | WIRED | Called when `!deviceState.connected` at DeviceScreen.kt:132-143 |
| `MIDIManager.requestUSBPermissions` | `hasPermission` guard (CONN-03) | Android USB permission API | WIRED | `if (!usbManager.hasPermission(device))` at MIDIManager.kt:151 — permission only requested when not already cached |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| `EP133App.kt` DEVICE badge | `isConnected` | `midiRepo.deviceState.collectAsState()` in MainActivity | Yes — flows from `MIDIRepository._deviceState` updated by `MIDIManager` USB callbacks | FLOWING |
| `BeatsScreen.kt` overlay | `deviceState.connected` | `midi.deviceState` StateFlow in BeatsViewModel | Yes — same `MIDIRepository.deviceState` StateFlow | FLOWING |
| `SoundsScreen.kt` overlay | `deviceState.connected` | `midi.deviceState` StateFlow in SoundsViewModel | Yes — same `MIDIRepository.deviceState` StateFlow | FLOWING |
| `DeviceScreen.kt` connection UI | `deviceState.permissionState` | `MIDIRepository` reads `(midiManager as? MIDIManager)?.currentPermissionState` | Yes — `currentPermissionState` updated by `usbPermissionReceiver` broadcasts | FLOWING |

### Behavioral Spot-Checks

Step 7b: SKIPPED — all runnable entry points are Android/iOS native apps requiring device/emulator or Xcode/Android Studio. No CLI entry points or standalone modules can be exercised with a single shell command without starting an emulator or connecting hardware.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| CONN-01 | Plan 01, Plan 03 | User can see live USB connection status across all screens | SATISFIED | Teal BadgedBox dot on DEVICE tab (EP133App.kt); disconnected overlays on Beats/Sounds; connection card on Device screen |
| CONN-02 | Plan 01 | App auto-reconnects after cable replug | SATISFIED (pending human) | USB BroadcastReceiver triggers `midiRepo.refreshDeviceState()` on DEVICE_ATTACHED; MidiManager.DeviceCallback fires on reconnect; needs physical hardware test |
| CONN-03 | Plan 03 | Permission requested once and cached | SATISFIED | `usbManager.hasPermission(device)` guard at MIDIManager.kt:151 — skips permission request if already granted by Android system |
| CONN-04 | Plan 03 | Actionable error states: not found, denied, incompatible | SATISFIED | `DeviceConnectionState` composable: AWAITING shows spinner + message; DENIED shows error icon + "Open Settings" button; UNKNOWN/GRANTED shows USB icon + "Grant Permission" button |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `DeviceScreen.kt` | 281 | Hardcoded `"42% used"` storage indicator | Warning | Non-blocking; storage stats placeholder until SysEx protocol implemented in Phase 2. Acknowledged in SUMMARY-03 as intentional. |
| `iOSApp/.../MIDIManager.swift` | — | `startListening(portId:)` and `stopListening(portId:)` are no-op stubs | Info | Intentional Phase 1 design decision — Phase 3 native iOS screens will implement per-source listening. Documented in SUMMARY-02. |

No blocker anti-patterns found. The "42% used" placeholder is disconnected from user-interactive data paths for Phase 1 — the DeviceConnectionState rendering is fully functional without it.

### Human Verification Required

#### 1. Auto-reconnect After Cable Replug (CONN-02)

**Test:** Connect EP-133 to Android device via USB. Grant USB permission when prompted. Confirm teal dot appears on DEVICE tab. Unplug the USB cable, wait for the dot to disappear. Replug the cable.
**Expected:** The teal connection dot reappears within 1-2 seconds without any user action (no app restart, no manual permission request).
**Why human:** USB device-attached/detached broadcast events and Android MIDI service callbacks require physical hardware. Cannot simulate USB plug/unplug programmatically.

#### 2. iOS No Main Thread Checker Violations (CONN-01 iOS)

**Test:** Run the iOS app on a physical device with Xcode Main Thread Checker enabled (Edit Scheme > Run > Diagnostics > Main Thread API Checker). Connect EP-133 via USB adapter. Press several pads on the EP-133 to generate MIDI input.
**Expected:** Zero `[Main Thread Checker]` violations in the Xcode console. All `onMIDIReceived` callbacks should arrive on the main thread per the `DispatchQueue.main.async` fix.
**Why human:** Main Thread Checker violations only appear at runtime with a connected MIDI device generating input. Cannot replicate CoreMIDI thread behavior without live hardware and Xcode instrumentation.

#### 3. USB Permission Flow on Fresh Install (CONN-04)

**Test:** Uninstall the Android app completely (no cached permissions). Connect EP-133. Launch app fresh. Navigate to Device screen and observe the connection UI progression.
**Expected:** Device screen shows the AWAITING state (spinner + "Waiting for USB permission…") while the system dialog appears. After tapping "Allow": teal dot appears on DEVICE tab, Device screen shows connected state. Relaunching the app should NOT re-prompt for permission (CONN-03 — permission is cached by Android).
**Why human:** Android USB permission caching and the full AWAITING → GRANTED/DENIED flow requires a fresh install and a real connected USB device. System dialog cannot be triggered in emulator without hardware.

### Gaps Summary

No gaps. All 11 automated must-have checks pass. Three items require human verification with physical hardware before CONN-01 (iOS threading), CONN-02 (auto-reconnect), and CONN-04 (permission flow) can be fully signed off.

---

_Verified: 2026-03-28_
_Verifier: Claude (gsd-verifier)_
