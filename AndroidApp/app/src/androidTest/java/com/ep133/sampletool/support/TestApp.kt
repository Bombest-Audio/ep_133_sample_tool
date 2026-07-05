package com.ep133.sampletool.support

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.ep133.sampletool.domain.midi.ProjectBackupManager
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.ui.EP133App
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.pads.PadsViewModel
import com.ep133.sampletool.ui.projects.ProjectsViewModel
import com.ep133.sampletool.ui.`import`.SampleImportViewModel

/**
 * Everything a full-app UI test needs: the scripted repo to drive state, the ViewModels to
 * reach test seams (e.g. importStagedBytes), and recorders for the SAF/updater callbacks
 * that MainActivity would normally wire to pickers and Custom Tabs.
 */
class TestAppContext(
    val repo: ScriptedMIDIRepository,
    val padsViewModel: PadsViewModel,
    val projectsViewModel: ProjectsViewModel,
    val deviceViewModel: DeviceViewModel,
    val sampleImportViewModel: SampleImportViewModel,
) {
    val backupRequests = mutableListOf<String>()
    val restoreRequests = mutableListOf<Unit>()
    val pickRequests = mutableListOf<Unit>()
    val firmwareUpdaterOpens = mutableListOf<Unit>()
}

/**
 * Launch the full EP133App shell against a scripted fake — mirrors MainActivity.onCreate wiring
 * (same ViewModel construction, same isConnected binding) minus USB, SAF pickers, and Custom
 * Tabs, which are recorded on the returned [TestAppContext] instead.
 */
fun ComposeContentTestRule.launchEP133App(
    repo: ScriptedMIDIRepository = ScriptedMIDIRepository(),
    catalog: FakeFirmwareCatalog = FakeFirmwareCatalog(),
): TestAppContext {
    val padsViewModel = PadsViewModel(repo)
    val projectsViewModel = ProjectsViewModel(repo, ProjectBackupManager(repo))
    val deviceViewModel = DeviceViewModel(repo, catalog)
    val sampleImportViewModel = SampleImportViewModel(repo, SampleImportManager(repo))
    val ctx = TestAppContext(repo, padsViewModel, projectsViewModel, deviceViewModel, sampleImportViewModel)

    deviceViewModel.onRequestBackup = { name -> ctx.backupRequests += name }
    deviceViewModel.onRequestRestore = { ctx.restoreRequests += Unit }
    deviceViewModel.onOpenFirmwareUpdater = { ctx.firmwareUpdaterOpens += Unit }
    sampleImportViewModel.onRequestPick = { ctx.pickRequests += Unit }

    setContent {
        val state by repo.deviceState.collectAsState()
        EP133App(
            padsViewModel = padsViewModel,
            projectsViewModel = projectsViewModel,
            deviceViewModel = deviceViewModel,
            sampleImportViewModel = sampleImportViewModel,
            isConnected = state.connected,
        )
    }
    return ctx
}
