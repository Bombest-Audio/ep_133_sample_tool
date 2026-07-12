package com.ep133.sampletool.domain

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.PadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One converted sample ready for the device: the payload plus its frame count. */
class SliceUpload(
    val name: String,
    val pcm: ByteArray,
    val channels: Int,
    val sampleRate: Int,
    val frames: Int,
)

/** Outcome of a single pad upload+assign; callers map it to their own progress/error handling. */
sealed interface PadUploadResult {
    object Done : PadUploadResult
    object UploadRejected : PadUploadResult
    object AssignRejected : PadUploadResult
    data class UploadFailed(val error: Throwable) : PadUploadResult
    data class AssignFailed(val error: Throwable) : PadUploadResult
}

/**
 * The shared device leg of every kit/chop upload: put a sample to /sounds, then bind the returned
 * node to a pad — both serialized on [deviceMutex] so they can't interleave with another transfer.
 *
 * Owns no UI state and does no logging; it returns a [PadUploadResult] the caller maps to its own
 * progress/error/snackbar handling. Rethrows [CancellationException]; any other failure from the
 * put or the assign becomes [PadUploadResult.UploadFailed] / [PadUploadResult.AssignFailed] so the
 * caller can tell the two legs apart.
 */
class PadUploadService(
    private val midi: MIDIRepository,
    private val deviceMutex: Mutex,
) {
    suspend fun uploadAndAssign(
        group: PadChannel,
        padIndex: Int,
        upload: SliceUpload,
        chokeOn: Boolean,
    ): PadUploadResult = deviceMutex.withLock {
        val nodeId = try {
            midi.putSampleFile(upload.name, upload.pcm, upload.channels, upload.sampleRate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock PadUploadResult.UploadFailed(e)
        }
        if (nodeId == null) return@withLock PadUploadResult.UploadRejected

        val ok = try {
            midi.assignSampleToPad(group, padIndex, nodeId, 0, upload.frames, muteGroup = chokeOn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock PadUploadResult.AssignFailed(e)
        }
        if (ok) PadUploadResult.Done else PadUploadResult.AssignRejected
    }
}
