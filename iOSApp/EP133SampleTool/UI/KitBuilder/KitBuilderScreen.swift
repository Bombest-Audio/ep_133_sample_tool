import SwiftUI
import AVFoundation

/// SwiftUI port of `AndroidApp/.../ui/kitbuilder/KitBuilderScreen.kt` (screen + co-located
/// `KitBuilderViewModel`).
///
/// Kit Builder: pick a pack folder, browse categories, audition one-shots, assign them
/// pad-first onto the 4×3 canvas, and LOAD the kit to a group. Embedded in the SAMPLES
/// screen's KIT mode; the push action lives in KitScreen's shared PUSH TO DEVICE button.
///
/// Android adaptations:
/// - SAF `OpenDocumentTree` + `takePersistableUriPermission` → SwiftUI folder picker; the
///   folder's security scope is held for the pack's whole browsing lifetime (children of a
///   security-scoped folder are only readable while the parent scope is active).
/// - `MediaPlayer.prepareAsync()` → `AVAudioPlayer` created OFF the main actor, playback
///   started from the completion hop (pinned behavior from the Android commit
///   "prepare auditions asynchronously" — SAF/cloud-backed files can block for seconds).

/// Pads per group — the kit canvas is the full 4×3 grid.
let KB_PAD_COUNT = 12

/// The pad cluster in visual order (top→bottom, left→right). The visual index IS the device
/// gridIndex used by `MIDIRepository.assignSampleToPad` (pad node = "%02d" of index + 1).
let KB_PAD_LABELS = ["7", "8", "9", "4", "5", "6", "1", "2", "3", ".", "0", "ENT"]

/// Visual index → fill-order rank 1..12 (`. 0 ENT 1 2 3 4 5 6 7 8 9`). Used by auto-advance to
/// walk to the next empty pad in the EP-133's natural numeric order.
let KB_FILL_RANK = [10, 11, 12, 7, 8, 9, 4, 5, 6, 1, 2, 3]

/// Per-pad load status while a kit upload runs.
enum KbLoadState {
    case idle, uploading, done, error
}

/// Per-group Kit Builder working state: each A/B/C/D group keeps its own staged kit, pad
/// selection, upload progress, and device mirror. Switching groups never discards a staged kit.
struct KbGroupState {
    var selectedPad: Int = 9                        // visual idx of '.' — first pad in fill order
    var assignments: [Int: KitSample] = [:]
    var loading: Bool = false
    var loadStates: [Int: KbLoadState] = [:]
    var loadedBanner: String? = nil
    var clearingPad: Int? = nil
    // gridIndex → sample name currently bound on the DEVICE for this group (read back from
    // hardware). Distinct from `assignments`, which are staged locally but not yet uploaded.
    var devicePads: [Int: String] = [:]
}

/// Flattened UI state the Kit Builder screen renders: the pack browser globals plus the SELECTED
/// group's `KbGroupState`, with group + choke sourced from the shared `GroupSession`.
struct KitBuilderState {
    var pack: KitPack? = nil
    var packLoading: Bool = false
    var category: String? = nil
    var auditioningURL: URL? = nil
    var group: PadChannel = .A
    var chokeGroup: Bool = true
    var selectedPad: Int = 9
    var assignments: [Int: KitSample] = [:]
    var loading: Bool = false
    var loadStates: [Int: KbLoadState] = [:]
    var loadedBanner: String? = nil
    var clearingPad: Int? = nil
    var devicePads: [Int: String] = [:]
}

/// The pack-browser globals (shared across groups — a pack is a browsing resource, not kit state).
private struct KbGlobals {
    var pack: KitPack? = nil
    var packLoading: Bool = false
    var category: String? = nil
    var auditioningURL: URL? = nil
}

/// Bridges AVAudioPlayer completion/error callbacks back to `stopAudition` (the
/// `setOnCompletionListener` / `setOnErrorListener` analog).
private final class AuditionPlayerDelegate: NSObject, AVAudioPlayerDelegate {
    private let onFinish: @MainActor () -> Void

    init(onFinish: @escaping @MainActor () -> Void) {
        self.onFinish = onFinish
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor [onFinish] in onFinish() }
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        Task { @MainActor [onFinish] in onFinish() }
    }
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@MainActor
@Observable
final class KitBuilderViewModel {
    private let midi: MIDIRepository
    @ObservationIgnored private let manager: SampleImportManager
    private let session: GroupSession

    private var globals = KbGlobals()
    private var groups: [PadChannel: KbGroupState]

    var snackbarMessage: String?

    /// Picker callback — set by KitScreen; presents the folder picker (the SAF
    /// OpenDocumentTree launcher analog).
    @ObservationIgnored var onRequestPackPick: (() -> Void)?

    /// Serializes device-upload + pad-assign calls (see `KitViewModel.deviceMutex`).
    @ObservationIgnored private let deviceMutex = AsyncMutex()

    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private var playerDelegate: AuditionPlayerDelegate?
    /// Invalidates in-flight async audition preparation when a newer audition/stop wins.
    @ObservationIgnored private var auditionGeneration = 0

    /// The picked pack folder whose security scope is currently held (see onPackPicked).
    @ObservationIgnored private var packRootURL: URL?
    @ObservationIgnored private var packRootScoped = false

    /// In-flight work, awaited by tests via `awaitIdle()` (the advanceUntilIdle seam).
    @ObservationIgnored private var pendingTasks: [UUID: Task<Void, Never>] = [:]
    @ObservationIgnored private var lastConnected: Bool

    /// Flattened state for the UI: globals + the selected group's state + session group/choke.
    var state: KitBuilderState {
        let sel = session.selected
        let gs = groups[sel] ?? KbGroupState()
        return KitBuilderState(
            pack: globals.pack,
            packLoading: globals.packLoading,
            category: globals.category,
            auditioningURL: globals.auditioningURL,
            group: sel,
            chokeGroup: session.chokeFor(sel),
            selectedPad: gs.selectedPad,
            assignments: gs.assignments,
            loading: gs.loading,
            loadStates: gs.loadStates,
            loadedBanner: gs.loadedBanner,
            clearingPad: gs.clearingPad,
            devicePads: gs.devicePads
        )
    }

    /// A nil `session` gets a fresh in-memory one (the Kotlin default-argument analog; a
    /// main-actor default value can't be evaluated in Swift's nonisolated default-arg context).
    init(_ midi: MIDIRepository, _ manager: SampleImportManager, session: GroupSession? = nil) {
        self.midi = midi
        self.manager = manager
        self.session = session ?? GroupSession()
        groups = PadChannel.allCases.reduce(into: [:]) { $0[$1] = KbGroupState() }
        lastConnected = midi.deviceState.connected

        // Hydrate the canvas from the device for the current selection (the Kotlin
        // `session.selected.collect` first emission), then re-read on every later selection
        // change; the connection observer below handles plug/unplug edges.
        refreshDevicePads()
        self.session.addSelectionObserver { [weak self] group in
            self?.refreshDevicePads(group)
        }
        observeConnection()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private func updateGroup(_ g: PadChannel, _ block: (inout KbGroupState) -> Void) {
        var gs = groups[g] ?? KbGroupState()
        block(&gs)
        groups[g] = gs
    }

    private func updateCurrent(_ block: (inout KbGroupState) -> Void) {
        updateGroup(session.selected, block)
    }

    /// Spawn tracked main-actor work (removed on completion; drained by `awaitIdle`).
    private func track(_ body: @escaping @MainActor () async -> Void) {
        let id = UUID()
        pendingTasks[id] = Task { [weak self] in
            await body()
            self?.pendingTasks[id] = nil
        }
    }

    /// Await all in-flight work — the test analog of Kotlin's `advanceUntilIdle()`.
    func awaitIdle() async {
        while let (id, task) = pendingTasks.first {
            await task.value
            pendingTasks[id] = nil
        }
    }

    /// Re-hydrate the canvas whenever a unit connects (and clear the mirrors on unplug), so the
    /// pads reflect what's actually loaded instead of a blank staging grid. `@Observable`
    /// tracking loop — the Kotlin `deviceState.map { it.connected }.distinctUntilChanged()`
    /// collector.
    private func observeConnection() {
        let connected = midi.deviceState.connected
        if connected != lastConnected {
            lastConnected = connected
            if connected {
                refreshDevicePads()
            } else {
                for g in PadChannel.allCases {
                    updateGroup(g) { $0.devicePads = [:] }
                }
            }
        }
        withObservationTracking {
            _ = midi.deviceState.connected
        } onChange: { [weak self] in
            Task { @MainActor in self?.observeConnection() }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    func dismissSnackbar() { snackbarMessage = nil }
    func dismissBanner() { updateCurrent { $0.loadedBanner = nil } }

    func triggerPackPick() { onRequestPackPick?() }

    /// Read `group`'s current pad bindings off the device and mirror them on that group's canvas.
    /// Results are keyed by the group they were read for, so a slow read can never land on the
    /// wrong group even if the selection moves mid-flight.
    func refreshDevicePads(_ group: PadChannel? = nil) {
        let g = group ?? session.selected
        track { [weak self] in
            guard let self else { return }
            let pads = (try? await self.midi.readGroupPadState(group: g)) ?? [:]
            self.updateGroup(g) { $0.devicePads = pads }
        }
    }

    /// Folder-picker callback: parse the pack folder into categories.
    ///
    /// Replaces the previous pack's security scope with the new folder's — children of a
    /// security-scoped folder are only readable while the parent scope is active, and
    /// auditions + kit loads happen long after the picker returns, so the scope is held for
    /// the pack's whole browsing lifetime (the `takePersistableUriPermission` analog).
    func onPackPicked(_ rootURL: URL) {
        if packRootScoped { packRootURL?.stopAccessingSecurityScopedResource() }
        packRootURL = rootURL
        packRootScoped = rootURL.startAccessingSecurityScopedResource()

        globals.packLoading = true
        track { [weak self] in
            guard let self else { return }
            let pack = await SamplePackLoader.load(from: rootURL)
            self.applyLoadedPack(pack)
        }
    }

    /// Testability seam: apply an already-parsed pack directly, bypassing the picker + loader.
    func loadPack(_ pack: KitPack) { applyLoadedPack(pack) }

    private func applyLoadedPack(_ pack: KitPack) {
        if pack.isEmpty {
            snackbarMessage = "No audio files found in that folder"
            globals.packLoading = false
            return
        }
        globals.pack = pack
        globals.packLoading = false
        globals.category = pack.categories.first?.id
        updateCurrent {
            $0.loadStates = [:]
            $0.loadedBanner = nil
        }
    }

    func onCategoryChange(_ id: String) { globals.category = id }

    /// Switch the shared group selection (device re-read happens via the session observer).
    func onGroupChange(_ group: PadChannel) { session.select(group) }

    func onChokeGroupChange(_ on: Bool) { session.setChoke(session.selected, on) }
    func onPadSelected(_ index: Int) { updateCurrent { $0.selectedPad = index } }

    /// Clear the selected pad ON THE DEVICE (write `{"sym":0}`), then drop it from the local
    /// canvas. The device may hold a sample the canvas never showed (it's a staging view, not a
    /// live mirror), so this always targets the hardware pad rather than the local map.
    func onClearPad() {
        let group = session.selected
        let pad = (groups[group] ?? KbGroupState()).selectedPad
        updateGroup(group) { $0.clearingPad = pad }
        track { [weak self] in
            guard let self else { return }
            let ok = await (try? self.deviceMutex.withLock {
                try await self.midi.clearPad(group: group, gridIndex: pad)
            }) ?? false
            self.updateGroup(group) {
                $0.clearingPad = nil
                if ok { $0.assignments[pad] = nil }
            }
            self.snackbarMessage = ok
                ? "Cleared pad \(KB_PAD_LABELS[pad]) on group \(group.rawValue)"
                : "Couldn't clear pad \(KB_PAD_LABELS[pad]) — is the EP-133 connected?"
            if ok { self.refreshDevicePads(group) }
        }
    }

    /// Assign `sample` to the selected pad, then auto-advance to the next empty pad in fill order.
    func onAssign(_ sample: KitSample) {
        updateCurrent { s in
            s.assignments[s.selectedPad] = sample
            var next = s.selectedPad
            let curRank = KB_FILL_RANK[s.selectedPad]
            for step in 1...KB_PAD_COUNT {
                let rank = ((curRank - 1 + step) % KB_PAD_COUNT) + 1
                if let idx = KB_FILL_RANK.firstIndex(of: rank), s.assignments[idx] == nil {
                    next = idx
                    break
                }
            }
            s.selectedPad = next
            s.loadStates = [:]
            s.loadedBanner = nil
        }
    }

    /// Toggle audition playback of `sample` (local preview, not the device).
    ///
    /// The AVAudioPlayer is created and prepared OFF the main actor — pack files can live on
    /// slow providers, and the Android port pinned this after `MediaPlayer.prepare()` froze the
    /// UI ("prepare auditions asynchronously"). Playback starts from the completion hop only if
    /// this audition is still the current one.
    func onAudition(_ sample: KitSample) {
        let current = globals.auditioningURL
        stopAudition()
        if current == sample.url { return }   // tapped the playing row's button → just stop

        globals.auditioningURL = sample.url
        auditionGeneration += 1
        let generation = auditionGeneration
        let url = sample.url
        track { [weak self] in
            let prepared: AVAudioPlayer? = await Task.detached(priority: .userInitiated) {
                try? AVAudioSession.sharedInstance().setCategory(.playback)
                let player = try? AVAudioPlayer(contentsOf: url)
                player?.prepareToPlay()
                return player
            }.value
            guard let self, self.auditionGeneration == generation,
                  self.globals.auditioningURL == url else { return }
            guard let prepared else {
                self.snackbarMessage = "Can't play \(sample.name)"
                self.stopAudition()
                return
            }
            let delegate = AuditionPlayerDelegate { [weak self] in self?.stopAudition() }
            prepared.delegate = delegate
            self.player = prepared
            self.playerDelegate = delegate
            prepared.play()
        }
    }

    private func stopAudition() {
        auditionGeneration += 1
        player?.stop()
        player = nil
        playerDelegate = nil
        globals.auditioningURL = nil
    }

    /// Upload every staged sample and bind it to its pad on the group that was selected when the
    /// push started (captured up front — switching groups mid-push must not redirect writes).
    /// Serial (device transfers are single-in-flight); per-pad status drives that group's canvas.
    /// Pads are written with the group's choke setting as `sound.mutegroup`.
    func onLoadKit() {
        let group = session.selected
        let chokeOn = session.chokeFor(group)
        let gs = groups[group] ?? KbGroupState()
        if gs.loading || gs.assignments.isEmpty { return }
        let entries = gs.assignments.sorted { KB_FILL_RANK[$0.key] < KB_FILL_RANK[$1.key] }
        updateGroup(group) {
            $0.loading = true
            $0.loadedBanner = nil
            $0.loadStates = entries.reduce(into: [:]) { $0[$1.key] = KbLoadState.idle }
        }

        track { [weak self] in
            guard let self else { return }
            var ok = 0
            var failed = 0
            for (padIdx, sample) in entries {
                self.updateGroup(group) { $0.loadStates[padIdx] = .uploading }
                var success = false
                do {
                    let converted = try await self.manager.convert(url: sample.url)
                    let frames = converted.pcm.count / 2 / converted.channels
                    let safeName = self.manager.sanitizeName(sample.name + ".wav") ?? "sample.wav"
                    let nodeId = try await self.deviceMutex.withLock {
                        try await self.midi.putSampleFile(
                            name: safeName,
                            pcmBytes: converted.pcm,
                            channels: converted.channels,
                            sampleRate: converted.sampleRate)
                    }
                    if let nodeId {
                        success = try await self.deviceMutex.withLock {
                            try await self.midi.assignSampleToPad(
                                group: group, gridIndex: padIdx, sampleNodeId: nodeId,
                                sampleStart: 0, sampleEnd: frames, muteGroup: chokeOn)
                        }
                    }
                } catch is CancellationError {
                    return
                } catch {
                    self.snackbarMessage = "\(sample.name): \(errorText(error))"
                    success = false
                }
                if success { ok += 1 } else { failed += 1 }
                self.updateGroup(group) {
                    $0.loadStates[padIdx] = success ? .done : .error
                }
            }
            self.updateGroup(group) {
                $0.loading = false
                $0.loadedBanner = failed == 0
                    ? "KIT LOADED → GROUP \(group.rawValue) · \(ok) pads written"
                    : "\(failed) FAILED · \(ok) loaded → GROUP \(group.rawValue)"
            }
            // The just-written pads are now on the device — re-read so the canvas reflects them.
            self.refreshDevicePads(group)
        }
    }
}

/// Short human-readable message for a load/audition failure (the Kotlin `e.message ?: "failed"`).
private func errorText(_ error: Error) -> String {
    switch error {
    case let e as SampleImportError:
        switch e {
        case .cannotRead(let m), .malformedWav(let m), .invalidArgument(let m): return m
        }
    case let e as MIDIRepositoryError:
        if case .deviceRejected(let m) = e { return m }
        return "failed"
    default:
        return "failed"
    }
}

// ── Screen ──────────────────────────────────────────────────────────────────

private let panelRadius: CGFloat = 3

private extension View {
    func kbPanel(fill: Color, border: Color? = nil, radius: CGFloat = panelRadius) -> some View {
        background(RoundedRectangle(cornerRadius: radius).fill(fill))
            .overlay {
                if let border {
                    RoundedRectangle(cornerRadius: radius).strokeBorder(border, lineWidth: 1)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: radius))
    }
}

private let kbPadFilledInk = Color(argb: 0xFF1A1206)
private let kbPadEmptyInk = Color(argb: 0xFFE2E3E4)
private let kbError = Color(argb: 0xFFD0021B)

/// Kit Builder (implements the "Kit Builder" design): pick a pack folder, browse categories,
/// audition one-shots, assign them pad-first onto the 4×3 canvas, and LOAD the kit to a group.
struct KitBuilderScreen: View {
    @Environment(\.ep133Tokens) private var t
    let viewModel: KitBuilderViewModel

    // Clear-pad confirmation. Always available: the pad may hold a sample on the DEVICE that
    // the canvas (a staging view) never showed, so clearing writes {"sym":0} to the hardware pad.
    @State private var confirmClear = false

    var body: some View {
        let s = viewModel.state

        ZStack(alignment: .bottom) {
            VStack(spacing: 0) {
                // One lazy scroll surface for canvas + tabs + samples: the canvas scrolls away
                // and the sample browser gets the full height. Category tabs pin as a sticky
                // header (Compose's stickyHeader → pinnedViews: .sectionHeaders).
                ScrollView {
                    LazyVStack(spacing: 0, pinnedViews: .sectionHeaders) {
                        kitCanvas(s)
                        if let pack = s.pack {
                            Section {
                                sampleList(pack: pack, s: s)
                            } header: {
                                categoryTabs(pack: pack, s: s)
                            }
                        } else {
                            emptyState(s)
                        }
                    }
                }
                .accessibilityIdentifier(TestTags.KB_SAMPLE_LIST)

                // Footer — fill meter + pack switcher. GROUP + CHOKE live in the pinned header
                // shared with CHOP; the push action is the shared PUSH TO DEVICE button below.
                if s.pack != nil {
                    footer(s)
                }
            }
            .background(t.bg)

            // ── Load banner ──
            if let banner = s.loadedBanner {
                loadBanner(banner)
            }

            if let message = viewModel.snackbarMessage {
                KitSnackbarToast(message: message)
                    .padding(.horizontal, 14)
                    .padding(.bottom, 70)
                    .task(id: message) {
                        try? await Task.sleep(nanoseconds: 2_500_000_000)
                        viewModel.dismissSnackbar()
                    }
            }
        }
        // Re-read the device pads whenever the Kit Builder becomes visible, so a kit
        // loaded/edited elsewhere (or before this screen opened) shows up on the canvas.
        .onAppear { viewModel.refreshDevicePads() }
        .overlay {
            if confirmClear {
                clearConfirmDialog(s)
            }
        }
    }

    // ── Kit canvas ────────────────────────────────────────────────────────────

    private func kitCanvas(_ s: KitBuilderState) -> some View {
        VStack(spacing: 9) {
            HStack {
                VStack(alignment: .leading, spacing: 0) {
                    Text("KIT CANVAS · TAP A PAD")
                        .font(mono(9))
                        .tracking(1.4)
                        .foregroundStyle(t.text3)
                    Text(s.pack?.name ?? "no pack loaded")
                        .font(mono(9))
                        .foregroundStyle(t.text2)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                let clearing = s.clearingPad != nil
                Button {
                    if !clearing { confirmClear = true }
                } label: {
                    Text(clearing ? "CLEARING…" : "⌫ CLEAR PAD \(KB_PAD_LABELS[s.selectedPad])")
                        .font(mono(9, .bold))
                        .foregroundStyle(t.accentInk)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .kbPanel(fill: t.panel, border: t.rule)
                }
                .buttonStyle(.plain)
                .disabled(clearing)
                .accessibilityIdentifier(TestTags.KB_CLEAR_PAD_BUTTON)
            }

            ForEach(0..<4, id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(0..<3, id: \.self) { col in
                        let idx = row * 3 + col
                        KbPadCell(
                            label: KB_PAD_LABELS[idx],
                            sample: s.assignments[idx],
                            deviceName: s.devicePads[idx],
                            selected: idx == s.selectedPad,
                            loadState: s.loadStates[idx],
                            onTap: { viewModel.onPadSelected(idx) }
                        )
                        .accessibilityIdentifier(TestTags.kbPadCell(idx))
                    }
                }
            }
        }
        .padding(.init(top: 12, leading: 14, bottom: 10, trailing: 14))
        .background(t.bg)
    }

    // ── Category tabs — sticky so the browser keeps context while the samples scroll ──

    private func categoryTabs(pack: KitPack, s: KitBuilderState) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(pack.categories, id: \.id) { cat in
                    let on = cat.id == s.category
                    HStack(spacing: 6) {
                        Text(cat.id)
                            .font(mono(10.5, .bold))
                            .tracking(0.6)
                            .foregroundStyle(on ? t.onAccent : t.text2)
                        Text("\(cat.samples.count)")
                            .font(mono(8.5, .bold))
                            .foregroundStyle(on ? t.onAccent.opacity(0.75) : t.text3)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .kbPanel(fill: on ? t.accent : t.inset, border: on ? t.accent : t.rule)
                    .contentShape(RoundedRectangle(cornerRadius: panelRadius))
                    .onTapGesture { viewModel.onCategoryChange(cat.id) }
                    .accessibilityIdentifier(TestTags.kbCategoryTab(cat.id))
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
        }
        .background(t.panel)
    }

    // ── Sample list — flattened into the page-level lazy stack ────────────────

    @ViewBuilder
    private func sampleList(pack: KitPack, s: KitBuilderState) -> some View {
        let samples = pack.categories.first { $0.id == s.category }?.samples ?? []
        let urlToPad = Dictionary(
            s.assignments.map { ($1.url, KB_PAD_LABELS[$0]) },
            uniquingKeysWith: { _, new in new }
        )

        HStack {
            Text("\(s.category ?? "") · \(samples.count)")
                .font(mono(9))
                .tracking(1.2)
                .foregroundStyle(t.text3)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text("TAP ROW → PAD \(KB_PAD_LABELS[s.selectedPad])")
                .font(mono(9))
                .tracking(0.8)
                .foregroundStyle(t.text3)
        }
        .padding(.init(top: 8, leading: 14, bottom: 4, trailing: 14))
        .background(t.panel)

        ForEach(samples, id: \.url) { sample in
            sampleRow(sample, playing: s.auditioningURL == sample.url, padLabel: urlToPad[sample.url])
        }
    }

    private func sampleRow(_ sample: KitSample, playing: Bool, padLabel: String?) -> some View {
        HStack(spacing: 11) {
            Button {
                viewModel.onAudition(sample)
            } label: {
                Text(playing ? "■" : "▶")
                    .font(.system(size: 10))
                    .foregroundStyle(playing ? t.onAccent : t.text2)
                    .frame(width: 30, height: 30)
                    .background(Circle().fill(playing ? t.live : t.inset))
                    .overlay(Circle().strokeBorder(playing ? t.live : t.rule, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier(TestTags.kbAuditionButton(sample.name))

            VStack(alignment: .leading, spacing: 0) {
                Text(sample.name)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(t.text)
                    .lineLimit(1)
                Text(sample.meta)
                    .font(mono(9))
                    .foregroundStyle(t.text3)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let padLabel {
                Text("◉ \(padLabel)")
                    .font(mono(9, .bold))
                    .foregroundStyle(t.onAccent)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .kbPanel(fill: t.live)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(playing ? t.inset : t.panel)
        .contentShape(Rectangle())
        .onTapGesture { viewModel.onAssign(sample) }
        // Keep the row a container (children keep their own identifiers) so the audition Button's
        // `kbAuditionButton` id survives instead of being overridden by the row id — otherwise the
        // whole row (button, name, meta) all report the row id and audition can't be targeted.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(TestTags.kbSampleRow(sample.name))
    }

    // ── Empty state — no pack loaded yet ──────────────────────────────────────

    private func emptyState(_ s: KitBuilderState) -> some View {
        VStack(spacing: 6) {
            Text(s.packLoading ? "reading pack…" : "pick a sample-pack folder to start")
                .font(mono(11))
                .foregroundStyle(t.text2)
                .multilineTextAlignment(.center)
            Text("a pack is a folder of category subfolders (KICKS, SNARES, HATS…)")
                .font(mono(9))
                .foregroundStyle(t.text3)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, minHeight: 260)
        .padding(24)
        .background(t.panel)
    }

    // ── Footer — fill meter + pack switcher ───────────────────────────────────

    private func footer(_ s: KitBuilderState) -> some View {
        HStack(spacing: 9) {
            // Fill count + bars
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .bottom, spacing: 0) {
                    Text(String(format: "%02d", s.assignments.count))
                        .font(mono(22, .bold))
                        .foregroundStyle(t.accent)
                        .accessibilityIdentifier(TestTags.KB_ASSIGNED_COUNT)
                    Text("/12")
                        .font(mono(11))
                        .foregroundStyle(t.text3)
                        .padding(.leading, 3)
                        .padding(.bottom, 2)
                }
                HStack(spacing: 2) {
                    ForEach(0..<KB_PAD_COUNT, id: \.self) { i in
                        RoundedRectangle(cornerRadius: 1)
                            .fill(i < s.assignments.count ? t.accent : t.rule)
                            .frame(width: 5, height: 4)
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .kbPanel(fill: t.panel, border: t.rule)

            Text("STAGED FOR GROUP \(s.group.rawValue)")
                .font(mono(9))
                .tracking(1)
                .foregroundStyle(t.text3)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text("PICK A DIFFERENT PACK")
                .font(mono(8.5))
                .tracking(1)
                .foregroundStyle(t.text2)
                .padding(2)
                .contentShape(Rectangle())
                .onTapGesture {
                    if !s.loading { viewModel.triggerPackPick() }
                }
                .accessibilityIdentifier(TestTags.KB_SWITCH_PACK_BUTTON)
        }
        .padding(.init(top: 10, leading: 14, bottom: 10, trailing: 14))
        .background(t.panel2)
    }

    // ── Load banner ───────────────────────────────────────────────────────────

    private func loadBanner(_ banner: String) -> some View {
        HStack {
            Text(banner)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(t.onAccent)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text("OK")
                .font(mono(10, .bold))
                .foregroundStyle(t.onAccent)
                .padding(6)
                .contentShape(Rectangle())
                .onTapGesture { viewModel.dismissBanner() }
        }
        .padding(12)
        .kbPanel(fill: banner.contains("FAILED") ? kbError : t.accent)
        .padding(13)
        .accessibilityIdentifier(TestTags.KB_LOAD_BANNER)
    }

    // ── Clear-pad confirmation dialog ─────────────────────────────────────────
    // A themed overlay (not .alert) so the dialog carries KB_CLEAR_CONFIRM_DIALOG and matches
    // the faceplate panel styling, like the Android AlertDialog with EP-133 colors.

    private func clearConfirmDialog(_ s: KitBuilderState) -> some View {
        let padLabel = KB_PAD_LABELS[s.selectedPad]
        let padSampleName = s.assignments[s.selectedPad]?.name ?? s.devicePads[s.selectedPad]
        let message = if let padSampleName {
            "Removes \"\(padSampleName)\" from pad \(padLabel) of group \(s.group.rawValue) on the EP-133."
        } else {
            "Unbinds whatever sample is on pad \(padLabel) of group \(s.group.rawValue) on the EP-133."
        }

        return ZStack {
            Color.black.opacity(0.5)
                .ignoresSafeArea()
                .onTapGesture { confirmClear = false }
            VStack(alignment: .leading, spacing: 12) {
                Text("Clear pad \(padLabel)?")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(t.text)
                Text(message)
                    .font(.system(size: 12))
                    .foregroundStyle(t.text2)
                HStack {
                    Spacer()
                    Button("Cancel") { confirmClear = false }
                        .font(.system(size: 13))
                        .foregroundStyle(t.text2)
                        .buttonStyle(.plain)
                    Button("Clear") {
                        viewModel.onClearPad()
                        confirmClear = false
                    }
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(t.accent)
                    .buttonStyle(.plain)
                    .padding(.leading, 18)
                }
            }
            .padding(16)
            .kbPanel(fill: t.panel, border: t.rule)
            .padding(.horizontal, 28)
            .accessibilityIdentifier(TestTags.KB_CLEAR_CONFIRM_DIALOG)
        }
    }
}

/// One pad on the kit canvas.
private struct KbPadCell: View {
    @Environment(\.ep133Tokens) private var t
    let label: String
    let sample: KitSample?
    let deviceName: String?
    let selected: Bool
    let loadState: KbLoadState?
    let onTap: () -> Void

    var body: some View {
        let staged = sample != nil
        // A pad already holding a device sample (and not being restaged) reads as "on device": a
        // dark pad face like empty, but with the sample name — distinct from the bright accent of
        // a staged pack sample about to be uploaded.
        let onDevice = !staged && deviceName != nil
        let bg: Color = if loadState == .error {
            kbError
        } else if staged {
            t.accent
        } else {
            t.padFace
        }
        let idInk: Color = if staged {
            kbPadFilledInk
        } else if onDevice {
            t.text2
        } else if selected {
            t.live
        } else {
            t.text3
        }
        let nameText: String = if let sample {
            sample.name
        } else if let deviceName {
            deviceName
        } else if selected {
            "assign →"
        } else {
            "empty"
        }
        let nameInk: Color = if loadState == .error {
            kbPadEmptyInk
        } else if staged {
            kbPadFilledInk
        } else if onDevice {
            t.text2
        } else if selected {
            t.live
        } else {
            t.text3
        }

        VStack(alignment: .leading) {
            HStack(alignment: .top) {
                Text(label)
                    .font(mono(11, .bold))
                    .foregroundStyle(idInk)
                    .frame(maxWidth: .infinity, alignment: .leading)
                switch loadState {
                case .uploading:
                    Text("…").font(mono(10, .bold)).foregroundStyle(kbPadFilledInk)
                case .done:
                    Text("✓").font(mono(10, .bold)).foregroundStyle(kbPadFilledInk.opacity(0.7))
                case .error:
                    Text("✕").font(mono(10, .bold)).foregroundStyle(kbPadEmptyInk)
                default:
                    // Marker for a pad that's occupied on the device but not (re)staged from a pack.
                    if onDevice {
                        Text("◉").font(mono(9, .bold)).foregroundStyle(t.text3)
                    }
                }
            }
            Spacer(minLength: 0)
            Text(nameText)
                .font(mono(9, staged || onDevice ? .semibold : .regular))
                .foregroundStyle(nameInk)
                .lineLimit(2)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity)
        .frame(height: 64)
        .background(RoundedRectangle(cornerRadius: 12).fill(bg))
        .overlay {
            if selected {
                RoundedRectangle(cornerRadius: 12).strokeBorder(t.live, lineWidth: 2)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .contentShape(RoundedRectangle(cornerRadius: 12))
        .onTapGesture(perform: onTap)
    }
}
