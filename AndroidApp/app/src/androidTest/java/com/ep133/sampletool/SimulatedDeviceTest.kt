package com.ep133.sampletool

import androidx.compose.ui.test.junit4.createComposeRule
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.robots.AppRobot
import com.ep133.sampletool.support.launchEP133App
import com.ep133.sampletool.support.sim.EP133DeviceSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Full-stack E2E against the wire-level device simulator: a REAL MIDIRepository speaking
 * real SysEx frames (7-bit packing, reqId routing, FILE_INIT session, paged PUT with acks,
 * metadata drill-downs) to [EP133DeviceSimulator]. Nothing above the MIDIPort seam is faked.
 *
 * These complement the ScriptedMIDIRepository tests: those pin UI branches cheaply; these
 * prove the protocol stack and the UI agree end-to-end, per docs/ep133-sysex-protocol.md.
 */
class SimulatedDeviceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Real repository over the simulator, connected the way MainActivity would. */
    private fun connect(sim: EP133DeviceSimulator): MIDIRepository {
        val repo = MIDIRepository(sim)
        repo.refreshDeviceState() // enumerates the sim's ports → connected=true, outputPortId set
        return repo
    }

    @Test
    fun deviceScreen_showsStatsQueriedOverTheWire() {
        // Arrange: two seeded samples; stats must come from GREET + /sounds LIST + metadata
        val sim = EP133DeviceSimulator(firmwareVersion = "2.0.5")
        sim.seedSound(ByteArray(1_048_576)) // 1 MB
        sim.seedSound(ByteArray(1_048_576))
        val repo = connect(sim)
        composeTestRule.launchEP133App(repo)
        val app = AppRobot(composeTestRule)
        // Act: opening the Device screen fires loadStats() → the real query pipeline
        val device = app.goToDevice()
        // Assert: firmware from the greet, sample count from FILE_LIST, storage from metadata
        device.waitForText("2.0.5")
        device.assertStatInGrid("2.0.5")
            .assertStatInGrid("2")        // sample count
            .assertStatInGrid("2/59MB")   // used = 2 MB of max_capacity (62,853,120 B ≈ 59 MB)
        app.assertConnectionLabel("CONNECTED")
    }

    @Test
    fun projectsScreen_listsSlotsResolvedByNodeWalk() {
        // Arrange: default tree = projects 01..09, project 02 active
        val sim = EP133DeviceSimulator()
        sim.setActiveProject(4000)
        val repo = connect(sim)
        composeTestRule.launchEP133App(repo)
        val app = AppRobot(composeTestRule)
        // Act: Projects screen walks / → /projects, reads the active pointer, lists children
        val projects = app.goToProjects()
        // Assert: slot 09 sits below the LazyColumn fold — assertSlotVisible scrolls to it
        projects.assertSlotVisible(3000, "01")
            .assertSlotVisible(4000, "02")
        composeTestRule.onNode(androidx.compose.ui.test.hasText("ACTIVE")).assertExists()
        projects.assertSlotVisible(11000, "09")
            .assertSlotCount(9)
    }

    @Test
    fun sampleImport_runsTheRealPagedPutProtocol() {
        // Arrange
        val sim = EP133DeviceSimulator()
        val repo = connect(sim)
        val ctx = composeTestRule.launchEP133App(repo)
        val app = AppRobot(composeTestRule)
        // Act: 2000 bytes → FILE_INIT, /sounds node walk, PUT INIT, 5 DATA pages + terminator
        val payload = ByteArray(2000) { (it % 251).toByte() }
        val import = app.goToImport()
        ctx.sampleImportViewModel.importStagedBytes("KICK9", payload)
        // Assert: UI reached DONE and the file landed on the "device" byte-for-byte
        import.assertRowVisible("KICK9")
            .waitForRowState("KICK9", "DONE", timeoutMillis = 10_000)
        val uploaded = sim.soundFiles().single { it.name == "KICK9.wav" }
        assertEquals(2000, uploaded.data.size)
        assertTrue("uploaded bytes must match the staged payload", uploaded.data.contentEquals(payload))
    }

    @Test
    fun sampleImport_pageFailure_forceClosesTheTransfer() {
        // Arrange: the device rejects DATA page 2 — the app must abort AND terminate the
        // transfer (an unterminated PUT wedges real hardware until power-cycle)
        val sim = EP133DeviceSimulator()
        sim.failPutDataAtPage = 2
        val repo = connect(sim)
        val ctx = composeTestRule.launchEP133App(repo)
        val app = AppRobot(composeTestRule)
        // Act
        val import = app.goToImport()
        ctx.sampleImportViewModel.importStagedBytes("SNARE9", ByteArray(2000))
        // Assert: UI shows the failure, no partial file committed, and — critically — the
        // app's forceCloseTransfer terminator cleared the wedge
        import.assertRowVisible("SNARE9")
            .waitForRowState("SNARE9", "ERROR", timeoutMillis = 10_000)
        assertTrue("no partial file may be committed", sim.soundFiles().none { it.name == "SNARE9.wav" })
        composeTestRule.waitUntil(5_000) { !sim.wedge }
    }

    @Test
    fun wedgedDevice_importFailsWithoutCommitting() {
        // Arrange: device already wedged by a previous unterminated PUT (HW-verified behavior:
        // it silently ignores new PUT INITs until power-cycled)
        val sim = EP133DeviceSimulator()
        sim.wedge = true
        val repo = connect(sim)
        val ctx = composeTestRule.launchEP133App(repo)
        val app = AppRobot(composeTestRule)
        // Act
        val import = app.goToImport()
        ctx.sampleImportViewModel.importStagedBytes("HAT9", ByteArray(500))
        // Assert: PUT INIT gets no ack → upload errors out after the 15s ack timeout;
        // nothing committed
        import.assertRowVisible("HAT9")
            .waitForRowState("HAT9", "ERROR", timeoutMillis = 25_000)
        assertTrue(sim.soundFiles().none { it.name == "HAT9.wav" })
    }

    @Test
    fun padsScreen_autoSyncsActiveGroupFromDevice() {
        // Arrange: hardware group buttons emit no MIDI — the app polls the metadata chain
        // /projects active → <project>/groups active → group name
        val sim = EP133DeviceSimulator()
        sim.setActiveGroup('B')
        val repo = connect(sim)
        composeTestRule.launchEP133App(repo)
        val app = AppRobot(composeTestRule)
        // Act: Pads screen polls every 1.5 s while RESUMED
        val pads = app.goToPads()
        // Assert: the grid re-skins to group B without any tap
        pads.waitForText("B.", timeoutMillis = 10_000)
        pads.assertPadLabel("B.")
    }

    @Test
    fun groupChipTap_writesActiveGroupToDeviceMetadata() {
        // Arrange
        val sim = EP133DeviceSimulator()
        val repo = connect(sim)
        composeTestRule.launchEP133App(repo)
        val app = AppRobot(composeTestRule)
        // Act: selecting group C sends a real METADATA SET to the groups node
        app.goToPads().selectGroup("C")
        // Assert
        composeTestRule.waitUntil(10_000) { sim.activeGroupLetter() == 'C' }
        assertEquals('C', sim.activeGroupLetter())
    }

    @Test
    fun padTap_sendsRealNoteFramesToThePort() {
        // Arrange
        val sim = EP133DeviceSimulator()
        val repo = connect(sim)
        composeTestRule.launchEP133App(repo)
        val app = AppRobot(composeTestRule)
        // Act: pad index 9 = "A." = note 36
        app.goToPads().tapPad(9)
        composeTestRule.waitForIdle()
        // Assert: an actual 0x90 frame crossed the wire
        composeTestRule.waitUntil(5_000) { sim.sentNoteOns().isNotEmpty() }
        assertEquals(36, sim.sentNoteOns().first().first)
    }
}
