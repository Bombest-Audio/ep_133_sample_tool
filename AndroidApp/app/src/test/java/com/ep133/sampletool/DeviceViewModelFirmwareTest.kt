package com.ep133.sampletool

import com.ep133.sampletool.domain.firmware.FirmwareCatalog
import com.ep133.sampletool.domain.firmware.FirmwareVersion
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.midi.MIDIPort
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.device.FirmwareUpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ── Test doubles ──────────────────────────────────────────────────────────────

/**
 * Fake FirmwareCatalog — returns a caller-specified result (or null) and records call count.
 */
private class FakeFirmwareCatalog(private val result: FirmwareVersion?) : FirmwareCatalog {
    var callCount = 0
    override suspend fun latestVersion(): FirmwareVersion? {
        callCount++
        return result
    }
}

/**
 * Minimal MIDIPort stub — no-ops for all methods; not connected.
 */
private class StubMIDIPort : MIDIPort {
    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null
    override fun getUSBDevices() = MIDIPort.Devices(emptyList(), emptyList())
    override fun sendMidi(portId: String, data: ByteArray) {}
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}

/**
 * Fake MIDIRepository that pre-seeds DeviceState with a known firmwareVersion.
 *
 * outputPortId is intentionally null — this causes queryDeviceStats() to return
 * false immediately (the real guard: `val portId = _deviceState.value.outputPortId ?: return false`).
 * The pre-seeded firmwareVersion survives the no-op queryDeviceStats call, so
 * checkFirmwareUpdate() receives the expected value.
 */
private class FirmwareTestMIDIRepo(
    firmwareVersion: String?,
) : MIDIRepository(StubMIDIPort()) {
    init {
        _deviceState.value = DeviceState(
            connected = true,
            outputPortId = null,   // no port → queryDeviceStats returns false immediately
            firmwareVersion = firmwareVersion,
        )
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

/**
 * Unit tests for DeviceViewModel firmware update state machine (FW-01 / FW-02).
 *
 * All tests use a fake FirmwareCatalog (no network) and a fake MIDIRepository
 * (no MIDI hardware). The Dispatchers.Main is set to a test dispatcher so
 * viewModelScope coroutines are fully controllable.
 */
class DeviceViewModelFirmwareTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Case 1: outdated firmware → UpdateAvailable ───────────────────────────

    @Test
    fun loadStats_outdatedFirmware_emitsUpdateAvailable() = runTest {
        val catalog = FakeFirmwareCatalog(result = FirmwareVersion.parse("2.5"))
        val repo = FirmwareTestMIDIRepo(firmwareVersion = "2.0.5")
        val vm = DeviceViewModel(repo, catalog)

        vm.loadStats()
        advanceUntilIdle()

        val state = vm.firmwareUpdate.value
        assertTrue(
            "Expected UpdateAvailable but got $state",
            state is FirmwareUpdateState.UpdateAvailable,
        )
        val update = state as FirmwareUpdateState.UpdateAvailable
        assertEquals("current must be 2.0.5", FirmwareVersion.parse("2.0.5"), update.current)
        assertEquals("latest must be 2.5", FirmwareVersion.parse("2.5"), update.latest)
    }

    // ── Case 2: current firmware == latest → UpToDate ────────────────────────

    @Test
    fun loadStats_currentFirmware_emitsUpToDate() = runTest {
        val catalog = FakeFirmwareCatalog(result = FirmwareVersion.parse("2.5"))
        val repo = FirmwareTestMIDIRepo(firmwareVersion = "2.5")
        val vm = DeviceViewModel(repo, catalog)

        vm.loadStats()
        advanceUntilIdle()

        assertEquals(
            "Expected UpToDate when device firmware == latest",
            FirmwareUpdateState.UpToDate,
            vm.firmwareUpdate.value,
        )
    }

    // ── Case 3: null firmwareVersion → Unknown, catalog not called ────────────

    @Test
    fun loadStats_nullFirmwareVersion_emitsUnknown_catalogNotCalled() = runTest {
        val catalog = FakeFirmwareCatalog(result = FirmwareVersion.parse("2.5"))
        val repo = FirmwareTestMIDIRepo(firmwareVersion = null)
        val vm = DeviceViewModel(repo, catalog)

        vm.loadStats()
        advanceUntilIdle()

        assertEquals(
            "Expected Idle (no firmware version → checkFirmwareUpdate not triggered)",
            FirmwareUpdateState.Idle,
            vm.firmwareUpdate.value,
        )
        assertEquals("catalog must not be called when firmwareVersion is null", 0, catalog.callCount)
    }

    // ── Case 4: unparseable firmware string → Unknown, catalog not called ─────

    @Test
    fun loadStats_unparseableFirmware_emitsUnknown_catalogNotCalled() = runTest {
        val catalog = FakeFirmwareCatalog(result = FirmwareVersion.parse("2.5"))
        val repo = FirmwareTestMIDIRepo(firmwareVersion = "not-a-version")
        val vm = DeviceViewModel(repo, catalog)

        vm.loadStats()
        advanceUntilIdle()

        assertEquals(
            "Expected Unknown for unparseable firmware string",
            FirmwareUpdateState.Unknown,
            vm.firmwareUpdate.value,
        )
        assertEquals("catalog must not be called for unparseable firmware", 0, catalog.callCount)
    }

    // ── Case 5: fake catalog returns null (defensive path) → Unknown ──────────
    //
    // Note: the real TeFirmwareCatalog floors to LATEST_KNOWN_FIRMWARE and never returns null.
    // This test covers the defensive code path for fake/null catalog results only.

    @Test
    fun loadStats_catalogReturnsNull_emitsUnknown() = runTest {
        val catalog = FakeFirmwareCatalog(result = null)  // fake-null path only
        val repo = FirmwareTestMIDIRepo(firmwareVersion = "2.0")
        val vm = DeviceViewModel(repo, catalog)

        vm.loadStats()
        advanceUntilIdle()

        assertEquals(
            "Expected Unknown when catalog returns null",
            FirmwareUpdateState.Unknown,
            vm.firmwareUpdate.value,
        )
    }

    // ── Case 6: checkFirmwareUpdate triggered after loadStats → catalog called once ──

    @Test
    fun loadStats_withFirmwareVersion_triggersCheckAndCallsCatalogOnce() = runTest {
        val catalog = FakeFirmwareCatalog(result = FirmwareVersion.parse("2.5"))
        val repo = FirmwareTestMIDIRepo(firmwareVersion = "2.0.5")
        val vm = DeviceViewModel(repo, catalog)

        vm.loadStats()
        advanceUntilIdle()

        assertEquals("catalog must be called exactly once after loadStats with firmware", 1, catalog.callCount)
    }
}
