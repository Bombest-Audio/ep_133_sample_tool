# EP-133 / EP-1320 K.O. II: USB-MIDI protocol reference

A reverse-engineered, hardware-verified reference for the Teenage Engineering **EP-133 K.O. II**
(and **EP-1320**) over USB-MIDI. It covers the whole wire surface a host can see: the SysEx
command space (device identity, echo, and the file subsystem that browses the filesystem,
reads/writes samples, reads project/group state, and triggers playback), the async events the
device pushes on its own, and the plain-MIDI control surface the pads and sequencer emit.

There is no official public spec for any of this.

## Status & confidence

- **Hardware-verified:** EP-133 K.O. II, firmware **2.5.0**, over USB-MIDI, probed directly with
  `sendmidi`/`receivemidi` on macOS and cross-checked against a native Android app. Everything in
  this document was observed on a real device unless explicitly marked otherwise.
- **Cross-checked against** Teenage Engineering's own EP-133 web tool (its minified JS bundle),
  which uses the same protocol. Command and status constants below are taken verbatim from that
  bundle, then confirmed on the wire where a non-destructive test exists.
- Items still marked _(unconfirmed)_ or _(from reference)_ are noted inline.

Nothing here is from Teenage Engineering. **Use at your own risk.** Writing to or deleting from
the device filesystem can modify or destroy your samples and projects, and the firmware-update
command can brick the unit. See the DANGER note under [Command space](#command-space).

## Transport

- The device enumerates as a standard **USB-MIDI** device (CoreMIDI name `EP-133`), one input + one output.
- **Pad presses** are ordinary MIDI **notes** on channel 1: notes 36–83, groups A/B/C/D =
  36–47 / 48–59 / 60–71 / 72–83, each pad = `groupBase + padOffset`. See
  [Control surface](#standard-midi-control-surface) for the full picture (velocity, group buttons,
  knobs, sequencer, clock).
- Everything else (identity, echo, filesystem, playback, events) is **SysEx**.

## Command space

Every SysEx frame carries one top-level command byte. Here's the whole set the device speaks. Names and values come straight from TE's own `TE_SYSEX` table, then confirmed on the wire
wherever there's a safe way to test it:

| Command | Value | Use |
|---|---|---|
| `GREET` | `1` | Handshake / device identity. |
| `ECHO` | `2` | Round-trip ping. Device returns the payload unchanged. Handy connectivity test. |
| `DFU` | `3` | **Firmware update / bootloader.** Sub-ops `DFU_ENTER`, `DFU_ENTER_MIDI`, `DFU_EXIT`. |
| `FILE` | `5` | **All filesystem operations** (and the async file events). |
| `PRODUCT_SPECIFIC` | `127` (`0x7F`) | Reserved product messages. The web tool barely uses it; not needed for file ops. |

> **☢️ DANGER: do not send `DFU` (3).** This is the firmware-update / bootloader entry point.
> Sending it (or its `DFU_ENTER` sub-op) can drop the device into a firmware-flash state and brick
> it. Nothing in this document requires it; it's listed only so you recognize it. This reference
> was built without ever transmitting a `DFU` message.

> **Gotcha #1:** file ops go under command **`5`**, payload starting at the file *subcommand*.
> Sending them under `127` with `5` as the first payload byte gets a generic rejection.

## Frame format

### Request (host → device)

```
F0  00 20 76  <deviceId>  40  <flags>  <reqId7>  <command>  <7-bit-packed payload…>  F7
```

| Field | Meaning |
|---|---|
| `F0` / `F7` | SysEx start / end. |
| `00 20 76` | Teenage Engineering manufacturer ID. |
| `deviceId` | Device address. The device reports its own ID in the greet reply (**0x33** on the unit tested). Greet is accepted at deviceId 0; address other requests to the reported ID. |
| `40` | TE subsystem byte. |
| `flags` | Transaction flags. `0x40` = is-request; `0x20` = request-id present; low nibble = `reqId >> 7`. Requests send `0x60 \| (reqId>>7)` (both bits set). The Android implementation names these `BIT_IS_REQUEST` / `BIT_REQUEST_ID_AVAILABLE`. |
| `reqId7` | Request ID low 7 bits (full reqId is 14-bit; high bits live in `flags & 0x0F`). The response **echoes** it, so use it to correlate. |
| `command` | Top-level command (above). |
| payload | **7-bit packed** (see below). |

`TE_SYSEX_HEADER_OVERHEAD = 8` (`00 20 76` + `deviceId` + `40` + `flags` + `reqId7` + `command`),
`TE_SYSEX_FOOTER_OVERHEAD = 1` (the trailing `F7`). The `F0` start byte sits outside both.

### Response (device → host)

Responses set flags to `0x20` (request-id present, is-request bit clear) and **insert a 1-byte
status code after the command byte, before the packed payload**:

```
00 20 76  <deviceId>  40  20  <reqId>  <command>  <STATUS>  <7-bit-packed payload…>
```
(`receivemidi` strips `F0`/`F7`, so the bytes above are exactly what you parse.)

> **Gotcha #0 (cost the most):** consume the STATUS byte *before* unpacking. Unpacking one byte
> early shifts every 7-bit group boundary and corrupts the payload: ASCII half-reads, binary
> fields turn to garbage.

### Async event (device → host, unsolicited)

The device also **pushes** frames you never asked for. They ride the `FILE` command (5) with
flags `0x40` (the is-request bit is set even on these device-initiated frames; no request-id) and
no status byte, and the packed payload begins with an event-type byte. See [Async events](#async-events).

## 7-bit packing

Data bytes must be ≤ 0x7F, so payloads are packed in groups of 8: a leading high-bits byte, then
up to 7 data bytes (MSB cleared). **LSB-first: data byte _k_ in a group takes its 8th bit from
bit _k_ of the high-bits byte.**

```
unpacked[k] = (packed_data[k] & 0x7F) | (((highbits >> k) & 1) << 7)
```

> **Gotcha #2 (resolved):** it is LSB-first, not MSB-first. Backwards leaves ASCII readable but
> corrupts node IDs / sizes / sample data.

## Handshake

### Greet (`command = 1`, empty payload)

Response payload is an ASCII key/value string. On the tested unit (fw 2.5.0):

```
product:EP-133;mode:normal;base_sku:TE032AS001;sku:TE032AS001;
os_version:2.5.0;sw_version:2.5.0;bl_version:1000.0.10;serial:XXXXXXXX
```

The response frame's `deviceId` is the device's real address, so adopt it (0x33 here).

### Echo (`command = 2`)

Send any packed payload; the device returns it unchanged with `STATUS = 0`. The web tool uses this
as a liveness check (it sends bytes and asserts the reply matches). It's the safest thing you can
throw at the device, a clean way to confirm the port and the framing before touching files.

```
→  00 20 76 33 40 60 06 02  00 01 02 03      # ECHO, reqId 6, packed payload [1,2,3]
←  00 20 76 33 40 20 06 02  00  00 01 02 03  # status 0, payload echoed
```

### File session init (`command = 5`, subcommand `FILE_INIT = 1`)

**Required before any file op**, or the device replies `can't list unless initialized`.

- Request payload: `[ FILE_INIT(1), flags, maxResponseLength (u32 BE) ]`. `flags = 1`
  (`FILE_INIT_SUBSCRIBE`, which also arms async events, see below) works; `maxResponseLength = 512` works.
- Response payload (unpacked): `[ flags(=12), chunkSize (u32 BE) ]`, the negotiated **chunk size 512**
  on the tested unit. Treat 512 as the per-message budget.
- The session lasts for the USB connection.

## File subcommands (`command = 5`)

Payload begins with the subcommand byte, then op-specific fields. Values are verbatim from the tool.

| Subcommand | Value | Verified |
|---|---|---|
| `FILE_INIT` | 1 | ✓ session handshake |
| `FILE_PUT` | 2 | ✓ create/write (INIT/DATA) |
| `FILE_GET` | 3 | ✓ read (INIT/DATA) |
| `FILE_LIST` | 4 | ✓ directory listing |
| `FILE_PLAYBACK` | 5 | ✓ start/stop playback |
| `FILE_DELETE` | 6 | _format from reference; not destructively tested_ |
| `FILE_METADATA` | 7 | ✓ get (set: from reference) |
| `FILE_INFO` | 11 | ✓ node info |
| `FILE_MOVED` | 12 | encoder for a move event (see events) |

Sub-types: GET/PUT `TYPE_INIT=0`, `TYPE_DATA=1`; METADATA `SET=1`, `GET=2`, `SET_PAGED=4`
(`SET_PAGED` sub-types INIT=0, DATA=1); PLAYBACK `START=1`, `STOP=2`.
Capability flags: `READ=4, WRITE=8, DELETE=16, MOVE=32, PLAYBACK=64`; node type `FILE=1, DIR=2`.
(Observed: directories `flags=0x0e` = READ|WRITE|DIR; sound files `flags=0x1d` = READ|WRITE|DELETE|FILE.)

## Async events

The device doesn't just answer questions. It pushes filesystem and state changes at you, unasked.
Each event is a `FILE` frame (command 5, flags `0x40`, no status byte); the packed payload starts
with an **event-type** byte, then a small header and a JSON or binary body.

| Event | Value | Meaning |
|---|---|---|
| `METADATA_UPDATED` | 3 | A node's metadata changed. Body is JSON, e.g. `{"active":<node>}`. |
| `FILE_ADDED` | 8 | A file appeared (e.g. a new sample). |
| `FILE_UPDATED` | 9 | A file's contents/metadata changed. |
| `FILE_DELETED` | 10 | A file was removed. |
| `FILE_MOVED` | 13 | A node moved (carries old/new ids). |

The most useful one in practice is **`METADATA_UPDATED` carrying `{"active":<node>}`**: the device
fires it whenever the active pad, group, or project changes. Selecting group A/B/C/D pushes
`{"active":3200/3300/3400/3500}`; tapping a pad pushes the active pad node. This is how you track
what the user is doing on the hardware in real time.

- **Subscription:** events flow only while a `FILE_INIT` with `flags = 1` (`FILE_INIT_SUBSCRIBE`)
  is in effect.
- **Gotcha #6:** the subscription **does not survive a USB re-enumeration**. If the device
  disconnects/reconnects (mode changes can cause this), re-send `FILE_INIT` to re-arm events, or
  they go silent.

## Standard-MIDI control surface

Almost none of the physical controls use SysEx. The musical layer is plain MIDI, and a lot of it
is silent. Observed on fw 2.5.0:

| Control | What it sends |
|---|---|
| **12 pads × 4 groups** | note-on/off, channel 1. Groups A/B/C/D map to note bases 36 / 48 / 60 / 72; pads run consecutively within a group (group A pads 1–12 = notes 36–47). **Velocity is fixed at 100**: the pads are not velocity-sensitive over MIDI; soft and hard hits both send 100. |
| **Group A/B/C/D buttons** | No note. They push a SysEx `METADATA_UPDATED` event `{"active":<groupNode>}` (when subscribed). |
| **Fader, volume knob, X knob, Y knob** | **Nothing.** These are local-only; they don't emit MIDI, not even metadata events. |
| **Sequencer playback** | The programmed pad notes, channel 1, same map as manual hits, a plain note stream. |
| **Play / Stop / Record / Tempo** | **Nothing.** No MIDI clock (`F8`), no start/stop (`FA`/`FC`), no CC. With default settings the K.O. II is not a clock source and won't sync downstream gear. |

> The active pad/group node IDs in the metadata events use a different index than the MIDI note
> (a pad that fires note 36 can report as node 3210). Resolve pad identity through the file tree
> (below), not by assuming `node = groupBase + noteOffset`.

## Node model & full device tree

The filesystem is addressed by numeric **node IDs**; **root is node `0`**. Verified layout
(node IDs are the device's own allocation on the tested unit):

```
0   /                       (root)
├── 1000  sounds            dir   (sample pool)
│   ├── 1   001.pcm   file  size 44824
│   ├── 2   002.pcm   file  size 43270
│   └── …   NNN.pcm         (sequential node IDs 1,2,3,…; device names slots NNN.pcm)
└── 2000  projects          dir
    ├── 3000  01            dir   (projects spaced by 1000: 3000,4000,…,11000)
    │   └── 3100  groups    dir   (= project + 100)
    │       ├── 3200  A     dir   (= project + 200/300/400/500)
    │       ├── 3300  B
    │       ├── 3400  C
    │       └── 3500  D
    ├── 4000  02 → groups 4100 → A 4200 / B 4300 / C 4400 / D 4500
    └── … 09 (node 11000)
```

Resolve a path by listing from root and matching each segment's child by name.

### FILE_LIST (subcommand 4)

- Request: `[ FILE_LIST(4), page (u16 BE), nodeId (u16 BE) ]`. Paginated: request page 0,1,… until an empty page.
- Response: `[ page (u16 BE), entries… ]`; each entry: `nodeId (u16 BE), flags (u8), fileSize (u32 BE), fileName (NUL-terminated ASCII)`. `flags & 2` = directory.

### FILE_INFO (subcommand 11)

- Request: `[ FILE_INFO(11), nodeId (u16 BE) ]`.
- Response: `[ nodeId u16, parentId u16, flags u8, size u32 BE, name + NUL ]`.
  Example (node 1): `nodeId=1, parentId=1000 (/sounds), flags=0x1d, size=44824, name="001.pcm"`.

### FILE_GET: read a file (subcommand 3)

1. **GET INIT:** `[ FILE_GET(3), TYPE_INIT(0), nodeId u16, page/offset u16 ]` → response carries the file header (name, flags, size).
2. **GET DATA:** `[ FILE_GET(3), TYPE_DATA(1), page u16 ]` → response `[ page u16, …raw bytes… ]`; page through to EOF. Sample data is raw **s16 LE PCM**.

### FILE_PUT: create / write a file (subcommand 2)

Hardware-verified upload sequence (e.g. a sample to `/sounds`):

1. Open the file session (`FILE_INIT`).
2. Resolve the parent dir (`/sounds` = node 1000).
3. **PUT INIT:** `[ FILE_PUT(2), TYPE_INIT(0), flags, fileId u16 (=0 for new), parentId u16, fileSize u32 BE, fileName + NUL, metadataJSON + NUL ]`.
   - `flags = READ(4)|FILE(1) = 5`. Metadata is **required** and must be valid JSON; a sample uses `{"channels":N,"samplerate":N}`.
   - Response: `status=0`, body = device-assigned **fileId** (u16). **Await this before sending DATA.**
4. **PUT DATA**, paged: `[ FILE_PUT(2), TYPE_DATA(1), page u16, …chunk… ]`.
   - Chunk size must fit the negotiated chunk size: **433 raw bytes** for chunkSize 512 (`calculateMaxPayloadLength(chunkSize-6)`); 4096-byte chunks are rejected (`unexpected page`).
   - **Each page is acked** (`status=0`, empty body, the page's reqId echoed). **Use a unique reqId per page and await each ack before sending the next**. USB-MIDI has no flow control.
5. **Terminator:** one final PUT DATA with a zero-length chunk; await its `status=0` ack.

> **Gotcha #3:** await the PUT INIT ack before any DATA, and await each DATA page before the next.
> Firing pages unpaced → `unexpected page`.
>
> **Gotcha #4 (device wedge):** an *incomplete* PUT (pages sent, never terminated) leaves the
> device unable to accept new PUTs, and silently ignores further PUT INITs until **power-cycled**.
> On any failure mid-transfer, send the zero-length terminator to close it cleanly.

### FILE_PLAYBACK (subcommand 5)

- Start: `[ FILE_PLAYBACK(5), START(1), nodeId u16 ]` → `status=0`. Stop: `[ …, STOP(2), nodeId u16 ]`.

### FILE_DELETE (subcommand 6): _format from reference, not destructively tested_

- `[ FILE_DELETE(6), nodeId u16 ]`. Requires `flags & DELETE`. Expect `status=0`.

### FILE_METADATA (subcommand 7)

- **GET:** `[ FILE_METADATA(7), GET(2), nodeId u16, page u16 ]` → JSON, wrapped with a 2-byte page
  prefix and a trailing NUL (extract the `{…}` span before parsing).
- **SET:** `[ FILE_METADATA(7), SET(1), nodeId u16, jsonBytes ]`. For metadata too large for one
  message, use `SET_PAGED(4)` with sub-types `INIT(0)`/`DATA(1)` _(format from reference)_.

#### Verified metadata schemas

- **`/sounds` (node 1000):** storage + supported formats:
  `{"max_capacity":62853120,"free_space_in_bytes":15390012,"formats":[{"type":"pcm","formats":[{"samplerate.range":[1,65535],"samplerate.native":46875,"channels":[1,2],"format":["s16"]}]}]}`
- **`/projects` (2000):** `{"active":<projectNode>}` (e.g. `{"active":3000}` = project 01).
- **`<project>/groups` (e.g. 3100):** `{"active":<groupNode>}` (e.g. `{"active":3200}` = group A).
- **`<group>` (e.g. 3200):** `{"active":<padNode>}` (active pad within the group; `{"active":0}` = none).
- **root / project node:** `{}` (empty).
- **sound file (e.g. node 1):** full sample parameters:
  `{"channels":1,"samplerate":46875,"format":"s16","crc":2874448156,"sound.loopstart":-1,"sound.loopend":-1,"name":"1_micro kick","sound.amplitude":100,"sound.playmode":"oneshot","sound.pan":0,"sound.pitch":0.00,"sound.rootnote":60,"time.mode":"off","sound.bpm":0.00,"sound.bars":1.00,"envelope.attack":0,"envelope.release":…}`

## Detecting the active project / pad group

Two ways, now that events are documented:

- **Live (preferred):** subscribe via `FILE_INIT` flag 1 and watch for `METADATA_UPDATED`
  `{"active":<node>}` events. The device tells you the moment the user changes pad, group, or
  project. Re-arm after any USB reconnect (Gotcha #6).
- **Poll:** read the metadata directly.

```
GET metadata /projects (2000)         → {"active": P}      # active project node
GET metadata <P>/groups               → {"active": G}      # active group node
LIST <P>/groups, match nodeId G       → name "A".."D"      # the active group
```

(`samplerate`/format constraints for uploads come from the `/sounds` metadata `formats` block.)

## Format & limits (for uploads)

- Sample format: **RIFF/PCM s16 LE**, mono or stereo (`channels:[1,2]`), native rate **46875 Hz**.
- Source-rate window **3000–768000 Hz**; max length **20 s** (enforced by the reference tool).
- Device names imported sounds as slot files (`NNN.pcm`).

## Status codes

Status is a single byte after the command. The tool's `TE_SYSEX` table defines ranges, not just
two values:

| Status | Name | Meaning |
|---|---|---|
| `0` | `STATUS_OK` | Success. |
| `1` | `STATUS_ERROR` | Generic error: payload is an ASCII message (`invalid id`, `unexpected page`, `not found`, `can't list unless initialized`). |
| `2` | `STATUS_COMMAND_NOT_FOUND` | Unknown command / subcommand. |
| `3` | `STATUS_BAD_REQUEST` | Malformed request. |
| `16`–`63` | `STATUS_SPECIFIC_ERROR_START`..`END` | Command-specific error range. |
| `64`+ | `STATUS_SPECIFIC_SUCCESS_START` | Command-specific success. `64` (`0x40`) is the "intermediate success, more pages coming" you see during paged transfers. |

> This corrects the earlier two-line table: `0x40` isn't a special magic value, it's the base of
> the command-specific *success* range.

## Reproducing this

macOS, with [`sendmidi`/`receivemidi`](https://github.com/gbevin)
(`brew install gbevin/tools/sendmidi gbevin/tools/receivemidi`). **`sendmidi` takes DECIMAL.**
TE manufacturer `00 20 76` is `0 32 118`.

```sh
receivemidi dev "EP-133" ts            # watch responses (separate shell)

# greet → device id 0x33:
sendmidi dev "EP-133" syx 0 32 118 0 64 96 1 1
# echo round-trip (safest test), reqId 6, payload [1,2,3]:
sendmidi dev "EP-133" syx 0 32 118 51 64 96 6 2 0 1 2 3
# open session (deviceId 51 = 0x33), then list root:
sendmidi dev "EP-133" syx 0 32 118 51 64 96 4 5 0 1 1 0 0 2 0
sendmidi dev "EP-133" syx 0 32 118 51 64 96 5 5 0 4 0 0 0 0
```

> **Gotcha #5:** every multi-byte field in a request payload must also be 7-bit packed before
> sending. A nodeId like 1000 (`0x03E8`) contains `0xE8` (>127) and **cannot** be sent raw in
> SysEx, so pack it. (Node 0 worked raw only because it has no high bits.)

## Appendix: captured frames (fw 2.5.0)

Raw `receivemidi` output from the spike, so you can check your decoder against real bytes.
`receivemidi` strips `F0`/`F7`, so each line is `00 20 76 …` through the last data byte.

```
# GREET reply: status 0, packed ASCII identity string
00 20 76 33 40 20 01 01 00 00 70 72 6F 64 75 63 74 00 3A 45 50 2D 31 33 33 …
  → product:EP-133;mode:normal;…;os_version:2.5.0;sw_version:2.5.0;bl_version:1000.0.10;serial:XXXXXXXX

# ECHO reply: status 0, payload [1,2,3] echoed
00 20 76 33 40 20 06 02 00 00 01 02 03

# FILE_INIT reply: status 0, flags=12, chunkSize=512
00 20 76 33 40 20 04 05 00 00 0C 00 00 02 00

# FILE_LIST root: status 0, two dir entries
00 20 76 33 40 20 05 05 00 08 00 00 03 68 0E 00 00 00 00 00 73 6F 75 6E 64 08 73 00 07 50 0E 00 00 00 00 00 70 72 6F 6A 65 00 63 74 73 00
  → node 1000 "sounds" (dir), node 2000 "projects" (dir)

# Pad tap (group A pad 1): a plain note-on PLUS an async METADATA_UPDATED event
note-on  channel 1  C1 (36)  velocity 100
00 20 76 33 40 40 00 05 04 03 0C 00 7B 22 61 63 00 74 69 76 65 22 3A 33 00 32 31 30 7D
  → {"active":3210}

# Group-button press (select group B): event only, no note
00 20 76 33 40 40 00 05 00 03 0C 1C 7B 22 61 63 00 74 69 76 65 22 3A 33 00 33 30 30 7D
  → {"active":3300}
```

## Provenance

Opcode and field layouts came from Teenage Engineering's own web tool; all wire behavior was
confirmed on a real K.O. II (firmware 2.5.0). Not affiliated with Teenage Engineering.
