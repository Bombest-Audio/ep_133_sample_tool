package com.ep133.sampletool.domain.midi

import android.util.Log
import com.ep133.sampletool.domain.midi.MIDIRepository.MidiEvent
import java.io.ByteArrayOutputStream

/**
 * Pure USB-MIDI byte-stream framer. Accumulates raw incoming bytes into complete SysEx and channel
 * messages, then hands each assembled frame to a callback. It owns only the framing buffers; what to
 * *do* with a message (route a SysEx response, emit a note event) stays with the caller.
 *
 * Extracted from [MIDIRepository] so the framing rules are unit-testable in isolation and the
 * repository is no longer both the parser and the router. Behavior matches the original
 * byte-for-byte:
 *
 * - 0xF0 starts SysEx accumulation
 * - 0xF7 ends SysEx and emits the complete message via [onSysEx]
 * - all other bytes while in SysEx are buffered
 * - non-SysEx bytes flow through channel-message assembly and emit via [onChannelMessage]
 *
 * Not thread-safe: the buffers assume single-threaded delivery, exactly as the MIDIManager callback
 * delivers input.
 */
class MidiByteStreamParser(
    private val onSysEx: (ByteArray) -> Unit,
    private val onChannelMessage: (MidiEvent) -> Unit,
) {
    private val sysExBuffer = ByteArrayOutputStream(512)
    private var inSysEx = false
    private val channelBuffer = ByteArrayOutputStream(3)

    /** Feed a chunk of raw MIDI bytes; emits whatever complete messages they complete. */
    fun parse(data: ByteArray) {
        for (b in data) {
            val byte = b.toInt() and 0xFF
            when {
                byte == 0xF0 -> {
                    sysExBuffer.reset()
                    sysExBuffer.write(b.toInt())
                    inSysEx = true
                }
                inSysEx && byte == 0xF7 -> {
                    sysExBuffer.write(b.toInt())
                    inSysEx = false
                    val complete = sysExBuffer.toByteArray()
                    sysExBuffer.reset()
                    onSysEx(complete)
                }
                inSysEx -> sysExBuffer.write(b.toInt())
                else -> parseChannelMessageByte(b)
            }
        }
    }

    /**
     * Accumulate channel message bytes (status + data bytes) and emit complete messages.
     *
     * Status bytes (high bit set) reset the buffer. Channel messages are typically 2-3 bytes.
     */
    private fun parseChannelMessageByte(b: Byte) {
        val byte = b.toInt() and 0xFF
        if (byte and 0x80 != 0) {
            // New status byte — flush pending partial message and start fresh
            channelBuffer.reset()
            channelBuffer.write(b.toInt())
        } else {
            channelBuffer.write(b.toInt())
        }

        val bytes = channelBuffer.toByteArray()
        if (bytes.isEmpty()) return
        val status = bytes[0].toInt() and 0xFF
        val type = status and 0xF0

        // Dispatch complete 3-byte messages (Note On/Off, CC, Pitch Bend)
        // Dispatch complete 2-byte messages (PC, Channel Pressure)
        val expectedLen = when (type) {
            0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 3
            0xC0, 0xD0 -> 2
            else -> return
        }

        if (bytes.size >= expectedLen) {
            val ch = status and 0x0F
            val note = bytes.getOrNull(1)?.toInt()?.and(0x7F) ?: 0
            val velocity = bytes.getOrNull(2)?.toInt()?.and(0x7F) ?: 0
            Log.d("EP133APP", "MIDI IN: type=0x${type.toString(16)} ch=$ch note=$note vel=$velocity")
            if (type == 0x90 || type == 0x80 || type == 0xC0) {
                onChannelMessage(MidiEvent(type, note, velocity, ch))
            }
            channelBuffer.reset()
        }
    }
}
