import SwiftUI

/// App entry — mirrors MainActivity's wiring: one MIDIManager (the CoreMIDI port), one
/// MIDIRepository owning it, created once at app level and injected via the environment.
@main
struct EP133SampleToolApp: App {

    @Environment(\.scenePhase) private var scenePhase

    /// Owns the MIDI stack for the app's lifetime (the MainActivity onCreate analog).
    @State private var midiStack = MIDIStack()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(midiStack.repository)
        }
        .onChange(of: scenePhase) { _, phase in
            midiStack.handleScenePhase(phase)
        }
    }
}

/// The MIDIManager → MIDIRepository pair plus foreground/background port handling.
///
/// MainActivity releases the exclusive Android USB-MIDI port in onStop and re-acquires it in
/// onStart. CoreMIDI has no exclusive-port model, so the closest mirror is tearing down the
/// MIDI client on background and rebuilding it on foreground, then re-reading the device list.
/// The very first activation is skipped — init already ran setup (MainActivity's `startedOnce`).
@MainActor
final class MIDIStack {
    let manager: MIDIManager
    let repository: MIDIRepository

    private var startedOnce = false

    init() {
        manager = MIDIManager()
        manager.setup()
        repository = MIDIRepository(manager)
        repository.refreshDeviceState()
    }

    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            if startedOnce {
                manager.setup()
                repository.refreshDeviceState()
            }
            startedOnce = true
        case .background:
            manager.close()
        default:
            break
        }
    }
}
