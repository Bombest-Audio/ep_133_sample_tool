package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.midi.SampleImportProgress
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Spy MIDIPort for concurrency tests — records sends, simulates connected device.
 */
private class ConcurrencySpyPort : MIDIPort {
    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null

    override fun getUSBDevices() = MIDIPort.Devices(
        inputs = listOf(MIDIPort.Device("in", "EP-133")),
        outputs = listOf(MIDIPort.Device("out", "EP-133")),
    )

    override fun sendMidi(portId: String, data: ByteArray) {}
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}

/**
 * Fake MIDIRepository that simulates the real single-in-flight guard.
 *
 * [putSampleFile] uses an [AtomicBoolean] as an in-flight flag that mirrors
 * [MIDIRepository.transferInFlight]:
 *   - If the flag is already true when a call arrives → collision: increment [collisions]
 *     and return false (simulates "transfer already in flight" error path).
 *   - Otherwise: set the flag, [yield] to force coroutine interleaving under the test
 *     dispatcher, clear the flag, increment [successCount], and return true.
 *
 * Without [SampleImportManager.uploadMutex] serializing callers, concurrent coroutines
 * would race on the flag and record collisions. With the mutex, they queue, so zero
 * collisions are observed.
 */
private class ConcurrencyFakeRepo(spy: ConcurrencySpyPort) : MIDIRepository(spy) {

    private val _state = MutableStateFlow(
        DeviceState(connected = true, outputPortId = "out"),
    )
    override val deviceState get() = _state

    init {
        _deviceState.value = _state.value
    }

    /** Number of times two concurrent calls overlapped on the in-flight flag. */
    val collisions = AtomicInteger(0)

    /** Number of successful (non-colliding) uploads. */
    val successCount = AtomicInteger(0)

    private val inFlight = AtomicBoolean(false)

    override suspend fun putSampleFile(name: String, wavBytes: ByteArray): Boolean {
        if (!inFlight.compareAndSet(false, true)) {
            // Another call is in flight — collision detected.
            collisions.incrementAndGet()
            return false
        }
        try {
            // Yield so the test dispatcher can schedule other coroutines while this
            // "transfer" is in progress. Under the real device the transfer takes
            // milliseconds; yield() is sufficient to expose interleaving in unit tests.
            yield()
            successCount.incrementAndGet()
            return true
        } finally {
            inFlight.set(false)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Proves that [SampleImportManager.uploadMutex] serializes concurrent batch uploads.
 *
 * The core property: when N samples are imported concurrently via [importSampleBytes],
 * the [MIDIRepository.putSampleFile] calls must be serialized so that only one is
 * in-flight at a time. Without the mutex, [ConcurrencyFakeRepo]'s in-flight flag would
 * detect overlapping calls and record collisions. With the mutex, every call completes
 * sequentially, collisions == 0, and every flow reaches [SampleImportProgress.Done].
 */
class SampleImportConcurrencyTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Primary: 5 concurrent imports → zero collisions, all Done
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun concurrentBatchImports_zeroCollisions_allDone() = runTest {
        val spy = ConcurrencySpyPort()
        val repo = ConcurrencyFakeRepo(spy)
        val manager = SampleImportManager(repo)

        val fileCount = 5
        val results = Array<List<SampleImportProgress>>(fileCount) { emptyList() }

        // Launch all imports concurrently — mirrors what onFilesPicked does.
        val jobs = (0 until fileCount).map { i ->
            launch {
                results[i] = manager.importSampleBytes(
                    "sample$i.wav",
                    ByteArray(100) { (i and 0xFF).toByte() },
                ).toList()
            }
        }

        advanceUntilIdle()
        jobs.forEach { it.join() }

        // No two uploads should have overlapped on the in-flight flag.
        assertEquals(
            "uploadMutex must prevent concurrent putSampleFile calls; got ${repo.collisions.get()} collision(s)",
            0,
            repo.collisions.get(),
        )

        // Every upload succeeded.
        assertEquals(
            "All $fileCount uploads must succeed; got ${repo.successCount.get()}",
            fileCount,
            repo.successCount.get(),
        )

        // Every flow must have terminated with Done (not Error).
        results.forEachIndexed { i, events ->
            val last = events.lastOrNull()
            assertTrue(
                "Flow $i must reach Done; last event was $last",
                last is SampleImportProgress.Done,
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sanity: the fake itself is collision-sensitive (documents the bug)
    //
    // Calls putSampleFile directly from two overlapping coroutines WITHOUT the mutex
    // to confirm the fake correctly records a collision.  This verifies that the
    // primary test above is meaningful — if the fake never collided, passing zero
    // collisions would prove nothing.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun fakeRepo_detectsCollision_whenCalledConcurrentlyWithoutMutex() = runTest {
        val spy = ConcurrencySpyPort()
        val repo = ConcurrencyFakeRepo(spy)

        // Two coroutines calling putSampleFile concurrently, no mutex.
        val j1 = launch { repo.putSampleFile("a.wav", ByteArray(10)) }
        val j2 = launch { repo.putSampleFile("b.wav", ByteArray(10)) }

        advanceUntilIdle()
        j1.join()
        j2.join()

        // With no serialization, the fake must have detected at least one collision.
        assertTrue(
            "Fake should detect a collision when putSampleFile is called concurrently; got ${repo.collisions.get()}",
            repo.collisions.get() > 0,
        )
    }
}
