---
phase: 01-midi-foundation
plan: 02
subsystem: ios-midi-threading
tags: [ios, swift, coremidi, threading, protocol]
dependency_graph:
  requires: []
  provides: [ios-midi-threading-fix, ios-sysex-buffer-fix, midi-port-protocol, midi-manager-observable]
  affects: [iOSApp/MIDI/MIDIManager.swift, iOSApp/MIDI/MIDIPort.swift, iOSApp/App/EP133SampleToolApp.swift]
tech_stack:
  added: [MIDIPort Swift protocol, MIDIManagerObservable ObservableObject]
  patterns: [DispatchQueue.main.async-weak-self, UnsafeMutableRawPointer-allocation, environmentObject-injection]
key_files:
  created:
    - iOSApp/EP133SampleTool/MIDI/MIDIPort.swift
  modified:
    - iOSApp/EP133SampleTool/MIDI/MIDIManager.swift
    - iOSApp/EP133SampleTool/App/EP133SampleToolApp.swift
decisions:
  - "MIDIDevice and MIDIDeviceList defined as top-level types in MIDIPort.swift — avoids protocol nested type complexity"
  - "MIDIManagerObservable co-located in MIDIManager.swift — simpler than separate file for Phase 1"
  - "ObservableObject + @Published (not @Observable) — iOS 16 deployment target requires ObservableObject"
  - "isConnected derived from getUSBDevices() in onDevicesChanged — simple boolean sufficient for Phase 1"
metrics:
  duration_minutes: 20
  completed: 2026-03-28
  tasks_completed: 4
  files_modified: 3
---

# Phase 1 Plan 02: iOS CoreMIDI Threading + Protocol Fixes Summary

**One-liner:** CoreMIDI onMIDIReceived dispatched to main thread via GCD; sendRawBytes fixed to allocate full byteCount; MIDIPort Swift protocol defined; MIDIManagerObservable injected into SwiftUI environment.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 2-01 | Fix handleMIDIInput thread dispatch | 85838f1 | MIDIManager.swift |
| 2-02 | Fix sendRawBytes buffer allocation | 0a2f344 | MIDIManager.swift |
| 2-03 | Create MIDIPort Swift protocol | fe7941f | MIDIPort.swift (created), MIDIManager.swift |
| 2-04 | Wire MIDIManagerObservable into SwiftUI environment | 7d6b457 | MIDIManager.swift, EP133SampleToolApp.swift |

## Changes per File

### MIDIManager.swift — Thread dispatch fix (Task 2-01)

Before (line 177):
```swift
onMIDIReceived?(portId, bytes)   // fires on CoreMIDI thread
```

After:
```swift
let capturedPortId = portId
let capturedBytes = bytes
DispatchQueue.main.async { [weak self] in
    self?.onMIDIReceived?(capturedPortId, capturedBytes)
}
```
Captured as local constants before async block to avoid stale loop variable capture (Research pitfall A).

### MIDIManager.swift — sendRawBytes allocation fix (Task 2-02)

Before:
```swift
let packetListSize = MemoryLayout<MIDIPacketList>.size + data.count
let packetListPtr = UnsafeMutablePointer<MIDIPacketList>.allocate(capacity: 1)
```

After:
```swift
let packetListSize = MemoryLayout<MIDIPacketList>.size
    + MemoryLayout<MIDIPacket>.size
    + data.count
let rawPtr = UnsafeMutableRawPointer.allocate(byteCount: packetListSize, alignment: ...)
let packetListPtr = rawPtr.bindMemory(to: MIDIPacketList.self, capacity: 1)
```

### MIDIPort.swift — New file (Task 2-03)

Protocol with `AnyObject` constraint. Members: `onMIDIReceived`, `onDevicesChanged`, `setup()`, `close()`, `getUSBDevices() -> MIDIDeviceList`, `sendMIDI(to:data:)`, `startListening(portId:)`, `stopListening(portId:)`.

`MIDIDevice` and `MIDIDeviceList` defined as top-level types in this file.

### MIDIManager.swift — Protocol conformance (Task 2-03)

- Class declaration: `final class MIDIManager: MIDIPort`
- Removed nested `MIDIDevice` and `DeviceList` inner structs (now in MIDIPort.swift)
- `getUSBDevices()` return type updated to `MIDIDeviceList`
- Added no-op stubs: `func startListening(portId: String) {}` and `func stopListening(portId: String) {}`

### MIDIManager.swift — MIDIManagerObservable (Task 2-04)

New class at bottom of file:
```swift
final class MIDIManagerObservable: ObservableObject {
    let midi = MIDIManager()
    @Published var isConnected: Bool = false
    init() {
        midi.onDevicesChanged = { [weak self] in ... }
        midi.setup()
    }
}
```

### EP133SampleToolApp.swift (Task 2-04)

```swift
@StateObject private var midiManager = MIDIManagerObservable()
// ContentView gets .environmentObject(midiManager)
```

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

- `startListening(portId:)` and `stopListening(portId:)` are no-ops — Phase 3 will implement per-source listening for native SwiftUI screens.

## Self-Check: PASSED
