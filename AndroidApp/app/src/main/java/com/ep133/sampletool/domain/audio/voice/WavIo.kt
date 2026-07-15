package com.ep133.sampletool.domain.audio.voice

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded mono float PCM plus its sample rate. */
data class WavData(val samples: FloatArray, val sampleRate: Int) {
    override fun equals(other: Any?): Boolean =
        other is WavData && sampleRate == other.sampleRate && samples.contentEquals(other.samples)

    override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
}

/**
 * Minimal RIFF/WAVE reader for instrument samples - pure JVM, no Android
 * imports. Supports PCM 16-bit, mono or stereo (stereo is downmixed to mono
 * by averaging). That covers standard multisample exports; anything else
 * throws so a bad asset fails loudly instead of playing garbage.
 */
object WavIo {

    fun read(input: InputStream): WavData = read(input.readBytes())

    fun read(bytes: ByteArray): WavData {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 44) { "Not a WAV file: only ${bytes.size} bytes" }
        require(tag(buf, 0) == "RIFF" && tag(buf, 8) == "WAVE") { "Not a RIFF/WAVE file" }

        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var data: ByteArray? = null

        while (pos + 8 <= bytes.size) {
            val chunkId = tag(buf, pos)
            val chunkSize = buf.getInt(pos + 4)
            val body = pos + 8
            when (chunkId) {
                "fmt " -> {
                    val format = buf.getShort(body).toInt()
                    require(format == 1) { "Unsupported WAV format $format (PCM only)" }
                    channels = buf.getShort(body + 2).toInt()
                    sampleRate = buf.getInt(body + 4)
                    bitsPerSample = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    val size = chunkSize.coerceAtMost(bytes.size - body)
                    data = bytes.copyOfRange(body, body + size)
                }
            }
            pos = body + chunkSize + (chunkSize and 1) // chunks are word-aligned
        }

        require(sampleRate > 0 && channels in 1..2) { "Bad fmt chunk (sr=$sampleRate ch=$channels)" }
        require(bitsPerSample == 16) { "Unsupported bit depth $bitsPerSample (16-bit PCM only)" }
        val pcmBytes = requireNotNull(data) { "WAV has no data chunk" }

        val pcm = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = pcm.remaining() / channels
        val out = FloatArray(frames)
        if (channels == 1) {
            for (i in 0 until frames) out[i] = pcm.get(i) / 32768f
        } else {
            for (i in 0 until frames) {
                out[i] = (pcm.get(2 * i) + pcm.get(2 * i + 1)) / 2f / 32768f
            }
        }
        return WavData(out, sampleRate)
    }

    private fun tag(buf: ByteBuffer, offset: Int): String =
        String(ByteArray(4) { buf.get(offset + it) }, Charsets.US_ASCII)
}
