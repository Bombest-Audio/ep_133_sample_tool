package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.spike.NodeDump
import com.ep133.sampletool.spike.PatternSpikeWalker
import com.ep133.sampletool.support.sim.EP133DeviceSimulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulator E2E for [PatternSpikeWalker] (Phase 6 pattern-write spike, Plan 02 Task 3).
 *
 * Proves the walker enumerates a full seeded subtree (all pages, all children) and — via a
 * seeded positive control — that it WOULD surface a real on-device pattern node if one
 * existed, so a hardware NO-GO is trustworthy evidence rather than an untested walker bug.
 *
 * Mirrors [SimulatedDeviceTest]'s connect()/closeRepos() harness: a REAL [MIDIRepository]
 * speaking real SysEx frames to [EP133DeviceSimulator], nothing above the MIDIPort seam faked.
 */
class PatternSpikeWalkerSimTest {

    private val openRepos = mutableListOf<MIDIRepository>()

    @After
    fun closeRepos() {
        openRepos.forEach { it.close() }
        openRepos.clear()
    }

    private suspend fun connect(sim: EP133DeviceSimulator): MIDIRepository {
        val repo = MIDIRepository(sim)
        repo.refreshDeviceState()
        openRepos += repo
        // refreshDeviceState() fires a fire-and-forget stats query (GREET) on a background
        // scope. Its GREET response calls FileTransferClient.onDeviceGreet(), which fails
        // every in-flight fileWaiters entry — a real race against the walker's own FILE_INIT/
        // LIST calls if they overlap. Settle on the GREET landing before returning, exactly
        // as the Compose test rule's implicit synchronization does for SimulatedDeviceTest.
        withTimeout(5_000) {
            while (repo.deviceState.value.firmwareVersion == null) delay(10)
        }
        return repo
    }

    @Test
    fun walk_enumeratesTheFullSeededSubtree() = runBlocking {
        // Arrange: default sim tree — project 01 (3000) -> groups (3100) -> A..D (3200..3500)
        val sim = EP133DeviceSimulator()
        sim.setActiveProject(3000)
        val repo = connect(sim)

        // Act
        val dump: List<NodeDump> = PatternSpikeWalker(repo).walk(3000)

        // Assert: groups dir + all 4 group dirs are present with correct parentIds and decoded
        // dir flags (READ|WRITE|DIR), and the groups dir's own child count is exhaustive (4).
        val groups = dump.single { it.nodeId == 3100 }
        assertEquals(3000, groups.parentId)
        assertEquals("READ|WRITE|DIR", groups.flagsDecoded)
        assertEquals(4, groups.fileListChildren)

        val groupLetters = listOf(3200, 3300, 3400, 3500)
        groupLetters.forEach { id ->
            val group = dump.single { it.nodeId == id }
            assertEquals(3100, group.parentId)
            assertEquals("READ|WRITE|DIR", group.flagsDecoded)
        }
    }

    @Test
    fun walk_findsASeededPositiveControlPatternNode() = runBlocking {
        // Arrange: seed a fake write-flagged "pattern" FILE node under group A (3200)
        val sim = EP133DeviceSimulator()
        sim.setActiveProject(3000)
        val patternNode = sim.seedPatternNode(groupNode = 3200)
        val repo = connect(sim)

        // Act
        val dump: List<NodeDump> = PatternSpikeWalker(repo).walk(3000)

        // Assert: the walker surfaces it, correctly classified as a write candidate — proving
        // it WOULD find a real pattern node if hardware exposed one.
        val pattern = dump.single { it.nodeId == patternNode.id }
        assertEquals("pattern", pattern.name)
        assertEquals(3200, pattern.parentId)
        assertTrue(
            "seeded pattern node must classify as a write candidate",
            SysExProtocol.isWriteCandidate(pattern.flagsRaw),
        )
        // RESEARCH gap C: a FILE_LIST issued directly against this FILE node is TESTED empty,
        // not assumed — the walker actually probes it.
        assertEquals(0, pattern.fileListChildren)
    }

    @Test
    fun walk_pagesUntilAnEmptyPage() = runBlocking {
        // Arrange: the sim's default groups dir already returns a single non-empty page 0 then
        // an empty page 1 (EP133DeviceSimulator TE_SYSEX_FILE_LIST) — exercise that boundary by
        // seeding one extra child so page 0 has 5 entries, still followed by an empty page 1.
        val sim = EP133DeviceSimulator()
        sim.setActiveProject(3000)
        sim.seedPatternNode(groupNode = 3100, name = "extra-group-child")
        val repo = connect(sim)

        // Act
        val dump: List<NodeDump> = PatternSpikeWalker(repo).walk(3000)

        // Assert: all 5 children of the groups dir (A..D + the extra seeded node) are present —
        // the walker did not stop after a single page.
        val groups = dump.single { it.nodeId == 3100 }
        assertEquals(5, groups.fileListChildren)
        assertTrue(dump.any { it.name == "extra-group-child" })
    }
}
