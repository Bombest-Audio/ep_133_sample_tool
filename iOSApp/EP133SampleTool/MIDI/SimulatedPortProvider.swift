import Foundation

/// Debug-only seam for the Simulated EP-133 toggle.
///
/// Main source references this type (never `EP133DeviceSimulator` directly), so the
/// simulator class stays out of release binaries entirely. Android splits this across
/// src/debug and src/release variant sources; on iOS one file with `#if DEBUG` carries
/// both halves:
/// - Debug: `available = true`, `makePort()` returns a fresh simulator per activation
///   (clean device state each time the toggle turns on).
/// - Release: `available = false`, `makePort()` returns nil — the Device screen never
///   installs the toggle wiring, so the toggle stays hidden and no simulator behavior
///   is compiled in.
enum SimulatedPortProvider {

    #if DEBUG
    static let available = true

    static func makePort() -> MIDIPort? { EP133DeviceSimulator() }
    #else
    static let available = false

    static func makePort() -> MIDIPort? { nil }
    #endif
}
