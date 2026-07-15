# NO-GO: the EP-133 exposes no writable pattern node over SysEx

**Verdict: NO-GO.** An exhaustive, read-only enumeration of a physical EP-133 K.O. II found no writable pattern, sequence, scene, or step-data node anywhere in the device's SysEx file tree. Every writable node the device exposes is a known sound-pad slot or a `/sounds` sample. There is nothing to write a Chords pattern into, so a direct on-device pattern push is not possible on this firmware. Bake-to-sample (Phase 9) remains the guaranteed push path, and nothing downstream was depending on this outcome.

This resolves the Phase 6 spike. Backlog item 999.1 (editable-pattern push) is dropped.

## Method

- **Device:** EP-133 K.O. II, physical unit over USB-MIDI, driven by the debug-only `PatternSpikeWalker` (recursive read-only tree walker) via the "Run Pattern Spike" affordance.
- **Firmware:** GREET reported `sw_version=2.5.0` (`os_version=2.5.0`, `bl_version=1000.0.10`). This matches the firmware the canonical protocol reference was verified against, so assumption A4 (documented tree = unit under test) holds. The verdict is scoped to fw 2.5.0.
- **Date:** 2026-07-14.
- **What I enumerated:**
  - A full recursive walk of the active project slot `/projects/09` (node 11000): 53 nodes.
  - A full recursive walk from root `/`: 600 nodes (all 9 project slots plus the entire `/sounds` pool).
- **Paging depth:** every `FILE_LIST` was paged until an empty page, not stopped at page 0. Each group returned exactly 12 pad entries on page 0 and an empty page 1 (no hidden later-page child). Each pad FILE node was directly `FILE_LIST`ed and returned a device error (status 1, "invalid id"), confirming it has no children.

The walk is read-only: `FILE_LIST` + `FILE_INFO` + `METADATA GET` only. No write, no delete, no paged upload. Nothing on the device was modified.

## Evidence: node dump

Full dump of the `/projects/09` subtree (fw 2.5.0). `flags` shown raw + decoded; metadata is the verbatim node metadata JSON.

| nodeId | parent | flags | name | metadata |
|--------|--------|-------|------|----------|
| 11100 | 11000 | 0x0e READ\|WRITE\|DIR | groups | `{"active":11200}` |
| 11200 | 11100 | 0x0e READ\|WRITE\|DIR | A | `{"active":11201}` |
| 11201 | 11200 | 0x1d READ\|WRITE\|DELETE\|FILE | 01 | `{"sym":0}` |
| 11202 | 11200 | 0x1d READ\|WRITE\|DELETE\|FILE | 02 | `{"sym":0}` |
| 11203 | 11200 | 0x1d READ\|WRITE\|DELETE\|FILE | 03 | `{"sym":0}` |
| 11204 | 11200 | 0x1d READ\|WRITE\|DELETE\|FILE | 04 | `{"sym":0}` |
| 11205 | 11200 | 0x1d READ\|WRITE\|DELETE\|FILE | 05 | `{"sym":0}` |
| 11206 | 11200 | 0x1d READ\|WRITE\|DELETE\|FILE | 06 | `{"sym":0}` |
| 11207 | 11200 | 0x1d READ\|WRITE\|DELETE\|FILE | 07 | `{"sym":63,"sound.playmode":"oneshot","sample.start":0,"sample.end":13264,"envelope.attack":0,"envelope.release":255,"sound.pitch":0,"sound.amplitude":100,"sound.pan":0,"sound.mutegroup":false,"time.mode":"off","midi.channel":0}` |
| 11208 | 11200 | 0x1d READ\|WRITE\|DELETE\|FILE | 08 | `{"sym":62,"sound.playmode":"oneshot","sample.start":0,"sample.end":9052,...}` |
| 11209 | 11200 | 0x1d READ\|WRITE\|DELETE\|FILE | 09 | `{"sym":0}` |
| 11210 | 11200 | 0x1d READ\|WRITE\|DELETE\|FILE | 10 | `{"sym":68,"sample.end":24529,...}` |
| 11211 | 11200 | 0x1d READ\|WRITE\|DELETE\|FILE | 11 | `{"sym":65,"sample.end":31156,...}` |
| 11212 | 11200 | 0x1d READ\|WRITE\|DELETE\|FILE | 12 | `{"sym":61,"sample.end":8178,...}` |
| 11300 | 11100 | 0x0e READ\|WRITE\|DIR | B | `{"active":0}` |
| 11301-11312 | 11300 | 0x1d READ\|WRITE\|DELETE\|FILE | 01-12 | `{"sym":0}` (all 12 empty) |
| 11400 | 11100 | 0x0e READ\|WRITE\|DIR | C | `{"active":0}` |
| 11401-11412 | 11400 | 0x1d READ\|WRITE\|DELETE\|FILE | 01-12 | `{"sym":0}` (all 12 empty) |
| 11500 | 11100 | 0x0e READ\|WRITE\|DIR | D | `{"active":0}` |
| 11501-11512 | 11500 | 0x1d READ\|WRITE\|DELETE\|FILE | 01-12 | `{"sym":0}` (all 12 empty) |

53 nodes total: 1 `groups` dir + 4 group dirs + 48 pad FILE nodes. The tool flagged 48 write-candidate FILE nodes, all of them pad sound-slots.

Root-level structure (from the 600-node root walk), confirming the top of the tree:

```
0  /
├── 1000  sounds    dir  {"max_capacity":62853120,"free_space_in_bytes":42798228,"formats":[{"type":"pcm",...,"samplerate.native":46875,...,"format":["s16"]}]}
└── 2000  projects  dir  {"active":11000}
    └── 01..09      each → groups → A/B/C/D → 12 pads   (identical shape across all 9 slots)
```

## Analysis

Every writable node is a sound object, not a sequence object. The pad FILE nodes carry a **sound-binding** metadata schema: `sym` (the assigned sample symbol), `sound.playmode`, `sample.start`/`sample.end`, `envelope.attack`/`release`, `sound.pitch`/`amplitude`/`pan`/`mutegroup`, `time.mode`, `midi.channel`. That is the pad's sound configuration. None of it is step, note, trigger, or pattern-grid data. An empty pad reads `{"sym":0}`; a loaded pad adds its sound parameters. There is no per-step key, no hidden trigger array, no scene structure.

The group and project nodes are pure directories whose only metadata is an `active` pointer (`{"active":<childNode>}`). They hold no sequence data either.

So the 48 "write candidates" are writable in the sense the sample-manager already uses (you can reassign a pad's sound), not in the sense the spike was hunting for (a pattern node you could push a Chords sequence into). The EP-133 has an on-device sequencer, but that state is not represented anywhere in the FILE tree on fw 2.5.0.

## Tested absence (why this is NO-GO, not a guess)

The spike defined four places an undocumented pattern node could hide. I searched all four on hardware:

- **Gap A - project node's full child set.** `listAllChildren(11000)` returned exactly one child, `groups` (11100). No `patterns`/`song`/`scenes`/`sequences` sibling. Confirmed across all 9 project slots in the root walk: every project's only child is `groups`.
- **Gap B - group children past page 0.** Each of A/B/C/D returned exactly 12 pad entries on page 0 and an empty page 1. No hidden 13th (non-pad) child.
- **Gap C - pad node children and full metadata.** Every pad FILE node was directly `FILE_LIST`ed and returned status 1 "invalid id" (no children). The full pad metadata was dumped (not just documented keys) and contains only the sound-binding schema above.
- **Gap D - root top level.** Root has exactly two children, `sounds` and `projects`. No top-level `patterns` directory.

Across 600 enumerated nodes, no pattern-write node exists. The absence is tested, not assumed.

One boundary is explicitly out of scope for this spike: pattern state could ride the `PRODUCT_SPECIFIC (127)` command or an undocumented non-FILE subcommand rather than the FILE tree (research Open Question Q1). The ROADMAP scoped this spike to the FILE-node question. A `PRODUCT_SPECIFIC` probe is a separate, future investigation, not a reopening of this one.

## Fallback confirmation

Bake-to-sample (Phase 9) remains the guaranteed way to get Chords content onto the device: render the pattern to audio and upload it through the proven `/sounds` FILE_PUT path, which this same enumeration confirms is healthy (writable sound nodes, working paging). Nothing downstream blocks on the pattern-write question. The Chords milestone does not depend on a direct pattern write and never did.

## Follow-on

- Drop backlog item 999.1 (editable-pattern push). The hardware does not support it on fw 2.5.0.
- Do not add `PATTERN-01`. That requirement was conditional on a GO.
- If a future firmware or a `PRODUCT_SPECIFIC` probe is ever worth chasing, it starts fresh from this document: the FILE tree is now exhaustively mapped, so any new pattern surface would have to be a new command, not a missed node.

## Note on the tooling fix behind this run

The first hardware run wedged the walker in a tight loop. Root cause: `listNodeBody` passed a device error response (status 1, "invalid id") through as if it were list data, so `listAllChildren` never saw a terminal page and paged forever. The simulator only ever returns clean empty pages, so this hardware-only error path was never exercised in the sim tests. Fixed by treating a non-success FILE_LIST status as terminal (return null, stop paging), which also makes a bad path segment resolve to null instead of matching mis-parsed error bytes. The clean 600-node walk above is from the fixed build.
