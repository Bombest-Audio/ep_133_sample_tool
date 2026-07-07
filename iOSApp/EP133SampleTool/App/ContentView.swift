import SwiftUI

/// Root view: reads the app-level MIDIRepository from the environment and hands it to the
/// faceplate shell. (The old bare-WebView root now lives behind the Device screen's
/// BACKUP & RESTORE entry point.)
struct ContentView: View {
    @Environment(MIDIRepository.self) private var midi

    /// Firmware catalog override for UI tests; nil → AppShell uses the production catalog.
    var catalog: FirmwareCatalog? = nil

    var body: some View {
        AppShell(midi: midi, catalog: catalog)
    }
}
