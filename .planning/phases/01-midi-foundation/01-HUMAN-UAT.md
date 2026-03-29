---
status: approved
phase: 01-midi-foundation
source: [01-VERIFICATION.md]
started: 2026-03-28T00:00:00.000Z
updated: 2026-03-28T00:00:00.000Z
---

## Current Test

approved

## Tests

### 1. Auto-reconnect after cable replug (CONN-02)

expected: After unplugging and re-plugging the EP-133 USB cable, the app reconnects automatically without requiring a restart or any user action. The teal dot on the DEVICE tab reappears within a second or two of replugging.
result: passed

### 2. iOS MIDI threading — no Main Thread Checker violation (CONN-01 iOS)

expected: With EP-133 connected to an iOS device, triggering MIDI input (pressing pads) produces no `[Main Thread Checker]` purple warnings in the Xcode console. No crash or hang occurs when MIDI events arrive.
result: passed

### 3. USB permission flow on fresh install (CONN-04)

expected: On a fresh install (or after clearing app data), connecting the EP-133 shows the "Waiting for USB permission…" state (CircularProgressIndicator) briefly before the system permission dialog appears. Approving shows the device. Denying shows "USB permission required" with an Open Settings button.
result: passed

## Summary

total: 3
passed: 3
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
