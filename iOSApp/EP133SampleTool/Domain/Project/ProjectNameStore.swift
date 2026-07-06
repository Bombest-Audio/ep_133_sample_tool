import Foundation
import Observation

/// App-side custom names for EP-133 project slots. The device has no rename op (slots are fixed,
/// numbered 01..09), so names live only in the app and are applied at export/backup time. Keyed by
/// slot number (1..9) — stable across sessions, unlike the device node id.
///
/// Mirrors AndroidApp/app/src/main/java/com/ep133/sampletool/domain/project/ProjectNameStore.kt.
@MainActor
protocol ProjectNameStore: AnyObject {
    /// slot number (1..9) → custom name. Absent = use the device slot number.
    var names: [Int: String] { get }
    func setName(slot: Int, name: String)
    func clearName(slot: Int)
}

extension ProjectNameStore {
    func nameFor(slot: Int) -> String? {
        guard let name = names[slot], !name.isEmpty else { return nil }
        return name
    }
}

/// In-memory store — the default for tests / previews.
@MainActor
@Observable
final class InMemoryProjectNameStore: ProjectNameStore {
    private(set) var names: [Int: String] = [:]

    func setName(slot: Int, name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            names.removeValue(forKey: slot)
        } else {
            names[slot] = trimmed
        }
    }

    func clearName(slot: Int) {
        names.removeValue(forKey: slot)
    }
}

/// UserDefaults-backed store used by the app (the SharedPreferences analog). Keys are `slot.<n>`.
@MainActor
@Observable
final class DefaultsProjectNameStore: ProjectNameStore {
    private static let keyPrefix = "slot."

    private let defaults: UserDefaults
    private(set) var names: [Int: String]

    /// - Parameter suiteName: override for tests; the app uses the shared suite.
    init(suiteName: String = "ep133_project_names") {
        let defaults = UserDefaults(suiteName: suiteName) ?? .standard
        self.defaults = defaults
        self.names = Self.loadAll(from: defaults)
    }

    private static func loadAll(from defaults: UserDefaults) -> [Int: String] {
        var loaded: [Int: String] = [:]
        for (key, value) in defaults.dictionaryRepresentation() {
            guard key.hasPrefix(keyPrefix),
                  let slot = Int(key.dropFirst(keyPrefix.count)),
                  let name = value as? String, !name.isEmpty else { continue }
            loaded[slot] = name
        }
        return loaded
    }

    func setName(slot: Int, name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            clearName(slot: slot)
            return
        }
        defaults.set(trimmed, forKey: Self.keyPrefix + String(slot))
        names[slot] = trimmed
    }

    func clearName(slot: Int) {
        defaults.removeObject(forKey: Self.keyPrefix + String(slot))
        names.removeValue(forKey: slot)
    }
}
