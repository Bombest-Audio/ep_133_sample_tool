---
quick_id: 260621-cu5
slug: harden-sample-import
title: Harden Android sample import — name sanitization + resampler input guards
created: 2026-06-21
completed: 2026-06-21
status: complete
commit: e13cd3c
---

# Quick Task 260621-cu5: Summary

ASCII-safe `sanitizeName` rewrite + `Resampler` input guards so odd filenames can't
produce lossily-encoded US_ASCII device paths and zero-channel/zero-rate inputs can't
throw uncaught `ArithmeticException`.

## What changed

### SampleImportManager.kt — `sanitizeName` rewrite (Task 1)

Old implementation only blocked `/`, `\`, `..`, and control chars. Non-ASCII input
(accented letters, emoji) passed through to `buildFilePutFrame`, which encodes with
`Charsets.US_ASCII` — producing `?` bytes and a corrupt `/sounds/<name>` path on the
device.

New implementation:

1. Extracts basename by stripping any path prefix (`/` or `\` separated) then drops
   the file extension via `substringBeforeLast('.')`.
2. Replaces every character outside `[A-Za-z0-9 _-]` with `_`. This makes the result
   pure ASCII and lossless under US_ASCII encoding. All old traversal vectors (`/`,
   `\`, `..`, control chars) are subsumed — they all map to `_`.
3. Collapses whitespace runs to a single space, then trims leading/trailing whitespace
   and leading/trailing `.`/`_`/`-`.
4. Caps the basename at `MAX_BASENAME_LEN = 32` characters, re-trimming trailing
   `_`/`-`/space at the cut point.
5. Returns `null` if the result is empty after sanitizing.
6. Returns `"$base.wav"`.

Regression contract preserved: `sanitizeName("kick.wav") == "kick.wav"`,
`"snare.wav" -> "snare.wav"`, `"hihat.wav" -> "hihat.wav"`.

`MAX_BASENAME_LEN` exposed as `companion object const` for use in tests.

### Resampler.kt — input guards (Task 2)

Added three `require()` calls at the top of `toRate`, before the fast-path:

```kotlin
require(channels >= 1) { "channels must be >= 1, was $channels" }
require(srcRate > 0) { "srcRate must be > 0, was $srcRate" }
require(dstRate > 0) { "dstRate must be > 0, was $dstRate" }
```

Without these, `channels == 0` caused integer division by zero; `srcRate <= 0` would
produce negative frame counts or infinite loops. Updated `@throws` KDoc.

### ResamplerTest.kt — three new guard tests (Task 3a)

- `throws_whenChannelsIsZero` — `channels = 0` → `IllegalArgumentException`
- `throws_whenSrcRateIsZero` — `srcRate = 0` → `IllegalArgumentException`
- `throws_whenDstRateIsZero` — `dstRate = 0` → `IllegalArgumentException`

All using `@Test(expected = IllegalArgumentException::class)`. Existing tests D/E/F
unchanged and still pass.

### SampleImportSanitizeTest.kt — new test class (Task 3b)

Constructs `SampleImportManager` with a disconnected no-op
`SanitizeNoOpRepo(SanitizeNoOpPort())` (mirrors `SampleImportFakeRepo` pattern;
renamed to avoid top-level declaration clash).

Tests:

| Test | Input | Expected |
|------|-------|----------|
| `kick_passesThroughUnchanged` | `"kick.wav"` | `"kick.wav"` |
| `snare_passesThroughUnchanged` | `"snare.wav"` | `"snare.wav"` |
| `hihat_passesThroughUnchanged` | `"hihat.wav"` | `"hihat.wav"` |
| `nonAscii_cafe_producesAsciiResult` | `"café.wav"` | non-null, all codes < 128, ends `.wav`, no `?` |
| `emoji_unicodeOnly_producesAsciiOrNull` | `"🥁.wav"` | `null` or pure ASCII + `.wav` |
| `traversal_dotDot_noSlashOrDoubleDotInResult` | `"../../etc/passwd"` | no `/`, `\`, or `..` |
| `subdirPath_noSlashInResult` | `"a/b/c.wav"` | no `/` |
| `overlong_name_basenameCappedAt32` | 200-`a` + `.wav` | basename length <= 32 |
| `trailingWhitespace_isTrimmed` | `"  kick  .wav"` | valid, basename no leading/trailing space |
| `trailingDots_areTrimmed` | `"kick...wav"` | valid, no trailing `_` or `-` |
| `empty_string_returnsNull` | `""` | `null` |
| `slashesOnly_returnsNull` | `"///"` | `null` |
| `dotsOnly_returnsNull` | `"..."` | `null` |

## Verification

```
:app:testDebugUnitTest  — BUILD SUCCESSFUL (full suite green)
:app:assembleDebug      — BUILD SUCCESSFUL
```

No pre-existing tests modified. No scope violations (MIDIRepository, SysExProtocol,
AudioDecoder, WavEncoder untouched).

## Deviations from plan

None. Plan executed exactly as written.

## Known stubs

None introduced by this task.

## Threat surface scan

No new network endpoints, auth paths, file access patterns, or schema changes introduced.
The sanitizeName rewrite closes an existing encoding-loss gap (non-ASCII input reaching
`buildFilePutFrame`'s US_ASCII encoder) — this is mitigation, not new surface.
