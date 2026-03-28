---
phase: 01-midi-foundation
plan: 01
subsystem: android-midi-threading
tags: [android, midi, threading, coroutines, stability]
dependency_graph:
  requires: []
  provides: [android-midi-threading-fix, sequencer-scope-lifecycle, test-stubs-wave0]
  affects: [AndroidApp/midi/MIDIManager.kt, AndroidApp/domain/sequencer/SequencerEngine.kt, AndroidApp/MainActivity.kt]
tech_stack:
  added: []
  patterns: [SupervisorJob-backed-scope, lifecycleScope, mainHandler.post-dispatch]
key_files:
  created:
    - AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIManagerThreadingTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIRepositoryTest.kt
    - AndroidApp/app/src/test/java/com/ep133/sampletool/SequencerEngineScopeTest.kt
  modified:
    - AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt
    - AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt
decisions:
  - "mainHandler.post{} in MidiReceiver.onSend — same pattern as notifyDevicesChanged(), no new mechanism"
  - "SupervisorJob prevents child coroutine failure from propagating to cancel the whole sequencer scope"
  - "lifecycleScope replaces manual CoroutineScope to eliminate duplicate lifecycle management"
metrics:
  duration_minutes: 25
  completed: 2026-03-28
  tasks_completed: 4
  files_modified: 6
---

# Phase 1 Plan 01: Android MIDI Threading + Coroutine Scope Fixes Summary

**One-liner:** MidiReceiver.onSend dispatch to main thread via mainHandler.post; SequencerEngine SupervisorJob scope with close(); MainActivity lifecycleScope migration.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1-01 | Create unit test stubs (Wave 0) | 1de6b8d | MIDIManagerThreadingTest.kt, MIDIRepositoryTest.kt, SequencerEngineScopeTest.kt (created) |
| 1-02 | Fix MIDIManager.kt MIDI thread dispatch | e55213d | MIDIManager.kt |
| 1-03 | Add SupervisorJob + close() to SequencerEngine | d199751 | SequencerEngine.kt |
| 1-04 | Migrate MainActivity to lifecycleScope | 17ec489 | MainActivity.kt |

## Changes per File

### MIDIManager.kt
- Line 253 (startListening/MidiReceiver.onSend): wrapped `onMidiReceived?.invoke(portId, bytes)` in `mainHandler.post { }`. Now mirrors the existing `notifyDevicesChanged()` pattern at line 276.

### SequencerEngine.kt
- Added `import kotlinx.coroutines.SupervisorJob`
- Replaced `private val scope = CoroutineScope(Dispatchers.Default)` with:
  ```kotlin
  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.Default + job)
  ```
- Added `fun close() { job.cancel() }` public method

### MainActivity.kt
- Removed `private val scope = CoroutineScope(Dispatchers.Main)` field
- Removed `private var screenOnJob: Job? = null` field
- Changed `screenOnJob = scope.launch { ... }` to `lifecycleScope.launch { ... }`
- Removed `screenOnJob?.cancel()` from onDestroy()
- Added `sequencer.close()` call in onDestroy(), before midiRepo.close()
- Added `import androidx.lifecycle.lifecycleScope`
- Removed unused imports: `CoroutineScope`, `Dispatchers`, `Job`

### Unit Test Stubs (Wave 0)
- All three test files compile and run — all tests @Ignore
- No android.* imports in unit test files (JVM test target)

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

Wave 0 test stubs are intentional placeholders. These will be implemented after the threading and scope fixes are validated in testing:
- `MIDIManagerThreadingTest.onMidiReceived_isInvokedOnMainThread` — requires Robolectric or device test to verify thread dispatch
- `MIDIRepositoryTest.deviceState_emitsConnectedTrueWhenDeviceAdded` — requires mock MIDIPort
- `SequencerEngineScopeTest.close_cancelsPendingNoteOffJobs` — implement with kotlinx-coroutines-test

## Self-Check: PASSED
