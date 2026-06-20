# Phase 05 Sample Import (Android) — Human UAT (physical EP-133 required)

These checks need a physical EP-133 (or EP-1320) connected over USB to an Android device
with USB host, plus on-device MediaCodec. No emulator can do USB MIDI, and pure-JVM unit
tests cannot exercise MediaCodec decode. Each item lists the assumption the code ships
against, exact verification steps, and the fallback if the assumption is wrong.

**Status legend:** ☐ not verified · ✅ verified · ⚠️ failed (apply fallback)

> Seeded at planning time; entries are filled/confirmed by the Wave 2 (05-03) and Wave 3
> (05-04) checkpoints. Format mirrors 04-HUMAN-UAT.md.

---

## UAT-DECODE — Real MediaCodec decode of a WAV/MP3 (SAMPLE-02, RESEARCH A5)

**Assumption shipped:** `AudioDecoder.decode(context, uri)` decodes any common Splice export
format (WAV/MP3/AAC/FLAC/OGG) to 16-bit PCM via `MediaExtractor` + `MediaCodec.createDecoderByType`,
returning `(ShortArray, srcRate, channels)`. Marked `// HARDWARE/INSTRUMENTATION-VERIFY (UAT-DECODE)`
in `domain/audio/AudioDecoder.kt`. Pure-JVM tests cover the conversion math (Resampler/WavEncoder)
but not the decode itself.

**Steps:**
1. Open the Import tab, pick a real 44.1 kHz WAV and an MP3.
2. Confirm both decode + convert without error (each staged row leaves CONVERTING without an ERROR state).

**Fallback if it fails (decode throws / unsupported format):** confirm the picked MIME is one
MediaCodec supports at API 29+. For an exotic format, surface a clear per-row "unsupported format"
error; a bundled decoder (FFmpeg) is explicitly out of scope (RESEARCH "Alternatives Considered").

**Status:** ☐ not verified

---

## UAT-SOUNDS-PUT — New-file /sounds upload addressing: path string vs node ID (SAMPLE-03, RESEARCH Open Q1 / Landmine 4)

**Assumption shipped:** `MIDIRepository.putSampleFile(name, wavBytes)` uploads a NEW
`/sounds/<name>.wav` via the Phase 4 paged INIT + paged-DATA loop (mirrors `putProjectArchive`),
resolving `/sounds` and issuing a node-targeted INIT for the new file. Marked
`// HARDWARE-VERIFY (Open Q1 / Landmine 4)` in `MIDIRepository.putSampleFile`. Paged transfer
(not Phase 2 single-chunk) is required for multi-KB samples (Landmine 5, unit-guarded by
`SampleImportTest`).

**Steps:**
1. Connect a real EP-133. Import one converted sample.
2. Confirm it appears in `/sounds` and is assignable to a pad.

**Fallback if it fails (upload acks but the sample never appears):** the firmware likely requires
the Phase 2 path-string framing for a non-existent `/sounds` file. One-line switch documented in
`putSampleFile`: replace the node-ID INIT with `SysExProtocol.buildFilePutFrame(currentDeviceId,
"/sounds/$name", chunk, chunkIndex, requestId)` path-string framing while keeping the paged DATA loop
(BackupManager restore proved path-string `/sounds` writes work).

**Status:** ☐ not verified

---

## UAT-PITCH — Playback-pitch correctness of the 46875 Hz conversion (SAMPLE-02)

**Assumption shipped:** resampling to exactly 46875 Hz / s16 (the EP-133's rate) plays back at
the correct pitch and length. A 44100 source is resampled up to 46875 by `Resampler.toRate`; the
encoder hard-locks 46875 (`WavEncoder.encodeWav` require()s it — Landmine 2 guard).

**Steps:**
1. Import a 44.1 kHz sample of known pitch/length.
2. Play it on the device; confirm correct pitch and duration (no detune/speed artifact).

**Fallback if it fails (detuned/slow/fast):** verify the WAV header carries 46875 (not 44100) and
that the resampler ran (no pass-through on a non-46875 source). If a quality artifact (not a rate
error) is audible, the linear interpolator can be upgraded to sinc (RESEARCH "Alternatives") —
revisit only on complaint.

**Status:** ☐ not verified

---

## UAT-IMPORT-UI — End-to-end import through the UI (SAMPLE-01/04, Wave 3)

**Assumption shipped (Wave 3):** the Import tab renders a pick-files action launching the SAF
`OpenMultipleDocuments` picker (audio/* filter), a staged row per picked file with per-file state
(pending/converting/loading/done/error) and progress, and a clear success/failure result per
sample plus a batch snackbar. All UI state is unit-asserted (`SampleImportViewModelTest` GREEN);
the live picker + decode + on-device upload below need hardware + a real `Context`. This subsumes
UAT-DECODE / UAT-SOUNDS-PUT / UAT-PITCH end-to-end through the UI.

**Steps:**
1. Open the Import tab → tap pick → select a 44.1 kHz WAV and an MP3.
2. Confirm one staged row per file, per-file progress advances, and each row ends DONE (sample lands
   in `/sounds` and on a pad) or ERROR with a readable message.
3. With the device disconnected, confirm picking still works (conversion is offline) and upload rows
   surface a "No EP-133 connected" error rather than crashing.

**Fallback if it fails:** for picker/permission issues, confirm the launcher is `OpenMultipleDocuments`
registered before `setContent` and launched with `arrayOf("audio/*")`. For upload/decode failures,
apply the UAT-DECODE / UAT-SOUNDS-PUT fallbacks above.

**Status:** ☐ not verified
