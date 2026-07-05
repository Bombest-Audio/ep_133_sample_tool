package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.ProjectBackupManager
import com.ep133.sampletool.domain.project.InMemoryProjectNameStore
import com.ep133.sampletool.midi.MIDIPort
import org.junit.Test
import org.junit.Assert.*

/**
 * App-side project naming: the [InMemoryProjectNameStore] contract and the effect of a custom
 * name on [ProjectBackupManager.suggestedProjectFilename]. Device slots have no rename op, so the
 * name lives in the app and only shows up at export time — these tests pin both halves.
 */
class ProjectNameStoreTest {

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

    private fun slot(name: String) =
        MIDIRepository.ProjectSlot(nodeId = 10, name = name, sizeBytes = 2048L, isActive = false)

    private fun manager() = ProjectBackupManager(MIDIRepository(SilentPort()))

    @Test
    fun store_setAndReadBack_thenClearOnBlank() {
        val store = InMemoryProjectNameStore()
        assertNull(store.nameFor(3))

        store.setName(3, "Summer Beat Tape")
        assertEquals("Summer Beat Tape", store.nameFor(3))
        assertEquals("Summer Beat Tape", store.names.value[3])

        // Blank name is treated as a clear.
        store.setName(3, "   ")
        assertNull(store.nameFor(3))
        assertFalse(store.names.value.containsKey(3))
    }

    @Test
    fun store_trimsWhitespace() {
        val store = InMemoryProjectNameStore()
        store.setName(1, "  Drum Kit  ")
        assertEquals("Drum Kit", store.nameFor(1))
    }

    @Test
    fun filename_withoutCustomName_isPlainSlotForm() {
        val name = manager().suggestedProjectFilename(slot("03"))
        assertTrue(name, name.matches(Regex("""EP133-P03-\d{4}-\d{2}-\d{2}-\d{4}\.tar""")))
    }

    @Test
    fun filename_prefixesSanitizedCustomName() {
        val name = manager().suggestedProjectFilename(slot("03"), "Summer Beat Tape")
        assertTrue(name, name.startsWith("Summer_Beat_Tape-EP133-P03-"))
        assertTrue(name.endsWith(".tar"))
    }

    @Test
    fun filename_stripsFilesystemUnsafeCharacters() {
        val name = manager().suggestedProjectFilename(slot("07"), "Lo-Fi/Beats: v2!")
        // '-', '/', ':', '!' removed; remaining word chars kept, spaces → underscores.
        assertTrue(name, name.startsWith("LoFiBeats_v2-EP133-P07-"))
    }

    @Test
    fun filename_blankCustomNameFallsBackToPlainForm() {
        val name = manager().suggestedProjectFilename(slot("02"), "   ")
        assertTrue(name, name.startsWith("EP133-P02-"))
    }
}
