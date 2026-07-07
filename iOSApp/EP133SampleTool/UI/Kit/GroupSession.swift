import Foundation
import Observation

/// Which workflow a group is designated for on the SAMPLES page.
///
/// Declared here (not in KitScreen.swift) because `GroupSession` owns the designation map;
/// raw values match the Kotlin enum names so persisted values are compatible.
enum KitMode: String, CaseIterable {
    case chop = "CHOP"
    case kit = "KIT"
}

/// Single source of truth for the SAMPLES page's group state, shared by the Loop Chopper
/// (`KitViewModel`) and the Kit Builder (`KitBuilderViewModel`):
///
/// - which group (A/B/C/D) is selected — one selection for the whole page;
/// - each group's **designation** — is it a CHOP group or a KIT group;
/// - each group's choke setting (`sound.mutegroup` at push time).
///
/// The EP-133 itself has no notion of a group "type" — designation is an app concept that decides
/// which workflow (chop a loop vs. build a kit) targets the group. Selecting a group flips the page
/// into that group's designated mode; toggling CHOP|KIT re-designates the selected group.
///
/// Designation and choke persist across launches via `defaults` when provided (production uses
/// the suite "ep133_group_session" — the Android SharedPreferences file name); a nil `defaults`
/// keeps everything in memory (tests).
///
/// Port of `AndroidApp/.../ui/kit/GroupSession.kt`. StateFlows → `@Observable` stored
/// properties; the Kotlin `session.selected.collect { … }` observer in KitBuilderViewModel maps
/// to `addSelectionObserver` (called synchronously from `select`).
@MainActor
@Observable
final class GroupSession {

    @ObservationIgnored private let defaults: UserDefaults?

    /// The group the whole SAMPLES page is looking at.
    private(set) var selected: PadChannel = .A

    /// Per-group designation: CHOP group or KIT group.
    private(set) var designations: [PadChannel: KitMode]

    /// Per-group choke setting, applied as `sound.mutegroup` on every pad written to the group.
    private(set) var choke: [PadChannel: Bool]

    /// Selection observers — the Swift analog of Kotlin collectors on `session.selected`.
    /// Called synchronously after every `select` with the new group (KitBuilderViewModel
    /// registers its device-pad re-read here).
    @ObservationIgnored private var selectionObservers: [(PadChannel) -> Void] = []

    init(defaults: UserDefaults? = nil) {
        self.defaults = defaults
        designations = PadChannel.allCases.reduce(into: [:]) { map, g in
            let stored = defaults?.string(forKey: Self.designationKey(g))
            map[g] = stored.flatMap(KitMode.init(rawValue:)) ?? .chop
        }
        choke = PadChannel.allCases.reduce(into: [:]) { map, g in
            map[g] = (defaults?.object(forKey: Self.chokeKey(g)) as? Bool) ?? true
        }
    }

    func select(_ group: PadChannel) {
        selected = group
        for observer in selectionObservers { observer(group) }
    }

    func designate(_ group: PadChannel, _ mode: KitMode) {
        designations[group] = mode
        defaults?.set(mode.rawValue, forKey: Self.designationKey(group))
    }

    func setChoke(_ group: PadChannel, _ on: Bool) {
        choke[group] = on
        defaults?.set(on, forKey: Self.chokeKey(group))
    }

    func designationFor(_ group: PadChannel) -> KitMode { designations[group] ?? .chop }
    func chokeFor(_ group: PadChannel) -> Bool { choke[group] ?? true }

    /// Register a callback for subsequent selection changes (fires on `select`, not with the
    /// current value — callers read `selected` directly for the initial state).
    func addSelectionObserver(_ observer: @escaping (PadChannel) -> Void) {
        selectionObservers.append(observer)
    }

    private static func designationKey(_ g: PadChannel) -> String { "designation.\(g.rawValue)" }
    private static func chokeKey(_ g: PadChannel) -> String { "choke.\(g.rawValue)" }
}
