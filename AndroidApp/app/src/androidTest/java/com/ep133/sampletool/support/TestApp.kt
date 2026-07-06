package com.ep133.sampletool.support

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.ProjectBackupManager
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.project.InMemoryProjectNameStore
import com.ep133.sampletool.ui.EP133App
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.kit.GroupSession
import com.ep133.sampletool.ui.kit.KitViewModel
import com.ep133.sampletool.ui.kitbuilder.KitBuilderViewModel
import com.ep133.sampletool.ui.pads.PadsViewModel
import com.ep133.sampletool.ui.projects.ProjectsViewModel

/**
 * Everything a full-app UI test needs: the repo (scripted fake or the real MIDIRepository over
 * the device simulator), the ViewModels to reach test seams, and recorders for the SAF/updater
 * callbacks that MainActivity would normally wire to pickers and Custom Tabs.
 */
class TestAppContext(
    val repo: MIDIRepository,
    val padsViewModel: PadsViewModel,
    val projectsViewModel: ProjectsViewModel,
    val deviceViewModel: DeviceViewModel,
    val kitViewModel: KitViewModel,
    val kitBuilderViewModel: KitBuilderViewModel,
) {
    val backupRequests = mutableListOf<String>()
    val restoreRequests = mutableListOf<Unit>()
    val firmwareUpdaterOpens = mutableListOf<Unit>()
}

/**
 * Launch the full EP133App shell against a fake — mirrors MainActivity.onCreate wiring
 * (same ViewModel construction, same isConnected binding) minus USB, SAF pickers, Custom
 * Tabs, and SharedPreferences (in-memory GroupSession/name store instead).
 *
 * Pass a plain [ScriptedMIDIRepository] to drive UI branches directly, or a real
 * `MIDIRepository(EP133DeviceSimulator())` to run the full protocol stack end-to-end.
 */
fun ComposeContentTestRule.launchEP133App(
    repo: MIDIRepository = ScriptedMIDIRepository(),
    catalog: FakeFirmwareCatalog = FakeFirmwareCatalog(),
): TestAppContext {
    val padsViewModel = PadsViewModel(repo)
    val projectsViewModel = ProjectsViewModel(repo, ProjectBackupManager(repo), InMemoryProjectNameStore())
    val deviceViewModel = DeviceViewModel(repo, catalog)
    val sampleImportManager = SampleImportManager(repo)
    val groupSession = GroupSession() // prefs-less: in-memory group/choke state
    val kitViewModel = KitViewModel(repo, sampleImportManager, groupSession)
    val kitBuilderViewModel = KitBuilderViewModel(repo, sampleImportManager, groupSession)
    val ctx = TestAppContext(
        repo, padsViewModel, projectsViewModel, deviceViewModel, kitViewModel, kitBuilderViewModel,
    )

    deviceViewModel.onRequestBackup = { name -> ctx.backupRequests += name }
    deviceViewModel.onRequestRestore = { ctx.restoreRequests += Unit }
    deviceViewModel.onOpenFirmwareUpdater = { ctx.firmwareUpdaterOpens += Unit }

    setContent {
        val state by repo.deviceState.collectAsState()
        EP133App(
            padsViewModel = padsViewModel,
            projectsViewModel = projectsViewModel,
            deviceViewModel = deviceViewModel,
            kitViewModel = kitViewModel,
            kitBuilderViewModel = kitBuilderViewModel,
            isConnected = state.connected,
        )
    }
    return ctx
}
