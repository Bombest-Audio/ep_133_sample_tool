package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.midi.MIDIPort
import com.ep133.sampletool.ui.kit.GroupSession
import com.ep133.sampletool.ui.kit.KitMode
import com.ep133.sampletool.ui.kit.KitViewModel
import com.ep133.sampletool.ui.kitbuilder.KitBuilderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The [GroupSession] contract and its sharing between [KitViewModel] (Loop Chopper) and
 * [KitBuilderViewModel] (Kit Builder): one selection, per-group CHOP/KIT designation, per-group
 * choke — a single source of truth driving both workflows.
 */
class GroupSessionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ── Test doubles ──────────────────────────────────────────────────────────

    private class SilentPort : MIDIPort {
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

    /** Fake repo whose [readGroupPadState] returns canned per-group pad maps and records reads. */
    private class PadStateFakeRepo(
        private val padsByGroup: Map<PadChannel, Map<Int, String>> = emptyMap(),
    ) : MIDIRepository(SilentPort()) {
        private val _state = MutableStateFlow(DeviceState(connected = true, outputPortId = "out"))
        override val deviceState get() = _state
        init { _deviceState.value = _state.value }

        val reads = mutableListOf<PadChannel>()
        override suspend fun readGroupPadState(group: PadChannel): Map<Int, String> {
            reads.add(group)
            return padsByGroup[group].orEmpty()
        }
    }

    private fun buildVms(
        session: GroupSession = GroupSession(),
        repo: MIDIRepository = PadStateFakeRepo(),
    ): Triple<KitViewModel, KitBuilderViewModel, GroupSession> {
        val manager = SampleImportManager(repo)
        return Triple(
            KitViewModel(repo, manager, session),
            KitBuilderViewModel(repo, manager, session),
            session,
        )
    }

    // ── GroupSession contract ────────────────────────────────────────────────

    @Test
    fun defaults_allGroupsChopWithChokeOn() {
        val session = GroupSession()
        assertEquals(PadChannel.A, session.selected.value)
        PadChannel.entries.forEach { g ->
            assertEquals("group $g defaults to CHOP", KitMode.CHOP, session.designationFor(g))
            assertTrue("group $g choke defaults on", session.chokeFor(g))
        }
    }

    @Test
    fun designation_isPerGroup() {
        val session = GroupSession()
        session.designate(PadChannel.B, KitMode.KIT)
        assertEquals(KitMode.KIT, session.designationFor(PadChannel.B))
        assertEquals("other groups untouched", KitMode.CHOP, session.designationFor(PadChannel.A))
        assertEquals(KitMode.CHOP, session.designationFor(PadChannel.C))
    }

    @Test
    fun choke_isPerGroup() {
        val session = GroupSession()
        session.setChoke(PadChannel.C, false)
        assertFalse(session.chokeFor(PadChannel.C))
        assertTrue("other groups untouched", session.chokeFor(PadChannel.A))
    }

    // ── Designation drives the page mode through KitViewModel ────────────────

    @Test
    fun selectingAGroup_flipsPageModeToItsDesignation() = runTest {
        val (kitVm, _, _) = buildVms()

        // Designate B as a KIT group while A stays CHOP.
        kitVm.onGroupChange(PadChannel.B)
        kitVm.onModeChange(KitMode.KIT)
        advanceUntilIdle()
        assertEquals(KitMode.KIT, kitVm.mode.value)

        // Selecting A flips the page back to CHOP; back to B → KIT again.
        kitVm.onGroupChange(PadChannel.A)
        advanceUntilIdle()
        assertEquals(KitMode.CHOP, kitVm.mode.value)

        kitVm.onGroupChange(PadChannel.B)
        advanceUntilIdle()
        assertEquals("B remembered its KIT designation", KitMode.KIT, kitVm.mode.value)
    }

    // ── One session, two ViewModels ──────────────────────────────────────────

    @Test
    fun groupSelection_isSharedBetweenChopperAndBuilder() = runTest {
        val (kitVm, builderVm, session) = buildVms()
        advanceUntilIdle()

        builderVm.onGroupChange(PadChannel.D)
        advanceUntilIdle()
        assertEquals("chopper follows the builder's selection", PadChannel.D, kitVm.selectedGroup.value)
        assertEquals(PadChannel.D, builderVm.state.value.group)
        assertEquals(PadChannel.D, session.selected.value)

        kitVm.onGroupChange(PadChannel.B)
        advanceUntilIdle()
        assertEquals("builder follows the chopper's selection", PadChannel.B, builderVm.state.value.group)
    }

    @Test
    fun choke_isSharedBetweenChopperAndBuilder() = runTest {
        val (kitVm, builderVm, _) = buildVms()
        advanceUntilIdle()

        kitVm.onChokeGroupChange(false)
        advanceUntilIdle()
        assertFalse("builder sees the chopper's choke change", builderVm.state.value.chokeGroup)

        builderVm.onChokeGroupChange(true)
        advanceUntilIdle()
        assertTrue("chopper sees the builder's choke change", kitVm.chokeGroup.value)
    }

    // ── Kit Builder per-group state ──────────────────────────────────────────

    @Test
    fun builder_padSelection_isPerGroup() = runTest {
        val (_, builderVm, _) = buildVms()
        advanceUntilIdle()

        builderVm.onPadSelected(2)
        advanceUntilIdle()
        assertEquals(2, builderVm.state.value.selectedPad)

        builderVm.onGroupChange(PadChannel.B)
        advanceUntilIdle()
        assertEquals("B has its own default pad selection", 9, builderVm.state.value.selectedPad)

        builderVm.onGroupChange(PadChannel.A)
        advanceUntilIdle()
        assertEquals("A's pad selection persisted", 2, builderVm.state.value.selectedPad)
    }

    @Test
    fun builder_devicePads_areMirroredPerGroup() = runTest {
        val repo = PadStateFakeRepo(
            padsByGroup = mapOf(
                PadChannel.A to mapOf(0 to "kick", 1 to "snare"),
                PadChannel.B to mapOf(5 to "hat"),
            ),
        )
        val (_, builderVm, _) = buildVms(repo = repo)
        advanceUntilIdle()   // init observers fire: connected + initial selection → read A

        assertEquals("A's mirror is A's pads", mapOf(0 to "kick", 1 to "snare"), builderVm.state.value.devicePads)

        builderVm.onGroupChange(PadChannel.B)
        advanceUntilIdle()
        assertEquals("B's mirror is B's pads", mapOf(5 to "hat"), builderVm.state.value.devicePads)
        assertTrue("the device was actually re-read for B", repo.reads.contains(PadChannel.B))

        builderVm.onGroupChange(PadChannel.A)
        advanceUntilIdle()
        assertEquals("back on A, A's pads again", mapOf(0 to "kick", 1 to "snare"), builderVm.state.value.devicePads)
    }
}
