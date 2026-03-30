---
status: partial
phase: 02-android-device-management
source: [02-VERIFICATION.md]
started: 2026-03-30
updated: 2026-03-30
---

## Current Test

[awaiting human testing]

## Tests

### 1. Live Device Stats (DEV-01)
expected: Connect EP-133 via USB, launch app, navigate to Device screen. SAMPLES, STORAGE (MB), and FIRMWARE cards populate within 5 seconds. DeviceCard storage bar also populates with the correct percentage (matching STORAGE card MB values). Spinner shown in bar area while loading.
result: [pending]

### 2. SAF Backup Picker (DEV-03)
expected: Tap the "Backup" button on Device screen. Android system file creation dialog opens with suggested filename "EP133-YYYY-MM-DD-HHmm.pak".
result: [pending]

### 3. SAF Restore Picker + Confirmation Dialog (DEV-04)
expected: Tap "Restore" button, select a .pak file. Android file picker opens; after selection, an AlertDialog shows "Restore EP-133? This will overwrite all content..." with Cancel/Restore buttons. Cancel aborts; Restore triggers progress bar.
result: [pending]

### 4. Multi-Touch Pad Velocity (PERF-01)
expected: Press two different pads simultaneously with two fingers on PadsScreen. Both pads illuminate simultaneously. EP-133 hardware produces two simultaneous sounds.
result: [pending]

### 5. Sound Preview (PERF-02)
expected: Tap the play icon next to a sound in the Sounds browser. EP-133 emits the sound, then stops after ~500ms.
result: [pending]

### 6. Hardware Transport Sync (PERF-03)
expected: On BeatsScreen, tap Play — EP-133 hardware syncs to sequencer start (MIDI Start 0xFA). Tap Stop — EP-133 transport also stops (MIDI Stop 0xFC).
result: [pending]

### 7. Scale-Lock Visual Highlighting (PERF-04)
expected: Set scale to "Minor", root to "A" on Device screen; navigate to Pads screen. Pads whose note % 12 is in {0, 3, 5, 7, 10} relative to A show a teal border. Other pads show no border. Selecting "None" removes all borders.
result: [pending]

## Summary

total: 7
passed: 0
issues: 0
pending: 7
skipped: 0
blocked: 0

## Gaps
