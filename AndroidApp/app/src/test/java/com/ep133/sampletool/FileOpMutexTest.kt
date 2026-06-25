package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Deadlock-free serialization tests for [MIDIRepository.fileOpMutex].
 *
 * Verifies that two file ops running concurrently on the same repo both complete without
 * hanging — proving the mutex serialises without deadlocking — and that their results are
 * correct (proving serialisation doesn't corrupt state).
 *
 * The key risk: kotlinx [Mutex] is NOT reentrant.  If any withLock body calls another
 * withLock on the same mutex, the coroutine suspends waiting for itself → deadlock.
 * These tests use an auto-ack port that responds synchronously so they complete quickly
 * even though each call awaits a MIDI response.
 *
 * Test design:
 *   - [MutexAutoAckPort] responds to every outbound FILE (cmd=5) frame with a matching
 *     status-0 response in the same coroutine (synchronous delivery into [onMidiReceived]).
 *   - [MutexTestRepo] pre-sets connected state and stubs [resolveNodeId] so ops reach the
 *     MIDI layer without hardware round-trips.
 *   - Two calls are launched concurrently with [async]; both are joined.  If either
 *     deadlocks, runTest times out and the test fails.
 */
class FileOpMutexTest {

    // ── Auto-ack MIDIPort ───────────────────────────────────────────────────────

    /**
     * Responds to every FILE (cmd=5) outbound frame with a minimal status=0 response
     * synchronously (before [sendMidi] returns).  This lets async coroutines that await
     * a MIDI response complete without a real hardware device.
     */
    private class MutexAutoAckPort : MIDIPort {
        override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
        override var onDevicesChanged: (() -> Unit)? = null

        override fun getUSBDevices() = MIDIPort.Devices(
            inputs  = listOf(MIDIPort.Device("in",  "EP-133")),
            outputs = listOf(MIDIPort.Device("out", "EP-133")),
        )

        override fun sendMidi(portId: String, data: ByteArray) {
            if (data.size < 10) return
            val command = data[8].toInt() and 0x7F
            if (command != SysExProtocol.TE_SYSEX_FILE) return

            // Echo back reqId extracted from frame[6]/[7].
            val reqHigh = (data[6].toInt() and 0x0F)
            val reqLow  = data[7].toInt() and 0x7F
            // Minimal STATUS-ONLY response: F0 TE_ID 00 40 reqHigh reqLow 05 00 F7
            val response = byteArrayOf(
                0xF0.toByte(),
                SysExProtocol.TE_ID_0,
                SysExProtocol.TE_ID_1,
                SysExProtocol.TE_ID_2,
                0x00,              // deviceId
                0x40,
                reqHigh.toByte(),
                reqLow.toByte(),
                SysExProtocol.TE_SYSEX_FILE.toByte(),
                0x00,              // status = STATUS_OK
                0xF7.toByte(),
            )
            onMidiReceived?.invoke("in", response)
        }

        override fun requestUSBPermissions() {}
        override fun refreshDevices() {}
        override fun startListening(portId: String) {}
        override fun closeAllListeners() {}
        override fun prewarmSendPort(portId: String) {}
        override fun close() {}
    }

    // ── Testable repo ───────────────────────────────────────────────────────────

    private class MutexTestRepo(port: MutexAutoAckPort) : MIDIRepository(port) {
        init {
            _deviceState.value = DeviceState(connected = true, outputPortId = "out")
        }

        /** Stub: skip FILE_LIST round-trips for /sounds. */
        override suspend fun resolveNodeId(path: String): Int? =
            if (path == "/sounds") 42 else null
    }

    // ── Tests ───────────────────────────────────────────────────────────────────

    /**
     * Two concurrent [ensureFileSessionInit] calls both complete without hanging.
     *
     * The first caller acquires the mutex, sends FILE_INIT (reqId=83), receives the auto-ack,
     * and releases.  The second caller then acquires the mutex, but since [fileSessionInitialized]
     * is already true it returns immediately.  Neither call deadlocks.
     */
    @Test
    fun concurrentEnsureFileSessionInit_bothCompleteWithoutDeadlock() = runTest {
        val port = MutexAutoAckPort()
        val repo = MutexTestRepo(port)

        val r1 = async(Dispatchers.Unconfined) { repo.ensureFileSessionInit() }
        val r2 = async(Dispatchers.Unconfined) { repo.ensureFileSessionInit() }

        // If either call deadlocked, runTest would time out.
        val first  = r1.await()
        val second = r2.await()

        // Both should report the session as initialized.
        assertTrue("first ensureFileSessionInit should return true", first)
        assertTrue("second ensureFileSessionInit should return true", second)
    }

    /**
     * Concurrent [getActiveGroupIndex] calls both complete without deadlock.
     *
     * getActiveGroupIndex acquires fileOpMutex and internally calls ensureFileSessionInitNoLock
     * (not the locking wrapper).  Running two instances concurrently proves:
     *   1. No nested withLock on the same mutex (would deadlock).
     *   2. Serialization: second call waits for first to finish — neither corrupts shared state.
     *
     * With the auto-ack port the entire walk (FILE_INIT → resolveNodeIdInternal → getMetadataJson →
     * getNodeInfo) succeeds with status=0 responses, so each call returns a non-null result or
     * null (the fake node walk won't find "active" project data, so null is expected — but what
     * matters is that both calls return, not that they return a specific value).
     */
    @Test
    fun concurrentGetActiveGroupIndex_bothCompleteWithoutDeadlock() = runTest {
        val port = MutexAutoAckPort()
        val repo = MutexTestRepo(port)

        val r1 = async(Dispatchers.Unconfined) { repo.getActiveGroupIndex() }
        yield()
        val r2 = async(Dispatchers.Unconfined) { repo.getActiveGroupIndex() }

        // Await both — runTest will time out if either hangs.
        r1.await()
        r2.await()
        // Both completed = no deadlock.  Specific return value is null (no real device) — don't assert it.
    }

    /**
     * Concurrent [getActiveGroupIndex] + [ensureFileSessionInit] both complete without deadlock.
     *
     * Models the actual production race: active-group poll (getActiveGroupIndex every 1.5 s)
     * fires at the same time as the file session is being initialised by another path.
     * The mutex serialises them; neither can deadlock because ensureFileSessionInit is a
     * top-level locked entry point (not called from within getActiveGroupIndex's locked body —
     * that uses ensureFileSessionInitNoLock instead).
     */
    @Test
    fun concurrentPollAndInit_bothCompleteWithoutDeadlock() = runTest {
        val port = MutexAutoAckPort()
        val repo = MutexTestRepo(port)

        val poll = async(Dispatchers.Unconfined) { repo.getActiveGroupIndex() }
        val init = async(Dispatchers.Unconfined) { repo.ensureFileSessionInit() }

        poll.await()
        init.await()
        // Both completed — no deadlock between top-level locking entry points.
    }
}
