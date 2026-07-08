package com.ep133.sampletool.domain.midi

import android.util.Log
import com.ep133.sampletool.domain.model.PadChannel
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pad-assignment layer extracted from [MIDIRepository] (999.5 Plan 02).
 *
 * Owns the active-group sync and pad-binding operations: [getActiveGroupIndex],
 * [setActiveGroup], [assignSampleToPad], [clearPad], [clearProject], and
 * [readGroupPadState], plus their NoLock helpers. Layered on [FileTransferClient] (D-02):
 * every multi-step device walk here runs inside a single [FileTransferClient.withFileOpLock]
 * acquisition and calls only FTC's NoLock primitives (never FTC's own locking wrappers) to
 * avoid re-entering the non-reentrant [kotlinx.coroutines.sync.Mutex] FTC owns.
 *
 * [MIDIRepository] constructs `FTC(port)` then `PadAssignmentService(ftc)` and delegates its
 * public pad-assignment methods here unchanged (signatures byte-identical).
 */
open class PadAssignmentService(private val ftc: FileTransferClient) {

    /**
     * Guard for the active-group poll: set to true while a [getActiveGroupIndex] call is
     * running. Checked BEFORE acquiring FTC's file-op mutex so that a queued poll (suspended on
     * the mutex) does not accumulate behind a running poll — the second call returns null
     * immediately rather than stacking up.
     *
     * This is an AtomicBoolean so it can be read from any thread without synchronisation.
     * FTC's mutex still serialises the actual op body; this flag is purely an entry guard.
     */
    private val activeGroupPollInFlight = AtomicBoolean(false)

    // ── Active-group sync: nodeId-form metadata round-trips (Step 1) ─────────────
    //
    // These implement the reference tool's group-select mechanism:
    //   getActiveGroupIndex: /projects→active→projName→/projects/<p>/groups→active→getNode→name
    //   setActiveGroup:      resolve group nodeId, SET groups-dir {active:<nodeId>}
    //
    // Both functions are serialised via FTC's file-op mutex (withFileOpLock) so they cannot
    // interleave with putSampleFile or the active-group poll. All internal helpers
    // (ftc.resolveNodeIdInternal, ftc.getMetadataJson, ftc.getNodeInfo, ftc.setMetadata) are
    // called on FTC without acquiring the mutex again — they are the NoLock-safe primitives.

    /**
     * Read the device's current active group and return its index (0=A, 1=B, 2=C, 3=D).
     *
     * Fast path (after first call): the heavy directory-walk is cached so subsequent poll ticks
     * issue only two METADATA GET round-trips (projects-node → active-project, groups-node →
     * active-group). The one-time resolution of each cache entry is logged verbosely.
     *
     * Guard: if a poll is already in flight ([activeGroupPollInFlight]), the new call returns
     * null immediately rather than queuing behind the mutex. This prevents 1.5 s poll ticks from
     * accumulating into a backlog when the device is slow.
     *
     * Cache invalidation: both caches are cleared on device greet (new connection).
     *
     * Full walk:
     *   1. METADATA GET on projectsNode → "active" = activeProjNodeId  (fast, cached node)
     *   2. If groupsNodeCache[activeProjNodeId] miss: resolveNodeIdInternal to find groups dir;
     *      log all children to confirm structure (HW-VERIFY-2).
     *   3. METADATA GET on groupsNode → "active" = activeGroupNodeId  (fast, cached node)
     *   4. If groupNodeNameCache[groupsNode] miss: list groupsNode children; log name→nodeId
     *      map (HW-VERIFY-2); cache it.
     *   5. Look up activeGroupNodeId in name map; find PadChannel index by name.
     *
     * @return Group index 0–3, or null if the device is disconnected / no active project.
     */
    open suspend fun getActiveGroupIndex(): Int? {
        Log.d("EP133APP", "MIDI META: getActiveGroupIndex() called")
        // Poll guard: return immediately if a poll is already running. This prevents a
        // 1.5 s tick from stacking up behind the mutex while a previous tick's FILE_LIST
        // is still in flight — the root cause of the poll-backlog timeout loop.
        if (!activeGroupPollInFlight.compareAndSet(false, true)) {
            Log.d("EP133APP", "MIDI META: getActiveGroupIndex — poll already in flight, skipping")
            return null
        }
        return try {
            ftc.withFileOpLock {
                ftc.ensureFileSessionInitNoLock()
                try {
                    getActiveGroupIndexNoLock()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("EP133APP", "MIDI META: getActiveGroupIndex failed", e)
                    null
                }
            }
        } finally {
            activeGroupPollInFlight.set(false)
        }
    }

    /**
     * NoLock body for [getActiveGroupIndex].
     * MUST only be called from within an [FileTransferClient.withFileOpLock] context.
     */
    private suspend fun getActiveGroupIndexNoLock(): Int? {
        // Step 1: resolve the /projects node once (cached implicitly by resolveNodeIdInternal
        // finding it by name from root). We need the node ID so we can METADATA GET it.
        // Use the fast METADATA GET path — no directory list needed here.
        val projectsNode = ftc.resolveNodeIdInternal("/projects") ?: return null

        // Step 2: METADATA GET on projects node → get active-project nodeId (fast, 1 round-trip).
        val activeProjNodeId = ftc.getMetadataJson(projectsNode).optInt("active", -1)
            .takeIf { it >= 0 } ?: return null
        Log.d("EP133APP", "MIDI META: active project nodeId=$activeProjNodeId")

        // Step 3: resolve groups dir — cache hit avoids directory walk after first call.
        val groupsNode = ftc.groupsNodeCache.getOrPut(activeProjNodeId) {
            // One-time: need the project name to build the path, so do FILE_INFO on activeProjNodeId.
            val projName = ftc.getNodeInfo(activeProjNodeId)?.name ?: run {
                Log.w("EP133APP", "MIDI META: could not get project name for nodeId=$activeProjNodeId")
                return null
            }
            Log.d("EP133APP", "MIDI META: active project name='$projName' (one-time resolution)")
            val gn = ftc.resolveNodeIdInternal("/projects/$projName/groups") ?: run {
                Log.w("EP133APP", "MIDI META: /projects/$projName/groups not found — device may use different structure")
                return null
            }
            Log.d("EP133APP", "MIDI META: resolved groups dir nodeId=$gn for project='$projName' (cached)")
            gn
        }

        // Step 4: METADATA GET on groups dir → get active-group nodeId (fast, 1 round-trip).
        val activeGroupNodeId = ftc.getMetadataJson(groupsNode).optInt("active", -1)
            .takeIf { it >= 0 } ?: return null
        Log.d("EP133APP", "MIDI META: active group nodeId=$activeGroupNodeId")

        // Step 5: resolve group name — cache hit avoids directory walk after first call.
        val nameMap = ftc.groupNodeNameCache.getOrPut(groupsNode) {
            // One-time: list groups dir children to build nodeId→name map.
            val body = ftc.listNodeBody(groupsNode, requestId = ftc.nextRequestId()) ?: run {
                Log.w("EP133APP", "MIDI META: could not list groups dir nodeId=$groupsNode")
                return null
            }
            val entries = SysExProtocol.parseFileListEntries(body)
            // Log the actual structure — HW-VERIFY-2 confirms group names.
            Log.d("EP133APP", "MIDI META: groups dir children (one-time resolution): ${entries.map { "${it.nodeId}='${it.name}'" }}")
            if (entries.isEmpty()) {
                Log.w("EP133APP", "MIDI META: groups dir nodeId=$groupsNode has no children — device structure unexpected")
                return null
            }
            entries.associate { it.nodeId to it.name }
        }

        val groupName = nameMap[activeGroupNodeId] ?: run {
            Log.w("EP133APP", "MIDI META: active group nodeId=$activeGroupNodeId not in groups dir map=$nameMap")
            return null
        }
        Log.d("EP133APP", "MIDI META: active group name='$groupName' nodeId=$activeGroupNodeId")
        val idx = PadChannel.entries.indexOfFirst { it.name == groupName }
        return idx.takeIf { it >= 0 }
    }

    /**
     * Set the device's active group to [index] (0=A, 1=B, 2=C, 3=D).
     *
     * Resolves: /projects → active-project name → /projects/<name>/groups/<letter> (target nodeId)
     * → /projects/<name>/groups (groups-dir nodeId) → SET groups-dir `{active:<targetNodeId>}`.
     *
     * @return true if the SET ack was received, false on error or timeout.
     */
    open suspend fun setActiveGroup(index: Int): Boolean {
        val channel = PadChannel.entries.getOrNull(index) ?: return false
        return ftc.withFileOpLock {
            try {
                val projectsNode = ftc.resolveNodeIdInternal("/projects") ?: return@withFileOpLock false
                val activeProjNodeId = ftc.getMetadataJson(projectsNode).optInt("active", -1)
                    .takeIf { it >= 0 } ?: return@withFileOpLock false
                val projName = ftc.getNodeInfo(activeProjNodeId)?.name ?: return@withFileOpLock false
                val groupNode = ftc.resolveNodeIdInternal("/projects/$projName/groups/${channel.name}") ?: return@withFileOpLock false
                val groupsNode = ftc.resolveNodeIdInternal("/projects/$projName/groups") ?: return@withFileOpLock false
                ftc.setMetadata(groupsNode, """{"active":$groupNode}""")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EP133APP", "MIDI META: setActiveGroup($index) failed", e)
                false
            }
        }
    }

    // ── Pad-assignment (hardware-verified 2026-06-29) ─────────────────────────────
    //
    // Each pad in the active project is a FILE node at:
    //   active-project → groups/<letter> → "<gridIndex+1 zero-padded>"
    // A pad's metadata JSON IS its sound binding: writing the verified JSON below
    // with sym=<sampleNodeId> makes the device play that sample when the pad is hit.

    /**
     * Bind a sample to a pad on the active project via a single METADATA SET.
     *
     * Pad node resolution (hardware-verified 2026-06-29):
     *   /projects → active-project node → FILE_INFO → project name →
     *   /projects/<name>/groups/<group.name> → list children → pick entry
     *   named "%02d".format(gridIndex + 1)
     *
     * Writes the hardware-verified pad metadata JSON (any field deviation may cause
     * the device to silently ignore the assignment):
     * ```
     * {"sym":<sampleNodeId>,"sample.start":<start>,"sample.end":<end>,
     *  "sound.playmode":"<playmode>","sound.amplitude":100,"sound.pan":0,
     *  "sound.pitch":0.00,"envelope.attack":0,"envelope.release":255,
     *  "sound.mutegroup":false,"time.mode":"off","midi.channel":0}
     * ```
     *
     * @param group         Target pad group (A/B/C/D).
     * @param gridIndex     Display-order pad index within the group (0..11, top-left to bottom-right,
     *                      matching [EP133Pads.GRID_ORDER]).
     * @param sampleNodeId  Device-assigned node ID of the uploaded sample (from [FileTransferClient.putSampleFile]).
     * @param sampleStart   Start frame (0-based, inclusive).  Use 0 to start from the beginning.
     * @param sampleEnd     End frame (exclusive).  Use total frame count to play the full sample.
     * @param playmode      EP-133 playmode string (default "oneshot").
     * @return              true if the device acknowledged the METADATA SET; false on error or
     *                      if the pad node cannot be resolved (logged with details).
     */
    open suspend fun assignSampleToPad(
        group: PadChannel,
        gridIndex: Int,
        sampleNodeId: Int,
        sampleStart: Int,
        sampleEnd: Int,
        playmode: String = "oneshot",
        muteGroup: Boolean = false,
    ): Boolean {
        return ftc.withFileOpLock {
            ftc.ensureFileSessionInitNoLock()
            try {
                val padNodeId = resolvePadNodeIdNoLock(group, gridIndex) ?: return@withFileOpLock false

                // Build and write the hardware-verified pad metadata JSON.
                val json = buildPadAssignmentJson(sampleNodeId, sampleStart, sampleEnd, playmode, muteGroup)
                Log.d("EP133APP", "assignSampleToPad: group=${group.name} gridIndex=$gridIndex padNode=$padNodeId json=$json")
                ftc.setMetadata(padNodeId, json)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EP133APP", "assignSampleToPad(${group.name}[$gridIndex]) failed", e)
                false
            }
        }
    }

    /**
     * Build the hardware-verified pad assignment metadata JSON.
     *
     * Shape verified on real device (2026-06-29): device accepted and played the sample
     * after receiving a METADATA SET with exactly these keys and types.
     *
     * @param sampleNodeId  Device-assigned node ID from [FileTransferClient.putSampleFile] (the `sym` field).
     * @param sampleStart   Start frame (0 = beginning of sample).
     * @param sampleEnd     End frame (total frame count = full sample).
     * @param playmode      EP-133 playmode string (e.g. "oneshot", "loop").
     */
    internal fun buildPadAssignmentJson(
        sampleNodeId: Int,
        sampleStart: Int,
        sampleEnd: Int,
        playmode: String,
        muteGroup: Boolean = false,
    ): String = """{"sym":$sampleNodeId,"sample.start":$sampleStart,"sample.end":$sampleEnd,"sound.playmode":"$playmode","sound.amplitude":100,"sound.pan":0,"sound.pitch":0.00,"envelope.attack":0,"envelope.release":255,"sound.mutegroup":$muteGroup,"time.mode":"off","midi.channel":0}"""

    /**
     * Metadata for an empty (unbound) pad — the device's own representation. Hardware-verified:
     * an empty pad's FILE_METADATA GET returns exactly `{"sym":0}` (see pad dumps), so SET-ting
     * it back unbinds the pad.
     */
    private val padEmptyJson = """{"sym":0}"""

    /**
     * Resolve a pad node id at `/projects/<active>/groups/<X>/<NN>` inside an
     * [FileTransferClient.withFileOpLock]-locked context. Shared by [assignSampleToPad] and
     * [clearPad].
     */
    private suspend fun resolvePadNodeIdNoLock(group: PadChannel, gridIndex: Int): Int? {
        val projectsNode = ftc.resolveNodeIdInternal("/projects") ?: run {
            Log.w("EP133APP", "resolvePadNode: cannot resolve /projects"); return null
        }
        val activeProjNodeId = ftc.getMetadataJson(projectsNode).optInt("active", -1).takeIf { it >= 0 } ?: run {
            Log.w("EP133APP", "resolvePadNode: no active project in /projects metadata"); return null
        }
        val projName = ftc.getNodeInfo(activeProjNodeId)?.name ?: run {
            Log.w("EP133APP", "resolvePadNode: cannot get project name for nodeId=$activeProjNodeId"); return null
        }
        val groupDirPath = "/projects/$projName/groups/${group.name}"
        val groupDirNode = ftc.resolveNodeIdInternal(groupDirPath) ?: run {
            Log.w("EP133APP", "resolvePadNode: cannot resolve $groupDirPath"); return null
        }
        val body = ftc.listNodeBody(groupDirNode, requestId = ftc.nextRequestId()) ?: run {
            Log.w("EP133APP", "resolvePadNode: FILE_LIST for $groupDirPath returned null"); return null
        }
        // Pad node name = "%02d".format(gridIndex + 1) per hardware-verified naming.
        val padName = "%02d".format(gridIndex + 1)
        return SysExProtocol.parseFileListEntries(body).firstOrNull { it.name == padName }?.nodeId ?: run {
            Log.w("EP133APP", "resolvePadNode: pad '$padName' not found in $groupDirPath"); return null
        }
    }

    /**
     * Unbind a pad on the device: write the empty-pad metadata (`{"sym":0}`) to the pad node so it
     * plays nothing. Returns false if the pad can't be resolved.
     */
    open suspend fun clearPad(group: PadChannel, gridIndex: Int): Boolean {
        return ftc.withFileOpLock {
            ftc.ensureFileSessionInitNoLock()
            try {
                val padNodeId = resolvePadNodeIdNoLock(group, gridIndex) ?: return@withFileOpLock false
                Log.d("EP133APP", "clearPad: group=${group.name} gridIndex=$gridIndex padNode=$padNodeId")
                ftc.setMetadata(padNodeId, padEmptyJson)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EP133APP", "clearPad(${group.name}[$gridIndex]) failed", e)
                false
            }
        }
    }

    /**
     * Clear a project slot: reset every pad in all four groups to empty (`{"sym":0}`). The slot
     * itself always survives — project directories carry no DELETE capability (flags `0x0e`), so
     * "clearing" means emptying its pads, not removing the numbered slot.
     *
     * @return the number of pads emptied, or -1 on failure.
     */
    open suspend fun clearProject(projectName: String): Int {
        return ftc.withFileOpLock {
            ftc.ensureFileSessionInitNoLock()
            try {
                var cleared = 0
                for (group in PadChannel.entries) {
                    val groupDir = ftc.resolveNodeIdInternal("/projects/$projectName/groups/${group.name}")
                        ?: continue
                    val body = ftc.listNodeBody(groupDir, requestId = ftc.nextRequestId()) ?: continue
                    val padNodes = SysExProtocol.parseFileListEntries(body)
                        .filter { it.name.length == 2 && it.name.all(Char::isDigit) }  // pad dirs 01..12
                        .map { it.nodeId }
                    for (padNode in padNodes) {
                        if (ftc.setMetadata(padNode, padEmptyJson)) cleared++
                    }
                }
                Log.d("EP133APP", "clearProject($projectName): emptied $cleared pads")
                cleared
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EP133APP", "clearProject($projectName) failed", e)
                -1
            }
        }
    }

    /**
     * Read which sample is bound to each pad of [group] in the active project.
     *
     * Returns gridIndex (0-based, 0..11) → sample display name for every OCCUPIED pad — a pad whose
     * metadata `sym` is non-zero (an empty pad is `{"sym":0}`). Empty pads are omitted. Names have
     * their extension stripped; if the sample node can't be named, falls back to `"sample"`.
     *
     * This lets the Kit Builder canvas mirror what's actually on the device instead of showing a
     * blank staging grid. Resolves the group dir + pad list once, then a METADATA GET per pad (plus
     * a FILE_INFO per occupied pad to name it). Returns an empty map on error.
     */
    open suspend fun readGroupPadState(group: PadChannel): Map<Int, String> {
        return ftc.withFileOpLock {
            ftc.ensureFileSessionInitNoLock()
            try {
                val projectsNode = ftc.resolveNodeIdInternal("/projects") ?: return@withFileOpLock emptyMap()
                val activeProjNodeId = ftc.getMetadataJson(projectsNode).optInt("active", -1)
                    .takeIf { it >= 0 } ?: return@withFileOpLock emptyMap()
                val projName = ftc.getNodeInfo(activeProjNodeId)?.name ?: return@withFileOpLock emptyMap()
                val groupDir = ftc.resolveNodeIdInternal("/projects/$projName/groups/${group.name}")
                    ?: return@withFileOpLock emptyMap()
                val body = ftc.listNodeBody(groupDir, requestId = ftc.nextRequestId()) ?: return@withFileOpLock emptyMap()
                val padEntries = SysExProtocol.parseFileListEntries(body)
                    .filter { it.name.length == 2 && it.name.all(Char::isDigit) }  // pad dirs 01..12
                val result = LinkedHashMap<Int, String>()
                for (entry in padEntries) {
                    val gridIndex = (entry.name.toIntOrNull() ?: continue) - 1
                    if (gridIndex !in 0 until 12) continue
                    val sym = ftc.getMetadataJson(entry.nodeId).optInt("sym", 0)
                    if (sym == 0) continue
                    val name = ftc.getNodeInfo(sym)?.name?.substringBeforeLast('.') ?: "sample"
                    result[gridIndex] = name
                }
                Log.d("EP133APP", "readGroupPadState(${group.name}): ${result.size} occupied pads")
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EP133APP", "readGroupPadState(${group.name}) failed", e)
                emptyMap()
            }
        }
    }
}
