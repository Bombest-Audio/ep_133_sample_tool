package com.ep133.sampletool.domain.export

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Exports a Standard MIDI File (SMF type 1) skeleton for the project.
 *
 * Track 0 carries tempo (120 BPM) and 4/4 time signature; tracks 1..N are one track per
 * pad group A-D. Each assigned pad gets a single marker note at that pad's device MIDI
 * note (group bases A/B/C/D = 36/48/60/72 per docs/ep133-sysex-protocol.md), velocity 100
 * (the EP-133's fixed pad velocity), one beat apart, half a beat long.
 *
 * The skeleton is musically inert on purpose: pattern data is not readable from the
 * hardware, so this file documents the pad-to-note map and gives a DAW a ready-to-record
 * track layout rather than pretending to be a sequence.
 *
 * Param mapping: only the note number applies here; pitch/pan/gain/trim are audio-domain
 * and covered by the audio exports.
 */
class MidiFileExporter : ProjectExporter {

    override val id: String = "midi"

    override fun export(
        manifest: com.ep133.sampletool.domain.backup.ProjectManifest,
        outDir: File,
        baseName: String,
    ): ExportResult {
        val model = buildExportModel(manifest)
        outDir.mkdirs()
        writeExportReadme(outDir, model, "MIDI")

        val mid = File(outDir, "$baseName.mid")
        mid.writeBytes(smfBytes(model))
        return ExportResult(outDir, mid)
    }

    private fun smfBytes(model: ExportModel): ByteArray {
        val tracks = mutableListOf<ByteArray>()
        tracks += tempoTrack(model.manifest.projectName)
        for ((group, pads) in model.groups) {
            tracks += groupTrack(group, pads)
        }
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        out.writeInt32(6)
        out.writeInt16(1) // format 1
        out.writeInt16(tracks.size)
        out.writeInt16(TICKS_PER_QUARTER)
        for (t in tracks) {
            out.write("MTrk".toByteArray(Charsets.US_ASCII))
            out.writeInt32(t.size)
            out.write(t)
        }
        return out.toByteArray()
    }

    private fun tempoTrack(projectName: String): ByteArray {
        val t = ByteArrayOutputStream()
        t.writeVlq(0)
        t.metaEvent(0x03, "EP-133 $projectName".toByteArray(Charsets.UTF_8)) // track name
        t.writeVlq(0)
        // Set tempo: 500000 us per quarter = 120 BPM.
        t.metaEvent(0x51, byteArrayOf(0x07, 0xA1.toByte(), 0x20))
        t.writeVlq(0)
        // 4/4, standard metronome bytes.
        t.metaEvent(0x58, byteArrayOf(4, 2, 24, 8))
        t.endOfTrack()
        return t.toByteArray()
    }

    private fun groupTrack(group: String, pads: List<PadExport>): ByteArray {
        val t = ByteArrayOutputStream()
        t.writeVlq(0)
        t.metaEvent(0x03, "Group $group".toByteArray(Charsets.UTF_8))
        var delta = 0
        for (pad in pads) {
            // Note on (channel 1) after the accumulated gap, off half a beat later.
            t.writeVlq(delta)
            t.write(byteArrayOf(0x90.toByte(), pad.midiNote.toByte(), PAD_VELOCITY))
            t.writeVlq(TICKS_PER_QUARTER / 2)
            t.write(byteArrayOf(0x80.toByte(), pad.midiNote.toByte(), 0))
            delta = TICKS_PER_QUARTER / 2 // remainder of the 1-beat grid slot
        }
        t.endOfTrack()
        return t.toByteArray()
    }

    private fun ByteArrayOutputStream.metaEvent(type: Int, data: ByteArray) {
        write(0xFF)
        write(type)
        writeVlq(data.size)
        write(data)
    }

    private fun ByteArrayOutputStream.endOfTrack() {
        writeVlq(0)
        write(0xFF)
        write(0x2F)
        write(0x00)
    }

    private fun ByteArrayOutputStream.writeInt32(v: Int) {
        write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v)
    }

    private fun ByteArrayOutputStream.writeInt16(v: Int) {
        write(v ushr 8); write(v)
    }

    /** MIDI variable-length quantity. */
    private fun ByteArrayOutputStream.writeVlq(value: Int) {
        var buffer = value and 0x7F
        var v = value ushr 7
        while (v > 0) {
            buffer = (buffer shl 8) or 0x80 or (v and 0x7F)
            v = v ushr 7
        }
        while (true) {
            write(buffer and 0xFF)
            if (buffer and 0x80 != 0) buffer = buffer ushr 8 else break
        }
    }

    private companion object {
        const val TICKS_PER_QUARTER = 480
        /** The EP-133 sends fixed velocity 100 for every pad hit. */
        const val PAD_VELOCITY: Byte = 100
    }
}
