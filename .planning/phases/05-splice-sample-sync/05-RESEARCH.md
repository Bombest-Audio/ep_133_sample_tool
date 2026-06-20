# Phase 5: Splice Sample Sync — Research

**Researched:** 2026-06-20
**Domain:** Splice sample access (API / ToS / local folder), Android SAF import, WAV → EP-133 format conversion, reuse of the Phase 4 paged FILE_PUT stack
**Confidence:** HIGH on the feasibility verdict (official docs + ToS quoted directly); HIGH on the EP-133 WAV format (extracted from `data/index.js`); HIGH on the device-load reuse (Phase 4 stack already shipped)

---

## ⛔ FEASIBILITY VERDICT: NO PROGRAMMATIC SPLICE PATH — MANUAL IMPORT (SAF) IS THE FLOOR AND THE CEILING ON ANDROID

**There is no buildable, ToS-permitted, on-device way to reach a user's Splice library from an Android app. Build the manual-import path: user picks sample files via SAF, the app converts them to the EP-133's WAV format, and loads them over the existing Phase 4 paged FILE_PUT stack.** This is a clean "infeasible via API → fallback" outcome, not a forced result.

Ranking of the three candidate access paths, with evidence:

| Path | Buildable on Android? | ToS-permitted? | Verdict |
|------|----------------------|----------------|---------|
| (a) Official Splice API (OAuth/partner) | **No — does not exist** | n/a | Eliminated |
| (a′) Internal Splice GraphQL API (what `splicerr` hits) | Technically yes | **No — violates ToS §II.7.a + §II.7.j** | Disqualified — do not build |
| (b) Local Splice desktop folder (`~/Splice`) | **No on Android** (folder is desktop-only) | Yes (reading the user's own files) | Not viable on-device; only via a desktop/companion path |
| (c) Manual import (SAF file picker) | **Yes** | Yes (user-initiated, user-owned files) | **RECOMMENDED — the only buildable path** |

### Why (a) the official API is out

There is **no public or partner API** for the Splice sample library (splice.com). Every "Splice API" that surfaces in search is a different product — the Canton Network blockchain (`docs.sync.global`, `hyperledger-labs/splice`) and SPLICE Software's insurance platform (`developers.splicesoftware.com`). Neither is the music sample service. `[CITED: en.wikipedia.org/wiki/Splice_(platform)]` `[CITED: docs.sync.global]`

Splice's own help docs confirm the *intended* integration model is desktop-app drag-and-drop into a DAW, and explicitly state that pulling samples into arbitrary third-party plug-ins is **not supported**: "Dragging samples directly from Splice into Max for Live devices or third-party plug-ins is not supported." `[CITED: support.splice.com]` There is no documented programmatic surface for a third-party app.

### Why (a′) the internal GraphQL API is disqualified (the load-bearing finding)

The desktop app talks to an **internal, undocumented GraphQL API**. The community frontend `splicerr` reaches the user's library through exactly this — and notes Splice has been tightening it (Apollo preflight headers now required), i.e. Splice actively defends it. `splicerr` markets "no authentication required," which tells you it is operating against an internal surface that was never meant for third parties. `[CITED: github.com/Exorsky/splicerr]`

Building against that endpoint is **a direct Terms-of-Use violation**, quoted verbatim from the official terms `[CITED: splice.com/terms]`:

- **§II.7.j** — prohibits "use [of] any manual or automated software, devices or other processes (including but not limited to spiders, robots, scrapers, crawlers, avatars, data mining tools or the like) to 'scrape' or download data from any web pages contained in the Websites."
- **§II.7.a** — prohibits "disassembling, decompiling, reverse compiling or reverse engineering or otherwise attempting to discover the source code of any portion of the Service." (Determining the GraphQL schema/headers to drive it is exactly this.)
- **§XII.3** — "If you violate any provision of the Agreement or otherwise misuse the Service, Splice may, at its sole discretion, terminate the Agreement or suspend or terminate your access to the Service."

So the only *technically* programmatic path (a′) is the one path we must **not** ship: it risks getting our users' paid Splice accounts terminated, and it's a moving target Splice is actively hardening. This is a real legal/product constraint, surfaced honestly — it kills the API dream, and that's the correct answer.

### Why (b) the local folder doesn't help on Android

The Splice desktop app downloads samples to a known folder — **`~/Splice` on macOS** (`Macintosh HD/Users/[username]/Splice`) and **`C:\Documents\Splice` on Windows**, user-relocatable in app preferences. `[CITED: support.splice.com/en/articles/8652662]` Reading those files (the user's own, already-licensed samples) is fine. **But that folder does not exist on Android** — Splice ships no Android app, and there's no on-device equivalent. The only way (b) becomes real is off-device: a desktop/Electron step, or the user syncing that folder somewhere the Android app can see it (a cloud-synced directory exposed via SAF — which then just collapses into path (c) anyway).

### Recommendation, including the desktop question

**Ship path (c) — SAF manual import — as the Android feature.** It's the only path that is simultaneously buildable on-device and ToS-clean. The user already has their licensed samples on their phone (downloaded from the Splice website, AirDropped, cloud-synced, etc.); the app lets them pick those files, converts them, and loads them onto the EP-133. The feature is effectively "import any audio file onto the EP-133," with Splice samples being the motivating case — *not* a Splice integration.

**Desktop/Electron is genuinely the better home for a true Splice sync**, and worth flagging to Thomas: this repo already ships an Electron target. Only on desktop does the local `~/Splice` folder exist, so a desktop "watch the Splice folder → convert → load over USB MIDI" feature is the one place a real sync (path b) is both feasible and ToS-clean (it reads the user's own local files, no API). That's a separate phase, not this one. **For Android, manual import is the answer.** Recommend renaming the phase deliverable from "Splice Sample Sync" to "Sample Import" to set honest expectations — there is no Splice-specific machinery being built.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### The gate (now resolved)
- (a) Official Splice API — **does not exist**; the internal GraphQL API exists but is **ToS-disallowed** (scraping/reverse-engineering clauses). Do not build against it.
- (b) Local Splice desktop folder — **desktop-only**; absent on Android. Viable only via a desktop/companion path, out of scope for the Android target.
- (c) Manual import — **the chosen path.** User selects sample files via SAF; app converts + loads.

### Locked Decisions
- **Android only.** Reuse the Phase 4 multi-page `FILE_PUT` stack (`ProjectBackupManager` pattern, `MIDIRepository.putProjectArchive`-style paged PUT, `SysExProtocol` builders) — not a parallel implementation.
- Do **not** re-solve the SysEx transfer; build on Phase 4.
- If no viable programmatic path exists → recommend the best fallback. **It is manual-import (SAF). Surfaced as the verdict above.**

### Claude's Discretion (post-feasibility)
- UI for browsing/selecting sample files (now: a SAF picker + a staged-import list, not a Splice browser).
- Where converted WAVs are staged before load.

### Deferred Ideas (OUT OF SCOPE)
- Formal requirements (SPLICE-01..0N) — lock after this verdict. Suggested rename: **SAMPLE-01..0N** (import, not Splice sync).
- iOS.
- A desktop/Electron Splice-folder watcher (the one place a real sync is feasible + ToS-clean) — note for a future phase, not this one.
</user_constraints>

---

## Summary

The phase's make-or-break unknown resolves negative on the API question and positive on the buildable fallback. Splice exposes **no public/partner API** for its sample library; the only programmatic surface is an **internal GraphQL endpoint** that Splice's own Terms of Use forbid third parties from touching (anti-scraping §II.7.j and anti-reverse-engineering §II.7.a, enforced by account termination §XII.3). The Splice desktop **local folder** exists only on desktop OSes, not Android. The remaining path — and the one the app should ship — is **manual import via Android's Storage Access Framework**: the user picks audio files, the app converts them to the EP-133's expected WAV format, and loads them onto the device over the Phase 4 paged FILE_PUT stack.

The conversion target is fully known from the compiled web app: the EP-133 expects **16-bit signed PCM WAV at 46875 Hz, mono or stereo**. The web app's `uploadSound` passes through any file that's already `WAV / s16 / 46875 Hz / 1–2 channels` untouched, and otherwise decodes → resamples to 46875 → re-encodes as 16-bit WAV. On Android, that conversion is achievable without third-party libraries using `MediaExtractor`/`MediaCodec` (decode) + a small linear/sinc resampler + a hand-written WAV (RIFF) encoder — all platform APIs.

Device loading **reuses Phase 4 directly**: the same paged `FILE_PUT` (`buildFilePutInitFrame`/`buildFilePutDataFrame`, `MIDIRepository.putProjectArchive`'s INIT-then-paged-DATA loop), but targeting **`/sounds/<name>.wav`** instead of `/projects`. The `ProjectBackupManager` orchestration (Flow of progress events, IO-dispatched file reads, connected-device guard) is the pattern to mirror in a new `SampleImportManager`.

**Primary recommendation:** Three waves — (1) **SAF import + WAV conversion** (`MediaCodec` decode → resample to 46875 → 16-bit WAV encode, unit-tested on the encoder and resampler with synthetic PCM); (2) **paged `/sounds` PUT** reusing the Phase 4 stack with a node-resolved `/sounds` target; (3) **import UI + staged-list ViewModel** mirroring `DeviceScreen`. Wave 1 is the real work; Wave 2 is largely a re-target of shipped code.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Splice library access | **None — not built** | — | No ToS-clean programmatic path on Android (verdict). Excluded by design. |
| File selection | Android system (SAF `OpenMultipleDocuments`) | UI (trigger) | OS owns the picker + persistable URI grants; app just consumes the `content://` URIs |
| Audio decode | Domain (`MediaExtractor`/`MediaCodec`) | — | Format decoding is a device-tier concern, fully unit-isolable from UI |
| Resample → 46875 Hz + 16-bit WAV encode | Domain (pure Kotlin DSP + RIFF writer) | — | Pure transform; must be hardware-free and unit-tested with synthetic PCM |
| Sample upload to `/sounds` | Domain (`MIDIRepository` paged PUT, reused) | UI (progress) | SysEx is device protocol; VM only observes progress. **Reuses Phase 4.** |
| Staged-import list + progress | UI (`SampleImportViewModel` + Compose) | — | Pure UI state over a list of pending/converted/loaded items |

---

## Standard Stack

### Core (all already in project — no new dependencies)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `android.media.MediaExtractor` / `MediaCodec` | System (API 29+) | Decode arbitrary audio (MP3/AAC/WAV/FLAC/OGG) to PCM | Platform codec; no native lib needed; min API 29 already met |
| Storage Access Framework (`ActivityResultContracts.OpenMultipleDocuments`) | androidx.activity (present) | User file selection → `content://` URIs | The supported file-picker since API 19; no storage permission needed |
| `android.media.midi` | System (API 29+) | SysEx I/O to EP-133 | Already drives Phases 2 + 4 |
| `kotlinx.coroutines` (+ `-test`) | via Compose BOM / 1.7.3 | Async convert + paged transfer; unit tests | Existing |
| Jetpack Compose BOM | 2024.02.00 | Import screen + staged list | Existing UI stack |
| Navigation Compose | 2.7.7 | Register the Import destination | Existing nav pattern (`EP133App.kt`) |

### New Dependencies Required
**None required.** Decode (`MediaCodec`), resample (hand-written, ~40 lines), and WAV encode (hand-written RIFF, ~30 lines) are all achievable with the platform. See *Alternatives* if a more capable codec surface is wanted.

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Hand-written linear/sinc resampler | A native resampler (e.g. the web app's `resample.wasm` equivalent) | More accurate but adds an NDK/native build; overkill for 1–2 conversions/import. Linear-interpolation resampling at 46875 Hz is audibly fine for EP-133 sample playback. Revisit only if quality complaints arise. |
| `MediaCodec` decode | A bundled decoder lib (e.g. an FFmpeg wrapper) | Broader format support (exotic codecs) but a large native dependency + licensing weight. `MediaCodec` covers WAV/MP3/AAC/FLAC/OGG — every common Splice export format. Stick with platform. |
| SAF per-import picker | `MediaStore` audio query (browse all device audio) | Could list all on-device audio for a richer browser, but adds `READ_MEDIA_AUDIO` permission (API 33+) and scope-storage complexity. SAF picker needs **no runtime permission** and is the lower-friction floor. |

**Installation:** No `npm`/Gradle additions. `MediaCodec`, `MediaExtractor`, SAF contracts, and `android.media.midi` are all on the classpath. **Package Legitimacy Audit: not applicable — zero external packages installed this phase.**

---

## EP-133 Sample Format (extracted from `data/index.js`) — Confidence: HIGH

The device's expected `/sounds` WAV format is hard-coded in the compiled K.O. II web app:

```
const DEVICE_SAMPLE_RATE = 46875, DEVICE_AUDIO_FORMAT = "s16";
```

`[VERIFIED: data/index.js grep]` — the literal `DEVICE_SAMPLE_RATE=46875,DEVICE_AUDIO_FORMAT="s16"` is present in the bundle.

| Property | EP-133 requirement | Source |
|----------|--------------------|--------|
| Container | RIFF/WAV (PCM) | `uploadSound` fast-path checks `container === "WAV"` |
| Sample format | **16-bit signed PCM** (`s16`) | `DEVICE_AUDIO_FORMAT="s16"`; the bit-depth switch maps `"s16" → 16` |
| Sample rate | **46875 Hz** | `DEVICE_SAMPLE_RATE=46875` |
| Channels | **mono or stereo** (1 or 2) | fast-path predicate `(channels === 1 || channels === 2)` |
| Max length / size | Bounded by `/sounds` free space (per-slot + total) — not a hard sample-count in the bundle | `BackupManager` already reads `/sounds` storageUsed/Total via FILE_METADATA; gate import on free space |

### The web app's conversion pipeline (the spec to mirror)
From `uploadSound` (paraphrased from the matched source):

```
if (meta.container === "WAV"
    && meta.format === DEVICE_AUDIO_FORMAT          // "s16"
    && meta.sample_rate === DEVICE_SAMPLE_RATE      // 46875
    && (channels === 1 || channels === 2))
    → use the slice as-is (no conversion)
else
    → decodeAudioData(...) (or AIFF path)
    → resampler.resampleAudioData(pcm, srcRate, 46875)
    → resampler.createWav(name, { channels, sampleRate: 46875, bitDepth: 16, ... })
```

Two takeaways for Android:
1. **Pass-through fast path:** if the picked file is already 16-bit WAV @ 46875 Hz mono/stereo, upload its bytes unchanged — skip decode/resample entirely. (Splice samples are usually 44100 Hz, so most will need conversion, but cheap to check the RIFF `fmt ` chunk first.)
2. **Resample target is fixed at 46875 Hz**, output **16-bit**, channels preserved (mono stays mono, stereo stays stereo — do **not** force-downmix; the device accepts both). Cap the input sample rate at 46875 (`Math.min(src, DEVICE_SAMPLE_RATE)` in the bundle implies it never upsamples — downsample-or-equal only).

---

## Android Conversion Pipeline (Wave 1 — the real work)

### Recommended structure
```
AndroidApp/app/src/main/java/com/ep133/sampletool/
├── domain/audio/
│   ├── AudioDecoder.kt          # NEW: MediaExtractor+MediaCodec → Float/Short PCM + (rate, channels)
│   ├── Resampler.kt             # NEW: pure Kotlin, src → 46875 Hz (linear interp); unit-tested
│   └── WavEncoder.kt            # NEW: pure Kotlin RIFF/PCM-16 writer; unit-tested
├── domain/midi/
│   ├── SampleImportManager.kt   # NEW: orchestrates convert → /sounds PUT (mirror ProjectBackupManager)
│   └── MIDIRepository.kt        # ADD: putSampleFile(name, wavBytes) — paged PUT to /sounds (reuse putProjectArchive plumbing)
└── ui/import/
    ├── SampleImportScreen.kt    # NEW: SAF picker + staged list (mirror DeviceScreen)
    └── SampleImportViewModel.kt # NEW: co-located, mirrors DeviceViewModel
```

### Pattern 1: SAF multi-file pick (no runtime permission)
```kotlin
// Source: developer.android.com/training/data-storage/shared/documents-files [CITED]
val picker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenMultipleDocuments()
) { uris -> viewModel.onFilesPicked(uris) }   // List<Uri>, content:// — read via contentResolver.openInputStream
// launch with audio MIME filter:
picker.launch(arrayOf("audio/*"))
```

### Pattern 2: Decode → resample → 16-bit WAV
```kotlin
// AudioDecoder: MediaExtractor.setDataSource(fd) → select audio track →
//   MediaCodec.createDecoderByType(mime) → drain output buffers → ShortArray PCM + (srcRate, channels)
// Resampler: if srcRate == 46875 return as-is; else linear-interpolate per channel to 46875.
// WavEncoder: RIFF header (s16, 46875, channels) + PCM bytes (little-endian Int16).
suspend fun convertToEp133Wav(uri: Uri): ByteArray = withContext(Dispatchers.Default) {
    val (pcm, srcRate, channels) = AudioDecoder.decode(context, uri)   // ShortArray
    val resampled = Resampler.toRate(pcm, srcRate, 46875, channels)    // skips work if already 46875
    WavEncoder.encode(resampled, sampleRate = 46875, channels = channels, bitDepth = 16)
}
```
**Fast path:** before decoding, sniff the RIFF `fmt ` chunk; if it's already `PCM/16-bit/46875/(1|2)ch`, return the raw bytes and skip the whole pipeline (mirrors the web app's pass-through).

### Pattern 3: Load to `/sounds` reusing the Phase 4 paged PUT
The Phase 4 `putProjectArchive` is a generic paged INIT→DATA PUT against a resolved node ID. **Generalize it** (or add a sibling) that targets a *new* file under `/sounds` rather than an existing slot node:

```kotlin
// Reuses SysExProtocol.buildFilePutInitFrame / buildFilePutDataFrame and the
// INIT-then-paged-DATA loop already shipped in MIDIRepository.putProjectArchive.
// Difference: the target is a NEW file "/sounds/<name>.wav", not an existing /projects/P{NN} node.
suspend fun putSampleFile(name: String, wavBytes: ByteArray): Boolean {
    // Resolve /sounds → nodeId (or use the Phase 2 path-string FILE_PUT to /sounds/<name>,
    // whichever the device accepts — see Landmine 4). Then paged PUT wavBytes.
}
```
`SampleImportManager` mirrors `ProjectBackupManager`: a `Flow<ImportProgress>` emitting `Progress(current,total)` per sample → `Done(name)` / `Error(msg)`, with a connected-device guard and IO-dispatched reads — copy that class's shape verbatim.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Decoding MP3/AAC/FLAC/OGG to PCM | A format-specific decoder | `MediaExtractor` + `MediaCodec` | Platform handles every common Splice export format; hardware-accelerated |
| File selection + URI permission grants | Custom file browser + permission flow | SAF `OpenMultipleDocuments` | No runtime permission, persistable grants, OEM-correct |
| Multi-chunk SysEx framing for the upload | A new chunk loop | **Reuse Phase 4 `putProjectArchive` / `buildFilePut*Frame`** | The paged INIT/DATA protocol is already shipped and unit-tested; only the target path changes |
| 7-bit packing of WAV bytes | New codec | Existing `SysExProtocol.pack7bit`/`unpack7bit` | Correct and tested since Phase 2 |
| Progress/orchestration plumbing | New manager from scratch | Mirror `ProjectBackupManager` (Flow + IO dispatch + device guard) | Identical shape; copy the pattern |

**Don't hand-roll, but DO hand-roll these two** (no good platform/library alternative, and they're tiny): the **WAV/RIFF encoder** (~30 lines, write `RIFF`/`fmt `/`data` chunks, Int16 LE) and the **resampler** (~40 lines, per-channel linear interpolation to 46875). Both are pure functions — unit-test them with synthetic PCM (a sine at a known rate → assert output length and a few sample values). A native/WASM resampler is overkill here.

**Key insight:** This phase is 80% "import any audio file to the EP-133" and 0% "Splice integration." Frame it that way and the work is standard Android audio plumbing plus a re-target of Phase 4's PUT.

---

## Common Pitfalls / Landmines

### Landmine 1: Treating this as a Splice integration at all
**What goes wrong:** Any task that calls the internal Splice GraphQL API (auth, library listing, sample download) ships a **ToS violation** that can get users' paid Splice accounts terminated (§XII.3), and breaks whenever Splice rotates its preflight headers (already happening — `splicerr`'s open issues).
**How to avoid:** No Splice network calls. The user brings their own already-downloaded files; the app imports them. Drop "Splice" from the implementation surface entirely.
**Warning sign:** A task mentions OAuth, GraphQL, a Splice token, or a `splice.com` host.

### Landmine 2: Wrong sample rate (44100 instead of 46875)
**What goes wrong:** Splice samples are typically 44100 Hz. Uploading them unconverted (or resampling to 44100) produces samples the EP-133 plays back **detuned/at the wrong speed**, or rejects.
**Why it happens:** 46875 is an unusual TE-specific rate; easy to default to 44100/48000.
**How to avoid:** Hard-code the target at `46875`; assert it in the WAV encoder. Add a unit test that a 44100 input yields a 46875-rate WAV header.
**Warning sign:** Imported samples sound pitched/slow on the device.

### Landmine 3: Bit depth / endianness in the WAV encoder
**What goes wrong:** `MediaCodec` PCM output is 16-bit but the RIFF `data` chunk must be **little-endian Int16**; a byte-order or float-vs-int slip yields white noise.
**How to avoid:** Encode `ShortArray` → bytes with explicit `ByteOrder.LITTLE_ENDIAN`; unit-test a known PCM array round-trips to the exact expected bytes.
**Warning sign:** Static/noise on playback; device rejects the WAV.

### Landmine 4: `/sounds` PUT target — node ID vs path string (inherited Phase 4 open question)
**What goes wrong:** Phase 4 flagged (Open Q1 / Pitfall 2) that the device's real addressing is **node-ID-based**, while Phase 2 shipped path-string frames for `/sounds`. For a *new* `/sounds/<name>.wav`, the file doesn't exist yet, so node resolution differs from the existing-slot case.
**How to avoid:** Two candidate approaches — (i) Phase 2's path-string `buildFilePutFrame` already wrote to `/sounds/$name` (BackupManager restore does this) — **reuse that path-string PUT for new sample files**, since it demonstrably worked for `/sounds` restore; (ii) if the device requires node-ID for creates, resolve `/sounds` → nodeId and use the paged node PUT. Default to (i) (proven for `/sounds`) and verify on hardware.
**Warning sign:** Upload acknowledges but the sample never appears in `/sounds`.

### Landmine 5: Single-chunk vs paged PUT for large samples
**What goes wrong:** Phase 2's `buildFilePutFrame` is the **simplified single-chunk** model (the same one Phase 4 documented as broken for multi-KB blobs). A multi-second 16-bit 46875 Hz stereo sample is hundreds of KB — single-chunk truncates it.
**How to avoid:** Use the **Phase 4 paged PUT** (`buildFilePutInitFrame` + paged `buildFilePutDataFrame`, the `putProjectArchive` loop) for sample uploads, not the Phase 2 single-chunk PUT — even though Phase 2's path-string targeting (Landmine 4) is the part to reuse. I.e. **path-string target + paged transfer.** Confirm the device accepts a path-string INIT for a new `/sounds` file; if it only accepts node-ID INIT, resolve the node first.
**Warning sign:** Large samples upload partially / are truncated.

### Landmine 6: `/sounds` free-space exhaustion
**What goes wrong:** The EP-133 has limited sample storage; a batch import can overflow it and fail mid-batch, leaving a partial library.
**How to avoid:** Before a batch, read `/sounds` storageUsed/storageTotal (FILE_METADATA — `BackupManager`/`MIDIRepository` already do this for the device stats) and pre-flight the total converted size; warn + stop before overflow.
**Warning sign:** Later samples in a batch fail with a device storage error.

### Landmine 7: SAF URI lifetime
**What goes wrong:** A `content://` URI from the picker is only readable for the current grant; reading it later (after process death, or in a background worker) throws `SecurityException`.
**How to avoid:** Read the bytes (or copy to a staging file in `getExternalFilesDir`) **during the grant**, inside the picker callback's coroutine. Don't persist the URI for deferred reads unless you call `takePersistableUriPermission`.
**Warning sign:** `SecurityException` on a re-opened import.

---

## Code Examples

### WAV (RIFF) encoder — the one piece to hand-write
```kotlin
// Pure, unit-testable. Source: RIFF/WAVE spec; target format from data/index.js (s16, 46875).
fun encodeWav(pcm: ShortArray, sampleRate: Int = 46875, channels: Int = 1): ByteArray {
    val byteRate = sampleRate * channels * 2
    val dataSize = pcm.size * 2
    val buf = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray()); buf.putInt(36 + dataSize); buf.put("WAVE".toByteArray())
    buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1)        // PCM
    buf.putShort(channels.toShort()); buf.putInt(sampleRate); buf.putInt(byteRate)
    buf.putShort((channels * 2).toShort()); buf.putShort(16)             // block align, bits/sample
    buf.put("data".toByteArray()); buf.putInt(dataSize)
    for (s in pcm) buf.putShort(s)
    return buf.array()
}
```

### Reusing the Phase 4 paged PUT shape (to ADD to MIDIRepository)
```kotlin
// Mirrors putProjectArchive (MIDIRepository.kt:525) — same INIT + paged-DATA loop,
// different target file. Sample bytes are 7-bit packed by the frame builder.
suspend fun putSampleFile(name: String, wavBytes: ByteArray): Boolean {
    // 1. INIT for a new /sounds/<name>.wav (path-string per Landmine 4, or resolved nodeId)
    // 2. loop buildFilePutDataFrame(page, chunk) in MAX_PAGE_BYTES slices
    // 3. await STATUS_OK ack (PUT_ACK_TIMEOUT_MS)
    // Copy the body of putProjectArchive almost verbatim.
}
```

---

## State of the Art

| Old framing (CONTEXT) | Current framing (this research) | Why |
|------------------------|----------------------------------|-----|
| "Splice Sample Sync" via an API | "Sample Import" via SAF | No ToS-clean Splice API exists on Android |
| Auth/OAuth to Splice | **No auth** — user brings their own files | The only programmatic Splice surface is ToS-disallowed |
| Splice browser UI | SAF file picker + staged-import list | No library to browse without violating ToS |
| (implied) on-device Splice access | Desktop/Electron is the only real Splice-sync home | Local `~/Splice` folder is desktop-only |

**Deprecated/out:** any plan that talks to `splice.com` programmatically — disqualified by Terms of Use §II.7.a + §II.7.j.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | EP-133 expects 16-bit PCM WAV @ 46875 Hz, mono/stereo | Format | LOW — `DEVICE_SAMPLE_RATE=46875,DEVICE_AUDIO_FORMAT="s16"` is a verbatim bundle match; channel-count predicate matched. Verify one real upload on hardware. |
| A2 | The device never upsamples (caps input at 46875) | Format | LOW — `Math.min(src, DEVICE_SAMPLE_RATE)` in bundle; if a >46875 source appears, downsample to 46875 (always safe). |
| A3 | Path-string FILE_PUT to `/sounds/<name>` works for new files (Phase 2 restore did it) | Landmine 4 | MEDIUM — Phase 4 flagged node-ID addressing; `/sounds` may have worked via firmware convenience. Hardware-verify; node-ID fallback exists. |
| A4 | Paged PUT (Phase 4) is required for multi-KB samples; single-chunk truncates | Landmine 5 | LOW — consistent with Phase 4's documented single-chunk limitation. |
| A5 | `MediaCodec` covers all common Splice export formats (WAV/MP3/AAC/FLAC/OGG) | Stack | LOW — these are standard Android-supported codecs at API 29+. Exotic formats (rare on Splice) would need a fallback. |
| A6 | Splice ToS §II.7.a/j/§XII.3 quotes are current as of 2026-06-20 | Verdict | LOW — quoted verbatim from splice.com/terms this session. ToS can change but only tighten on this axis. |

---

## Open Questions

1. **`/sounds` new-file PUT addressing — path string vs node ID.**
   - Known: Phase 2 BackupManager restore PUT to `/sounds/$name` via path-string frames; Phase 4 says the device's real model is node-ID.
   - Recommendation: try path-string first (proven for `/sounds`), node-ID resolve as fallback; one hardware check.

2. **Does an imported sample need accompanying metadata (name/slot assignment)?**
   - Known: the web app's `createWav`/`uploadSound` set a name and TE meta (`prepareTeenageMeta`). A bare WAV PUT may land in `/sounds` unnamed or unassigned to a pad.
   - Recommendation: ship "land in `/sounds` library" for v1 (user assigns to pads on the device); investigate `prepareTeenageMeta` only if bare uploads don't appear correctly. Surface as a planner decision.

3. **Phase rename.**
   - Recommendation: rename deliverable to "Sample Import" / requirement IDs `SAMPLE-01..0N`; note the desktop Splice-folder watcher as a future phase. Thomas's call.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Android Studio + SDK 35, JDK 17 | Build/test | Assumed (project builds) | per CLAUDE.md | — |
| `MediaCodec`/`MediaExtractor` | Audio decode | ✓ (platform, API 29+) | system | — |
| SAF `OpenMultipleDocuments` | File pick | ✓ (androidx.activity, present) | — | — |
| `android.media.midi` + USB-host Android device | `/sounds` PUT | Unknown (needs hardware) | — | Emulator can't do USB MIDI; conversion unit-tests run without hardware |
| Physical EP-133 | Upload + format validation | Unknown | — | None — final WAV-format correctness needs one hardware upload |

**Missing with no fallback:** physical EP-133 + USB-host Android device for the upload + format-acceptance check. Everything up to the wire (decode, resample, encode, frame building, paged-PUT loop) is unit-testable without hardware.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2 + kotlinx-coroutines-test 1.7.3 |
| Config file | none — standard Android unit test runner |
| Quick run command | `cd AndroidApp && ./gradlew :app:testDebugUnitTest` |
| Full suite command | `cd AndroidApp && ./gradlew :app:testDebugUnitTest :app:lintDebug` |

### Phase Requirements → Test Map (suggested SAMPLE-* IDs)
| Req | Behavior | Test Type | Automated Command | File Exists? |
|-----|----------|-----------|-------------------|-------------|
| SAMPLE-conv | WAV encoder emits correct RIFF header (s16/46875/ch) for known PCM | unit | `./gradlew :app:testDebugUnitTest --tests "*.WavEncoderTest"` | ❌ Wave 0 |
| SAMPLE-conv | Resampler: 44100→46875 length + endpoint samples correct | unit | `./gradlew :app:testDebugUnitTest --tests "*.ResamplerTest"` | ❌ Wave 0 |
| SAMPLE-conv | Pass-through: already-46875/s16 WAV returned byte-identical | unit | `./gradlew :app:testDebugUnitTest --tests "*.WavEncoderTest"` | ❌ Wave 0 |
| SAMPLE-load | `putSampleFile` builds INIT + paged DATA frames for a multi-KB WAV | unit | `./gradlew :app:testDebugUnitTest --tests "*.SampleImportTest"` | ❌ Wave 0 |
| SAMPLE-load | 7-bit pack/unpack round-trips the WAV payload | unit | `./gradlew :app:testDebugUnitTest --tests "*.SysExProtocolTest"` | partial (Phase 2) |
| SAMPLE-ui | ViewModel maps picked URIs → staged list, progress states | unit | `./gradlew :app:testDebugUnitTest --tests "*.SampleImportViewModelTest"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `cd AndroidApp && ./gradlew :app:testDebugUnitTest`
- **Per wave merge:** `cd AndroidApp && ./gradlew :app:testDebugUnitTest :app:lintDebug`
- **Phase gate:** full unit suite green + a manual UAT on a physical EP-133 (import one 44100 Hz file → appears in `/sounds`, plays at correct pitch) before `/gsd:verify-work`.

### Wave 0 Gaps
- [ ] `WavEncoderTest.kt` — RIFF header bytes + pass-through
- [ ] `ResamplerTest.kt` — resample length/values, no-op when already 46875
- [ ] `SampleImportTest.kt` — paged PUT frame building for `/sounds`
- [ ] `SampleImportViewModelTest.kt` — URI → staged-list + progress
- [ ] (decode is hardware/instrumentation-bound; cover `AudioDecoder` with a Robolectric or instrumented test, or treat as manual-only)

**Manual-only (hardware required):** real decode of a Splice WAV/MP3, on-device `/sounds` upload, playback-pitch correctness.

---

## Security Domain

`security_enforcement` not set in config → treated as enabled. Offline, single-user device tool. The new surface is **untrusted file input** (arbitrary audio files the user picks) and a destructive device write.

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | offline; **and explicitly no Splice auth** (the whole point of the verdict) |
| V3 Session Management | no | none |
| V4 Access Control | no | single local user |
| V5 Input Validation | **yes** | Validate decoded audio bounds; cap converted size vs `/sounds` free space; reject zero-length/malformed PCM; sanitize the sample filename written to `/sounds` (no path separators / traversal) |
| V6 Cryptography | no | no secrets; no crypto |

| Threat Pattern | STRIDE | Standard Mitigation |
|----------------|--------|---------------------|
| Malformed/huge audio file OOMs the decoder | DoS | Stream-decode with bounded buffers; cap total PCM size; catch `MediaCodec` exceptions |
| Crafted filename writes outside `/sounds` (traversal) | Tampering | Sanitize to a safe basename + `.wav`; reject `/`, `..`, control chars before PUT |
| Oversized batch overflows device storage mid-write | DoS | Pre-flight total size vs `/sounds` storageTotal−storageUsed; stop before write |
| **Driving the Splice GraphQL API** | Legal/ToS (out-of-band) | **Do not build it** — verdict-level mitigation |

---

## Project Constraints (from CLAUDE.md)

- Kotlin: `val` over `var`; `when` over if/else chains; no `GlobalScope`; `viewModelScope`/`lifecycleScope`; rethrow `CancellationException`.
- Naming: `MIDI`/`USB`/`EP133` casing; `StateFlow`/`SharedFlow` for reactive state, never expose `MutableStateFlow`; underscore-prefixed private backing fields.
- ViewModels co-located with their Screen composable (`SampleImportViewModel` in `SampleImportScreen.kt`).
- `withContext(Dispatchers.IO)` for file reads/writes; `withContext(Dispatchers.Default)` for the CPU-bound convert; `Log.e(TAG, msg, throwable)` always with the throwable. Tags: `EP133MIDI` (MIDI), `EP133APP` (repository).
- Android min API 29; no cross-platform framework; `data/index.js` read-only (reference only).
- GSD workflow: route changes through a GSD command. Commit format `feat(05-...-XX): …`.
- ProGuard `isMinifyEnabled = true` for release — `@Keep` any reflectively-accessed new public API.

---

## Sources

### Primary (HIGH confidence)
- `data/index.js` (compiled EP-133 K.O. II web app) — `DEVICE_SAMPLE_RATE=46875`, `DEVICE_AUDIO_FORMAT="s16"`, `uploadSound` pass-through/convert logic, `resampleAudioData`/`createWav`, channel predicate, `s16→16` bit-depth map.
- `splice.com/terms` — Terms of Use **§II.7.a** (anti reverse-engineering), **§II.7.j** (anti scraping/data-mining), **§XII.3** (account termination), quoted verbatim this session.
- `AndroidApp/.../SysExProtocol.kt`, `MIDIRepository.kt` (`putProjectArchive`, paged PUT builders), `ProjectBackupManager.kt`, `BackupManager.kt` (`/sounds` path-string PUT, FILE_METADATA storage read) — the reuse surface.
- `.planning/phases/04-project-management/04-RESEARCH.md` — paged INIT/DATA protocol, node-ID vs path-string open question, `/sounds` mechanics.

### Secondary (MEDIUM confidence)
- `support.splice.com` — desktop folder locations (`~/Splice` macOS, `C:\Documents\Splice` Windows), "no third-party plug-in drag-and-drop" support note.
- `github.com/Exorsky/splicerr` — confirms the only Splice sample-library surface is an internal GraphQL API ("no authentication required"; Apollo-preflight hardening) — the path we must NOT build.
- `developer.android.com` — SAF `OpenMultipleDocuments`, `MediaCodec`/`MediaExtractor` patterns.

### Tertiary (LOW confidence — context only, not relied on)
- Search hits for unrelated "Splice" products (Canton Network `docs.sync.global`, SPLICE Software insurance `developers.splicesoftware.com`) — confirmed NOT the music platform; listed to show why "Splice API" search results are misleading.

## Metadata

**Confidence breakdown:**
- Feasibility verdict (no ToS-clean Splice API on Android): HIGH — official ToS quoted verbatim + internal-API confirmation via splicerr.
- EP-133 WAV format (s16 / 46875 / mono-stereo): HIGH — verbatim string match in the reference bundle.
- Conversion pipeline (decode/resample/encode on Android): HIGH on approach (platform APIs), MEDIUM on resampler quality choice (linear vs sinc — recommend linear, revisit on complaint).
- Device load reuse (paged `/sounds` PUT): HIGH on the paged transfer (Phase 4 shipped), MEDIUM on path-string-vs-nodeID for a new `/sounds` file (one hardware check).

**Research date:** 2026-06-20
**Valid until:** 2026-07-20 (Splice ToS only tightens on automated access; Android APIs stable; TE firmware format unlikely to change)
