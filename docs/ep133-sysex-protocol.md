# EP-133 / EP-1320 K.O. II — USB-MIDI SysEx File Protocol

Reverse-engineered notes on the Teenage Engineering **EP-133 K.O. II** (and **EP-1320**)
USB-MIDI System Exclusive protocol — specifically the **file-transfer subsystem** used to
list, read, write, and tag files on the device (`/sounds`, `/projects`, etc.).

This is the part of the protocol that lets a host app browse the device's filesystem, upload
samples, read which project/group is active, and back up or restore content. There's no
official public spec, so this is here for anyone else building for the K.O. II — so you don't
have to spend a night with a MIDI sniffer to get the device to talk back.

## Status & confidence

- **Verified on hardware:** EP-133 K.O. II, firmware **2.0.5**, over USB-MIDI to macOS, using
  `sendmidi`/`receivemidi`. The frame envelope, the greet, the `FILE_INIT` requirement, the
  command-byte framing, and the `INIT → LIST` round-trip are confirmed against a real device.
- **Derived from the official web tool:** the subcommand opcodes and request/response field
  layouts were extracted from Teenage Engineering's own EP-133 web sample tool (the minified
  JS bundle it ships) and cross-checked against hardware. These are marked _(from reference)_.
- **Unconfirmed details** are flagged inline. Corrections welcome.

Nothing here is from Teenage Engineering. Use at your own risk; writing to the device's
filesystem can modify or delete your samples and projects.

## Transport

- The device enumerates as a standard **USB-MIDI** device (CoreMIDI name `EP-133`). It exposes
  one MIDI input and one MIDI output.
- Pad presses are ordinary MIDI **note** messages on channel 1 (notes 36–83: groups A/B/C/D =
  36–47 / 48–59 / 60–71 / 72–83, each pad = `groupBase + padOffset`). The hardware **group
  buttons emit no MIDI** — the active group is only readable by querying file metadata.
- Everything else (filesystem, device info) is **SysEx**.

## Frame envelope

Every SysEx message uses this layout:

```
F0  00 20 76  <deviceId>  40  <flags>  <reqId7>  <command>  <7-bit-packed payload…>  F7
```

| Byte(s) | Meaning |
|---|---|
| `F0` | SysEx start |
| `00 20 76` | Teenage Engineering manufacturer ID |
| `deviceId` | Device address (7-bit). The device's own ID is reported in its greet reply (e.g. **0x33** on the unit tested). Read it from a greet response; don't hardcode. |
| `40` | TE subsystem byte |
| `flags` | `0x40` = is-request, `0x20` = request-id-present, low nibble = `reqId >> 7`. Requests typically send `0x60 | (reqId>>7)`. Responses clear the is-request bit (e.g. `0x20`). |
| `reqId7` | Request ID, low 7 bits. Responses **echo** this — use it to correlate replies to requests. |
| `command` | Top-level command (see below). |
| payload | **7-bit packed** (see "7-bit packing"). |
| `F7` | SysEx end |

### Top-level commands

| Command | Value | Use |
|---|---|---|
| `GREET` | `1` | Device handshake / identity |
| `FILE` | `5` | **All filesystem operations** (`TE_SYSEX_FILE`) |
| `PRODUCT_SPECIFIC` | `127` (`0x7F`) | Other product messages. **Not** for file ops. |

> **Gotcha #1:** File operations go under command **`5`**, with the payload starting at the
> file _subcommand_. Sending them under command `127` with `5` as the first payload byte gets a
> generic 1-byte status `0x01` rejection. (An easy wrong turn if you assume "product specific"
> wraps everything.)

## Handshake

### 1. Greet (`command = 1`)

Request: `command = 1`, empty payload.

Response payload (after unpacking) is a null-padded ASCII key/value string, e.g.:

```
product:EP-133;mode:normal;base_sku:TE032AS001;sku:TE032AS001;
os_version:2.0.5;sw_version:2.0.5;bl_version:1000.0.10;serial:E3PUA1T2
```

The response frame's `deviceId` byte is the device's real address — adopt it for subsequent
requests.

### 2. File session init (`command = 5`, subcommand `FILE_INIT = 1`)

**You must open a file session before any file op**, or the device replies with the ASCII
error `can't list unless initialized`.

Request payload (before packing): `[ FILE_INIT(1), flags, maxResponseLength (u32 BE) ]`
- `flags`: `1` (subscribe) works. _(exact flag semantics: from reference)_
- `maxResponseLength`: the largest response you can accept; the device replies with a
  negotiated chunk size to use for paged transfers. _(response chunk-size byte offset:
  confirm against your capture)_

After `FILE_INIT` succeeds, file ops work for the life of the connection.

## File subcommands (`command = 5`)

Payload always begins with the subcommand byte, then op-specific fields. _(values from
reference, cross-checked on hardware where noted)_

| Subcommand | Value | Notes |
|---|---|---|
| `FILE_INIT` | 1 | Open session (above) |
| `FILE_PUT` | 2 | Write a file (INIT/DATA, below) |
| `FILE_GET` | 3 | Read a file (INIT/DATA) |
| `FILE_LIST` | 4 | List a directory node ✓ verified |
| `FILE_PLAYBACK` | 5 | Trigger playback |
| `FILE_DELETE` | 6 | Delete |
| `FILE_METADATA` | 7 | Get/set metadata (incl. the active-project pointer) |
| `FILE_INFO` | 11 | File info |
| `FILE_MOVED` | 12 | Notification |

Sub-types used by GET/PUT/METADATA: `TYPE_INIT = 0`, `TYPE_DATA = 1`.
Capability/type flags: `READ=4, WRITE=8, DELETE=16, MOVE=32, PLAYBACK=64`; `TYPE_FILE=1, TYPE_DIR=2`.

### Node model

The filesystem is addressed by numeric **node IDs**. **Root is node `0`.** Resolve a path by
listing from the root and matching each segment's child by name:

```
list(node=0) → find entry named "projects" → its nodeId → list(thatNode) → …
```

### FILE_LIST (verified)

Request payload: `[ FILE_LIST(4), page (u16 BE), nodeId (u16 BE) ]`. Paginated — request page 0,
1, … until the device returns an empty page.

Response payload: `[ page (u16 BE), entries… ]`. Each entry _(from reference)_:
`nodeId (u16 BE), flags (u8), fileSize (u32 BE), fileName (NUL-terminated ASCII)`.
`flags & TYPE_DIR` distinguishes directories.

A verified `INIT → LIST(node=0)` on hardware returns the root containing `sounds` and
`projects` (with their node IDs).

### FILE_PUT — create a file (from reference, byte-cross-checked)

To create a new file (e.g. upload a sample to `/sounds`):

1. Resolve the parent directory node (e.g. `/sounds`).
2. **PUT INIT:** `[ FILE_PUT(2), TYPE_INIT(0), flags, fileId (u16, =0 for new),
   parentNodeId (u16), fileSize (u32 BE), fileName + NUL (≤54 chars), metadataJSON + NUL (optional) ]`.
   The device returns the assigned `fileId`.
3. **PUT DATA**, paged: `[ FILE_PUT(2), TYPE_DATA(1), page (u16 BE), …chunk… ]`, chunk sized to
   the negotiated chunk size.
4. **Terminator:** one final `PUT DATA` with a zero-length chunk.
5. Await success status.

### FILE_METADATA — active group / project

Group/project state isn't a MIDI message; it's file metadata. The active project is a pointer
in `/projects` metadata; reading it (via `FILE_METADATA` GET on the resolved node) is how you
detect the active group. _(end-to-end active-group read: in progress)_

## 7-bit packing

MIDI data bytes must be ≤ 0x7F, so payloads are packed: for each group of up to 7 bytes, a
leading "high-bits" byte carries the 8th (MSB) of each following byte, then the 7 bytes follow
with their MSB cleared.

> **Gotcha #2:** confirm the exact bit order (which group position maps to which bit of the
> high-bits byte) against a known device response before trusting decoded binary fields. ASCII
> fields survive a wrong bit order; node IDs / sizes won't.

## Reproducing this

macOS, with [`sendmidi`/`receivemidi`](https://github.com/gbevin) (`brew install
gbevin/tools/sendmidi gbevin/tools/receivemidi`):

```sh
# Watch everything the device sends:
receivemidi dev "EP-133"

# In another shell — greet (note: sendmidi takes DECIMAL; TE manufacturer = 0 32 118):
sendmidi dev "EP-133" syx 0 32 118 0 64 96 1 1

# Open a file session (FILE_INIT), then list the root node (deviceId 0x33 = 51):
sendmidi dev "EP-133" syx 0 32 118 51 64 96 4 5 0 1 1 0 0 2 0
sendmidi dev "EP-133" syx 0 32 118 51 64 96 5 5 0 4 0 0 0 0
```

> **Gotcha #3:** `sendmidi` parses bare numbers as **decimal**. `00 20 76` (hex) is `0 32 118`
> (decimal). Sending hex-looking decimals quietly corrupts the manufacturer ID and the device
> ignores you.

## Credits

Built while making an open-source offline sample tool for the EP-133. The opcode/field layouts
were learned from Teenage Engineering's own web sample tool; the wire behavior was confirmed on
a real K.O. II. Shout to the EP-133 / K.O. II community.

Found something wrong or filled in a gap? PRs and corrections welcome.
