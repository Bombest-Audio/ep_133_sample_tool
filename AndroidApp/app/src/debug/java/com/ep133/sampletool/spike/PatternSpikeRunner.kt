package com.ep133.sampletool.spike

import android.util.Log
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol

private const val TAG = "EP133SPIKE"

/**
 * Debug-only trigger that runs [PatternSpikeWalker] against a live, connected [MIDIRepository]
 * and logs the full node dump — the capture mechanism for the Plan 04 hardware evidence run.
 *
 * The log helper prints ONLY node fields (id/parent/flags/size/name/metadata). It never prints
 * any device identifier or host filesystem path (see the project's redaction memory) — the
 * GREET response and any file-picker path are simply never touched here.
 *
 * Lives in src/debug — never ships.
 */
object PatternSpikeRunner {

    /** Run the walker rooted at [scratchProjectPath] (e.g. "/projects/09") and log the dump. */
    suspend fun run(repo: MIDIRepository, scratchProjectPath: String): List<NodeDump> {
        Log.i(TAG, "==== Pattern spike run starting: root='$scratchProjectPath' ====")
        val dump = PatternSpikeWalker(repo).walkProjectByPath(scratchProjectPath)
        logDump(dump)
        return dump
    }

    private fun logDump(dump: List<NodeDump>) {
        if (dump.isEmpty()) {
            Log.w(TAG, "walk returned zero nodes — check the scratch path resolved")
            return
        }
        Log.i(TAG, "%-6s %-6s %-24s %-10s %-16s %s".format("nodeId", "parent", "flags", "size", "name", "metadata"))
        for (n in dump) {
            Log.i(
                TAG,
                "%-6d %-6d %-24s %-10d %-16s %s".format(
                    n.nodeId,
                    n.parentId,
                    "0x%02x(%s)".format(n.flagsRaw, n.flagsDecoded),
                    n.sizeBytes,
                    n.name,
                    n.metadataJson,
                ),
            )
        }

        val writeCandidates = dump.filter { SysExProtocol.isWriteCandidate(it.flagsRaw) }
        Log.i(TAG, "==== ${dump.size} nodes total, ${writeCandidates.size} write-candidate FILE node(s) ====")
        if (writeCandidates.isEmpty()) {
            Log.i(TAG, "No WRITE-flagged non-dir node found in this subtree.")
        } else {
            writeCandidates.forEach { n ->
                Log.i(TAG, "WRITE CANDIDATE: nodeId=${n.nodeId} name='${n.name}' flags=${n.flagsDecoded}")
            }
        }
    }
}
