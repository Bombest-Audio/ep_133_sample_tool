package com.ep133.sampletool.spike

import android.util.Log
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import org.json.JSONObject

private const val TAG = "EP133SPIKE"

/**
 * One flattened, fully-decoded node from a [PatternSpikeWalker.walk] pass.
 *
 * @param fileListChildren For a non-dir FILE node, the count returned by a FILE_LIST issued
 *   directly against that node (RESEARCH gap C — expected 0, but TESTED, not assumed). For a
 *   dir node this mirrors the number of children the walk recursed into.
 */
data class NodeDump(
    val nodeId: Int,
    val parentId: Int,
    val flagsRaw: Int,
    val flagsDecoded: String,
    val sizeBytes: Long,
    val name: String,
    val metadataJson: String,
    val fileListChildren: Int,
)

/**
 * Read-only recursive tree walker (Phase 6 pattern-write spike, Plan 02).
 *
 * Composes the Plan 01 primitives ([MIDIRepository.listAllChildren], [MIDIRepository.getNodeInfo],
 * [MIDIRepository.getMetadataJson], [SysExProtocol.decodeFlags]) into a flat, exhaustive
 * [NodeDump] list. Cannot wedge or destroy anything — LIST/INFO/METADATA-GET only, no paged
 * upload or metadata write (those are Plan 03's job).
 *
 * Lives in src/debug — reaches real hardware in debug builds, never ships.
 */
class PatternSpikeWalker(private val repo: MIDIRepository) {

    /** Resolve [path] to a node id, then [walk] it. Returns an empty list if resolution fails. */
    suspend fun walkProjectByPath(path: String): List<NodeDump> {
        val rootId = repo.resolveNodeId(path) ?: run {
            Log.w(TAG, "walkProjectByPath: could not resolve '$path'")
            return emptyList()
        }
        return walk(rootId)
    }

    /**
     * Recursively enumerate every node under [rootNodeId] (all pages, all children), producing
     * a flat depth-first [NodeDump] list. Every device call is defensively guarded: a null
     * NodeInfo, empty children, or a thrown parse degrades to a logged skip and the walk
     * continues (ASVS V5) — it never crashes.
     */
    suspend fun walk(rootNodeId: Int): List<NodeDump> {
        repo.ensureFileSessionInit()
        val out = mutableListOf<NodeDump>()
        walkInto(rootNodeId, out)
        return out
    }

    private suspend fun walkInto(nodeId: Int, out: MutableList<NodeDump>) {
        val children = try {
            repo.listAllChildren(nodeId)
        } catch (e: Exception) {
            Log.w(TAG, "listAllChildren($nodeId) failed — skipping subtree", e)
            emptyList()
        }
        if (children.isEmpty()) return

        for (child in children) {
            val info = try {
                repo.getNodeInfo(child.nodeId)
            } catch (e: Exception) {
                Log.w(TAG, "getNodeInfo(${child.nodeId}) failed — skipping node", e)
                null
            }
            if (info == null) {
                Log.w(TAG, "getNodeInfo(${child.nodeId}) returned null — skipping node")
                continue
            }

            val metadataJson = try {
                repo.getMetadataJson(child.nodeId).toString()
            } catch (e: Exception) {
                Log.w(TAG, "getMetadataJson(${child.nodeId}) failed — recording empty metadata", e)
                "{}"
            }

            if (info.isDir) {
                // Pre-order: record the dir node itself (fileListChildren = its own direct child
                // count, from the FILE_LIST we're about to recurse through), then descend.
                val dirChildren = try {
                    repo.listAllChildren(info.nodeId)
                } catch (e: Exception) {
                    Log.w(TAG, "listAllChildren(${info.nodeId}) failed — recording 0 children", e)
                    emptyList()
                }
                out.add(
                    NodeDump(
                        nodeId = info.nodeId,
                        parentId = info.parentId,
                        flagsRaw = info.flags,
                        flagsDecoded = SysExProtocol.decodeFlags(info.flags),
                        sizeBytes = info.sizeBytes,
                        name = info.name,
                        metadataJson = metadataJson,
                        fileListChildren = dirChildren.size,
                    ),
                )
                walkInto(info.nodeId, out)
            } else {
                // RESEARCH gap C: issue a FILE_LIST directly against this FILE node. Read-only,
                // cannot wedge. Expected-empty, but this makes the absence TESTED evidence.
                val fileListChildren = try {
                    repo.listAllChildren(info.nodeId).size
                } catch (e: Exception) {
                    Log.w(TAG, "gap-C FILE_LIST probe on FILE node ${info.nodeId} failed", e)
                    0
                }
                out.add(
                    NodeDump(
                        nodeId = info.nodeId,
                        parentId = info.parentId,
                        flagsRaw = info.flags,
                        flagsDecoded = SysExProtocol.decodeFlags(info.flags),
                        sizeBytes = info.sizeBytes,
                        name = info.name,
                        metadataJson = metadataJson,
                        fileListChildren = fileListChildren,
                    ),
                )
            }
        }
    }

    // ── Write round-trip (Phase 6 Plan 03) ─────────────────────────────────────────────────
    //
    // GO-path demonstration code only — never invoked unless Plan 04's hardware evidence run
    // surfaces an actual write-candidate node. Prefer [writeRoundTrip] (METADATA SET) always;
    // it is the ONLY path that cannot wedge the device (RESEARCH §The Wedge). Reach for
    // [writeRoundTripFileBytes] only when a candidate node is proven to be binary file bytes,
    // not metadata — and even then it reuses the existing FILE_PUT primitive verbatim.

    /**
     * Wedge-safe, reversible metadata write round-trip (RESEARCH's 8-step safest-write recipe,
     * steps 1-7, metadata variant): save the exact original for [nodeId], apply [mutate] to a
     * COPY, write the change via `METADATA SET` (cannot wedge — `setMetadata` never touches the
     * FILE_PUT paging/terminator path), read back to confirm the change landed, then restore the
     * retained original and confirm the node is byte-identical to how it started.
     *
     * The retained "original" is the exact JSON STRING read back from the device, not a
     * JSONObject reconstructed from it — restoring that string verbatim (rather than
     * re-serializing a JSONObject, which could reorder keys) is what makes the final state
     * byte-identical to the start state.
     *
     * @return true only if BOTH the change-landed assertion (step 4) and the restore-verified
     *   assertion (step 6) pass. Always attempts the restore write even if the change-landed
     *   check fails, so the scratch slot is never left stuck on the mutated value.
     */
    suspend fun writeRoundTrip(nodeId: Int, mutate: (JSONObject) -> Unit): Boolean {
        // 1. Read + retain the exact original as a string — the restore source of truth.
        val originalString = repo.getMetadataJson(nodeId).toString()

        // 2. Apply the smallest reversible change to a COPY re-parsed from the retained string;
        //    `mutate` never sees (and can never accidentally corrupt) the retained original.
        val changed = JSONObject(originalString)
        mutate(changed)
        val changedString = changed.toString()

        // 3. Write the change. setMetadata cannot wedge the device (RESEARCH §The Wedge).
        if (!repo.setMetadata(nodeId, changedString)) {
            Log.w(TAG, "writeRoundTrip($nodeId): METADATA SET (change) did not ack")
            return false
        }

        // 4. Read back and assert the change landed.
        val afterChange = repo.getMetadataJson(nodeId).toString()
        val changeLanded = afterChange == changedString
        if (!changeLanded) {
            Log.w(
                TAG,
                "writeRoundTrip($nodeId): change did not land — after='$afterChange' expected='$changedString'",
            )
        }

        // 5. Restore the retained original — attempted unconditionally so the scratch slot never
        //    ends stuck on the mutated value even if step 4's landing check failed.
        if (!repo.setMetadata(nodeId, originalString)) {
            Log.w(TAG, "writeRoundTrip($nodeId): METADATA SET (restore) did not ack")
            return false
        }

        // 6. Re-read and assert byte-identical restoration.
        val afterRestore = repo.getMetadataJson(nodeId).toString()
        val restored = afterRestore == originalString
        if (!restored) {
            Log.w(
                TAG,
                "writeRoundTrip($nodeId): restore verification failed — after='$afterRestore' original='$originalString'",
            )
        }

        return changeLanded && restored
    }

    /**
     * FILE_PUT fallback (documented, wedge-risk) — reserved for a write target PROVEN to be
     * binary file bytes, never metadata. Prefer [writeRoundTrip] (METADATA SET); it cannot
     * wedge. This fallback exists only because RESEARCH's Write-Candidate table allows for a
     * node that is writable but is NOT metadata (a binary FILE node).
     *
     * Contains NO paging or terminator logic of its own — re-implementing that would reintroduce
     * the wedge (RESEARCH §Anti-Patterns: "Hand-rolling a FILE_PUT"). [MIDIRepository.putSampleFile]
     * is the only existing byte-PUT primitive in the FTC stack; it always creates a file under
     * `/sounds` addressed by [name] (there is no existing "overwrite this nodeId's bytes in
     * place" primitive on the device protocol). [nodeId] identifies the ORIGINAL node whose bytes
     * are read via [MIDIRepository.getFileBytes] for save/restore; the mutated/restore writes
     * are necessarily routed through that same name-addressed `/sounds` upload path. If Plan 04's
     * hardware evidence surfaces a genuinely different binary-FILE write target with its own
     * overwrite primitive, that primitive must be added as new, tested FTC code — never
     * hand-rolled here.
     *
     * Per RESEARCH's 8-step recipe (step 8), follows the mutated PUT with one more trivial
     * successful PUT — via the SAME proven [MIDIRepository.putSampleFile] path — to confirm the
     * device did not wedge, before attempting the restore PUT.
     *
     * @return true only if the mutated PUT, the post-write wedge-check PUT, and the restore PUT
     *   all succeed.
     */
    suspend fun writeRoundTripFileBytes(
        nodeId: Int,
        name: String,
        mutate: (ByteArray) -> ByteArray,
    ): Boolean {
        val original = repo.getFileBytes(nodeId) ?: run {
            Log.w(TAG, "writeRoundTripFileBytes($nodeId): could not read original bytes")
            return false
        }
        val mutated = mutate(original)

        if (repo.putSampleFile(name, mutated) == null) {
            Log.w(TAG, "writeRoundTripFileBytes($nodeId): mutated PUT failed")
            return false
        }

        // Step 8: confirm no wedge with one more trivial successful PUT via the same proven
        // path before attempting the restore write.
        if (repo.putSampleFile("$name-wedge-check", ByteArray(1)) == null) {
            Log.w(
                TAG,
                "writeRoundTripFileBytes($nodeId): post-write wedge-check PUT failed — " +
                    "device may be wedged, STOP and power-cycle per RESEARCH",
            )
            return false
        }

        if (repo.putSampleFile(name, original) == null) {
            Log.w(TAG, "writeRoundTripFileBytes($nodeId): restore PUT failed")
            return false
        }
        return true
    }
}
