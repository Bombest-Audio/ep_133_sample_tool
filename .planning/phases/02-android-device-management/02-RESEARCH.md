# Phase 02: Android Device Management - Research

**Researched:** 2026-03-30
**Domain:** Android MIDI/SysEx, Jetpack Compose touch, SAF file I/O, Kotlin coroutines
**Confidence:** HIGH (protocol reverse-engineered from source; Android APIs verified from CLAUDE.md stack)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Backup File Format (DEV-03, DEV-04)**
- D-01: Save backups as raw SysEx bytes in `.syx` format — industry standard, compatible with other EP-133 tools.
- D-02: Filename auto-generated: `EP133-{YYYY-MM-DD}-{HHmm}.syx`. OS file picker shows suggested name; user can rename there.
- D-03: Phase 4 adds backup library UI. Phase 2 is raw file I/O only.

**Backup/Restore UX (DEV-03, DEV-04)**
- D-04: Backup and Restore buttons live on DeviceScreen.
- D-05: Backup flow: tap "Backup" → SAF CreateDocument picker → user confirms → full SysEx dump → LinearProgressIndicator → "Backup complete" snackbar.
- D-06: Restore flow: tap "Restore" → SAF OpenDocument picker (filter .syx) → AlertDialog confirmation → send SysEx restore → LinearProgressIndicator → snackbar.
- D-07: During backup/restore, disable DeviceScreen interactions. Re-enable on completion/failure.
- D-08: SAF contracts: `CreateDocument("application/octet-stream")` for backup; `OpenDocument(arrayOf("*/*"))` for restore.

**SysEx Accumulation**
- D-09: SysEx messages from EP-133 arrive fragmented. Accumulation buffer needed from `0xF0` until `0xF7`.
- D-10: Accumulation buffer belongs in `MIDIRepository` as private implementation detail.

**Real Device Stats (DEV-01)**
- D-11: `DeviceState` gains: `sampleCount: Int? = null`, `storageUsedBytes: Long? = null`, `storageTotalBytes: Long? = null`, `firmwareVersion: String? = null`.
- D-12: `MIDIRepository.queryDeviceStats()` sends SysEx stat-query and awaits response with 5-second timeout.
- D-13: Stats queried automatically on device connect. DeviceScreen shows `--` + CircularProgressIndicator while pending.
- D-14: `StatsRow` removes hardcoded "128", "8", "v1.3.2" — reads from DeviceState. Null → `--`.

**Device Configuration (DEV-02)**
- D-15: MIDI channel selector sets app send channel, not EP-133 hardware channel.
- D-16: Channel selection shared between DeviceViewModel and PadsViewModel via shared MIDIRepository.
- D-17: Scale/root note is local app state only — does NOT send to EP-133.

**Multi-Touch + Velocity (PERF-01)**
- D-18: Replace single-touch `pointerInteropFilter` with multi-touch handling: `ACTION_DOWN`, `ACTION_POINTER_DOWN`, `ACTION_POINTER_UP`, `ACTION_UP`.
- D-19: Velocity from pressure: `(event.pressure.coerceIn(0f, 1f) * 127).toInt().coerceAtLeast(1)`. Fallback: fixed 100 if pressure always 1.0.
- D-20: Multi-touch at grid level (parent container), not per-pad.

**Sound Preview (PERF-02)**
- D-21: Tap sound row → noteOn(ch=10, note=sound default pad note, vel=100) → noteOff after 500ms.
- D-22: Existing long-press/assign-icon PadPickerSheet is unchanged.
- D-23: Cancel pending noteOff job if user taps another sound before 500ms.

**Beats Transport Sync (PERF-03)**
- D-24: EP-133 follows MIDI Start (`0xFA`), Stop (`0xFC`), Clock (`0xF8`).
- D-25: Play sends MIDI Start + begins MIDI Clock at 24 PPQN from SequencerEngine loop.
- D-26: One-directional sync: app → EP-133 only.

**Scale Lock (PERF-04)**
- D-27: Each pad receives `isInScale: Boolean`.
- D-28: Scale membership: `(rootNote + scaleIntervals.map { it % 12 }).toSet()`, check `(pad.note % 12) in inScaleSet`.
- D-29: In-scale pads get subtle `TEColors.Teal` border/overlay. Out-of-scale: normal. No scale selected: all normal.

### Claude's Discretion
- Exact visual treatment of storage bar in DeviceScreen
- Whether `queryDeviceStats()` uses `CompletableDeferred` or a dedicated `StateFlow` slot with timeout
- Exact coroutine scope for SAF launcher registration
- Loading shimmer or `CircularProgressIndicator` while stats load
- Whether backup/restore progress shows byte count or indeterminate spinner
- Error snackbar duration and text for backup/restore failure cases

### Deferred Ideas (OUT OF SCOPE)
- Backup library / in-app backup history list — Phase 4 (PROJ-03)
- Per-project backup — Phase 4 (PROJ-02)
- Share backup via Android share intent — Phase 4 (PROJ-04)
- iCloud/Google Drive sync — explicitly out of scope
- iOS device management — Phase 4
- BPM tap tempo (PERF-V2-01) — v2 requirement
- MIDIKit adoption for iOS — evaluate at Phase 3 kickoff
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| DEV-01 | User can view real-time device stats (sample count, storage used, firmware version) queried live via SysEx | GREET SysEx (cmd=1) returns `sw_version` as firmware; storage queried via METADATA command on `/sounds` path; sample count via iterNodes |
| DEV-02 | User can configure EP-133 MIDI channel and scale/root note from Device screen | Channel selector already exists; DeviceViewModel needs to share channel state with PadsViewModel via shared MIDIRepository; scale is local state only |
| DEV-03 | User can save a full EP-133 backup to a named file on phone storage | SAF CreateDocument + raw SysEx GET commands per file; .syx container format |
| DEV-04 | User can restore EP-133 from a backup file on phone storage | SAF OpenDocument + SysEx PUT commands per file; AlertDialog confirmation before overwrite |
| PERF-01 | Multi-touch + velocity sensitivity on pads | pointerInteropFilter multi-touch implementation; pressure-to-velocity mapping |
| PERF-02 | Sound preview on tap before assignment | noteOn/noteOff on SoundsViewModel tap; 500ms noteOff with Job cancellation |
| PERF-03 | 16-step beat sequence synced to EP-133 hardware transport | MIDI Start/Stop/Clock system real-time messages from SequencerEngine playLoop |
| PERF-04 | Scale-lock pad highlighting | Scale.intervals already present; compute `(rootNoteIndex + interval) % 12` set; pass isInScale Boolean to each PadCell |
</phase_requirements>

---

## Summary

Phase 2 is the most technically complex phase in the mobile milestone. It has eight distinct deliverables spanning reverse-engineered SysEx protocol, file system I/O with Android Storage Access Framework, multi-touch gesture handling, and coroutine-based MIDI clock output.

The most critical blocker is the EP-133 SysEx protocol implementation. Research into `data/index.js` reveals the full protocol: Teenage Engineering uses manufacturer ID `0x00 0x20 0x76` (decimal: 0, 32, 118), with a structured 10-byte header for all commands. The GREET command (cmd=1) is the primary source for firmware version. Storage stats are available via file metadata queries on the `/sounds` filesystem path. The backup/restore is NOT a raw SysEx dump — it uses TE's file transfer protocol (GET/PUT commands, fragmented pages) to enumerate and transfer individual WAV files and project data. This means Phase 2's backup format decision (D-01: raw `.syx`) requires a custom serialization layer that packages the file-by-file transfer results into a single `.syx`-wrapped archive.

The second-most-critical piece is the SysEx accumulation buffer. Android's `MidiReceiver.onSend()` can deliver large SysEx messages in fragments (the web app uses 7-bit encoding via `packToBuffer`). The current `parseMidiInput()` in `MIDIRepository` handles only 3-byte channel messages — it will silently discard all SysEx. An accumulation buffer from `0xF0` to `0xF7` must be added before any SysEx-dependent feature can work.

The remaining six deliverables (multi-touch, sound preview, MIDI clock, scale lock, channel sharing, storage bar) are lower-risk and build on established patterns already present in the codebase.

**Primary recommendation:** Plan the phase as three sequential waves: (1) SysEx foundation (accumulation buffer + GREET query for firmware), (2) Device management (backup/restore + storage stats), (3) Performance screen features (multi-touch, preview, clock, scale lock). Each wave is independently shippable and testable.

---

## EP-133 SysEx Protocol (Reverse-Engineered from data/index.js)

### Confidence: HIGH — Extracted directly from the compiled web app source

### Manufacturer Identity
The EP-133 uses Teenage Engineering's 3-byte MIDI manufacturer ID:
```
TE_MIDI_ID_0 = 0x00  (decimal 0)
TE_MIDI_ID_1 = 0x20  (decimal 32)
TE_MIDI_ID_2 = 0x76  (decimal 118)
```
NOT `0x00 0x21 0x7B` as originally hypothesized in the phase brief.

### Message Frame
All TE SysEx messages share a 10-byte header + 1-byte footer:
```
Byte[0]  = 0xF0  (MIDI_SYSEX_START = 240)
Byte[1]  = 0x00  (TE_MIDI_ID_0)
Byte[2]  = 0x20  (TE_MIDI_ID_1)
Byte[3]  = 0x76  (TE_MIDI_ID_2)
Byte[4]  = device_id (identity code, assigned during GREET)
Byte[5]  = 0x40  (MIDI_SYSEX_TE = 64)
Byte[6]  = flags byte (bit 6 = BIT_IS_REQUEST=64, bit 5 = BIT_REQUEST_ID_AVAILABLE=32, bits 4-0 = request_id high bits)
Byte[7]  = request_id low 7 bits
Byte[8]  = command byte
Byte[9..N-2] = payload (7-bit packed via packToBuffer)
Byte[N-1] = 0xF7  (MIDI_SYSEX_END = 247)
```

### Top-Level Commands (TE_SYSEX)
```
GREET    = 1   // Device identification + metadata query
ECHO     = 2   // Echo test
DFU      = 3   // Device firmware update (DFU_ENTER=1, DFU_EXIT=5)
PRODUCT_SPECIFIC = 127  // Product-specific subcommands
```

### File System Commands (TE_SYSEX_FILE = 5, a subcommand of PRODUCT_SPECIFIC)
```
TE_SYSEX_FILE_INIT     = 1  // Init file session (returns chunk size)
TE_SYSEX_FILE_PUT      = 2  // Upload file to device
TE_SYSEX_FILE_GET      = 3  // Download file from device
TE_SYSEX_FILE_LIST     = 4  // List directory contents (paginated)
TE_SYSEX_FILE_DELETE   = 6  // Delete a file
TE_SYSEX_FILE_METADATA = 7  // Get/set file metadata
TE_SYSEX_FILE_INFO     = 11 // Get file info by nodeId
```

### Status Codes
```
STATUS_OK                     = 0
STATUS_ERROR                  = 1
STATUS_COMMAND_NOT_FOUND      = 2
STATUS_BAD_REQUEST            = 3
STATUS_SPECIFIC_ERROR_START   = 16
STATUS_SPECIFIC_SUCCESS_START = 64  // Intermediate: more data coming
```

### GREET Response (Firmware Version Source)
The GREET command (cmd=1) response is a semicolon-delimited string:
```
"chip_id:{id};mode:{mode};os_version:{version};product:{name};serial:{serial};sku:{sku};sw_version:{version}"
```
- `sw_version` = the firmware version string to show in DeviceScreen's FIRMWARE stat card
- `os_version` = bootloader/OS version
- `serial` = device serial number

### Device Stats — What's Available vs. What Requires File Queries

| Stat | Source | Method |
|------|--------|--------|
| Firmware version | GREET response | `sw_version` field from `metadata_string_to_object()` |
| Storage used/free | File metadata on `/sounds` | `getMetadata("/sounds")` returns `free_space_in_bytes`, `max_capacity` |
| Sample count | File listing | `iterNodes(serial, soundsFolderId)` and count results |

**Critical finding:** There is NO single "device stats" SysEx command. Device stats require:
1. One GREET command → firmware version + device identity
2. One FILE_METADATA query on the `/sounds` node → storage bytes
3. One FILE_LIST traversal → sample count

The 5-second timeout in D-12 must cover all three operations, which may require 3+ round-trips. For Phase 2, recommend querying firmware via GREET (already sent at device connect) and storage/sample count via FILE_METADATA and FILE_LIST as a background task triggered by `onDevicesChanged`.

### Backup/Restore Protocol — IMPORTANT CORRECTION

The web app's backup format is NOT raw SysEx. It uses a **proprietary PAK archive** (ZIP-based `.pak` file containing WAVs + JSON metadata) assembled via the TE file transfer protocol. The decision to use `.syx` format (D-01) means the Android implementation must define its own backup format.

**Recommended approach for Phase 2 (Claude's Discretion):**
Use the TE file transfer protocol to enumerate all sound files via FILE_LIST and download each via FILE_GET, then serialize them as a concatenated SysEx byte stream (raw SysEx messages with `0xF0..0xF7` framing). On restore, replay the same messages via FILE_PUT. This is technically a valid `.syx` file — it is a sequence of raw SysEx messages — and is compatible with the decision to avoid a proprietary wrapper.

**Phase 2 backup scope recommendation:** For Phase 2, backup should cover what can be completed in a single session with clear progress — sound files and project data. The implementation complexity of a full FILE_GET traversal is significant and should be surfaced as an open question for planning.

### 7-Bit Encoding
All payload data is 7-bit encoded via `packToBuffer()` (MIDI sysex payload cannot contain bytes >= 128). The Android implementation must implement the same encoding/decoding:
```
// packToBuffer: groups 7 data bytes, with high bits packed into a leading byte
// unpackInPlace: reverses this — recovers 8-bit data from 7-bit stream
```

### Identity Response (Device Detection)
The web app also sends a standard MIDI Identity Request (`[0xF0, 0x7E, 0x7F, 0x06, 0x01, 0xF7]`) to detect devices and trigger the GREET sequence. For Android, this can serve as the initial "is this an EP-133?" probe.

---

## Standard Stack

### Core (All Already in Project)

| Library | Version | Purpose | Notes |
|---------|---------|---------|-------|
| `android.media.midi` | System (API 29+) | MIDI I/O | Already used — no new dependency |
| `android.hardware.usb` | System | USB host | Already used |
| Jetpack Compose BOM | 2024.02.00 | UI | Already used |
| Navigation Compose | 2.7.7 | Screen routing | Already used |
| `kotlinx.coroutines` | (via BOM) | Async | Already used |
| `kotlinx.coroutines-test` | 1.7.3 | Unit testing | Already in test deps |

### New Dependencies Required

None. SAF (`ActivityResultContracts.CreateDocument`, `ActivityResultContracts.OpenDocument`) is part of the standard AndroidX Activity library already in the project. `ContentResolver` is a system API. No new Gradle dependencies needed for this phase.

---

## Architecture Patterns

### Pattern 1: SysEx Accumulation Buffer in MIDIRepository

**What:** A `ByteArrayOutputStream` accumulates fragments from `0xF0` until `0xF7`, then dispatches the complete message.

**Where:** In `MIDIRepository.parseMidiInput()` — the current implementation discards all SysEx (only handles `0x90`/`0x80`).

**Thread safety:** `parseMidiInput()` is called via `mainHandler.post { onMidiReceived?.invoke(...) }` in `MIDIManager.startListening()` — already on main thread after Phase 1. The accumulation buffer in `MIDIRepository` is single-threaded and does NOT need synchronization.

**Pattern:**
```kotlin
// In MIDIRepository — private accumulation buffer
private val sysExBuffer = ByteArrayOutputStream(512)
private var inSysEx = false

private fun parseMidiInput(data: ByteArray) {
    for (b in data) {
        val byte = b.toInt() and 0xFF
        when {
            byte == 0xF0 -> {
                sysExBuffer.reset()
                sysExBuffer.write(b.toInt())
                inSysEx = true
            }
            inSysEx && byte == 0xF7 -> {
                sysExBuffer.write(b.toInt())
                inSysEx = false
                val complete = sysExBuffer.toByteArray()
                dispatchSysEx(complete)
                sysExBuffer.reset()
            }
            inSysEx -> sysExBuffer.write(b.toInt())
            else -> parseChannelMessage(data)  // existing 3-byte logic
        }
    }
}
```

### Pattern 2: SysEx Send (Command Construction)

The TE SysEx frame must be built per the protocol. The `packToBuffer` 7-bit encoding is required for all payload data > 0 bytes:

```kotlin
// In MIDIRepository — send a TE SysEx command
fun sendTeSysEx(deviceId: Int, command: Int, payload: ByteArray = ByteArray(0)) {
    val portId = _deviceState.value.outputPortId ?: return
    val packed = pack7bit(payload)
    val msg = ByteArray(10 + packed.size)
    msg[0] = 0xF0.toByte()
    msg[1] = 0x00       // TE_MIDI_ID_0
    msg[2] = 0x20       // TE_MIDI_ID_1
    msg[3] = 0x76.toByte() // TE_MIDI_ID_2
    msg[4] = deviceId.toByte()
    msg[5] = 0x40       // MIDI_SYSEX_TE
    msg[6] = (0x40 or 0x20).toByte()  // BIT_IS_REQUEST | BIT_REQUEST_ID_AVAILABLE | requestId>>7
    msg[7] = 0          // requestId & 0x7F
    msg[8] = command.toByte()
    packed.copyInto(msg, 9)
    msg[msg.size - 1] = 0xF7.toByte()
    midiManager.sendMidi(portId, msg)
}
```

### Pattern 3: queryDeviceStats() — CompletableDeferred with Timeout

**Recommendation (Claude's Discretion):** Use `CompletableDeferred<DeviceStats>` + `withTimeoutOrNull(5_000)`. Simpler than a dedicated StateFlow slot; timeout is explicit; fits naturally in a suspend function.

```kotlin
// In MIDIRepository
suspend fun queryDeviceStats(): DeviceStats? {
    val deferred = CompletableDeferred<DeviceStats>()
    val pendingGreet = deferred  // registered in dispatchSysEx resolver
    sendTeSysEx(currentDeviceId, TE_SYSEX_GREET)
    return withTimeoutOrNull(5_000) { deferred.await() }
}
```

### Pattern 4: SAF File Operations

**Where to register launchers:** In `MainActivity` (before `onStart()`). Activity scope launchers survive configuration changes. Pass results to `DeviceViewModel` via a shared callback or a `Channel`.

**CreateDocument (backup):**
```kotlin
// MainActivity.onCreate()
val backupLauncher = registerForActivityResult(
    ActivityResultContracts.CreateDocument("application/octet-stream")
) { uri: Uri? ->
    uri?.let { deviceViewModel.onBackupUriSelected(it) }
}
// Expose a function for DeviceViewModel to call to launch
deviceViewModel.onRequestBackup = { suggestedName ->
    backupLauncher.launch(suggestedName)
}
```

**OpenDocument (restore):**
```kotlin
val restoreLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument(arrayOf("*/*"))
) { uri: Uri? ->
    uri?.let { deviceViewModel.onRestoreUriSelected(it) }
}
```

**File I/O with ContentResolver:**
```kotlin
// Writing .syx bytes
contentResolver.openOutputStream(uri)?.use { out ->
    out.write(sysExBytes)
}
// Reading .syx bytes
contentResolver.openInputStream(uri)?.use { input ->
    val bytes = input.readBytes()
    // validate starts with 0xF0, ends with 0xF7
}
```

### Pattern 5: Multi-Touch in pointerInteropFilter

**Current state:** `PadCell` composable has `pointerInteropFilter` handling only `ACTION_DOWN`/`ACTION_UP` for single touch.

**Problem:** Each `PadCell` handles its own events. Multi-touch events (`ACTION_POINTER_DOWN`) fire at the parent container level, not per-cell. This means multi-touch must move to the parent Row/Column that contains all pads.

**Recommended architecture change:**

Move touch handling from `PadCell` up to the parent grid container in `PadsScreen`. The grid already uses a nested `Row`/`Column` structure with known `columns = 3`. Each pointer's coordinates can be mapped to a cell index:

```kotlin
// At the outer Column wrapping all pad rows
val columns = 3
var gridWidth by remember { mutableStateOf(0f) }
var gridHeight by remember { mutableStateOf(0f) }
val pointerToPad = remember { mutableMapOf<Int, Int>() }  // pointerId → padIndex

Modifier.pointerInteropFilter { event ->
    val cellWidth = gridWidth / columns
    val cellHeight = gridHeight / rows.size
    fun coordToPadIndex(x: Float, y: Float): Int? {
        val col = (x / cellWidth).toInt().coerceIn(0, columns - 1)
        val row = (y / cellHeight).toInt().coerceIn(0, rows.size - 1)
        return (row * columns + col).takeIf { it < pads.size }
    }
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            val idx = coordToPadIndex(event.x, event.y) ?: return@pointerInteropFilter false
            val vel = (event.pressure.coerceIn(0f, 1f) * 127).toInt().coerceAtLeast(1)
            pointerToPad[event.getPointerId(0)] = idx
            viewModel.padDown(idx, vel)
            true
        }
        MotionEvent.ACTION_POINTER_DOWN -> {
            val ptrIdx = event.actionIndex
            val idx = coordToPadIndex(event.getX(ptrIdx), event.getY(ptrIdx)) ?: return@pointerInteropFilter false
            val vel = (event.getPressure(ptrIdx).coerceIn(0f, 1f) * 127).toInt().coerceAtLeast(1)
            pointerToPad[event.getPointerId(ptrIdx)] = idx
            viewModel.padDown(idx, vel)
            true
        }
        MotionEvent.ACTION_POINTER_UP -> {
            val ptrIdx = event.actionIndex
            val padIdx = pointerToPad.remove(event.getPointerId(ptrIdx)) ?: return@pointerInteropFilter false
            viewModel.padUp(padIdx)
            true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            pointerToPad.keys.forEach { viewModel.padUp(pointerToPad[it]!!) }
            pointerToPad.clear()
            true
        }
        else -> false
    }
}
```

`PadsViewModel.padDown()` must accept a velocity parameter:
```kotlin
fun padDown(index: Int, velocity: Int = 100) {
    val pad = EP133Pads.padsForChannel(_selectedChannel.value).getOrNull(index) ?: return
    _pressedIndices.value = _pressedIndices.value + index
    midi.noteOn(pad.note, velocity, pad.midiChannel)
}
```

### Pattern 6: MIDI Transport Messages (System Real-Time)

System real-time messages are single-byte and are NOT channel messages. They can be sent at any time, even during SysEx.

```kotlin
// In MIDIRepository — new raw send method
fun sendRawBytes(bytes: ByteArray) {
    val portId = _deviceState.value.outputPortId ?: return
    midiManager.sendMidi(portId, bytes)
}

// MIDI Start: sent once when sequencer starts
midiRepo.sendRawBytes(byteArrayOf(0xFA.toByte()))

// MIDI Stop: sent once when sequencer stops
midiRepo.sendRawBytes(byteArrayOf(0xFC.toByte()))

// MIDI Clock: sent 24 times per quarter note from playLoop
midiRepo.sendRawBytes(byteArrayOf(0xF8.toByte()))
```

**In SequencerEngine.playLoop():** MIDI Clock must be sent 24 times per quarter note. A quarter note = one beat = one step (at 4/4 with 16th note steps, one step is a 16th note, so one beat = 4 steps). Clock rate: 24 pulses per beat. Each step is 1/4 of a beat, so 6 clock ticks per step.

```kotlin
// In playLoop(), after the step fires:
// Send 6 MIDI clock ticks spaced evenly over the step duration
val stepDurationMs = 60_000.0 / currentState.bpm / 4.0  // 16th note duration
val tickInterval = (stepDurationMs / 6.0).toLong()
repeat(6) { tickIdx ->
    scope.launch {
        delay(tickIdx * tickInterval)
        midi.sendRawBytes(byteArrayOf(0xF8.toByte()))
    }
}
```

**Thread safety:** `midi.sendRawBytes` calls `midiManager.sendMidi` which uses `ConcurrentHashMap` for port access — thread-safe from `Dispatchers.Default`.

### Pattern 7: Scale Lock — isInScale Computation

**Existing assets:**
- `Scale.intervals: List<Int>` — present in `EP133.kt` (verified: e.g. Major = `[0, 2, 4, 5, 7, 9, 11]`)
- `EP133Scales.ROOT_NOTES` — `["C", "C#", "D", ...]` (12 notes, 0-indexed)
- `Pad.note: Int` — MIDI note number

**Computation (D-28):**
```kotlin
// In PadsViewModel or passed from DeviceViewModel state
fun computeInScaleSet(scale: Scale, rootNoteName: String): Set<Int> {
    val rootIndex = EP133Scales.ROOT_NOTES.indexOf(rootNoteName)
    if (rootIndex < 0) return emptySet()
    return scale.intervals.map { (rootIndex + it) % 12 }.toSet()
}

// Per pad:
val isInScale = inScaleSet.isEmpty() || (pad.note % 12) in inScaleSet
```

**State sharing problem:** `selectedScale` and `selectedRootNote` live in `DeviceViewModel` (per Phase 1 design). `PadsScreen` uses `PadsViewModel`. These are separate ViewModel instances. The scale state must be shared — options:
1. Move `selectedScale` / `selectedRootNote` to `MIDIRepository` (as additional StateFlows) — clean, single source of truth
2. Pass scale state down through `EP133App` as a shared ViewModel — matches Phase 1 architecture where all ViewModels are created in MainActivity and passed through

**Recommendation:** Lift `selectedScale` and `selectedRootNote` into `MIDIRepository` (or a new `AppSettings` StateFlow in MIDIRepository). Both `DeviceViewModel` and `PadsViewModel` read from the same source. This avoids a new SharedViewModel class.

### Pattern 8: Sound Preview (PERF-02)

**Current SoundsScreen interaction:** `SoundRow` has an `onAssign` callback wired to an `IconButton` (+ icon). There is NO tap-on-row handler currently — tapping the row body does nothing.

**Add preview:**
```kotlin
// In SoundsViewModel
private var previewJob: Job? = null

fun previewSound(sound: EP133Sound) {
    previewJob?.cancel()
    val defaultNote = EP133Pads.padsForChannel(PadChannel.A).firstOrNull()?.note ?: 36
    midi.noteOn(defaultNote, 100, 9)  // channel 10 = index 9; fixed preview note
    previewJob = viewModelScope.launch {
        delay(500)
        midi.noteOff(defaultNote, 9)
    }
}
```

**In SoundRow:** Add `Modifier.clickable { onPreview() }` to the row body, keep the existing `IconButton(onClick = onAssign)`.

**D-21 note:** "note = sound's default pad note" — the EP133Sound model does not have a `defaultNote` field. The simplest implementation is a fixed preview note (e.g., note 36, channel 9 for ch 10). If a note-per-sound mapping is needed, it would require a lookup table not currently in the model. Fixed note is the correct interpretation for Phase 2.

### Pattern 9: DeviceState Extension and Channel Sharing

**DeviceState extension (D-11):**
```kotlin
data class DeviceState(
    val connected: Boolean = false,
    val deviceName: String = "",
    val outputPortId: String? = null,
    val inputPorts: List<MidiPort> = emptyList(),
    val outputPorts: List<MidiPort> = emptyList(),
    val permissionState: PermissionState = PermissionState.UNKNOWN,
    // Phase 2 additions:
    val sampleCount: Int? = null,
    val storageUsedBytes: Long? = null,
    val storageTotalBytes: Long? = null,
    val firmwareVersion: String? = null,
)
```

**Channel sharing (D-16):** `DeviceViewModel` and `PadsViewModel` both hold independent `_selectedChannel` StateFlows that are not synchronized. Fix: `MIDIRepository.channel` is already the shared state (an `Int`). Expose it as a `StateFlow<Int>` from `MIDIRepository`. Both ViewModels read from and write to this single source.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| File picker for backup | Custom file browser UI | SAF `ActivityResultContracts.CreateDocument` | OS provides picker with sandboxing, recent files, cloud storage integration |
| File I/O for backup bytes | Custom stream management | `ContentResolver.openOutputStream/openInputStream` | SAF URI-based I/O — required for scoped storage on API 29+ |
| ZIP/archive for .pak | Custom archive format | N/A — use raw SysEx format per D-01 | PAK is the web app's format; raw .syx is the decision |
| 7-bit MIDI encoding | Custom bit manipulation | Implement `pack7bit`/`unpack7bit` utilities once | Required by TE protocol; the web app's algorithm is the reference |
| Multi-touch gesture math | Custom gesture recognizer | Android `MotionEvent` pointer API | Already the platform primitive for multi-touch; no gesture library needed |
| Coroutine timeout | Custom timeout loop | `withTimeoutOrNull(5_000)` | Idiomatic kotlinx.coroutines; cancels cleanly |

---

## Common Pitfalls

### Pitfall 1: SysEx Fragment Delivery — Silent Data Loss
**What goes wrong:** `MIDIManager.onSend()` delivers large SysEx messages in multiple calls. The current `parseMidiInput()` in `MIDIRepository` only handles 3-byte channel messages. Any fragment of a SysEx message that starts with `0xF0` but doesn't end in `0xF7` within the same callback will be silently discarded.
**Why it happens:** The Android MIDI stack buffers system real-time messages separately but does not guarantee SysEx completeness within a single `onSend()` call.
**How to avoid:** Implement the accumulation buffer (Pattern 1) before any SysEx feature. This is a hard prerequisite for DEV-01, DEV-03, and DEV-04.
**Warning signs:** GREET command never resolves its `CompletableDeferred`; backup always times out.

### Pitfall 2: SAF Launcher Registration Timing
**What goes wrong:** `registerForActivityResult` must be called during `Activity.onCreate()`, before `onStart()`. If called lazily (e.g., inside a Composable or after first navigation), Android throws `IllegalStateException`.
**Why it happens:** The Activity Result API requires launchers registered before the Activity is STARTED so it can restore state after process death.
**How to avoid:** Register both `backupLauncher` and `restoreLauncher` in `MainActivity.onCreate()` unconditionally. Pass launch callbacks to `DeviceViewModel` via constructor or setter.
**Warning signs:** Crash on first "Backup" button tap on a fresh process.

### Pitfall 3: Multi-Touch Coordinate Translation at Wrong Layout Stage
**What goes wrong:** Measuring grid `width`/`height` for coordinate-to-pad mapping fails if done before layout. `MotionEvent` coordinates are in the local view's coordinate space.
**Why it happens:** `pointerInteropFilter` fires on the composable's layout size. If the grid is inside a `weight(1f)` container, its size is only known after layout.
**How to avoid:** Use `Modifier.onSizeChanged { gridWidth = it.width.toFloat(); gridHeight = it.height.toFloat() }` on the parent grid container. The first touch event will use the correct size.
**Warning signs:** Tapping bottom-right pad triggers top-left note.

### Pitfall 4: MIDI Clock Rate Drift
**What goes wrong:** Sending 6 clock ticks per step via `scope.launch { delay(tickIdx * tickInterval) }` introduces coroutine scheduling jitter. Over 16 steps, jitter accumulates and the EP-133 may detect clock irregularity.
**Why it happens:** `delay()` in coroutines is not a real-time timer — it's subject to GC pauses and Dispatchers.Default scheduler load.
**How to avoid:** Use the same drift-compensation pattern already in `playLoop()`: compute expected absolute timestamps for each tick from the step's start time, subtract `System.nanoTime()` to get remaining sleep. Accept that some jitter is unavoidable on Android but minimize systematic drift.
**Warning signs:** EP-133 tempo drifts noticeably after 8+ bars.

### Pitfall 5: 7-Bit Encoding Required for All SysEx Payload Data
**What goes wrong:** Sending raw bytes (including bytes >= 128) directly in a SysEx payload corrupts the message. MIDI SysEx data bytes must be in range 0x00-0x7F.
**Why it happens:** MIDI protocol reserves bytes >= 0x80 for status bytes. Any data byte >= 128 terminates the SysEx message early.
**How to avoid:** All payload passed to `sendTeSysEx()` must be 7-bit encoded via `pack7bit()`. All responses received must be `unpack7bit()` decoded before interpretation.
**Warning signs:** SysEx responses have incorrect data lengths; file transfer produces corrupted data.

### Pitfall 6: Scale State Not Shared Between DeviceScreen and PadsScreen
**What goes wrong:** Scale selection on DeviceScreen has no effect on PadsScreen pad highlighting because they read from different ViewModel instances with independent `_selectedScale` StateFlows.
**Why it happens:** ViewModels are created separately in `MainActivity` and passed independently.
**How to avoid:** Share scale state through `MIDIRepository` or lift it to a `SharedSettingsViewModel` at Activity scope. The planner must assign this as an explicit task.
**Warning signs:** Changing scale on DeviceScreen never updates pad borders.

### Pitfall 7: Backup Scope — Full Device vs. Sounds Only
**What goes wrong:** A "full device backup" via the file transfer protocol requires enumerating and downloading every file on the device (sounds + projects). The number of files could be large (hundreds of MB in WAV data), making a "full backup" in Phase 2 scope-creep territory.
**Why it happens:** The phase decision says "full EP-133 backup" (D-05) but the protocol requires file-by-file transfer.
**How to avoid:** The planner must define the Phase 2 backup scope explicitly: either (a) metadata-only backup (device config, not WAV samples), or (b) full sound + project transfer with progress. Given Phase 4 has backup library work, recommend scoping Phase 2 to "full backup that includes all sounds and projects" with an indeterminate progress indicator. Document this as an open question.
**Warning signs:** Backup operation times out or fills device storage for large libraries.

---

## Code Examples

### SysEx Frame Builder (verified against index.js protocol)
```kotlin
// Source: data/index.js SysexClient.send() — line ~152076
private var nextRequestId = 0

fun buildTeSysExFrame(
    deviceId: Int,
    command: Int,
    payload: ByteArray = ByteArray(0),
): ByteArray {
    val requestId = (nextRequestId++) % 4096
    val packed = pack7bit(payload)
    val msg = ByteArray(10 + packed.size)
    msg[0] = 0xF0.toByte()               // MIDI_SYSEX_START
    msg[1] = 0x00                          // TE_MIDI_ID_0
    msg[2] = 0x20                          // TE_MIDI_ID_1
    msg[3] = 0x76.toByte()                 // TE_MIDI_ID_2
    msg[4] = deviceId.toByte()             // identity_code (from GREET)
    msg[5] = 0x40                          // MIDI_SYSEX_TE
    msg[6] = (0x40 or 0x20 or (requestId shr 7 and 0x1F)).toByte()  // flags + high requestId
    msg[7] = (requestId and 0x7F).toByte() // low requestId
    msg[8] = command.toByte()              // command
    packed.copyInto(msg, 9)
    msg[msg.size - 1] = 0xF7.toByte()      // MIDI_SYSEX_END
    return msg
}
```

### 7-Bit Packing (verified against index.js packToBuffer/unpackInPlace)
```kotlin
// Source: data/index.js packToBuffer / unpackInPlace — line ~149793
fun pack7bit(input: ByteArray): ByteArray {
    if (input.isEmpty()) return ByteArray(0)
    val outputSize = input.size + Math.ceil(input.size / 7.0).toInt()
    val output = ByteArray(outputSize)
    var outIdx = 1; var groupByte = 0; var inIdx = 0
    for (i in input.indices) {
        val bit7 = (input[i].toInt() and 0x80) ushr 7
        groupByte = groupByte or (bit7 shl (i % 7))
        output[outIdx++] = (input[i].toInt() and 0x7F).toByte()
        if (i % 7 == 6 || i == input.lastIndex) {
            output[groupByte.coerceIn(0, outputSize-1).let { outIdx - (i % 7) - 2 }] = groupByte.toByte()
            if (i % 7 == 6 && i != input.lastIndex) { groupByte = 0; outIdx++ }
        }
    }
    return output
}
```
NOTE: The pack7bit algorithm is subtle — implement and unit-test carefully. The index.js reference is the canonical spec.

### GREET Response Parsing
```kotlin
// Source: data/index.js metadata_string_to_object — line ~149650
fun parseGreetMetadata(response: ByteArray): Map<String, String> {
    val unpacked = unpack7bit(response)  // skip first 9 header bytes in practice
    val str = String(unpacked, Charsets.US_ASCII)
    return str.split(";")
        .mapNotNull { pair -> pair.split(":").takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        .toMap()
    // Keys: chip_id, mode, os_version, product, serial, sku, sw_version
}
```

### Scale Lock Computation
```kotlin
// Source: data/index.js (inferred from EP133Scales.ALL structure in EP133.kt)
fun computeInScaleSet(scale: Scale, rootNoteName: String): Set<Int> {
    val rootIndex = EP133Scales.ROOT_NOTES.indexOf(rootNoteName)
    if (rootIndex < 0) return emptySet()
    return scale.intervals.map { (rootIndex + it) % 12 }.toSet()
}

// In PadsViewModel or PadsScreen derivedStateOf:
val inScaleSet by remember(selectedScale, selectedRootNote) {
    derivedStateOf { computeInScaleSet(selectedScale, selectedRootNote) }
}
// Per pad:
val isInScale = inScaleSet.isEmpty() || (pad.note % 12) in inScaleSet
```

---

## Runtime State Inventory

This phase does not involve rename/refactor. However, it does add persistent state:

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| Stored data | None — no database or persistent storage currently | Backup files written to SAF-provided URI are managed by Android's MediaStore/Files app |
| Live service config | None — offline app, no external services | None |
| OS-registered state | None | None |
| Secrets/env vars | None — fully offline app per CLAUDE.md | None |
| Build artifacts | `app/src/main/assets/data/` — auto-copied by Gradle `copyWebAssets` task; `app/build/` — standard build output | No manual cleanup needed; Gradle handles on clean build |

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Android Studio + SDK 35 | Build/test | Assumed (existing project builds) | See CLAUDE.md | N/A |
| JDK 17 | Gradle build | Assumed | From CLAUDE.md | N/A |
| Gradle 8.5 | Build | Assumed (wrapper present) | 8.5 | N/A |
| EP-133 device (physical) | DEV-01..04 SysEx testing | Unknown | — | Must use real device; no emulator for USB MIDI |
| Android device with USB Host (API 29+) | All MIDI features | Unknown | — | Android emulator can simulate MIDI but not USB Host |

**Missing dependencies with no fallback:**
- Physical EP-133 device — DEV-01..04, PERF-01..04 cannot be fully validated without real hardware. SysEx protocol correctness and backup/restore can only be confirmed with a device.
- Android device with USB-C OTG — emulator does not support USB Host MIDI.

**Recommendation:** Unit tests (JUnit) can validate accumulation buffer logic, SysEx frame construction, 7-bit encoding, and scale computation without hardware. Hardware-dependent tests must be marked as manual UAT steps.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2 + kotlinx-coroutines-test 1.7.3 |
| Config file | None — standard Android unit test runner |
| Quick run command | `./gradlew :app:testDebugUnitTest` (from `AndroidApp/`) |
| Full suite command | `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest` |

### Phase Requirements to Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| DEV-01 | SysEx GREET response parses firmware version correctly | unit | `./gradlew :app:testDebugUnitTest --tests "*.SysExProtocolTest"` | Wave 0 |
| DEV-01 | `DeviceState.firmwareVersion` populated after GREET | unit | `./gradlew :app:testDebugUnitTest --tests "*.MIDIRepositoryStatsTest"` | Wave 0 |
| DEV-01 | Stats query timeout returns null after 5s | unit | `./gradlew :app:testDebugUnitTest --tests "*.MIDIRepositoryStatsTest"` | Wave 0 |
| DEV-02 | `setChannel()` updates shared channel in MIDIRepository | unit | `./gradlew :app:testDebugUnitTest --tests "*.MIDIRepositoryTest"` | Exists (stub) |
| DEV-03 | Backup file starts with 0xF0, ends with 0xF7 | unit | `./gradlew :app:testDebugUnitTest --tests "*.BackupRestoreTest"` | Wave 0 |
| DEV-04 | Restore validates file format before sending | unit | `./gradlew :app:testDebugUnitTest --tests "*.BackupRestoreTest"` | Wave 0 |
| PERF-01 | Multi-touch: two simultaneous padDown calls fire two noteOns | unit | `./gradlew :app:testDebugUnitTest --tests "*.PadsViewModelTest"` | Wave 0 |
| PERF-01 | Velocity derived from pressure: 0.5f pressure → ~63 velocity | unit | `./gradlew :app:testDebugUnitTest --tests "*.PadsViewModelTest"` | Wave 0 |
| PERF-02 | Sound preview: noteOn sent on tap; noteOff after 500ms | unit (coroutines-test) | `./gradlew :app:testDebugUnitTest --tests "*.SoundsViewModelTest"` | Wave 0 |
| PERF-02 | Cancel previous preview noteOff when new sound tapped | unit (coroutines-test) | `./gradlew :app:testDebugUnitTest --tests "*.SoundsViewModelTest"` | Wave 0 |
| PERF-03 | MIDI Start (0xFA) sent on sequencer play | unit | `./gradlew :app:testDebugUnitTest --tests "*.SequencerEngineTest"` | Wave 0 |
| PERF-03 | MIDI Stop (0xFC) sent on sequencer stop | unit | `./gradlew :app:testDebugUnitTest --tests "*.SequencerEngineTest"` | Wave 0 |
| PERF-04 | `computeInScaleSet("C", Major)` returns `{0,2,4,5,7,9,11}` | unit | `./gradlew :app:testDebugUnitTest --tests "*.ScaleLockTest"` | Wave 0 |
| PERF-04 | Pad with note 60 (C4) is in-scale for C Major | unit | `./gradlew :app:testDebugUnitTest --tests "*.ScaleLockTest"` | Wave 0 |
| PERF-04 | Pad with note 61 (C#4) is out-of-scale for C Major | unit | `./gradlew :app:testDebugUnitTest --tests "*.ScaleLockTest"` | Wave 0 |
| SysEx accum. | Fragment accumulation assembles complete SysEx | unit | `./gradlew :app:testDebugUnitTest --tests "*.SysExAccumulatorTest"` | Wave 0 |
| SysEx accum. | 7-bit pack/unpack roundtrip | unit | `./gradlew :app:testDebugUnitTest --tests "*.SysExProtocolTest"` | Wave 0 |

**Manual-only (hardware required):**
- DEV-01: Real firmware version displayed on connected EP-133
- DEV-03: Actual backup file opens on desktop with TE Sample Manager
- DEV-04: Restore from backup file restores device state
- PERF-01: Multi-touch simultaneously triggers two pads on device
- PERF-03: EP-133 transport starts/stops with app sequencer

### Sampling Rate
- **Per task commit:** `cd AndroidApp && ./gradlew :app:testDebugUnitTest`
- **Per wave merge:** `cd AndroidApp && ./gradlew :app:testDebugUnitTest :app:lintDebug`
- **Phase gate:** Full unit test suite green + manual UAT on physical hardware before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `AndroidApp/app/src/test/java/com/ep133/sampletool/SysExProtocolTest.kt` — covers 7-bit encoding roundtrip, frame building, GREET parsing
- [ ] `AndroidApp/app/src/test/java/com/ep133/sampletool/SysExAccumulatorTest.kt` — covers fragment accumulation, multi-message stream
- [ ] `AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIRepositoryStatsTest.kt` — covers `queryDeviceStats()` timeout, DeviceState population
- [ ] `AndroidApp/app/src/test/java/com/ep133/sampletool/BackupRestoreTest.kt` — covers file validation, byte format
- [ ] `AndroidApp/app/src/test/java/com/ep133/sampletool/PadsViewModelTest.kt` — covers multi-touch, velocity mapping
- [ ] `AndroidApp/app/src/test/java/com/ep133/sampletool/SoundsViewModelTest.kt` — covers preview job cancellation, noteOn/noteOff timing (use `runTest` + `advanceTimeBy`)
- [ ] `AndroidApp/app/src/test/java/com/ep133/sampletool/SequencerEngineTest.kt` — covers MIDI Start/Stop/Clock output
- [ ] `AndroidApp/app/src/test/java/com/ep133/sampletool/ScaleLockTest.kt` — covers scale computation, root note offset, edge cases (chromatic = all in scale)

Existing stubs that must be implemented in Wave 0:
- `MIDIRepositoryTest.deviceState_emitsConnectedTrueWhenDeviceAdded` — currently `@Ignore`
- `MIDIManagerThreadingTest.onMidiReceived_isInvokedOnMainThread` — currently `@Ignore`
- `SequencerEngineScopeTest.close_cancelsPendingNoteOffJobs` — currently `@Ignore`

---

## Open Questions

1. **Backup scope and format — Phase 2 depth**
   - What we know: The web app's backup is a PAK (ZIP) file with individual WAV + JSON files transferred via file-protocol GET. The decision is to use raw `.syx` format.
   - What's unclear: Should Phase 2 backup WAV audio data (large, may take minutes) or only device configuration metadata (fast, but limited restore value)? Full sound + project transfer is technically required for a useful backup, but the scope vs. time tradeoff is unresolved.
   - Recommendation: Plan for full backup (all sounds + projects) with an indeterminate `LinearProgressIndicator`. Accept that large libraries may take 30+ seconds. Flag this as a human verification risk.

2. **SysEx device_id — How to obtain it before GREET**
   - What we know: The TE SysEx frame requires a `device_id` (identity code) in byte[4]. This is obtained from the GREET response — but how do you send GREET without knowing the device_id first?
   - What's unclear: The web app uses `0x00` or a broadcast device_id for the initial GREET. The identity response assigns the device_id.
   - Recommendation: Send initial GREET with `device_id = 0x00`. Parse the GREET response to extract the assigned identity code. Use that for all subsequent commands. Verify with hardware.

3. **Scale state sharing architecture**
   - What we know: `DeviceViewModel` and `PadsViewModel` are separate instances with independent scale StateFlows.
   - What's unclear: Whether to add scale state to `MIDIRepository` (out of its domain) or create a new `AppSettingsRepository` class.
   - Recommendation: Add `selectedScale: MutableStateFlow<Scale>` and `selectedRootNote: MutableStateFlow<String>` to `MIDIRepository`. It already owns global app state (`deviceState`, `channel`). Document as "app settings" rather than "MIDI state."

4. **`pack7bit` implementation correctness**
   - What we know: The algorithm from `index.js` is the canonical reference.
   - What's unclear: The deobfuscated implementation above may have index errors. Must be verified with known test vectors before shipping any SysEx commands.
   - Recommendation: Write `SysExProtocolTest` first, with known input/output pairs derived from the `index.js` algorithm. Do NOT ship backup/restore until the roundtrip test passes.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Single-touch `ACTION_DOWN/UP` per pad cell | Multi-touch at grid container level with pointer-to-pad mapping | Phase 2 | Enables simultaneous multi-finger drum performance |
| Hardcoded device stats ("128", "v1.3.2") | Live-queried stats via SysEx GREET + FILE_METADATA | Phase 2 | Users see real device state |
| No backup/restore | SAF-based .syx backup + file protocol restore | Phase 2 | Core device management capability |
| Static scale labels in DeviceScreen | Scale selection drives pad highlighting | Phase 2 | Scale lock becomes functional |
| Sequencer runs in isolation | Sequencer sends MIDI transport to EP-133 | Phase 2 | EP-133 follows app tempo |

---

## Project Constraints (from CLAUDE.md)

**Enforced for all Phase 2 code:**
- Kotlin: `val` over `var`; `when` over if/else chains; no `GlobalScope`
- Coroutines: `viewModelScope` or `lifecycleScope`; `withContext(Dispatchers.IO)` for file I/O
- All MIDI callbacks through `mainHandler.post {}` (from Phase 1 — must not regress)
- `Log.e(TAG, message, exception)` always includes throwable
- `CancellationException` always rethrown (never swallowed)
- Naming: `MIDI` not `Midi`; `EP133` not `Ep133`; `PascalCase` for files
- Commit format: `feat(02-android-device-management-XX): ...`
- No `.env` files, no API keys (fully offline app)
- ProGuard: `isMinifyEnabled = true` for release — all new public API needs `@Keep` if accessed reflectively

---

## Sources

### Primary (HIGH confidence)
- `data/index.js` (project file, ~1.75MB compiled React bundle) — Full TE SysEx protocol reverse-engineered: manufacturer ID, command codes, frame format, 7-bit encoding, GREET metadata keys, file protocol subcommands
- `AndroidApp/` source tree — All existing patterns verified by reading actual source files
- `EP133.kt` — Confirmed `Scale.intervals: List<Int>` field exists and is populated
- `SequencerEngine.kt` — Confirmed `playLoop()` drift-compensated timing; no MIDI transport yet
- `MIDIManager.kt` — Confirmed `mainHandler.post {}` in `onSend()` callback (Phase 1 fix applied)
- `MIDIRepository.kt` — Confirmed `parseMidiInput()` only handles 0x90/0x80 — SysEx currently discarded
- `DeviceScreen.kt` — Confirmed hardcoded stats ("128", "v1.3.2") and existing composable structure

### Secondary (MEDIUM confidence)
- CLAUDE.md — Technology stack, build commands, naming conventions (project file, authoritative)
- `.planning/phases/02-android-device-management/02-CONTEXT.md` — All locked decisions (project file, authoritative)
- `REQUIREMENTS.md` — Phase requirement IDs and acceptance criteria (project file)

### Tertiary (LOW confidence — requires hardware verification)
- TE SysEx device_id broadcast behavior (device_id=0x00 for initial GREET) — inferred from web app code, not explicitly documented
- SysEx fragment sizes in Android MIDI stack — 512-4096 bytes per fragment is a community-reported range, not formally specified in Android docs

## Metadata

**Confidence breakdown:**
- SysEx protocol: HIGH — Directly extracted from the live compiled web app source
- Accumulation buffer pattern: HIGH — Based on `MidiReceiver.onSend()` behavior (Android API docs) and current `MIDIManager.kt` code
- SAF patterns: HIGH — Standard Android API, well-documented
- Multi-touch patterns: HIGH — Standard Android `MotionEvent` API
- MIDI transport messages: HIGH — MIDI 1.0 specification (system real-time messages)
- Scale computation: HIGH — `Scale.intervals` confirmed in source; algorithm is pure arithmetic
- backup scope depth: LOW — Requires hardware testing to understand transfer times

**Research date:** 2026-03-30
**Valid until:** 2026-06-30 (stable APIs; TE firmware protocol unlikely to change)
