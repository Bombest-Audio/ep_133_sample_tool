# Phase 5 Sample Import — Open-Questions Research (hardware/protocol)

**Researched:** 2026-06-21
**Domain:** EP-133 `/sounds` upload protocol, WAV format, Android MediaCodec PCM guarantees
**Method:** Reverse-engineered the bundled reference web app `data/index.js` (the original TE EP-133 K.O. II sample tool that this project wraps) and compared against the Kotlin implementation. Corroborated with TE specs and Android docs.
**Headline:** The current Kotlin `putSampleFile` does **not** match the reference tool's upload protocol. The reference is **node-ID-based with a rich INIT frame** (parentId + filename + fileSize + flags + JSON metadata), then paged DATA ending in a zero-length terminator. This is the single highest-priority finding.

**Extends** `05-RESEARCH.md` (do not re-read for the format basics — confirmed here from the same bundle). Resolves the open items in `05-HUMAN-UAT.md`.

---

## TL;DR — what to change

| # | Question | Answer | Confidence | Code impact |
|---|----------|--------|-----------|-------------|
| 1 | UAT-SOUNDS-PUT: new-file addressing | **Node-ID INIT with parentId(/sounds) + filename + fileSize + flags + metadata, then paged DATA + empty terminator.** Path-string framing is NOT what the reference uses. | **HIGH** (verbatim from `data/index.js`) | `MIDIRepository.putSampleFile` + `SysExProtocol` INIT builder need rework — see §1 |
| 2 | Mono vs stereo | **Both accepted; no downmix.** Device declares supported channel counts at runtime. Preserve channels. | **HIGH** | Current code already correct (no change) |
| 3 | WAV format | **RIFF/PCM s16 LE @ 46875 Hz, ≤20 s, src rate 3 kHz–768 kHz, never upsampled.** Header layout correct. | **HIGH** | Add a 20-second / length guard (currently missing) — see §3 |
| 4 | UAT-DECODE: MediaCodec PCM | **NOT guaranteed 16-bit.** FLAC/HD decoders can emit float or 24-bit. Must read `KEY_PCM_ENCODING` from the output format and convert. | **HIGH** | `AudioDecoder` has a real bug — see §4 |
| 5 | UAT-PITCH: 46875 native | **Confirmed native; off-rate plays detuned.** Resample-to-46875 is correct. | **HIGH** | No change |

---

## 1. UAT-SOUNDS-PUT — how the reference tool uploads a new `/sounds` file

### The ground-truth algorithm (from `data/index.js`)

`uploadSound(file)` does this (de-minified):

```
$  = await getNodeIdByPath("/sounds")        // resolve the /sounds DIRECTORY node id
name = file.name
... read meta, resample to 46875/s16, build PCM `ot`, build metadata `nt` ...
fileId = await put(serial, ot, normalizeFileName(name), $ /*parentId*/, 0 /*fileId*/, nt, progress)
await fileHandler.setMetadata(serial, fileId, st)
await fileHandler.init(serial)               // refresh device file table
```
`[VERIFIED: data/index.js — "async uploadSound(o,_,j=0)" … "lt=await this.put(this.device.serial,ot,normalizeFileName(_e),$,j,nt,_)"]`

The inner `fileHandler.put` signature is:

```
put(serial, data, name, parentNodeId, fileId=0, metadata=null, progress=null,
    isDir=false, capabilities=[TE_SYSEX_FILE_CAPABILITY_READ], timeout=null)
```
`[VERIFIED: data/index.js — "async put(a,o,_,j,$=0,_e=null,et=null,tt=!1,rt=[TE_SYSEX_FILE_CAPABILITY_READ],it=null)"]`

For a **new file**, `fileId` is **0** (the `j=0` default flows straight through from `uploadSound`). The device assigns the real fileId and returns it in the INIT response.

The put body:

```
flags = OR(capabilities) | (isDir ? FILE_TYPE_DIR : FILE_TYPE_FILE)   // = READ | FILE = 1|1
chunkSize = deviceChunkSizes.get(serial)                              // negotiated per device
INIT  = new SysExFilePutInitRequest(fileId=0, parentId=$, flags, fileSize=data.length, name, JSON.stringify(metadata))
resp  = sendSysExFileRequest(serial, INIT)        // → SysExFilePutInitResponse.fileId  (assigned id)
maxPayload = calculateMaxPayloadLength(chunkSize - 6, true)
page = 0; offset = 0
while (offset < fileSize) {
    DATA = new SysExFilePutDataRequest(page, data.slice(offset, offset+maxPayload))
    sendSysExFileRequest(serial, DATA)
    offset += maxPayload; page += 1
}
sendSysExFileRequest(serial, new SysExFilePutDataRequest(page, new Uint8Array(0)))   // ZERO-LENGTH terminator
return resp.fileId
```
`[VERIFIED: data/index.js — "const ct=new SysExFilePutInitRequest($,j,ot,at,_,…)…calculateMaxPayloadLength(st-6,!0)…new SysExFilePutDataRequest(lt,new Uint8Array(o.slice(nt,nt+mt)))… const dt=new SysExFilePutDataRequest(lt,new Uint8Array(0));return await this.sendSysExFileRequest(a,dt,it),ut.fileId"]`

### Exact wire frames

The transport calls `sendAndReceiveTeSysexBySerial(serial, TE_SYSEX_FILE /*=5*/, req.asBytes(), …)`. So `TE_SYSEX_FILE (5)` is the subsystem byte; `req.asBytes()` is the file payload that begins with the FILE_PUT op. `[VERIFIED: data/index.js — "this.sysExApi.sendAndReceiveTeSysexBySerial(a,TE_SYSEX_FILE,o.asBytes(),…)"]`

**PUT INIT payload** (`SysExFilePutInitRequest.asBytes()`), big-endian, BEFORE 7-bit packing:

```
offset 0   uint8   TE_SYSEX_FILE_PUT       (2)
offset 1   uint8   TE_SYSEX_FILE_PUT_TYPE_INIT (0)
offset 2   uint8   flags                   (capabilities | FILE_TYPE_FILE)
offset 3-4 uint16  fileId                  (0 for a NEW file)
offset 5-6 uint16  parentId                (the /sounds directory node id)
offset 7-10 uint32 fileSize                (byte length of the WAV/PCM payload)
offset 11.. string filename + NUL          (writeStringToView, null-terminated; filename sliced to 54 chars)
then       string metadata + NUL           (JSON, only if non-null)
```
`[VERIFIED: data/index.js — "class SysExFilePutInitRequest{constructor(a,o,_,j,$,_e=null){this.fileId=a,this.parentId=o,this.flags=_,this.fileSize=j,this.filename=$,this.metadata=_e,this.filename=$.slice(0,54)}asBytes(){…setUint8(0,TE_SYSEX_FILE_PUT),…setUint8(1,TE_SYSEX_FILE_PUT_TYPE_INIT),…setUint8(2,this.flags),…setUint16(3,this.fileId),…setUint16(5,this.parentId),…setUint32(7,this.fileSize),writeStringToView(_,11,this.filename,!0)…"]`

**PUT INIT response:** `fileId = data[0]<<8 | data[1]` — the device's assigned node id for the new file. `[VERIFIED: data/index.js — "class SysExFilePutInitResponse{constructor(a){…this.fileId=a[0]<<8|a[1]}}"]`

**PUT DATA payload** (`SysExFilePutDataRequest.asBytes()`):

```
offset 0   uint8   TE_SYSEX_FILE_PUT       (2)
offset 1   uint8   TE_SYSEX_FILE_PUT_TYPE_DATA (1)
offset 2-3 uint16  page
offset 4.. bytes   chunk data
```
`[VERIFIED: data/index.js — "class SysExFilePutDataRequest{constructor(a,o){this.page=a,this.data=o}asBytes(){…setUint8(0,TE_SYSEX_FILE_PUT),…setUint8(1,TE_SYSEX_FILE_PUT_TYPE_DATA),…setUint16(2,this.page);for(…)o.setUint8(4+_,this.data[_])…"]`

### Does the current Kotlin match? No.

The shipped `putSampleFile` (per the Codex fix note in `05-HUMAN-UAT.md`) uses **path-string framing** — `SysExProtocol.buildFilePutFrame(deviceId, "/sounds/$name", chunk, chunkIndex, requestId)` on every chunk. That builder emits `[TE_SYSEX_FILE, TE_SYSEX_FILE_PUT, <path ASCII bytes>, <chunkIndex u16>, <data>]`. `[VERIFIED: MIDIRepository.kt:582-623 putSampleFile; SysExProtocol.kt:225-243 buildFilePutFrame]`

Three concrete divergences from the reference:

1. **No INIT handshake.** The reference always sends a TYPE_INIT first (announcing parentId + fileSize + filename + metadata) and reads back the assigned fileId. The current path-string code sends only TYPE-less path frames — there is no INIT, so the device never learns the total size, the parent directory, or the filename as a structured field.
2. **Filename is in the wrong place.** The reference carries the filename **inside the INIT frame** (offset 11, null-terminated, ≤54 chars). The current code embeds `/sounds/$name` as a raw path string in every DATA-style frame. The device's protocol model is "create a child named X under parent node $" — not "write to path string."
3. **Wrong DATA header + no terminator.** Reference DATA = `[FILE_PUT, TYPE_DATA, page_u16, data]`. Current DATA = `[FILE_PUT, <path>, chunkIndex_u16, data]` (path instead of TYPE byte; chunkIndex instead of page). And the reference sends a **zero-length DATA page** to terminate; the current code does not.

Note the existing paged builders (`buildFilePutInitFrame`, `buildFilePutDataFrame`, used by `putProjectArchive`) are **closer** but still wrong for samples: `buildFilePutInitFrame` writes `[FILE, FILE_PUT, TYPE_INIT, nodeId_u16, fileSize_u32]` — it omits the **flags byte**, the **parentId**, the **filename**, and the **metadata**, and it reuses `nodeId` where the reference puts `fileId=0`. `[VERIFIED: SysExProtocol.kt:346-354 buildFilePutInitPayload]` `buildFilePutDataFrame` writes a 5-byte header `[FILE, FILE_PUT, TYPE_DATA, page_u16]` — the reference DATA frame has no leading `FILE` byte inside `asBytes()` because `TE_SYSEX_FILE` is supplied as the separate subsystem argument. Whether the Kotlin transport double-prefixes `FILE` is worth a hardware check, but the project layer (`buildFrame`) does not add it, so the extra `TE_SYSEX_FILE` byte the Kotlin builders include is part of the payload — matching how `buildFileSystemFrame` works for the other file ops, so this is internally consistent. The **missing fields in INIT** are the real problem.

### Recommendation (minimal, precise)

Rework the sample upload to mirror the reference. Concretely:

- **`SysExProtocol`**: add a new INIT builder that matches the reference exactly:
  ```
  buildFileCreatePutInitFrame(deviceId, parentNodeId, fileSize, filename, requestId,
                              fileId = 0, flags = CAP_READ or FILE_TYPE_FILE, metadataJson: String? = null)
  payload = [TE_SYSEX_FILE_PUT, TYPE_INIT, flags,
             fileId>>8, fileId&0xFF, parentNodeId>>8, parentNodeId&0xFF,
             fileSize 4 bytes BE, ...filename ASCII bytes, 0x00, ...metadata ASCII bytes, 0x00?]
  ```
  Keep the page-only DATA builder but **drop the leading `TE_SYSEX_FILE` from the payload only if** the hardware check shows double-prefixing (low risk; verify). The existing `buildFilePutDataFrame` page layout `[…, TYPE_DATA, page_u16, data]` already matches once you accept the FILE-subsystem-byte convention. Add the **zero-length final page**.
- **`MIDIRepository.putSampleFile`**: replace the path-string loop with: resolve `/sounds` → parentNodeId (via the existing `resolveNodeId("/sounds")`), send the new INIT, parse the returned assigned fileId from the INIT response, page DATA in `calculateMaxPayloadLength`-sized slices, send the empty terminator, await STATUS_OK. The INIT-response parsing seam already exists conceptually (`SysExProtocol.GetInitResponse` / the PUT ack flow) — add a `PutInitResponse{fileId}` parser (`data[0]<<8|data[1]`).
- **Chunk size:** the reference uses a device-negotiated `chunkSize` (from `SysExFileInitResponse.chunkSize`, set during `fileHandler.init`) and `calculateMaxPayloadLength(chunkSize-6, true)`. The project currently hard-codes `MAX_PAGE_BYTES=4096`. Either query the chunk size at init (preferred, matches reference) or keep a conservative fixed slice and verify on hardware. `calculateMaxPayloadLength(s,true) = let n = s-1-(HEADER+2+FOOTER); return n - floor(n/8)` — the `-n/8` accounts for 7-bit packing overhead. `[VERIFIED: data/index.js — "function calculateMaxPayloadLength(s,a){const o=TE_SYSEX_HEADER_OVERHEAD+2+TE_SYSEX_FOOTER_OVERHEAD;…const _=s-1-o;return _-Math.floor(_/8)}"]`

**Why this matters:** the path-string approach was a guess ("BackupManager restore did it"). But `BackupManager.restore` uses the **same single-chunk path-string `buildFilePutFrame` with `chunkIndex=0`** and a fixed 50 ms delay `[VERIFIED: BackupManager.kt:196-203]` — that path was itself never hardware-confirmed for multi-KB files (Phase 2 documented it as the broken single-chunk model). The reference protocol is the authoritative answer and it is unambiguous: **node-ID create-under-parent with a structured INIT**, not path strings.

> Caveat: I did not capture `TE_SYSEX_HEADER_OVERHEAD`/`TE_SYSEX_FOOTER_OVERHEAD` numeric values or the exact STATUS/ack byte position in the PUT response — the `sendSysExFileRequest` ack handling wasn't fully extracted. The existing `dispatchPagedPutResponse` (STATUS_OK at payload[0]) is the current assumption and should be confirmed against the reference's response parsing on the one hardware run. This is the only LOW-confidence sub-point in §1.

---

## 2. Mono vs stereo `/sounds` files

**Answer: the EP-133 accepts both mono and stereo; do NOT downmix. Preserve the source channel count.**
**Confidence: HIGH.**

Evidence:
- The fast-path predicate accepts 1 or 2 channels unchanged: `tt.container==="WAV" && tt.format===s16 && tt.sample_rate===46875 && (rt===1||rt===2)`. `[VERIFIED: data/index.js — "…&&(rt===1||rt===2))ot=et.slice(Et,ut)"]`
- `decodeAudioData` interleaves and **returns `numberOfChannels` verbatim** — no downmix anywhere. `[VERIFIED: data/index.js — "function decodeAudioData(s,a){…j=new Float32Array(_.length*_.numberOfChannels)…return[j.buffer,_.numberOfChannels]}"]`
- Channel acceptance is actually **device-declared**: `getSupportedFormatFor` filters the device's `soundFormats` table by `channels.includes(o.channels)`, and `soundFormats` is fetched from the device's `/sounds` metadata at runtime (`getMetadata(serial, soundsNode, 10).formats`). `[VERIFIED: data/index.js — "getSupportedFormatFor(o){…this.soundFormats…filter(j=>j.type==='pcm').flatMap(j=>j.formats).find(j=>j.format.includes(DEVICE_AUDIO_FORMAT)&&j.channels.includes(o.channels))}" and "…getNodeIdByPath('/sounds')…getMetadata(serial,o,10)…this.soundFormats=_.formats"]`
- TE's own spec: "46 kHz / 16-Bit with stereo input and output." `[CITED: soundonsound.com/reviews/teenage-engineering-ep-133-ko-ii — via WebSearch 2026-06-21]`

**Code impact: none.** `WavEncoder.encodeWav` and `Resampler.toRate` already preserve channels with no downmix, matching the reference. `[VERIFIED: WavEncoder.kt:58-91; Resampler.kt:52-98]` Leave it.

---

## 3. WAV format the device expects

**Answer: RIFF/PCM, signed 16-bit little-endian, 46875 Hz, mono or stereo, with hard input limits the current code is missing.**
**Confidence: HIGH.**

Confirmed constants: `DEVICE_SAMPLE_RATE=46875, DEVICE_AUDIO_FORMAT="s16"`; `audioFormatAsBitDepth("s16")=16`. `[VERIFIED: data/index.js — "const DEVICE_SAMPLE_RATE=46875,DEVICE_AUDIO_FORMAT=\"s16\"" and "function audioFormatAsBitDepth(s){switch(s){case\"s16\":return 16;case\"s24\":return 24;case\"float\":return 32;…}}"]`

The Kotlin `WavEncoder` header layout (RIFF/WAVE/fmt /data, audioFormat=1 PCM, 16-bit, LE, blockAlign=channels*2, byteRate=rate*channels*2) is byte-correct and matches the standard the device reads. `[VERIFIED: WavEncoder.kt:34-91]`

**Limits the reference enforces that the project does NOT (gap):**
- **Max length 20 seconds.** `if (((tt?.length)??0) > 20) throw "max sample length is 20 seconds"`. `[VERIFIED: data/index.js — "if(((mt=tt==null?void 0:tt.length)??0)>20)throw new UnsupportedAudio(\"max sample length is 20 seconds\")"]`
- **Source sample-rate window 3000–768000 Hz.** `if (tt.sample_rate<3000 || tt.sample_rate>768000) throw "invalid sample rate"`. `[VERIFIED: data/index.js — "if(tt.sample_rate<3e3||tt.sample_rate>768e3)throw new UnsupportedAudio(\"invalid sample rate\")"]`
- **Never upsample.** Target = `Math.min(source_rate, native)` where native comes from the device format table, falling back to 46875. So a <46875 source is preserved at its own rate, not pushed up. `[VERIFIED: data/index.js — "getTargetSampleRate(o){…_e=range[1]||native;return _e?Math.min(o.sample_rate,_e):DEVICE_SAMPLE_RATE}"]`

**Code impact (recommended, minor):**
- `SampleImportManager.convert` (or `AudioDecoder`): after decode, compute duration = `frames / srcRate`; if `> 20.0 s`, fail the row with "max sample length is 20 seconds" before resampling. File: `SampleImportManager.kt` convert path (~line 194-211). Cheap guard, matches reference; prevents oversized uploads the device would reject. `[Evidence: SampleImportManager.kt:194-211]`
- Reject `srcRate < 3000 || srcRate > 768000` with "invalid sample rate".
- Subtle behavioral mismatch worth a decision: the reference targets `min(srcRate, native)` — i.e. a **40000 Hz** source stays **40000 Hz**, NOT resampled to 46875. The current `Resampler.toRate(pcm, srcRate, 46875, ch)` **always forces 46875** unless `srcRate==46875` exactly. `[VERIFIED: Resampler.kt:52-98]` This means the project upsamples 44100→46875, whereas the reference would **keep 44100** (device plays lower rates natively). Upsampling 44100→46875 is harmless audio-wise but wastes ~6% storage and diverges from reference behavior. **Recommendation:** change the target to `min(srcRate, 46875)` to match the reference (only downsample when source > 46875; otherwise keep source rate). This is the more correct behavior and reduces upload size. Low risk; one-line change in the `convert` call site (pass `dstRate = min(srcRate, 46875)`), plus the WAV header must then carry the actual rate, not a hardcoded 46875 — `WavEncoder.encodeWav(resampled, actualRate, ch)`. **Flag for Thomas:** this contradicts the current "hard-lock 46875" guard in `WavEncoder` and UAT-PITCH's assumption. If you keep forcing 46875, pitch is still correct (the header rate matches the data rate) — you just upsample unnecessarily. So this is an optimization, not a correctness fix.

---

## 4. UAT-DECODE — MediaCodec PCM output is NOT guaranteed 16-bit

**Answer: No. On API 29+, audio decoders MAY emit `ENCODING_PCM_FLOAT` (32-bit float) or 24-bit PCM — notably for FLAC and high-resolution sources. The robust path is to read `KEY_PCM_ENCODING` from the decoder's OUTPUT format and convert accordingly. The current `AudioDecoder.bytesToShortArray` assumes 16-bit LE unconditionally and will produce garbage for a float/24-bit decoder.**
**Confidence: HIGH** (Android docs + the code is demonstrably unconditional).

Evidence:
- Android: "Raw audio buffers in float PCM encoding are possible … if KEY_PCM_ENCODING is set to ENCODING_PCM_FLOAT … and confirmed by getOutputFormat for decoders." Each PCM sample is "either a 16-bit signed integer or a float." Float output is available API>24. `[CITED: developer.android.com/reference/android/media/MediaCodec]` `[CITED: developer.android.com/reference/android/media/MediaFormat]`
- FLAC specifically is the common Splice/HD case that bites here — FLAC decoders can deliver >16-bit. `[CITED: github.com/google/ExoPlayer/issues/6749 — "Support MediaCodec audio Float output"]`
- The current decoder reads format fields once and never re-reads encoding after `INFO_OUTPUT_FORMAT_CHANGED`, then blindly does `ByteBuffer…asShortBuffer()`. `[VERIFIED: AudioDecoder.kt:187-194 (format-changed handler is a no-op log) and AudioDecoder.kt:200-204 (bytesToShortArray hardcodes 16-bit LE)]` The doc comment even claims "MediaCodec PCM output is always 16-bit LE at API 29+" — that claim is false for FLAC/HD.

**Code impact (real bug — recommended fix in `AudioDecoder.kt`):**
1. On `INFO_OUTPUT_FORMAT_CHANGED`, read the **output** format's `KEY_PCM_ENCODING` (default to `ENCODING_PCM_16BIT` if the key is absent — that default is correct for MP3/AAC/most WAV):
   ```kotlin
   val outFmt = codec.outputFormat
   val pcmEncoding = if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING))
       outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
   ```
2. Branch `bytesToShortArray` on `pcmEncoding`:
   - `ENCODING_PCM_16BIT` → current path (LE Int16).
   - `ENCODING_PCM_FLOAT` → read LE Float32, clamp to [-1,1], scale to Int16 (`(f*32767).roundToInt().coerceIn(...)`).
   - `ENCODING_PCM_24BIT_PACKED` / `ENCODING_PCM_32BIT` (rarer) → read accordingly and downshift to 16-bit; or, if you want to stay minimal, surface a clear "unsupported PCM encoding" per-row error for those two and only handle 16-bit + float (covers the realistic Splice export set: WAV/MP3/AAC/FLAC/OGG, where the only non-16-bit case in practice is FLAC-as-float).
3. Optionally request 16-bit up front to sidestep the issue on decoders that honor it: `format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)` before `configure`. This is a *request*, not a guarantee — you must still read the output encoding and handle float as a fallback. `[CITED: developer.android.com/reference/android/media/MediaFormat — KEY_PCM_ENCODING must be set before configure; confirm via getOutputFormat]`

Minimum viable fix: handle 16-bit + float (the FLAC case). That removes the only realistic UAT-DECODE failure mode. Add a unit-testable pure function `pcmBytesToShorts(bytes, encoding)` so the float→s16 conversion is JVM-tested even though decode itself stays hardware-bound.

---

## 5. UAT-PITCH — 46875 Hz is the true native rate; off-rate plays detuned

**Answer: Yes. 46875 Hz is the device's native rate. A WAV whose header rate differs from the device's playback assumption plays back pitch/speed-shifted. Resampling to (or correctly tagging) 46875 is the right fix.**
**Confidence: HIGH.**

Evidence:
- `DEVICE_SAMPLE_RATE=46875` is the hardcoded native rate, and `getTargetSampleRate` caps at `min(source, native)` — i.e. the device plays a sample at the rate stamped in its metadata; mismatched rate = wrong pitch. `[VERIFIED: data/index.js — "DEVICE_SAMPLE_RATE=46875" and getTargetSampleRate]`
- TE / press corroboration: the device's native rate is 46875 Hz and "the audio file sample-rate will be preserved if lower than 46875 Hz" — meaning the device honors the file's declared rate, so a wrong declared rate detunes playback. `[CITED: teenage.engineering + soundonsound.com — via WebSearch 2026-06-21]`

**Code impact: none for correctness.** The resampler + the WAV header carrying the same rate keeps pitch correct. `[VERIFIED: Resampler.kt:52-98; WavEncoder.kt:58-91]` (See §3 for the optional `min(srcRate,46875)` optimization — it does not affect pitch as long as the header rate equals the data rate.)

---

## What I did NOT fully resolve (honest gaps)

| Gap | Why it matters | How to close |
|-----|----------------|--------------|
| Exact `TE_SYSEX_HEADER_OVERHEAD` / `TE_SYSEX_FOOTER_OVERHEAD` numeric values | Drives `calculateMaxPayloadLength` page sizing | grep `data/index.js` for `TE_SYSEX_HEADER_OVERHEAD=` / `TE_SYSEX_FOOTER_OVERHEAD=`; or just use the device-negotiated `chunkSize` and the formula |
| PUT response STATUS byte offset / ack semantics in `sendSysExFileRequest` | Confirms `dispatchPagedPutResponse` reads the ack at the right place | extract `sendSysExFileRequest` + `sendAndReceiveTeSysexBySerial` response parsing from the bundle; one hardware capture |
| Whether `setMetadata` after upload is required for the sample to appear/play correctly | Reference calls `setMetadata(serial, fileId, st)` then `init` after every upload | the metadata is also embedded in the INIT frame, so a bare upload may suffice; verify on hardware, add `setMetadata` only if the sample lands but plays with wrong root note/loop |
| Device-negotiated `chunkSize` source (`SysExFileInitResponse.chunkSize`) | The project hardcodes 4096; reference uses the device's value | `[VERIFIED: data/index.js — "this.chunkSize=a[1]<<24|a[2]<<16|a[3]<<8|a[4]" in the INIT response; "this.deviceChunkSizes.set(a,j.chunkSize)"]` — implement `fileHandler.init`-equivalent that reads it |

These four are the residual hardware-verification items. None block writing the corrected INIT/DATA frames — they refine sizing and the post-upload metadata step.

---

## Sources

### Primary (HIGH — ground truth)
- `data/index.js` (bundled EP-133 K.O. II reference web app) — `uploadSound`, `fileHandler.put`, `SysExFilePutInitRequest`/`SysExFilePutInitResponse`/`SysExFilePutDataRequest`, `sendSysExFileRequest`→`sendAndReceiveTeSysexBySerial(serial, TE_SYSEX_FILE, asBytes())`, `calculateMaxPayloadLength`, `getTargetSampleRate`, `getSupportedFormatFor`, `decodeAudioData`, `audioFormatAsBitDepth`, `normalizeFileName`, `prepareTeenageMeta`, the 20 s / 3 kHz–768 kHz guards, `DEVICE_SAMPLE_RATE=46875`/`DEVICE_AUDIO_FORMAT="s16"`.
- Project Kotlin: `SysExProtocol.kt`, `MIDIRepository.kt` (`putSampleFile`, `putProjectArchive`, `resolveNodeId`), `BackupManager.kt` (`restore` path-string single-chunk), `AudioDecoder.kt`, `WavEncoder.kt`, `Resampler.kt`, `SampleImportManager.kt`.

### Secondary (MEDIUM/HIGH — corroboration)
- developer.android.com — MediaCodec / MediaFormat `KEY_PCM_ENCODING`, float output API>24, read from `getOutputFormat`.
- github.com/google/ExoPlayer/issues/6749 — FLAC/float MediaCodec output.
- teenage.engineering + soundonsound.com — 46875 Hz native, 46 kHz/16-bit stereo I/O, lower rates preserved.

### Prior work extended
- `05-RESEARCH.md` (format basics, FILE_PUT reuse, landmines), `05-HUMAN-UAT.md` (the four UAT items resolved above), `05-CONTEXT.md` (locked decisions).

---

## Metadata
**Confidence breakdown:** §1 HIGH on the protocol shape (verbatim bundle), LOW only on the exact ack-byte offset. §2 HIGH. §3 HIGH. §4 HIGH. §5 HIGH.
**Research date:** 2026-06-21
**Valid until:** stable — TE firmware format and the bundled reference don't change; Android MediaCodec behavior stable.
