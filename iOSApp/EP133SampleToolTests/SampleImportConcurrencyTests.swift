import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportConcurrencyTest.kt.

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles
// ─────────────────────────────────────────────────────────────────────────────

/// Spy MIDIPort for concurrency tests — records nothing, simulates a connected device.
private final class ConcurrencySpyPort: MIDIPort {
    var onMIDIReceived: ((String, [UInt8]) -> Void)?
    var onDevicesChanged: (() -> Void)?
    func setup() {}
    func close() {}
    func startListening(portId: String) {}
    func stopListening(portId: String) {}
    func getUSBDevices() -> MIDIDeviceList {
        MIDIDeviceList(
            inputs: [MIDIDevice(id: "in", name: "EP-133")],
            outputs: [MIDIDevice(id: "out", name: "EP-133")]
        )
    }
    func sendMIDI(to portId: String, data: [UInt8]) {}
}

/// Fake MIDIRepository that simulates the real single-in-flight guard.
///
/// `putSampleFile` uses an in-flight flag that mirrors the repository's
/// single-in-flight-transfer guard (Kotlin used AtomicBoolean; here all access is
/// MainActor-serialized so a plain Bool is the exact equivalent):
///   - If the flag is already true when a call arrives → collision: increment `collisions`
///     and return nil (simulates "transfer already in flight" error path).
///   - Otherwise: set the flag, `Task.yield()` to force task interleaving, clear the
///     flag, increment `successCount`, and return a fake node ID.
///
/// Without `SampleImportManager.uploadMutex` serializing callers, concurrent tasks
/// would race on the flag and record collisions. With the mutex, they queue, so zero
/// collisions are observed.
@MainActor
private final class ConcurrencyFakeRepo: MIDIRepository {

    /// Number of times two concurrent calls overlapped on the in-flight flag.
    private(set) var collisions = 0

    /// Number of successful (non-colliding) uploads.
    private(set) var successCount = 0

    private var inFlight = false

    init(_ spy: ConcurrencySpyPort) {
        super.init(spy)
        deviceState = DeviceState(connected: true, outputPortId: "out")
    }

    override func putSampleFile(
        name: String,
        pcmBytes: [UInt8],
        channels: Int,
        sampleRate: Int
    ) async throws -> Int? {
        if inFlight {
            // Another call is in flight — collision detected.
            collisions += 1
            return nil
        }
        inFlight = true
        defer { inFlight = false }
        // Yield so the executor can schedule other tasks while this "transfer" is in
        // progress. Under the real device the transfer takes milliseconds; yield() is
        // sufficient to expose interleaving in unit tests.
        await Task.yield()
        successCount += 1
        return 42  // fake node ID
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

/// Proves that `SampleImportManager.uploadMutex` serializes concurrent batch uploads.
///
/// The core property: when N samples are imported concurrently via `importSampleBytes`,
/// the `MIDIRepository.putSampleFile` calls must be serialized so that only one is
/// in-flight at a time. Without the mutex, `ConcurrencyFakeRepo`'s in-flight flag would
/// detect overlapping calls and record collisions. With the mutex, every call completes
/// sequentially, collisions == 0, and every stream reaches `SampleImportProgress.done`.
@MainActor
final class SampleImportConcurrencyTests: XCTestCase {

    // ──────────────────────────────────────────────────────────────────────────
    // Primary: 5 concurrent imports → zero collisions, all Done
    // ──────────────────────────────────────────────────────────────────────────

    func testConcurrentBatchImports_zeroCollisions_allDone() async {
        let spy = ConcurrencySpyPort()
        let repo = ConcurrencyFakeRepo(spy)
        let manager = SampleImportManager(repo)

        let fileCount = 5
        var results = [[SampleImportProgress]](repeating: [], count: fileCount)

        // Launch all imports concurrently — mirrors what the file picker callback does.
        // (Kotlin `launch` + `toList` → task group collecting each stream.)
        let streams = (0..<fileCount).map { i in
            manager.importSampleBytes(
                rawName: "sample\(i).wav",
                wavBytes: Data(repeating: UInt8(i & 0xFF), count: 100))
        }
        await withTaskGroup(of: (Int, [SampleImportProgress]).self) { group in
            for (i, stream) in streams.enumerated() {
                group.addTask {
                    var events: [SampleImportProgress] = []
                    for await event in stream {
                        events.append(event)
                    }
                    return (i, events)
                }
            }
            for await (i, events) in group {
                results[i] = events
            }
        }

        // No two uploads should have overlapped on the in-flight flag.
        XCTAssertEqual(
            0, repo.collisions,
            "uploadMutex must prevent concurrent putSampleFile calls; got \(repo.collisions) collision(s)")

        // Every upload succeeded.
        XCTAssertEqual(
            fileCount, repo.successCount,
            "All \(fileCount) uploads must succeed; got \(repo.successCount)")

        // Every stream must have terminated with Done (not Error).
        for (i, events) in results.enumerated() {
            let last = events.last
            guard case .done = last else {
                XCTFail("Stream \(i) must reach Done; last event was \(String(describing: last))")
                continue
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sanity: the fake itself is collision-sensitive (documents the bug)
    //
    // Calls putSampleFile directly from two overlapping tasks WITHOUT the mutex
    // to confirm the fake correctly records a collision.  This verifies that the
    // primary test above is meaningful — if the fake never collided, passing zero
    // collisions would prove nothing.
    // ──────────────────────────────────────────────────────────────────────────

    func testFakeRepo_detectsCollision_whenCalledConcurrentlyWithoutMutex() async {
        let spy = ConcurrencySpyPort()
        let repo = ConcurrencyFakeRepo(spy)

        // Two tasks calling putSampleFile concurrently, no mutex.
        async let first = repo.putSampleFile(
            name: "a.wav", pcmBytes: [UInt8](repeating: 0, count: 10),
            channels: 1, sampleRate: 46875)
        async let second = repo.putSampleFile(
            name: "b.wav", pcmBytes: [UInt8](repeating: 0, count: 10),
            channels: 1, sampleRate: 46875)
        _ = try? await first
        _ = try? await second

        // With no serialization, the fake must have detected at least one collision.
        XCTAssertTrue(
            repo.collisions > 0,
            "Fake should detect a collision when putSampleFile is called concurrently; got \(repo.collisions)")
    }
}
