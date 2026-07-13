package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.FileTransferClient
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression test for the connect-edge crash.
 *
 * [FileTransferClient.onDeviceConnected] fires [com.ep133.sampletool.domain.midi.FileWaiterRegistry.failAll],
 * which completes any in-flight file-op waiter EXCEPTIONALLY. Before the fix, [FileTransferClient.awaitFileOp]
 * only caught [kotlinx.coroutines.CancellationException], so that IllegalStateException propagated up through
 * the caller into `MIDIRepository`'s `repositoryScope.launch { queryDeviceStats() }` (a launch with no
 * CoroutineExceptionHandler) and crashed the app.
 *
 * The trigger: a second connect/reconnect edge fires while a file op is awaiting an ack (the debug
 * "Simulated EP-133" toggle reliably reproduces it; on hardware it's a USB re-seat during the startup
 * stats query or a sample import/backup).
 *
 * The fix: awaitFileOp catches the non-cancellation failure and returns null — the same "failed op"
 * signal every caller already handles as a timeout. This test starts a file op that registers a waiter
 * and awaits, fires onDeviceConnected() while it is in flight, and asserts the op returns null WITHOUT
 * throwing.
 */
class FileOpConnectEdgeTest {

    /** Inert port: sendMidi does nothing, so the op's waiter never gets an ack and stays in flight. */
    private class InertMIDIPort : MIDIPort {
        override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
        override var onDevicesChanged: (() -> Unit)? = null
        override fun getUSBDevices() = MIDIPort.Devices(emptyList(), emptyList())
        override fun sendMidi(portId: String, data: ByteArray) { /* inert — never acks */ }
        override fun requestUSBPermissions() {}
        override fun refreshDevices() {}
        override fun startListening(portId: String) {}
        override fun closeAllListeners() {}
        override fun prewarmSendPort(portId: String) {}
        override fun close() {}
    }

    @Test
    fun fileOpInFlight_onConnectEdgeFailAll_returnsNullWithoutThrowing() = runTest {
        // Arrange
        val port = InertMIDIPort()
        val ftc = FileTransferClient(port, outputPortId = { "out" }, deviceId = { 0 })

        // Act: start a file op that registers a waiter and awaits its ack (the inert port never sends one).
        val op = async { ftc.getNodeInfo(nodeId = 5) }
        runCurrent() // let the op register its waiter and suspend at deferred.await()

        // A connect/reconnect edge fires while the op is in flight — failAll completes the waiter
        // exceptionally. Before the fix this IllegalStateException escaped awaitFileOp and crashed
        // the repository launch scope; op.await() below would rethrow it and fail the test.
        ftc.onDeviceConnected()
        runCurrent() // let the resumed coroutine run deterministically on StandardTestDispatcher

        // Assert: the op completes as a failed op (null), not by throwing.
        assertNull("in-flight file op must return null on a connect-edge failAll, not throw", op.await())
    }
}
