package com.ep133.sampletool.domain.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RIFF/PCM-16 WAV encoder hard-locked to the EP-133's native format.
 *
 * The EP-133 K.O. II expects samples in /sounds at exactly:
 *   - Sample rate: 46875 Hz  (DEVICE_SAMPLE_RATE)
 *   - Bit depth: 16-bit signed integer, little-endian (DEVICE_BIT_DEPTH)
 *   - Channels: 1 (mono) or 2 (stereo) — passed through unchanged; no downmix
 *
 * RIFF byte layout (all fields little-endian):
 *   offset  0  "RIFF"         (4-CC)
 *   offset  4  chunkSize      (36 + dataSize, LE u32)
 *   offset  8  "WAVE"         (4-CC)
 *   offset 12  "fmt "         (4-CC)
 *   offset 16  subchunk1Size  (16, LE u32 — fixed for PCM)
 *   offset 20  audioFormat    (1 = PCM, LE u16)
 *   offset 22  channels       (LE u16)
 *   offset 24  sampleRate     (LE u32)
 *   offset 28  byteRate       (sampleRate * channels * 2, LE u32)
 *   offset 32  blockAlign     (channels * 2, LE u16)
 *   offset 34  bitsPerSample  (16, LE u16)
 *   offset 36  "data"         (4-CC)
 *   offset 40  dataSize       (pcm.size * 2, LE u32)
 *   offset 44  Int16 LE samples (interleaved for stereo)
 *
 * No Android imports — pure JVM; fully unit-testable on the JVM test runner.
 *
 * SAMPLE-02: pure encoding half. The device-bound decode half lands in Wave 2.
 */
object WavEncoder {

    /** The EP-133 K.O. II native sample rate. */
    private const val DEVICE_SAMPLE_RATE = 46875

    /** The EP-133 K.O. II native bit depth. */
    private const val DEVICE_BIT_DEPTH = 16

    /**
     * Encode [pcm] samples as a RIFF/PCM-16 WAV at the given [sampleRate] and [channels].
     *
     * In production use [sampleRate] is always [DEVICE_SAMPLE_RATE] (46875 Hz). The
     * parameter is exposed so callers can build test fixtures at other rates for use
     * with [isAlreadyDeviceFormat] (e.g. creating a 44100 Hz WAV to confirm it is
     * rejected by the pass-through sniffer — see WavEncoderTest C).
     *
     * Channel count is preserved as-is (mono → mono, stereo → stereo). No downmix
     * is performed (RESEARCH A1 — the device accepts both channel counts).
     *
     * @param pcm         Raw signed 16-bit PCM samples, interleaved for stereo.
     * @param sampleRate  Sample rate to embed in the header (default 46875).
     * @param channels    Number of audio channels: 1 (mono) or 2 (stereo).
     * @return            Complete RIFF WAV bytes including the 44-byte header.
     */
    fun encodeWav(pcm: ShortArray, sampleRate: Int = DEVICE_SAMPLE_RATE, channels: Int = 1): ByteArray {
        val dataSize = pcm.size * 2              // 2 bytes per Int16 sample
        val chunkSize = 36 + dataSize            // everything after the 8-byte RIFF descriptor
        val byteRate = sampleRate * channels * 2 // bytes per second
        val blockAlign = channels * 2            // bytes per sample frame

        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))  // offset 0
        buf.putInt(chunkSize)                            // offset 4
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))  // offset 8

        // fmt sub-chunk
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))  // offset 12
        buf.putInt(16)                                   // offset 16: subchunk1Size (fixed PCM)
        buf.putShort(1)                                  // offset 20: audioFormat = 1 (PCM)
        buf.putShort(channels.toShort())                 // offset 22: channels
        buf.putInt(sampleRate)                           // offset 24: sampleRate
        buf.putInt(byteRate)                             // offset 28: byteRate
        buf.putShort(blockAlign.toShort())               // offset 32: blockAlign
        buf.putShort(DEVICE_BIT_DEPTH.toShort())         // offset 34: bitsPerSample = 16

        // data sub-chunk
        buf.put("data".toByteArray(Charsets.US_ASCII))  // offset 36
        buf.putInt(dataSize)                             // offset 40: dataSize

        // PCM samples — Int16 LE, interleaved for stereo
        for (sample in pcm) {
            buf.putShort(sample)                         // offset 44+
        }

        return buf.array()
    }

    /**
     * Encode raw interleaved s16 LE PCM BYTES as a RIFF/PCM-16 WAV.
     *
     * Byte-level counterpart to [encodeWav] for callers whose pipeline already holds
     * little-endian PCM bytes (ConvertedSample.pcm) - avoids a bytes -> shorts -> bytes
     * round trip. Any trailing incomplete sample (odd byte count) is dropped.
     *
     * @param pcm         Interleaved s16 LE PCM bytes (no header), as produced by the
     *                    convert pipeline.
     * @param sampleRate  Sample rate to embed in the header (default 46875).
     * @param channels    Number of audio channels: 1 (mono) or 2 (stereo).
     * @return            Complete RIFF WAV bytes including the 44-byte header.
     */
    fun encodeWavBytes(pcm: ByteArray, sampleRate: Int = DEVICE_SAMPLE_RATE, channels: Int = 1): ByteArray {
        val dataSize = pcm.size and 0x7FFFFFFE          // whole s16 samples only
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * 2)
        buf.putShort((channels * 2).toShort())
        buf.putShort(DEVICE_BIT_DEPTH.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        buf.put(pcm, 0, dataSize)

        return buf.array()
    }

    /**
     * Returns `true` iff [wavBytes] is already in the EP-133's required format:
     *   - Valid RIFF/WAVE container
     *   - audioFormat == 1 (PCM, uncompressed)
     *   - bitsPerSample == 16
     *   - sampleRate == 46875 Hz  (Landmine 2 guard)
     *   - channels == 1 or 2
     *
     * Parsing is defensive (T-05-02-01 threat): any buffer shorter than 44 bytes,
     * with wrong RIFF/WAVE magic, or whose fmt chunk fields cannot be read safely
     * returns `false` rather than throwing. This is safe to call on untrusted file
     * bytes arriving from the Android Storage Access Framework.
     *
     * Fast-path: callers can skip re-encoding if this returns `true` (Pattern 2).
     */
    fun isAlreadyDeviceFormat(wavBytes: ByteArray): Boolean {
        // Need at least a complete 44-byte standard WAV header
        if (wavBytes.size < 44) return false

        return try {
            val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Verify RIFF magic (offset 0..3)
            val riff = ByteArray(4).also { buf.get(it) }
            if (!riff.contentEquals("RIFF".toByteArray(Charsets.US_ASCII))) return false

            // Skip chunkSize (4 bytes) — we don't validate it here
            buf.getInt()

            // Verify WAVE magic (offset 8..11)
            val wave = ByteArray(4).also { buf.get(it) }
            if (!wave.contentEquals("WAVE".toByteArray(Charsets.US_ASCII))) return false

            // Verify fmt  magic (offset 12..15)
            val fmt = ByteArray(4).also { buf.get(it) }
            if (!fmt.contentEquals("fmt ".toByteArray(Charsets.US_ASCII))) return false

            // Skip subchunk1Size (4 bytes, offset 16..19)
            buf.getInt()

            // Read fmt fields
            val audioFormat = buf.short.toInt() and 0xFFFF   // offset 20: expect 1 (PCM)
            val channels = buf.short.toInt() and 0xFFFF      // offset 22: 1 or 2
            val sampleRate = buf.int                          // offset 24: expect 46875
            // byteRate and blockAlign not checked (derived values)
            buf.getInt()    // byteRate  (offset 28)
            buf.getShort()  // blockAlign (offset 32)
            val bitsPerSample = buf.short.toInt() and 0xFFFF // offset 34: expect 16

            audioFormat == 1 &&
                bitsPerSample == DEVICE_BIT_DEPTH &&
                sampleRate == DEVICE_SAMPLE_RATE &&
                channels in 1..2
        } catch (_: Exception) {
            // Any buffer underflow or parsing error → not a valid device-format file
            false
        }
    }
}
