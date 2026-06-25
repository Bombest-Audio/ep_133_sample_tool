# EP-133 / EP-1320 K.O. II — USB-MIDI SysEx Protocol Reference

A reverse-engineered, hardware-verified reference for the Teenage Engineering **EP-133 K.O. II**
(and **EP-1320**) USB-MIDI System Exclusive protocol — the **file-transfer subsystem** that lets a
host browse the device filesystem, read/write samples, read project/group state, trigger
playback, and back up/restore content.

There is no official public spec. This is here so the next person building for the K.O. II
doesn't have to spend nights with a MIDI sniffer.

## Status & confidence

- **Hardware-verified:** EP-133 K.O. II, firmware **2.0.5**, over USB-MIDI, probed directly with
  `sendmidi`/`receivemidi` on macOS and cross-checked against a native Android app. Everything in
  this document was observed on a real device unless explicitly marked otherwise.
- **Cross-checked against** Teenage Engineering's own EP-133 web tool (its minified JS bundle),
  which uses the same protocol.
- Items still marked _(unconfirmed)_ are noted inline. Corrections welcome via PR.

Nothing here is from Teenage Engineering. **Use at your own risk** — writing to or deleting from
the device filesystem can modify or destroy your samples and projects.

## Transport

- The device enumerates as a standard **USB-MIDI** device (CoreMIDI name `EP-133`), one input + one output.
- **Pad presses** are ordinary MIDI **notes** on channel 1: notes 36–83, groups A/B/C/D =
  36–47 / 48–59 / 60–71 / 72–83, each pad = `groupBase + padOffset`. The hardware **group buttons
  emit no MIDI** — active group is only readable via file metadata (see below).
- Everything else (filesystem, device info, playback) is **SysEx**.

## Frame format

### Request

```
F0  00 20 76  <deviceId>  40  <flags>  <reqId7>  <command>  <7-bit-packed payload…>  F7
```

| Field | Meaning |
|---|---|
| `F0` / `F7` | SysEx start / end |
| `00 20 76` | Teenage Engineering manufacturer ID |
| `deviceId` | Device address. The device reports its own ID in the greet reply (**0x33** on the unit tested). Greet is accepted at deviceId 0; address other requests to the reported ID. |
| `40` | TE subsystem byte |
| `flags` | `0x40`=is-request, `0x20`=request-id-present, low nibble = `reqId>>7`. Requests send `0x60 | (reqId>>7)`. |
| `reqId7` | Request ID low 7 bits (full reqId is 14-bit: high bits in `flags & 0x0F`). The response **echoes** it — use it to correlate. |
| `command` | Top-level command (below). |
| payload | **7-bit packed** (see below). |

### Response

Responses drop the leading `F0` framing position and **insert a 1-byte status code after the
command byte, before the packed payload**:

```
00 20 76  <deviceId>  40  <flags>  <reqId>  <command>  <STATUS>  <7-bit-packed payload…>
```
(`receivemidi` strips `F0`/`F7`, so the bytes above are exactly what you parse.)

- `STATUS`: `0x00` = OK; `0x01` = error (payload is an ASCII message, e.g. `invalid id`,
  `unexpected page`, `can't list unless initialized`, `not found`); `0x40` (64) = intermediate
  success / more pages coming. (`0x04` was seen only from a device left in a wedged state.)

> **Gotcha #0 (cost the most):** consume the STATUS byte *before* unpacking. Unpacking one byte
> early shifts every 7-bit group boundary and corrupts the payload — ASCII half-reads, binary
> fields turn to garbage.

### Top-level commands

| Command | Value | Use |
|---|---|---|
| `GREET` | `1` | Handshake / device identity |
| `FILE` | `5` (`TE_SYSEX_FILE`) | **All filesystem operations** |
| `PRODUCT_SPECIFIC` | `127` (`0x7F`) | Other product messages — **not** for file ops |

> **Gotcha #1:** file ops go under command **`5`**, payload starting at the file *subcommand*.
> Sending them under `127` with `5` as the first payload byte gets a generic `0x01` rejection.

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

Response payload is an ASCII key/value string, e.g.:

```
product:EP-133;mode:normal;base_sku:TE032AS001;sku:TE032AS001;
os_version:2.0.5;sw_version:2.0.5;bl_version:1000.0.10;serial:E3PUA1T2
```

The response frame's `deviceId` is the device's real address — adopt it.

### File session init (`command = 5`, subcommand `FILE_INIT = 1`)

**Required before any file op**, or the device replies `can't list unless initialized`.

- Request payload: `[ FILE_INIT(1), flags, maxResponseLength (u32 BE) ]`. `flags = 1` (subscribe) works; `maxResponseLength = 512` works.
- Response payload (unpacked): the negotiated **chunk size** (512 on the tested unit). _Exact field offset within the small response body is firmware-defined; treating 512 as the per-message budget is correct in practice._
- The session lasts for the USB connection.

## File subcommands (`command = 5`)

Payload begins with the subcommand byte, then op-specific fields.

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

Sub-types: GET/PUT `TYPE_INIT=0`, `TYPE_DATA=1`; METADATA `SET=1`, `GET=2`; PLAYBACK `START=1`, `STOP=2`.
Flag bits: `READ=4, WRITE=8, DELETE=16, MOVE=32, PLAYBACK=64`; node type `FILE=1, DIR=2`.
(Observed: directories `flags=0x0e` = READ|WRITE|DIR; sound files `flags=0x1d` = READ|WRITE|DELETE|FILE.)

## Node model & full device tree

The filesystem is addressed by numeric **node IDs**; **root is node `0`**. Verified layout
(node IDs are the device's own allocation on the tested unit):

```
0   /                       (root)
├── 1000  sounds            dir   — sample pool
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

- Request: `[ FILE_LIST(4), page (u16 BE), nodeId (u16 BE) ]`. Paginated — request page 0,1,… until an empty page.
- Response: `[ page (u16 BE), entries… ]`; each entry: `nodeId (u16 BE), flags (u8), fileSize (u32 BE), fileName (NUL-terminated ASCII)`. `flags & 2` = directory.

### FILE_INFO (subcommand 11)

- Request: `[ FILE_INFO(11), nodeId (u16 BE) ]`.
- Response: `[ nodeId u16, parentId u16, flags u8, size u32 BE, name + NUL ]`.
  Example (node 1): `nodeId=1, parentId=1000 (/sounds), flags=0x1d, size=44824, name="001.pcm"`.

### FILE_GET — read a file (subcommand 3)

1. **GET INIT:** `[ FILE_GET(3), TYPE_INIT(0), nodeId u16, page/offset u16 ]` → response carries the file header (name, flags, size).
2. **GET DATA:** `[ FILE_GET(3), TYPE_DATA(1), page u16 ]` → response `[ page u16, …raw bytes… ]`; page through to EOF. Sample data is raw **s16 LE PCM**.

### FILE_PUT — create / write a file (subcommand 2)

Hardware-verified upload sequence (e.g. a sample to `/sounds`):

1. Open the file session (`FILE_INIT`).
2. Resolve the parent dir (`/sounds` = node 1000).
3. **PUT INIT:** `[ FILE_PUT(2), TYPE_INIT(0), flags, fileId u16 (=0 for new), parentId u16, fileSize u32 BE, fileName + NUL, metadataJSON + NUL ]`.
   - `flags = READ(4)|FILE(1) = 5`. Metadata is **required** and must be valid JSON; a sample uses `{"channels":N,"samplerate":N}`.
   - Response: `status=0`, body = device-assigned **fileId** (u16). **Await this before sending DATA.**
4. **PUT DATA**, paged: `[ FILE_PUT(2), TYPE_DATA(1), page u16, …chunk… ]`.
   - Chunk size must fit the negotiated chunk size: ~**427 raw bytes** for chunkSize 512 (`calculateMaxPayloadLength(chunkSize-6)`); 4096-byte chunks are rejected (`unexpected page`).
   - **Each page is acked** (`status=0`, empty body, the page's reqId echoed). **Use a unique reqId per page and await each ack before sending the next** — USB-MIDI has no flow control.
5. **Terminator:** one final PUT DATA with a zero-length chunk; await its `status=0` ack.

> **Gotcha #3:** await the PUT INIT ack before any DATA, and await each DATA page before the next.
> Firing pages unpaced → `unexpected page`.
>
> **Gotcha #4 (device wedge):** an *incomplete* PUT (pages sent, never terminated) leaves the
> device unable to accept new PUTs — it silently ignores further PUT INITs until **power-cycled**.
> On any failure mid-transfer, send the zero-length terminator to close it cleanly.

### FILE_PLAYBACK (subcommand 5)

- Start: `[ FILE_PLAYBACK(5), START(1), nodeId u16 ]` → `status=0`. Stop: `[ …, STOP(2), nodeId u16 ]`.

### FILE_DELETE (subcommand 6) — _format from reference, not destructively tested_

- `[ FILE_DELETE(6), nodeId u16 ]`. Requires `flags & DELETE`. Expect `status=0`.

### FILE_METADATA (subcommand 7)

- **GET:** `[ FILE_METADATA(7), GET(2), nodeId u16, page u16 ]` → JSON, wrapped with a 2-byte page
  prefix and a trailing NUL (extract the `{…}` span before parsing).
- **SET:** `[ FILE_METADATA(7), SET(1), nodeId u16, jsonBytes ]` _(format from reference; paged
  variant exists for large metadata)_.

#### Verified metadata schemas

- **`/sounds` (node 1000):** storage + supported formats —
  `{"max_capacity":62853120,"free_space_in_bytes":15390012,"formats":[{"type":"pcm","formats":[{"samplerate.range":[1,65535],"samplerate.native":46875,"channels":[1,2],"format":["s16"]}]}]}`
- **`/projects` (2000):** `{"active":<projectNode>}` (e.g. `{"active":3000}` = project 01).
- **`<project>/groups` (e.g. 3100):** `{"active":<groupNode>}` (e.g. `{"active":3200}` = group A).
- **`<group>` (e.g. 3200):** `{"active":<padNode>}` (active pad within the group; `{"active":0}` = none).
- **root / project node:** `{}` (empty).
- **sound file (e.g. node 1):** full sample parameters —
  `{"channels":1,"samplerate":46875,"format":"s16","crc":2874448156,"sound.loopstart":-1,"sound.loopend":-1,"name":"1_micro kick","sound.amplitude":100,"sound.playmode":"oneshot","sound.pan":0,"sound.pitch":0.00,"sound.rootnote":60,"time.mode":"off","sound.bpm":0.00,"sound.bars":1.00,"envelope.attack":0,"envelope.release":…}`

## Detecting the active project / pad group

The hardware group buttons emit no MIDI, so poll metadata:

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

| Status | Meaning |
|---|---|
| `0x00` | OK |
| `0x01` | Error — payload is an ASCII message (`invalid id`, `unexpected page`, `not found`, `can't list unless initialized`) |
| `0x40` (64) | Intermediate success — more pages coming (paged transfers) |

## Reproducing this

macOS, with [`sendmidi`/`receivemidi`](https://github.com/gbevin)
(`brew install gbevin/tools/sendmidi gbevin/tools/receivemidi`). **`sendmidi` takes DECIMAL** —
TE manufacturer `00 20 76` is `0 32 118`.

```sh
receivemidi dev "EP-133" ts            # watch responses (separate shell)

# greet → device id 0x33:
sendmidi dev "EP-133" syx 0 32 118 0 64 96 1 1
# open session, list root (deviceId 51 = 0x33):
sendmidi dev "EP-133" syx 0 32 118 51 64 96 4 5 0 1 1 0 0 2 0
sendmidi dev "EP-133" syx 0 32 118 51 64 96 5 5 0 4 0 0 0 0
```

> **Gotcha #5:** every multi-byte field in a request payload must also be 7-bit packed before
> sending. A nodeId like 1000 (`0x03E8`) contains `0xE8` (>127) and **cannot** be sent raw in
> SysEx — pack it. (Node 0 worked raw only because it has no high bits.)

## Credits

Reverse-engineered while building an open-source offline sample tool for the EP-133. Opcode/field
layouts were learned from Teenage Engineering's own web tool; all wire behavior was confirmed on a
real K.O. II (fw 2.0.5). Shout to the EP-133 / K.O. II community. PRs and corrections welcome.
