import SwiftUI

/// Root view: reads the app-level MIDIRepository from the environment and hands it to the
/// faceplate shell. (The old bare-WebView root now lives behind the Device screen's
/// BACKUP & RESTORE entry point.)
struct ContentView: View {
    @Environment(MIDIRepository.self) private var midi

    var body: some View {
        AppShell(midi: midi)
    }
}
