package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.FileOpKind
import com.ep133.sampletool.domain.midi.FileResponse
import com.ep133.sampletool.domain.midi.FileWaiter
import com.ep133.sampletool.domain.midi.FileWaiterRegistry
import com.ep133.sampletool.domain.midi.SysExProtocol
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileWaiterRegistryTest {

    private fun resp(reqId: Int, status: Int, body: ByteArray = ByteArray(0)) =
        FileResponse(reqId, status, body)

    @Test
    fun oneShot_routes_completesDeferred_andRemovesWaiter() = runTest {
        val reg = FileWaiterRegistry()
        val waiter = FileWaiter.OneShot(reqId = 100, kind = FileOpKind.INFO)
        reg.register(waiter)
        assertTrue(reg.isAwaiting(100))

        val r = reg.route(resp(100, SysExProtocol.STATUS_OK, byteArrayOf(1, 2, 3)))

        assertEquals(FileWaiterRegistry.RouteResult.OneShotCompleted, r)
        val delivered = waiter.deferred.await()
        assertEquals(100, delivered.reqId)
        assertArrayEquals(byteArrayOf(1, 2, 3), delivered.body)
        assertFalse(reg.isAwaiting(100))
    }

    @Test
    fun oneShot_secondResponseSameReqId_isUnmatched() = runTest {
        val reg = FileWaiterRegistry()
        reg.register(FileWaiter.OneShot(reqId = 101, kind = FileOpKind.INFO))
        reg.route(resp(101, SysExProtocol.STATUS_OK))
        val second = reg.route(resp(101, SysExProtocol.STATUS_OK))
        assertEquals(FileWaiterRegistry.RouteResult.Unmatched, second)
    }

    @Test
    fun stream_continuationKeepsRegistered_thenCompleteCloses_inOrder() = runTest {
        val reg = FileWaiterRegistry()
        val waiter = FileWaiter.Stream(reqId = 200, kind = FileOpKind.METADATA_GET)
        reg.register(waiter)

        val r0 = reg.route(resp(200, SysExProtocol.STATUS_SPECIFIC_SUCCESS_START, byteArrayOf(0)))
        assertEquals(FileWaiterRegistry.RouteResult.StreamContinued, r0)
        assertTrue(reg.isAwaiting(200))

        val r1 = reg.route(resp(200, SysExProtocol.STATUS_SPECIFIC_SUCCESS_START, byteArrayOf(1)))
        assertEquals(FileWaiterRegistry.RouteResult.StreamContinued, r1)

        val r2 = reg.route(resp(200, SysExProtocol.STATUS_OK, byteArrayOf(2)))
        assertEquals(FileWaiterRegistry.RouteResult.StreamCompleted, r2)
        assertFalse(reg.isAwaiting(200))

        val pages = mutableListOf<Int>()
        waiter.sink.consumeEach { pages.add(it.body[0].toInt()) }
        assertEquals(listOf(0, 1, 2), pages)
    }

    @Test
    fun stream_errorStatus_closesExceptionally_andDeregisters() = runTest {
        val reg = FileWaiterRegistry()
        val waiter = FileWaiter.Stream(reqId = 201, kind = FileOpKind.METADATA_GET)
        reg.register(waiter)

        val r = reg.route(resp(201, status = 1))
        assertEquals(FileWaiterRegistry.RouteResult.StreamError, r)
        assertFalse(reg.isAwaiting(201))

        try {
            waiter.sink.receive()
            fail("expected sink to be closed exceptionally")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun route_unknownReqId_isUnmatched() {
        val reg = FileWaiterRegistry()
        assertEquals(FileWaiterRegistry.RouteResult.Unmatched, reg.route(resp(999, SysExProtocol.STATUS_OK)))
    }

    @Test
    fun register_duplicateLiveReqId_throws() {
        val reg = FileWaiterRegistry()
        reg.register(FileWaiter.OneShot(reqId = 300, kind = FileOpKind.INIT))
        try {
            reg.register(FileWaiter.OneShot(reqId = 300, kind = FileOpKind.INFO))
            fail("expected duplicate registration to throw")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun failAll_completesOneShotExceptionally_andClosesStream_andClears() = runTest {
        val reg = FileWaiterRegistry()
        val oneShot = FileWaiter.OneShot(reqId = 400, kind = FileOpKind.INFO)
        val stream = FileWaiter.Stream(reqId = 401, kind = FileOpKind.METADATA_GET)
        reg.register(oneShot)
        reg.register(stream)

        reg.failAll(IllegalStateException("disconnect"))

        assertFalse(reg.isAwaiting(400))
        assertFalse(reg.isAwaiting(401))
        try {
            oneShot.deferred.await()
            fail("expected OneShot deferred to fail")
        } catch (e: IllegalStateException) {
            // expected
        }
        try {
            stream.sink.receive()
            fail("expected Stream sink to be closed")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun interleavedOneShots_eachResponseRoutesToItsOwnWaiter() = runTest {
        // The exact 999.4 failure the flag model had: two ops in flight, responses arrive
        // out of order. With reqId routing each must land on its own waiter.
        val reg = FileWaiterRegistry()
        val a = FileWaiter.OneShot(reqId = 500, kind = FileOpKind.INFO)
        val b = FileWaiter.OneShot(reqId = 501, kind = FileOpKind.METADATA_SET)
        reg.register(a)
        reg.register(b)

        reg.route(resp(501, SysExProtocol.STATUS_OK, byteArrayOf(0xB)))
        reg.route(resp(500, SysExProtocol.STATUS_OK, byteArrayOf(0xA)))

        assertArrayEquals(byteArrayOf(0xA), a.deferred.await().body)
        assertArrayEquals(byteArrayOf(0xB), b.deferred.await().body)
    }

    @Test
    fun fileResponse_equality_comparesBodyByContent() {
        assertEquals(resp(1, 0, byteArrayOf(1, 2, 3)), resp(1, 0, byteArrayOf(1, 2, 3)))
        assertFalse(resp(1, 0, byteArrayOf(1, 2, 3)) == resp(1, 0, byteArrayOf(9)))
    }
}
