import SwiftUI

/// SwiftUI port of `AndroidApp/.../ui/projects/ProjectsScreen.kt` (screen + co-located
/// `ProjectsViewModel`).
///
/// Adaptations from Android:
/// - `Context`-based storage → a `baseDirectory` URL (defaulting to the app's Documents
///   directory via `ProjectBackupManager.defaultBaseDirectory()`).
/// - `ShareCompat.IntentBuilder` + FileProvider `content://` URI → SwiftUI `ShareLink` on the
///   backup file URL. iOS derives the share type from the exported `.tar`; `SHARE_MIME` stays
///   as the cross-platform contract constant (asserted by ShareIntentTests).
/// - Material `AlertDialog`s → faceplate-styled overlay dialogs (keeps the theme and lets the
///   restore confirm carry its accessibility identifier, which system alerts can't).

/// MIME type used for sharing a project backup (T-04-09: opaque octet-stream).
let SHARE_MIME = "application/octet-stream"

/// Until the hardware backup→restore round-trip (Open Q2 / UAT-3) passes, the destructive
/// restore action stays disabled. The confirmation dialog is wired regardless; flipping this
/// to `true` after the round-trip is the only change needed to enable the button.
///
/// HARDWARE-GATE (Open Q2): enable restore after the hardware backup→restore round-trip.
private let RESTORE_ENABLED = false

// ── ViewModel ─────────────────────────────────────────────────────────────────

/// ViewModel for the Projects browser + backup library (PROJ-01 / PROJ-03 / PROJ-04).
///
/// Mirrors `DeviceViewModel`: `@MainActor @Observable` with private(set) state properties
/// replacing the Kotlin underscore-prefixed `MutableStateFlow` backing fields, `Task` launches
/// replacing `viewModelScope`, and a snackbar message channel. Backup/restore are driven by
/// iterating `ProjectBackupManager` streams and mapping progress → UI state.
@MainActor
@Observable
class ProjectsViewModel {

    private let midi: MIDIRepository
    @ObservationIgnored private let backupManager: ProjectBackupManager
    @ObservationIgnored private let nameStore: ProjectNameStore

    /// `nameStore` defaults to `InMemoryProjectNameStore` (like the Kotlin default parameter);
    /// nil-coalesced in the body because a `@MainActor` type can't be built in a nonisolated
    /// default-argument context.
    init(
        _ midi: MIDIRepository,
        backupManager: ProjectBackupManager,
        nameStore: ProjectNameStore? = nil
    ) {
        self.midi = midi
        self.backupManager = backupManager
        let store = nameStore ?? InMemoryProjectNameStore()
        self.nameStore = store
        self.projectNames = store.names
    }

    var deviceState: DeviceState { midi.deviceState }

    /// Slot number (1..9) → app-side custom project name (see `ProjectNameStore`).
    /// Observable mirror of the store — the protocol isn't `@Observable`, so every write
    /// republishes the map here (the Kotlin original exposed the store's own StateFlow).
    private(set) var projectNames: [Int: String]

    /// Set (or clear, when blank) the app-side custom name for a slot.
    func setProjectName(slot: MIDIRepository.ProjectSlot, name: String) {
        guard let n = slotNumberOf(slot) else { return }
        nameStore.setName(slot: n, name: name)
        projectNames = nameStore.names
    }

    private func slotNumberOf(_ slot: MIDIRepository.ProjectSlot) -> Int? {
        projectSlotNumber(slot.name)
    }

    private(set) var slots: [MIDIRepository.ProjectSlot] = []
    private(set) var backups: [BackupItem] = []
    private(set) var isBackupInProgress = false
    private(set) var backupProgress: Double = 0
    var snackbarMessage: String?

    /// Node id of the slot currently being cleared (nil = none), so its card shows a busy state.
    private(set) var clearingSlot: Int?

    @ObservationIgnored private var pendingRestoreFile: URL?
    private(set) var showRestoreConfirm = false

    // In-flight task seams — awaited by tests (the advanceUntilIdle analog, matching
    // DeviceViewModel.loadStatsTask).
    @ObservationIgnored private(set) var loadProjectsTask: Task<Void, Never>?
    @ObservationIgnored private(set) var backupTask: Task<Void, Never>?
    @ObservationIgnored private(set) var clearProjectTask: Task<Void, Never>?
    @ObservationIgnored private(set) var restoreTask: Task<Void, Never>?

    /// Empty every pad in all four groups of `slot` (the slot itself survives).
    func clearProject(_ slot: MIDIRepository.ProjectSlot) {
        if clearingSlot != nil { return }
        clearingSlot = slot.nodeId
        clearProjectTask = Task { [weak self] in
            guard let self else { return }
            // clearProject returns -1 on failure and only throws on cancellation.
            let cleared = (try? await midi.clearProject(projectName: slot.name)) ?? -1
            clearingSlot = nil
            if Task.isCancelled { return }
            snackbarMessage = cleared >= 0
                ? "Cleared project \(slot.name) — \(cleared) pads emptied"
                : "Couldn't clear project \(slot.name) — is the EP-133 connected?"
            loadProjects()
        }
    }

    /// Enumerate the 9 device project slots (PROJ-01). No-op when offline → empty list.
    func loadProjects() {
        loadProjectsTask = Task { [weak self] in
            guard let self else { return }
            do {
                slots = try await midi.listProjects()
            } catch is CancellationError {
                // Kotlin rethrows CancellationException; the task just ends.
            } catch {
                snackbarMessage = "Could not list projects: \(error)"
            }
        }
    }

    /// Enumerate the on-disk backup library, newest-first (PROJ-03). Browsable offline.
    /// (Synchronous here — the Swift `listBackups` returns `[]` on failure rather than
    /// throwing, so the Kotlin catch → snackbar branch has no counterpart.)
    func loadBackups(baseDirectory: URL = ProjectBackupManager.defaultBaseDirectory()) {
        backups = backupManager.listBackups(baseDirectory: baseDirectory)
    }

    /// Back up one slot to app storage, surfacing progress and refreshing the library on done.
    func backupSlot(
        _ slot: MIDIRepository.ProjectSlot,
        baseDirectory: URL = ProjectBackupManager.defaultBaseDirectory()
    ) {
        if isBackupInProgress { return }
        let customName = slotNumberOf(slot).flatMap { nameStore.nameFor(slot: $0) }
        isBackupInProgress = true
        backupProgress = 0
        backupTask = Task { [weak self] in
            guard let self else { return }
            for await progress in backupManager.backupProject(
                slot: slot, baseDirectory: baseDirectory, customName: customName
            ) {
                switch progress {
                case .progress(let current, let total):
                    if total > 0 { backupProgress = Double(current) / Double(total) }
                case .done(let file):
                    isBackupInProgress = false
                    backupProgress = 0
                    snackbarMessage = "Backup complete: \(file.lastPathComponent)"
                    loadBackups(baseDirectory: baseDirectory)
                case .error(let message):
                    isBackupInProgress = false
                    backupProgress = 0
                    snackbarMessage = "Backup failed: \(message)"
                }
            }
            // Stream ended without a terminal event (cancellation): never wedge the button.
            isBackupInProgress = false
        }
    }

    /// Stage a restore behind the destructive-action confirmation dialog (T-04-10).
    func requestRestore(file: URL) {
        if isBackupInProgress { return }
        pendingRestoreFile = file
        showRestoreConfirm = true
    }

    /// Run the staged restore after the user confirms (gated by `RESTORE_ENABLED`).
    func confirmRestore(baseDirectory: URL = ProjectBackupManager.defaultBaseDirectory()) {
        guard let file = pendingRestoreFile else { return }
        showRestoreConfirm = false
        pendingRestoreFile = nil
        restoreTask = Task { [weak self] in
            guard let self else { return }
            for await progress in backupManager.restoreProject(file: file, baseDirectory: baseDirectory) {
                switch progress {
                case .progress:
                    break  // progress surfaced via snackbar terminal states
                case .done:
                    snackbarMessage = "Restore complete. Your EP-133 will restart."
                case .error(let message):
                    snackbarMessage = "Restore failed: \(message)"
                }
            }
        }
    }

    func cancelRestore() {
        showRestoreConfirm = false
        pendingRestoreFile = nil
    }

    func dismissSnackbar() {
        snackbarMessage = nil
    }
}

/// Parse a device project-slot number (1..9) from its name. Device names are "01".."09", but
/// mirrors `ProjectBackupManager`'s digit-anywhere match so "P03"-style names still resolve to 3.
func projectSlotNumber(_ name: String) -> Int? {
    guard let range = name.range(of: #"\d{1,2}"#, options: .regularExpression) else { return nil }
    return Int(name[range])
}

/// Format a backup timestamp (epoch millis) for the library row.
private func formatTimestamp(_ epochMillis: Int64) -> String {
    let fmt = DateFormatter()
    fmt.locale = Locale(identifier: "en_US_POSIX")
    fmt.dateFormat = "yyyy-MM-dd HH:mm"
    return fmt.string(from: Date(timeIntervalSince1970: Double(epochMillis) / 1000))
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen — the app shell (EP133Scaffold) owns header + nav; this is the body.
// ─────────────────────────────────────────────────────────────────────────────

/// Hard 2–3pt corner used across the faceplate UI (mirrors the design's `border-radius:2px`).
private let panelRadius: CGFloat = 3

private extension View {
    func projectsPanel(fill: Color, border: Color) -> some View {
        background(RoundedRectangle(cornerRadius: panelRadius).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: panelRadius).strokeBorder(border, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: panelRadius))
    }
}

/// Deterministic 4-bar mini "fill" meter per project/backup — purely decorative, keyed off a
/// seed. `abs` guards the negative-seed case (string hash seeds) that Kotlin left implicit.
private func miniBars(seed: Int) -> [Double] {
    (0..<4).map { i in
        let v = (abs(seed &* 37 &+ i &* 41) % 60) + 40  // 40..99
        return Double(v) / 100.0
    }
}

/// Stable string seed for the decorative bar meter. Swift's `hashValue` is randomized per
/// process (unlike Kotlin's `String.hashCode()`), so a tiny FNV-1a keeps bars deterministic.
private func stableSeed(_ s: String) -> Int {
    var hash: UInt32 = 2_166_136_261
    for byte in s.utf8 {
        hash ^= UInt32(byte)
        hash = hash &* 16_777_619
    }
    return Int(Int32(bitPattern: hash))
}

struct ProjectsScreen: View {
    @Environment(MIDIRepository.self) private var midi
    @State private var viewModel: ProjectsViewModel?

    var body: some View {
        ZStack {
            if let viewModel {
                ProjectsScreenBody(viewModel: viewModel)
            }
        }
        .onAppear {
            // Lazily constructed from the environment repository; @State keeps the VM alive
            // across tab switches (the Projects tab stays mounted in AppShell's ZStack).
            if viewModel == nil {
                viewModel = ProjectsViewModel(
                    midi,
                    backupManager: ProjectBackupManager(midi),
                    nameStore: DefaultsProjectNameStore())
            }
        }
    }
}

private struct ProjectsScreenBody: View {
    @Environment(\.ep133Tokens) private var t
    let viewModel: ProjectsViewModel

    @State private var slotToClear: MIDIRepository.ProjectSlot?
    @State private var slotToRename: MIDIRepository.ProjectSlot?
    @State private var renameDraft = ""

    var body: some View {
        ZStack(alignment: .bottom) {
            t.bg.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 11) {
                    // ── Project slots (PROJ-01) ──
                    HStack {
                        EP133SectionLabel(text: "Projects")
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Text(viewModel.deviceState.connected ? "\(viewModel.slots.count) SLOTS" : "OFFLINE")
                            .font(mono(9.5))
                            .tracking(0.6)
                            .foregroundStyle(viewModel.deviceState.connected ? t.text3 : t.accent)
                    }

                    if !viewModel.deviceState.connected {
                        NotConnectedPanel()
                    } else if viewModel.slots.isEmpty {
                        EmptyNote(text: "No projects found.")
                    } else {
                        ForEach(viewModel.slots, id: \.nodeId) { slot in
                            SlotCard(
                                slot: slot,
                                customName: projectSlotNumber(slot.name)
                                    .flatMap { viewModel.projectNames[$0] },
                                isBackupInProgress: viewModel.isBackupInProgress,
                                backupProgress: viewModel.backupProgress,
                                isClearing: viewModel.clearingSlot == slot.nodeId,
                                onBackup: { viewModel.backupSlot(slot) },
                                onClear: { slotToClear = slot },
                                onRename: {
                                    renameDraft = projectSlotNumber(slot.name)
                                        .flatMap { viewModel.projectNames[$0] } ?? ""
                                    slotToRename = slot
                                }
                            )
                        }
                    }

                    // ── Backup library (PROJ-03) ──
                    EP133SectionLabel(text: "Backup Library")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 5)

                    if viewModel.backups.isEmpty {
                        EmptyNote(text: "No backups yet. Back up a slot to add one.")
                    } else {
                        ForEach(viewModel.backups, id: \.file) { backup in
                            BackupCard(
                                backup: backup,
                                onRestore: { viewModel.requestRestore(file: backup.file) }
                            )
                        }
                    }

                    // ── Restore-gated note (RESTORE_ENABLED) ──
                    RestoreGateNote()
                }
                .padding(14)
            }
            .accessibilityIdentifier(TestTags.PROJECTS_SLOT_LIST)

            // Snackbar analog: bottom toast, auto-dismissed (mirrors DeviceScreen).
            if let message = viewModel.snackbarMessage {
                SnackbarToast(message: message)
                    .padding(.horizontal, 14)
                    .padding(.bottom, 16)
                    .task(id: message) {
                        try? await Task.sleep(nanoseconds: 2_500_000_000)
                        viewModel.dismissSnackbar()
                    }
            }
        }
        .onAppear {
            // LaunchedEffect(Unit) analog — initial enumeration of slots + library.
            viewModel.loadProjects()
            viewModel.loadBackups()
        }
        .onChange(of: viewModel.deviceState.connected) { _, connected in
            // LaunchedEffect(deviceState.connected) analog.
            if connected { viewModel.loadProjects() }
        }
        .overlay { dialogs }
    }

    // ── Dialogs — faceplate overlays replacing the Material AlertDialogs ─────

    @ViewBuilder
    private var dialogs: some View {
        if let slot = slotToClear {
            clearConfirmDialog(slot)
        } else if let slot = slotToRename {
            renameDialog(slot)
        } else if viewModel.showRestoreConfirm {
            restoreConfirmDialog()
        }
    }

    private func clearConfirmDialog(_ slot: MIDIRepository.ProjectSlot) -> some View {
        DialogScrim(onDismiss: { slotToClear = nil }) {
            VStack(alignment: .leading, spacing: 12) {
                Text("Clear project \(slot.name)?")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(t.text)
                Text("Empties every pad in all four groups of project \(slot.name) on the "
                     + "EP-133. The project slot itself stays. This can't be undone.")
                    .font(.system(size: 12))
                    .lineSpacing(3)
                    .foregroundStyle(t.text2)
                HStack(spacing: 4) {
                    Spacer()
                    DialogTextButton(label: "Cancel", color: t.text2) { slotToClear = nil }
                    DialogTextButton(label: "Clear", color: t.accent, bold: true) {
                        viewModel.clearProject(slot)
                        slotToClear = nil
                    }
                }
            }
        }
    }

    private func renameDialog(_ slot: MIDIRepository.ProjectSlot) -> some View {
        DialogScrim(onDismiss: { slotToRename = nil }) {
            VStack(alignment: .leading, spacing: 12) {
                Text("Name project \(slot.name)")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(t.text)
                Text("The name lives in the app and is used as the export/backup filename. "
                     + "The device slot number (\(slot.name)) doesn't change.")
                    .font(.system(size: 12))
                    .lineSpacing(3)
                    .foregroundStyle(t.text2)
                TextField("e.g. Summer Beat Tape", text: $renameDraft)
                    .font(.system(size: 13))
                    .foregroundStyle(t.text)
                    .padding(.vertical, 8)
                    .padding(.horizontal, 10)
                    .projectsPanel(fill: t.inset, border: t.rule)
                    .onChange(of: renameDraft) { _, value in
                        if value.count > 40 { renameDraft = String(value.prefix(40)) }
                    }
                HStack(spacing: 4) {
                    Spacer()
                    DialogTextButton(label: "Cancel", color: t.text2) { slotToRename = nil }
                    DialogTextButton(label: "Save", color: t.accent, bold: true) {
                        viewModel.setProjectName(slot: slot, name: renameDraft)
                        slotToRename = nil
                    }
                }
            }
        }
    }

    private func restoreConfirmDialog() -> some View {
        DialogScrim(onDismiss: { viewModel.cancelRestore() }) {
            VStack(alignment: .leading, spacing: 12) {
                Text("Restore project?")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(t.text)
                Text("This will overwrite the matching slot on your EP-133. This cannot be undone.")
                    .font(.system(size: 12))
                    .lineSpacing(3)
                    .foregroundStyle(t.text2)
                HStack(spacing: 4) {
                    Spacer()
                    DialogTextButton(label: "Cancel", color: t.text2) { viewModel.cancelRestore() }
                    DialogTextButton(label: "Restore", color: t.accent, bold: true) {
                        viewModel.confirmRestore()
                    }
                }
            }
            .accessibilityIdentifier(TestTags.PROJECTS_RESTORE_CONFIRM_DIALOG)
        }
    }
}

// ── Dialog chrome ─────────────────────────────────────────────────────────────

/// Scrim + centered faceplate panel — the overlay analog of the Material AlertDialog, kept
/// on-theme (t.panel container, hard corners) like the Kotlin dialogs' custom colors.
private struct DialogScrim<Content: View>: View {
    @Environment(\.ep133Tokens) private var t
    let onDismiss: () -> Void
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack {
            Color.black.opacity(0.55)
                .ignoresSafeArea()
                .onTapGesture { onDismiss() }
            content()
                .padding(18)
                .frame(maxWidth: 340)
                .projectsPanel(fill: t.panel, border: t.rule)
                .padding(28)
        }
    }
}

/// The Material `TextButton` analog used inside dialogs.
private struct DialogTextButton: View {
    let label: String
    let color: Color
    var bold: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13, weight: bold ? .bold : .regular))
                .foregroundStyle(color)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
        }
        .buttonStyle(.plain)
    }
}

// ── Body panels ───────────────────────────────────────────────────────────────

/// Small mono note on an inset panel — used for empty states.
private struct EmptyNote: View {
    @Environment(\.ep133Tokens) private var t
    let text: String

    var body: some View {
        Text(text)
            .font(mono(11))
            .tracking(0.3)
            .foregroundStyle(t.text3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .projectsPanel(fill: t.inset, border: t.rule)
    }
}

/// "Connect EP-133" inset panel shown when no device is attached.
private struct NotConnectedPanel: View {
    @Environment(\.ep133Tokens) private var t

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "cable.connector")
                .font(.system(size: 30))
                .foregroundStyle(t.text3)
            Text("Connect your EP-133 via USB to browse and back up projects.")
                .font(mono(11.5))
                .tracking(0.3)
                .lineSpacing(4)
                .multilineTextAlignment(.center)
                .foregroundStyle(t.text2)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .projectsPanel(fill: t.inset, border: t.rule)
    }
}

/// A device project slot — faceplate card: name + node id, active dot/badge, a lightweight
/// groups/size summary as mono, a decorative fill meter, and Backup/Clear actions.
private struct SlotCard: View {
    @Environment(\.ep133Tokens) private var t
    let slot: MIDIRepository.ProjectSlot
    let customName: String?
    let isBackupInProgress: Bool
    let backupProgress: Double
    let isClearing: Bool
    let onBackup: () -> Void
    let onClear: () -> Void
    let onRename: () -> Void

    private var hasCustomName: Bool {
        !(customName ?? "").trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            // Name + active badge + slot tag + rename affordance.
            HStack(spacing: 7) {
                if slot.isActive {
                    EP133StatusDot(color: t.live, size: 7)
                }
                Text(hasCustomName ? customName! : "PROJECT \(slot.name)")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(t.text)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                // Device slot tag — shown when a custom name replaces the raw slot number.
                if hasCustomName {
                    Text("P\(slot.name)")
                        .font(mono(9, .semibold))
                        .tracking(0.5)
                        .foregroundStyle(t.text3)
                }
                if slot.isActive {
                    Text("ACTIVE")
                        .font(mono(9, .bold))
                        .tracking(0.6)
                        .foregroundStyle(t.live)
                }
                Button(action: onRename) {
                    Image(systemName: "pencil")
                        .font(.system(size: 13))
                        .foregroundStyle(t.text3)
                        .frame(width: 26, height: 26)
                }
                .buttonStyle(.plain)
                .disabled(isClearing)
                .accessibilityLabel("Rename project \(slot.name)")
            }

            // Lightweight summary (RESEARCH Open Q3 — name + summary, no archive inspection).
            Text(projectSummary(slot))
                .font(mono(9))
                .tracking(0.3)
                .foregroundStyle(t.text3)

            // Decorative 4-bar fill meter, keyed off the slot node id.
            BarMeter(seed: slot.nodeId)

            // Backup + Clear — outline buttons; backup converts to a progress strip while running.
            if isBackupInProgress {
                ProgressStrip(progress: backupProgress)
            } else {
                HStack(spacing: 8) {
                    Button(action: onBackup) {
                        HStack(spacing: 6) {
                            Image(systemName: "square.and.arrow.down")
                                .font(.system(size: 12))
                                .foregroundStyle(t.accent)
                            Text("BACKUP")
                                .font(mono(10, .bold))
                                .tracking(0.6)
                                .foregroundStyle(t.accent)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .projectsPanel(fill: .clear, border: t.rule)
                    }
                    .buttonStyle(.plain)
                    .disabled(isClearing)

                    Button(action: onClear) {
                        Text(isClearing ? "CLEARING…" : "CLEAR")
                            .font(mono(10, .bold))
                            .tracking(0.6)
                            .foregroundStyle(t.text2)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 9)
                            .projectsPanel(fill: .clear, border: t.rule)
                    }
                    .buttonStyle(.plain)
                    .disabled(isClearing)
                }
            }
        }
        .padding(12)
        .projectsPanel(fill: t.panel, border: slot.isActive ? t.live : t.rule)
        .accessibilityIdentifier(TestTags.projectSlot(slot.nodeId))
    }
}

/// A saved backup — faceplate card: name + backup tag, timestamp as mono, decorative bar
/// meter, and share / restore (restore stays disabled until `RESTORE_ENABLED`).
private struct BackupCard: View {
    @Environment(\.ep133Tokens) private var t
    let backup: BackupItem
    let onRestore: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Text(backup.name)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(t.text)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text("BACKUP")
                    .font(mono(9, .semibold))
                    .tracking(0.5)
                    .foregroundStyle(t.accent)
            }

            Text(formatTimestamp(backup.timestamp))
                .font(mono(9))
                .tracking(0.3)
                .foregroundStyle(t.text3)

            BarMeter(seed: stableSeed(backup.name))

            // Actions — share (enabled) + restore (gated until the hardware round-trip passes).
            HStack(spacing: 7) {
                // PROJ-04: the Android FileProvider/ShareCompat flow maps to ShareLink on the
                // backup file URL; the payload stays an opaque octet-stream (SHARE_MIME).
                ShareLink(item: backup.file, preview: SharePreview(backup.name)) {
                    ActionButtonLabel(
                        label: "SHARE", systemImage: "square.and.arrow.up", enabled: true)
                }
                .buttonStyle(.plain)

                Button(action: onRestore) {
                    ActionButtonLabel(
                        label: RESTORE_ENABLED ? "RESTORE" : "RESTORE · SOON",
                        systemImage: "clock.arrow.circlepath",
                        enabled: RESTORE_ENABLED)
                }
                .buttonStyle(.plain)
                .disabled(!RESTORE_ENABLED)
            }
        }
        .padding(12)
        .projectsPanel(fill: t.panel, border: t.rule)
        // Keep the card a container so its SHARE / RESTORE actions stay addressable as their own
        // elements instead of being merged into the card's single accessibility element.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(TestTags.backupCard(backup.name))
    }
}

/// Outline action button face — mono label + leading icon; dims its content when disabled.
private struct ActionButtonLabel: View {
    @Environment(\.ep133Tokens) private var t
    let label: String
    let systemImage: String
    let enabled: Bool

    var body: some View {
        let content = enabled ? t.text : t.text3.opacity(0.5)
        HStack(spacing: 5) {
            Image(systemName: systemImage)
                .font(.system(size: 11))
                .foregroundStyle(content)
            Text(label)
                .font(mono(9, .semibold))
                .tracking(0.5)
                .lineLimit(1)
                .foregroundStyle(content)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 9)
        .projectsPanel(fill: .clear, border: t.rule)
    }
}

/// Lightweight slot summary (RESEARCH Open Q3: v1 = name + active marker + lightweight summary,
/// no full-archive download). Groups A–D + an approximate size, never inspecting the archive.
private func projectSummary(_ slot: MIDIRepository.ProjectSlot) -> String {
    let kb = slot.sizeBytes / 1024
    return kb > 0 ? "GROUPS A–D · \(kb) KB" : "GROUPS A–D"
}

/// Decorative 4-bar fill meter drawn from a deterministic seed.
private struct BarMeter: View {
    @Environment(\.ep133Tokens) private var t
    let seed: Int

    var body: some View {
        HStack(spacing: 3) {
            ForEach(Array(miniBars(seed: seed).enumerated()), id: \.offset) { _, frac in
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        RoundedRectangle(cornerRadius: 2).fill(t.inset)
                        RoundedRectangle(cornerRadius: 2)
                            .fill(t.accent.opacity(0.55))
                            .frame(width: geo.size.width * frac)
                    }
                }
                .frame(height: 4)
            }
        }
    }
}

/// Determinate 12-cell paged progress strip — shown in place of the Backup button while running.
private struct ProgressStrip: View {
    @Environment(\.ep133Tokens) private var t
    let progress: Double

    var body: some View {
        let filled = min(max(Int(progress * 12), 0), 12)
        HStack(spacing: 3) {
            ForEach(0..<12, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(i < filled ? t.accent : t.inset)
                    .frame(height: 7)
            }
        }
    }
}

/// The restore-gated explainer — inset panel with an accent left rail and a mono "!" marker.
private struct RestoreGateNote: View {
    @Environment(\.ep133Tokens) private var t

    var body: some View {
        HStack(alignment: .top, spacing: 11) {
            Text("!")
                .font(mono(11, .bold))
                .foregroundStyle(t.accent)
            VStack(alignment: .leading, spacing: 2) {
                Text("restore is coming.")
                    .font(.system(size: 11.5, weight: .bold))
                    .foregroundStyle(t.text)
                Text("it's gated until we finish hardware testing — we won't ship a feature "
                     + "that can brick your unit.")
                    .font(.system(size: 11.5))
                    .lineSpacing(4)
                    .foregroundStyle(t.text2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: panelRadius).fill(t.inset))
        // The design's `border-left:3px solid` rail, drawn inside the rounded clip.
        .overlay(alignment: .leading) { t.accent.frame(width: 3) }
        .clipShape(RoundedRectangle(cornerRadius: panelRadius))
        .overlay(RoundedRectangle(cornerRadius: panelRadius).strokeBorder(t.rule, lineWidth: 1))
    }
}

// ── Snackbar analog — bottom toast panel (mirrors DeviceScreen's) ─────────────
private struct SnackbarToast: View {
    @Environment(\.ep133Tokens) private var t
    let message: String

    var body: some View {
        Text(message)
            .font(mono(10.5, .medium))
            .tracking(0.3)
            .foregroundStyle(t.text)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 13)
            .padding(.vertical, 11)
            .projectsPanel(fill: t.panel, border: t.rule)
            .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}
