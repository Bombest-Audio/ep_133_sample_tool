import Foundation

/// Mirrors AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/FileWaiterRegistry.kt.
///
/// The Kotlin original is thread-safe via ConcurrentHashMap because Android routes MIDI
/// callbacks off the op coroutines' context. On iOS the MIDIManager delivers callbacks on
/// the main thread, so the Swift port serializes everything on the main actor instead:
/// same reqId-keyed routing rules, no locks.

/// A single inbound FILE response after the dispatcher has consumed the STATUS byte and
/// unpacked the 7-bit body.
///
/// - `reqId`: the request id the device echoed back (correlation key)
/// - `status`: raw STATUS byte (payload[0])
/// - `body`: unpacked body (payload[1..] after `SysExProtocol.unpack7bit`); may be empty
///   for a status-only ack
struct FileResponse: Equatable {
    let reqId: Int
    let status: Int
    let body: [UInt8]

    init(reqId: Int, status: Int, body: [UInt8] = []) {
        self.reqId = reqId
        self.status = status
        self.body = body
    }
}

/// Op kind — diagnostic/logging only. Routing never depends on it; it routes purely by reqId.
enum FileOpKind {
    case fileInit, list, metadataGet, metadataSet, info, getInit, getData, putInit, putData
}

/// Errors surfaced by the registry and its waiters.
enum FileWaiterError: Error {
    /// A live waiter already exists for the reqId (programming error — Kotlin `check`).
    case duplicateWaiter(reqId: Int, existingKind: FileOpKind)
    /// The device answered a stream op with an error status.
    case deviceErrorStatus(Int)
    /// A OneShot wait exceeded its timeout (Kotlin callers wrap awaits in `withTimeout`).
    case timeout
}

/// One-shot promise mirroring kotlinx `CompletableDeferred`: complete-before-await and
/// await-before-complete both work; the first completion wins and later ones are ignored.
@MainActor
final class CompletableDeferred<T> {
    private var result: Result<T, Error>?
    private var continuations: [CheckedContinuation<T, Error>] = []

    func complete(_ value: T) { resolve(.success(value)) }
    func completeExceptionally(_ error: Error) { resolve(.failure(error)) }

    private func resolve(_ r: Result<T, Error>) {
        guard result == nil else { return }
        result = r
        let pending = continuations
        continuations.removeAll()
        for continuation in pending { continuation.resume(with: r) }
    }

    /// Suspend until completed (Kotlin `Deferred.await`).
    func value() async throws -> T {
        if let result { return try result.get() }
        return try await withCheckedThrowingContinuation { continuations.append($0) }
    }

    /// `value()` raced against `Task.sleep` — the Kotlin callers' `withTimeout` pattern.
    /// On timeout the deferred completes with `FileWaiterError.timeout` (first wins), so a
    /// late device response is dropped just like Kotlin's cancelled `withTimeout` scope.
    func value(timeout: TimeInterval) async throws -> T {
        // Task {} here inherits the class's main-actor isolation, so the
        // completeExceptionally call after the sleep is a plain synchronous call.
        let timer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.completeExceptionally(FileWaiterError.timeout)
        }
        defer { timer.cancel() }
        return try await value()
    }
}

/// A registered waiter for one outstanding file op, keyed by its `reqId`.
///
/// `OneShot` completes once (single-response ops: INIT, INFO, METADATA set, LIST, each GET page,
/// each PUT ack). `Stream` accumulates across continuation chunks and closes on the terminal
/// status (the multi-response METADATA GET).
@MainActor
class FileWaiter {
    let reqId: Int
    let kind: FileOpKind

    fileprivate init(reqId: Int, kind: FileOpKind) {
        self.reqId = reqId
        self.kind = kind
    }

    /// The next matching response completes `deferred`; the waiter is then removed.
    final class OneShot: FileWaiter {
        let deferred = CompletableDeferred<FileResponse>()

        override init(reqId: Int, kind: FileOpKind) {
            super.init(reqId: reqId, kind: kind)
        }
    }

    /// Each matching response is offered to `sink`. The waiter stays registered while the status
    /// is a continuation (`SysExProtocol.STATUS_SPECIFIC_SUCCESS_START` or higher) and is removed
    /// with `sink` finished on a terminal `SysExProtocol.STATUS_OK` or an error status. The caller
    /// drains `sink` with a `for try await` loop (Kotlin: unbounded Channel + receive loop).
    final class Stream: FileWaiter {
        let sink: AsyncThrowingStream<FileResponse, Error>
        private let sinkContinuation: AsyncThrowingStream<FileResponse, Error>.Continuation

        override init(reqId: Int, kind: FileOpKind) {
            var continuation: AsyncThrowingStream<FileResponse, Error>.Continuation!
            sink = AsyncThrowingStream(bufferingPolicy: .unbounded) { continuation = $0 }
            sinkContinuation = continuation
            super.init(reqId: reqId, kind: kind)
        }

        fileprivate func trySend(_ resp: FileResponse) {
            sinkContinuation.yield(resp)
        }

        fileprivate func close(_ cause: Error? = nil) {
            sinkContinuation.finish(throwing: cause)
        }
    }
}

/// reqId → waiter map. Replaces the old mutable-global-flag dispatch model (backlog 999.4):
/// each outgoing file op registers a waiter under its unique reqId, and the incoming-SysEx
/// dispatcher routes every response strictly by the echoed reqId — never by which global
/// flag happens to be set.
@MainActor
final class FileWaiterRegistry {
    private var waiters: [Int: FileWaiter] = [:]

    /// Register `waiter`. Throws if a live waiter already exists for that reqId (programming error).
    @discardableResult
    func register(_ waiter: FileWaiter) throws -> FileWaiter {
        if let prev = waiters[waiter.reqId] {
            throw FileWaiterError.duplicateWaiter(reqId: waiter.reqId, existingKind: prev.kind)
        }
        waiters[waiter.reqId] = waiter
        return waiter
    }

    /// Remove and return the waiter for `reqId` without completing it (timeout/cancel cleanup).
    @discardableResult
    func remove(_ reqId: Int) -> FileWaiter? {
        waiters.removeValue(forKey: reqId)
    }

    /// True if a waiter is currently registered for `reqId`.
    func isAwaiting(_ reqId: Int) -> Bool {
        waiters[reqId] != nil
    }

    /// Route `resp` to its waiter by reqId. The dispatcher uses the `RouteResult` only for logging.
    /// If no waiter matches, returns `.unmatched` (stale/duplicate response → drop).
    func route(_ resp: FileResponse) -> RouteResult {
        guard let waiter = waiters[resp.reqId] else { return .unmatched }
        switch waiter {
        case let oneShot as FileWaiter.OneShot:
            // Remove-if-still-current guards against a duplicate racing in
            // (mirrors the Kotlin atomic remove(k, v)).
            guard waiters[resp.reqId] === oneShot else { return .unmatched }
            waiters.removeValue(forKey: resp.reqId)
            oneShot.deferred.complete(resp)
            return .oneShotCompleted
        case let stream as FileWaiter.Stream:
            switch SysExProtocol.classifyTransferStatus(resp.status) {
            case .pending:
                stream.trySend(resp)
                return .streamContinued
            case .complete:
                stream.trySend(resp) // the terminal page may still carry data
                waiters.removeValue(forKey: resp.reqId)
                stream.close()
                return .streamCompleted
            case .error:
                waiters.removeValue(forKey: resp.reqId)
                stream.close(FileWaiterError.deviceErrorStatus(resp.status))
                return .streamError
            }
        default:
            return .unmatched
        }
    }

    /// Fail every live waiter (disconnect / device greet) so awaiting ops fail fast instead of hanging.
    func failAll(_ cause: Error) {
        let snapshot = Array(waiters.values)
        waiters.removeAll()
        for waiter in snapshot {
            switch waiter {
            case let oneShot as FileWaiter.OneShot:
                oneShot.deferred.completeExceptionally(cause)
            case let stream as FileWaiter.Stream:
                stream.close(cause)
            default:
                break
            }
        }
    }

    enum RouteResult {
        case oneShotCompleted, streamContinued, streamCompleted, streamError, unmatched
    }
}
