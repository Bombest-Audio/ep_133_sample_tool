# MPC .xpm schema notes (Phase 12 spike)

Status: SPIKE - written against the community-reverse-engineered schema, not
against real files. No .xpm files exist on this machine (the mounted DOJO
DRUMS packs are plain WAV folders; verified with `find ... -iname "*.xpm"`).
Per the ROADMAP fallback, we GO with a best-effort subset: root note, key
ranges, and the first velocity layer per instrument.

## What an .xpm is

An `.xpm` file is the XML "program" file saved by Akai MPC software (MPC 2.x
and MPC Beats) next to the WAV samples it references. Expansions ship as a
folder of `.xpm` programs plus `.WAV` files. Two program types matter here:

- **KEYGROUP** - a multisampled instrument. Instrument entries map key ranges
  (low/high note) to sample layers with a root note. Maps onto our
  `SampledInstrument` zone model and plays as a chord voice through
  `SampledInstrumentVoice`.
- **DRUM** - pad-oriented one-shots (one instrument entry per pad, up to 128).
  Maps onto the existing one-shot pack import path (a `KitCategory` of
  `KitSample`s in the Kit Builder).

## Assumed document shape

All schema assumptions are encoded in ONE place in code:
`MpcXpmSchema` in
`AndroidApp/app/src/main/java/com/ep133/sampletool/domain/pack/MpcExpansionParser.kt`.
This document mirrors those constants.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<MPCVObject>
  <Version> ... </Version>
  <Program type="Keygroup">          <!-- or type="Drum" -->
    <ProgramName>My Piano</ProgramName>
    <Instruments>
      <Instrument number="0">
        <LowNote>36</LowNote>        <!-- keygroup key range, MIDI note -->
        <HighNote>59</HighNote>
        <Layers>
          <Layer number="1">
            <SampleName>PianoC3</SampleName>   <!-- NO extension -->
            <SampleFile>PianoC3.WAV</SampleFile>  <!-- sometimes present -->
            <RootNote>48</RootNote>
            <VelStart>0</VelStart>
            <VelEnd>127</VelEnd>
            <Volume>1.0</Volume>     <!-- linear, 1.0 = unity -->
          </Layer>
          <Layer number="2"> ... </Layer>   <!-- further velocity layers -->
        </Layers>
      </Instrument>
    </Instruments>
  </Program>
</MPCVObject>
```

## Parsing rules (the best-effort subset)

| Field | Source | Default when missing |
|---|---|---|
| Program type | `Program@type`, case-insensitive (`keygroup` / `drum`) | `UNKNOWN` + warning; program skipped |
| Program name | `<ProgramName>` | file name without extension |
| Key range | `<LowNote>` / `<HighNote>` on Instrument | 0 / 127 |
| Root note | `<RootNote>` on the used Layer | midpoint of the key range |
| Sample name | `<SampleFile>` if present, else `<SampleName>` + `.wav` | layer skipped + warning |
| Velocity layers | **first Layer with a sample only** (lowest `number`) | - |
| Layer volume | `<Volume>` (linear) | 1.0 |

Tolerance contract:

- Unknown elements and attributes are ignored everywhere.
- Missing optional fields get the defaults above.
- A malformed document, an instrument with no usable layer, or an
  unrecognized program type produces `warnings` in `MpcParseResult`, never an
  exception. `program == null` only when nothing usable was found.
- DRUM programs: every instrument's first sample-bearing layer becomes one
  one-shot entry; key range and root note are ignored (pads, not keys).

## Sources of uncertainty (validate against real files)

1. **RootNote encoding** - some community dumps show `RootNote` as a 0-based
   MIDI number, others suggest MPC displays are offset by one octave
   (C3 = 48 vs 60 convention). We assume the value is the raw MIDI number.
2. **Volume scale** - assumed linear (1.0 = unity). Could be normalized 0..1
   against a different reference in some MPC versions.
3. **`SampleFile` vs `SampleName`** - we prefer `SampleFile` when present and
   otherwise append `.wav` to `SampleName`. Real expansions may use `.WAV`
   uppercase (resolution is case-insensitive at import time).
4. **Element casing / nesting drift across MPC versions** - MPC 2.x vs MPC
   Beats may differ. Element lookup is case-insensitive to absorb some drift.
5. **Drum pad ordering** - we assume `Instrument@number` document order is the
   pad order; the exact pad-number mapping (banks A-H) is not modeled.
6. **Round-robin / sample start-end / tuning opcodes** - ignored entirely in
   this subset (fine for v1: root, ranges, first layer).

## Validation checklist for when real .xpm files are available

- [ ] Open a real KEYGROUP program: zone count, key ranges, and root notes
      match what MPC software shows.
- [ ] Confirm `RootNote` octave convention (play C4 - correct pitch?).
- [ ] Confirm `Volume` is linear and unity is 1.0.
- [ ] Check whether `SampleFile` or `SampleName` is present, and the actual
      extension casing on disk.
- [ ] Open a real DRUM program: one-shot list matches the pad layout.
- [ ] Feed a multi-velocity keygroup: verify first-layer pick sounds right.
- [ ] Run every program in an expansion through the parser: zero exceptions,
      warnings only where features are genuinely unsupported.
