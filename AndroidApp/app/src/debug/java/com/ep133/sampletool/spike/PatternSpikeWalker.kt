package com.ep133.sampletool.spike

import android.util.Log
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol

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
}
