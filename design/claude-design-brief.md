# Claude Design brief — EP-133 Sample Tool

This is a paste-ready prompt for [Claude Design](https://claude.ai/). It produces one
cohesive, Teenage-Engineering-grade design system across three surfaces: a **landing
page**, a **designed SysEx protocol reference page**, and an **app UI redesign concept**.
The landing + protocol pages become the project's GitHub Pages site; the app concept is a
web mockup we port to Android Compose later.

**How to use it:** copy everything between the two `═══` markers into Claude Design. The
protocol facts in Page 2 are hardware-verified and pulled from
[`docs/ep133-sysex-protocol.md`](../docs/ep133-sysex-protocol.md) — keep them exact. The
color tokens match the real app (`AndroidApp/.../ui/theme/Color.kt` and `data/custom.js`).

═══════════════════════════════════════════════════════════════════════════════════

Build the public design system for **"EP-133 Sample Tool"** — a free, open-source
desktop / mobile / plugin app for managing samples on the Teenage Engineering EP-133
K.O. II and EP-1320 K.O. II hardware samplers. It's a gift to the community from Bombest
Audio. I need three connected surfaces in **one cohesive visual system**: (1) a landing
page, (2) a designed technical reference page for the device's reverse-engineered MIDI
SysEx protocol, and (3) a redesign concept of the app's own interface. Deliver responsive
HTML/CSS with the design tokens below as CSS custom properties so the landing + protocol
pages can ship as a GitHub Pages site.

**Copy voice — write all rendered text warm, direct, and human, like a maker talking to
other makers, not a corporation.** Confident and a little playful. Lead with "this one's
for the community — from Bombest Audio." Plain words, short sentences. No corporate filler,
no "revolutionize / seamless / robust / leverage", no rule-of-three slogans, no LLM tells.
Headlines can be punchy and lowercase where it feels right.

**Aesthetic — Teenage Engineering industrial design, translated to the web.** Utilitarian,
precise, confident, a little playful. Think TE's product pages and the OP-1 / EP-133
hardware: flat warm-gray faceplates, rubberized black pads, one loud accent color,
monospaced labels, hairline rules, dense functional grids, generous negative space. NO
gradients, NO glassmorphism, NO drop-shadow soup, NO generic SaaS-startup look. Every
element looks like it has a function. Numbers and codes are first-class typographic
citizens.

**Design tokens (use exactly):**
- Accent / action — orange `#EF4E27`. Connectivity / "live" — teal `#00A69C`.
- Faceplate grays — `#D1D2D4` (base), `#E2E3E5` (light), `#C6C7C9` (dark), `#D8D9DB` (buttons).
- Pad black `#222323` (+ `#2D2E2E` highlight). Ink `#191A1B`, secondary `#555657`,
  tertiary `#8A8B8D`. Borders `#B0B1B3`.
- Provide BOTH a light theme (faceplate-gray surfaces, dark ink) and a dark theme
  (pad-black surfaces `#121314` / `#1E1F20`, light ink `#E2E3E4`).
- Support an alternate **EP-1320 SKU accent** as a theme variant toggle — rust `#B22E20`
  with warm-tan `#999269`.
- Type: a clean grotesque for headings (tight tracking, ALL-CAPS for labels/eyebrows) and
  a **monospace** for codes, byte values, node IDs, and table data. Bold weights, ~2px
  letter-spacing on labels. Sentence case for body, UPPERCASE for system labels.
- Motifs: hairline 1px dividers, numbered/lettered chips (A/B/C/D pad groups), small
  status dots (teal = ok, orange = action), tabular tables with mono cells, a subtle
  dotted/grid background like a PCB or graph paper. Hard corners or very small radii — no
  pill-soft cards.

---

**Page 1 — Landing page.** Sections:
- **Hero** — product name, tagline "Offline sample management for the EP-133 K.O. II", the
  line "A gift to the community, from Bombest Audio", a primary download CTA + a GitHub
  link, and a clean illustrated / abstract nod to the EP-133 device.
- **Runs everywhere** — a tidy grid of platform cards with requirements: Desktop
  (Windows/macOS/Linux, Electron, Node 18+), Android (10+/API 29), iOS (16+),
  AU/VST3 plugin (macOS, JUCE 8).
- **Feature highlights** — 100% offline; projects-only backup/restore; custom colors &
  bank names; serial-number stripping; no telemetry; and "import your own WAV samples over
  USB" (the hard-won one — give it weight).
- **Protocol teaser** — a strip linking to the SysEx reference: "We reverse-engineered the
  whole file protocol. Read it →".
- **Footer** — open-source / credits: MIT for our code, TE's bundled assets stay TE's,
  built on garrettjwilke's tool, not affiliated with Teenage Engineering.

**Page 2 — SysEx protocol reference.** A long, beautifully typeset technical doc with a
sticky sidebar table of contents. Real, hardware-verified content — design it so dense
binary detail stays legible. Sections and the exact facts to typeset:
- **Transport** — device enumerates as standard USB-MIDI (CoreMIDI name `EP-133`); pads
  emit MIDI notes 36–83 across groups A/B/C/D; filesystem + playback are SysEx-only.
- **Frame format** — render the request frame as a labeled byte diagram:
  `F0 00 20 76 <deviceId> 40 <flags> <reqId> <command> <7-bit-packed payload…> F7`.
  Manufacturer ID `00 20 76` (Teenage Engineering). Device ID `0x33` on the tested unit.
  Subsystem byte `40`. Flags: `0x40` = is-request, `0x20` = request-id-present. The
  **response** frame drops `F0` and inserts a 1-byte **STATUS** code after the command.
- **7-bit packing** — explain LSB-first packing with a small visual: a high-bits byte +
  up to 7 data bytes; `unpacked[k] = (data[k] & 0x7F) | (((highbits >> k) & 1) << 7)`.
- **Handshake** — Greet (command `1`) returns an ASCII key/value string (product, firmware
  `2.0.5`, serial). File session init (command `5`, subcommand `FILE_INIT = 1`) is
  **required before any file op**; it negotiates a 512-byte chunk size.
- **File subcommands** (command `5`) — a mono table: FILE_INIT 1, FILE_PUT 2, FILE_GET 3,
  FILE_LIST 4, FILE_PLAYBACK 5, FILE_DELETE 6, FILE_METADATA 7, FILE_INFO 11.
- **Node tree** — render the device filesystem as a clean tree diagram with the real node
  IDs: root `0` → `sounds` `1000` (sample slots `001.pcm`, `002.pcm`, … as sequential node
  IDs) and `projects` `2000` → projects `01`–`09` (`3000`, `4000`, … `11000`), each with a
  `groups` dir → pad groups `A` / `B` / `C` / `D`.
- **FILE_PUT upload sequence** — a numbered, stepped flow diagram (this is the centerpiece):
  open session → resolve `/sounds` (node 1000) → PUT INIT (await the assigned fileId) →
  paged DATA (~427-byte chunks, await each page's ack, unique request id per page) →
  zero-length terminator (await OK).
- **Gotchas** — style these as prominent warning callouts (this is the hard-won knowledge):
  #0 consume the STATUS byte *before* unpacking; #1 file ops go under command `5`, not
  `127`; #2 packing is LSB-first, not MSB-first; #3 await each ack or you get "unexpected
  page"; #4 an incomplete PUT *wedges the device* until power-cycle — always send the
  terminator; #5 every multi-byte field must itself be 7-bit packed.
- **Metadata schemas** — a few JSON examples in mono code blocks (e.g. `/projects` →
  `{"active":<node>}`, and a sound file's full parameter blob).
- **Format & limits** — RIFF/PCM s16 LE, mono or stereo, native 46875 Hz, source rate
  3000–768000 Hz, max 20 s.
- **Reproduce it** — a short "do it yourself on macOS with sendmidi / receivemidi" callout
  with example decimal byte sequences.
- **Credits** — reverse-engineered by cross-referencing TE's own web tool against live
  hardware captures.

**Page 3 — App UI redesign concept.** A cohesive mockup of the app's screens (these map to
the real Android app), as design comps — phone frames or clean panels in the same system:
- **Pads** — 4×4 grid of sample pads, group A/B/C/D selector, waveform preview, BPM/transport.
- **Beats** — 16-step sequencer with step toggles + a playhead.
- **Sounds** — browsable list of 128 sounds across 10 banks (Kick / Snare / Cymb / Perc /
  Bass / Melod / Loop / User1 / User2 / SFX) with filter chips + preview.
- **Chords** — 8 programmable chord slots + a chord-builder.
- **Device** — USB/MIDI connection status, backup/restore, format/sync.
- **Projects** — saved-project cards.
- **Sample Import** — WAV file picker + per-page upload progress (surface the SysEx upload
  visually — it's the signature feature).

Make it look like something Teenage Engineering would actually ship: hardware-faithful pad
rendering (`#222323` rubber with a subtle highlight), orange/teal accents used sparingly and
meaningfully, mono labels for pad IDs and step numbers, faceplate-gray chrome. Include a
small component sheet (buttons, pads, chips, status dots, tables, sliders) so it's portable
to native Compose.

**Tone & don'ts:** confident and minimal, not corporate. No stock hero photography, no
purple-gradient SaaS hero, no rounded-everything. Embrace the engineered, slightly
retro-futurist TE feel. Mobile-responsive. Clean semantic HTML/CSS with the tokens above as
CSS custom properties.

═══════════════════════════════════════════════════════════════════════════════════
