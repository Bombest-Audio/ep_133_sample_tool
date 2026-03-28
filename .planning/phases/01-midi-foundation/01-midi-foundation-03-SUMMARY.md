---
phase: 01-midi-foundation
plan: 03
subsystem: android-connection-ui
tags: [android, compose, usb, permissions, ui]
dependency_graph:
  requires: [01-midi-foundation-01]
  provides: [permission-state-model, device-connection-badge, disconnected-overlays, conn04-test-cases]
  affects: [AndroidApp/domain/model/EP133.kt, AndroidApp/midi/MIDIManager.kt, AndroidApp/domain/midi/MIDIRepository.kt, AndroidApp/ui/device/DeviceScreen.kt, AndroidApp/ui/EP133App.kt, AndroidApp/ui/beats/BeatsScreen.kt, AndroidApp/ui/sounds/SoundsScreen.kt, AndroidApp/MainActivity.kt]
tech_stack:
  added: []
  patterns: [BadgedBox-connection-dot, Box-overlay-pattern, PermissionState-enum]
key_files:
  created: []
  modified:
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/EP133.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/beats/BeatsScreen.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/ui/sounds/SoundsScreen.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt
    - AndroidApp/app/src/androidTest/java/com/ep133/sampletool/TestMIDIRepository.kt
    - AndroidApp/app/src/androidTest/java/com/ep133/sampletool/DeviceScreenTest.kt
    - AndroidApp/app/src/androidTest/java/com/ep133/sampletool/NavigationTest.kt
decisions:
  - "PermissionState as separate enum (not folded into DeviceState.connected) — cleaner model for three-state UI"
  - "currentPermissionState as a MIDIManager field cast via (midiManager as? MIDIManager) — pragmatic Phase 1 solution; clean interface in Phase 2"
  - "Overlay pattern (not navigation) for disconnected state — aligns with D-18 no-auto-navigation requirement"
  - "deviceState open + _deviceState protected in MIDIRepository — enables TestMIDIRepository to inject initial state"
metrics:
  duration_minutes: 35
  completed: 2026-03-28
  tasks_completed: 5
  files_modified: 11
---

# Phase 1 Plan 03: Android USB Connection Status UI Summary

**One-liner:** PermissionState enum + DeviceState field; 500ms USB permission race removed; three-state DeviceScreen composable; TEColors.Teal connection badge on DEVICE tab; disconnected overlays on Beats and Sounds screens.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 3-01 | Add PermissionState enum and permissionState to DeviceState | 6733635 | EP133.kt |
| 3-02 | Remove 500ms delay; add permission state tracking | d67b819 | MIDIManager.kt, MIDIRepository.kt |
| 3-03 | Three-state DeviceConnectionState composable + CONN-04 tests | d9ee52c | DeviceScreen.kt, DeviceScreenTest.kt, TestMIDIRepository.kt, NavigationTest.kt |
| 3-04 | Connection badge on DEVICE tab | 93d4005 | EP133App.kt, MainActivity.kt |
| 3-05 | Disconnected overlays on Beats and Sounds screens | ec07c62 | BeatsScreen.kt, SoundsScreen.kt |

## Changes per File

### EP133.kt
Added before DeviceState:
```kotlin
enum class PermissionState { UNKNOWN, AWAITING, GRANTED, DENIED }
```
Added to DeviceState:
```kotlin
val permissionState: PermissionState = PermissionState.UNKNOWN,
```

### MIDIManager.kt
- Removed `mainHandler.postDelayed({ notifyDevicesChanged() }, 500)` from usbPermissionReceiver
- Added `var currentPermissionState: PermissionState = PermissionState.UNKNOWN` field
- In requestUSBPermissions(): set `currentPermissionState = PermissionState.AWAITING` before calling `usbManager.requestPermission()`
- In usbPermissionReceiver: set GRANTED or DENIED based on result
- Added `import com.ep133.sampletool.domain.model.PermissionState`

### MIDIRepository.kt
- Made `_deviceState` protected and `deviceState` open (for TestMIDIRepository subclassing)
- Both `updateDeviceStateOnly()` and `refreshDeviceState()` now include:
  ```kotlin
  val permState = (midiManager as? MIDIManager)?.currentPermissionState ?: PermissionState.UNKNOWN
  ```
  and pass it to DeviceState construction

### DeviceScreen.kt
New private composable:
```
DeviceConnectionState(permissionState, onGrantPermission, onOpenSettings)
  when AWAITING → CircularProgressIndicator + "Waiting for USB permission…"
  when DENIED → error icon + "Open Settings" Button
  else (UNKNOWN/GRANTED) → USB icon + "Grant Permission" Button
```
DeviceScreen() now renders DeviceConnectionState at top of Column when !deviceState.connected.

### EP133App.kt
- New `isConnected: Boolean = false` parameter
- DEVICE tab NavigationBarItem icon wrapped in BadgedBox showing 8dp TEColors.Teal dot when connected

### MainActivity.kt
- Collect `deviceState` via `collectAsState()` inside setContent lambda
- Pass `isConnected = deviceState.connected` to EP133App

### BeatsViewModel / BeatsScreen
- Added `val deviceState = midi.deviceState` to BeatsViewModel
- BeatsScreen wraps content in Box; shows disconnected overlay when !deviceState.connected

### SoundsViewModel / SoundsScreen
- Added `val deviceState = midi.deviceState` to SoundsViewModel
- SoundsScreen shows disconnected overlay inside existing Box when !deviceState.connected

### TestMIDIRepository.kt
- Added `initialState: DeviceState` constructor parameter
- Override `deviceState` to emit initialState
- Added `companion object { fun withPermissionState(state: PermissionState) }` factory

### DeviceScreenTest.kt
Three new CONN-04 tests:
1. `deviceScreen_showsGrantPermissionButtonWhenDisconnected`
2. `deviceScreen_showsAwaitingStateWhenPermissionPending`
3. `deviceScreen_showsDeniedStateWhenPermissionDenied`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed NavigationTest.kt missing chordsViewModel parameter**
- **Found during:** Task 3-03 androidTest compilation
- **Issue:** NavigationTest called EP133App() without the chordsViewModel parameter that was added in a previous commit. Blocked androidTest compilation.
- **Fix:** Added ChordPlayer import and `chordsViewModel = ChordsViewModel(chordPlayer)` to setUpApp()
- **Files modified:** NavigationTest.kt
- **Commit:** d9ee52c

## Checkpoint

Task 3-06 is a `checkpoint:human-verify` (blocking) — execution paused here.

The automated work is complete. Visual verification on a physical device or emulator is required before this plan can be marked fully done.

## Known Stubs

- DEVICE tab connection badge uses hardcoded 8dp dot — size not configurable. Intentional for Phase 1 simplicity.
- DeviceScreen storage indicator ("42% used") is a placeholder — will be replaced when SysEx protocol research completes in Phase 2.

## Self-Check: PASSED
