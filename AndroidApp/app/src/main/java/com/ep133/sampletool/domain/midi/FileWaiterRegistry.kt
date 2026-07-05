package com.ep133.sampletool.domain.midi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * A single inbound FILE response after the dispatcher has consumed the STATUS byte and
 * unpacked the 7-bit body.
 *
 * @param reqId  the request id the device echoed back (correlation key)
 * @param status raw STATUS byte (payload[0])
 * @param body   unpacked body (payload[1..] after [SysExProtocol.unpack7bit]); may be empty
 *               for a status-only ack
 */
data class FileResponse(
    val reqId: Int,
    val status: Int,
    val body: ByteArray,
) {
    // ByteArray uses identity equality by default; compare by content so callers and tests
    // can reason about response equality intuitively.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileResponse) return false
        return reqId == other.reqId && status == other.status && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = reqId
        result = 31 * result + status
        result = 31 * result + body.contentHashCode()
        return result
    }
}

/** Op kind — diagnostic/logging only. Routing never depends on it; it routes purely by reqId. */
enum class FileOpKind { INIT, LIST, METADATA_GET, METADATA_SET, INFO, GET_INIT, GET_DATA, PUT_INIT, PUT_DATA }

/**
 * A registered waiter for one outstanding file op, keyed by its [reqId].
 *
 * [OneShot] completes once (single-response ops: INIT, INFO, METADATA set, LIST, each GET page,
 * each PUT ack). [Stream] accumulates across continuation chunks and closes on the terminal
 * status (the multi-response METADATA GET).
 */
sealed interface FileWaiter {
    val reqId: Int
    val kind: FileOpKind

    /** The next matching response completes [deferred]; the waiter is then removed. */
    class OneShot(
        override val reqId: Int,
        override val kind: FileOpKind,
        val deferred: CompletableDeferred<FileResponse> = CompletableDeferred(),
    ) : FileWaiter

    /**
     * Each matching response is offered to [sink]. The waiter stays registered while the status
     * is a continuation ([SysExProtocol.STATUS_SPECIFIC_SUCCESS_START] or higher) and is removed
     * with [sink] closed on a terminal [SysExProtocol.STATUS_OK] or an error status. The caller
     * drains [sink] with a receive loop.
     */
    class Stream(
        override val reqId: Int,
        override val kind: FileOpKind,
        val sink: Channel<FileResponse> = Channel(Channel.UNLIMITED),
    ) : FileWaiter
}

/**
 * Thread-safe reqId → waiter map. Replaces the old mutable-global-flag dispatch model
 * (backlog 999.4): each outgoing file op registers a waiter under its unique reqId, and the
 * incoming-SysEx dispatcher routes every response strictly by the echoed reqId — never by which
 * global flag happens to be set.
 *
 * The map is touched from two contexts: op coroutines ([register]/[remove], serialized by the
 * caller's file-op mutex) and the MIDI receive callback ([route], NOT under that mutex). A
 * [ConcurrentHashMap] with atomic putIfAbsent / remove(k,v) keeps those safe without forcing the
 * hot receive path to block on a slow op.
 */
class FileWaiterRegistry {
    private val waiters = ConcurrentHashMap<Int, FileWaiter>()

    /** Register [waiter]. Throws if a live waiter already exists for that reqId (programming error). */
    fun register(waiter: FileWaiter): FileWaiter {
        val prev = waiters.putIfAbsent(waiter.reqId, waiter)
        check(prev == null) { "reqId ${waiter.reqId} already has a live waiter (${prev?.kind})" }
        return waiter
    }

    /** Remove and return the waiter for [reqId] without completing it (timeout/cancel cleanup). */
    fun remove(reqId: Int): FileWaiter? = waiters.remove(reqId)

    /** True if a waiter is currently registered for [reqId]. */
    fun isAwaiting(reqId: Int): Boolean = waiters.containsKey(reqId)

    /**
     * Route [resp] to its waiter by reqId. The dispatcher uses the [RouteResult] only for logging.
     * If no waiter matches, returns [RouteResult.Unmatched] (stale/duplicate response → drop).
     */
    fun route(resp: FileResponse): RouteResult {
        val waiter = waiters[resp.reqId] ?: return RouteResult.Unmatched
        return when (waiter) {
            is FileWaiter.OneShot -> {
                // Atomic remove-if-still-current guards against a duplicate racing in.
                if (waiters.remove(resp.reqId, waiter)) {
                    waiter.deferred.complete(resp)
                    RouteResult.OneShotCompleted
                } else {
                    RouteResult.Unmatched
                }
            }
            is FileWaiter.Stream -> when (SysExProtocol.classifyTransferStatus(resp.status)) {
                SysExProtocol.TransferStatus.PENDING -> {
                    waiter.sink.trySend(resp)
                    RouteResult.StreamContinued
                }
                SysExProtocol.TransferStatus.COMPLETE -> {
                    waiter.sink.trySend(resp) // the terminal page may still carry data
                    waiters.remove(resp.reqId, waiter)
                    waiter.sink.close()
                    RouteResult.StreamCompleted
                }
                SysExProtocol.TransferStatus.ERROR -> {
                    waiters.remove(resp.reqId, waiter)
                    waiter.sink.close(IllegalStateException("device error status ${resp.status}"))
                    RouteResult.StreamError
                }
            }
        }
    }

    /** Fail every live waiter (disconnect / device greet) so awaiting ops fail fast instead of hanging. */
    fun failAll(cause: Throwable) {
        val snapshot = waiters.values.toList()
        waiters.clear()
        snapshot.forEach { w ->
            when (w) {
                is FileWaiter.OneShot -> w.deferred.completeExceptionally(cause)
                is FileWaiter.Stream -> w.sink.close(cause)
            }
        }
    }

    enum class RouteResult { OneShotCompleted, StreamContinued, StreamCompleted, StreamError, Unmatched }
}
