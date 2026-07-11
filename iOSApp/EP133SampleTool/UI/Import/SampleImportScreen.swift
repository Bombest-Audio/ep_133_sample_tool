import SwiftUI
import UniformTypeIdentifiers

/// SwiftUI port of `AndroidApp/.../ui/import/SampleImportScreen.kt` (screen + co-located
/// `SampleImportViewModel`).
///
/// NOTE: like its Android counterpart, this module is NOT mounted anywhere in the app shell —
/// it's a legacy standalone module kept alive by its unit tests (single-sample import now
/// happens through the SAMPLES screen). It compiles, is fully tested, and can be mounted with
/// a one-line `SampleImportScreen()` if the standalone flow ever returns.
///
/// Adaptations from Android:
/// - SAF `OpenMultipleDocuments` + `content://` URIs → `fileImporter` + security-scoped file
///   URLs (`SampleImportManager.importSample` acquires the scope — the Landmine 7 analog).
/// - The `onRequestPick` seam survives: MainActivity set it to launch the SAF picker; here the
///   screen body sets it to present `fileImporter`.

/// One item in the staged-import list.
///
/// Tracks the import state per file: Pending → (Converting →) Loading → Done | Error.
/// `isDone()` and `isError()` support ViewModel test assertions.
struct StagedSample: Equatable {
    let name: String
    var state: StagedSampleState = .pending
    var progress: Double = 0
    var errorMessage: String? = nil

    /// Returns true iff this sample's import completed successfully.
    func isDone() -> Bool { state == .done }
    /// Returns true iff this sample's import failed.
    func isError() -> Bool { state == .error }
}

/// Import lifecycle state for a staged sample row.
enum StagedSampleState {
    case pending
    case converting
    case loading
    case done
    case error
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/// ViewModel for the sample import screen (SAMPLE-01 / SAMPLE-04).
///
/// Co-located with `SampleImportScreen` per project conventions (see CLAUDE.md).
/// Holds the staged-import list and drives per-sample progress via `SampleImportManager`.
/// Mirrors `DeviceViewModel`'s task + observable-state + picker-callback shape.
///
/// Entry points:
/// - `onFilesPicked`: real picker path — seeds rows, drives `SampleImportManager.importSample`
///   for the full convert + upload pipeline (bytes read inside the security scope).
/// - `importStagedBytes`: testability seam — pre-read bytes, no URL/AudioDecoder; used by
///   `SampleImportViewModelTests` without a real device or file picker.
@MainActor
@Observable
class SampleImportViewModel {

    private let midi: MIDIRepository
    @ObservationIgnored private let manager: SampleImportManager

    init(_ midi: MIDIRepository, manager: SampleImportManager) {
        self.midi = midi
        self.manager = manager
    }

    /// Per-file import list: one `StagedSample` per picked file.
    private(set) var stagedSamples: [StagedSample] = []

    /// Set when an import batch completes or fails at the batch level.
    var snackbarMessage: String?

    /// Picker callback — set by the hosting screen before first use (the Android analog was
    /// MainActivity setting the SAF launcher). Invoke to present the file picker.
    @ObservationIgnored var onRequestPick: (() -> Void)?

    /// In-flight import tasks — awaited by tests (the advanceUntilIdle analog, matching
    /// DeviceViewModel.loadStatsTask).
    @ObservationIgnored private(set) var importTasks: [Task<Void, Never>] = []

    /// Trigger the multi-file picker (delegates to the hosting screen's fileImporter).
    func triggerPick() {
        onRequestPick?()
    }

    /// Dismiss the current snackbar message.
    func dismissSnackbar() {
        snackbarMessage = nil
    }

    /// Called when the user completes the file picker.
    ///
    /// Seeds one `StagedSample` per URL then drives `SampleImportManager.importSample` for the
    /// full convert + upload pipeline. The manager reads each URL's bytes inside its
    /// security-scoped access (Landmine 7 — the iOS counterpart of Android's picker-callback
    /// `content://` grant).
    ///
    /// - Parameter urls: URLs returned by `fileImporter` (may be empty if the user cancels).
    func onFilesPicked(urls: [URL]) {
        if urls.isEmpty { return }

        // Derive display names from the URL's last path component (best-effort).
        let newItems = urls.map { url in
            let rawName = url.lastPathComponent
            return StagedSample(name: rawName.isEmpty ? "sample.wav" : rawName)
        }
        let startIndex = stagedSamples.count
        stagedSamples.append(contentsOf: newItems)

        // Launch one task per file (the Kotlin per-URI coroutines).
        for (i, url) in urls.enumerated() {
            let index = startIndex + i
            let rawName = newItems[i].name
            let task = Task { [weak self] in
                guard let self else { return }
                // Set CONVERTING before the decode/convert step.
                updateSample(index) { var s = $0; s.state = .converting; return s }
                await collectImport(manager.importSample(rawName: rawName, url: url), index: index)
            }
            importTasks.append(task)
        }
    }

    /// Testability seam: import a sample from pre-read bytes (no URL, no AudioDecoder).
    ///
    /// Used by `SampleImportViewModelTests` to exercise the state-machine without a real device
    /// or file picker (per 05-VALIDATION Manual-Only section). Adds a `StagedSample` in
    /// `.pending` and launches the upload via `SampleImportManager.importSampleBytes`.
    ///
    /// - Parameters:
    ///   - name:     Raw sample name (will be sanitized by the manager).
    ///   - wavBytes: Pre-read bytes to upload (assumed already in WAV format for tests).
    func importStagedBytes(name: String, wavBytes: Data) {
        // Add pending item at the end of the list.
        let index = stagedSamples.count
        stagedSamples.append(StagedSample(name: name))

        // Launch the import task (the Kotlin onEach{}.launchIn(viewModelScope)).
        let task = Task { [weak self] in
            guard let self else { return }
            await collectImport(manager.importSampleBytes(rawName: name, wavBytes: wavBytes), index: index)
        }
        importTasks.append(task)
    }

    /// Shared progress → row-state mapping for both import flows (the Kotlin `when(progress)`).
    private func collectImport(_ stream: AsyncStream<SampleImportProgress>, index: Int) async {
        for await progress in stream {
            switch progress {
            case .progress(let current, let total):
                let pct = total > 0 ? Double(current) / Double(total) : 0
                updateSample(index) { var s = $0; s.state = .loading; s.progress = pct; return s }
            case .done:
                updateSample(index) { var s = $0; s.state = .done; s.progress = 1; return s }
            case .error(let message):
                updateSample(index) { var s = $0; s.state = .error; s.errorMessage = message; return s }
                snackbarMessage = message
            }
        }
    }

    /// Update a single staged item by index (bounds-checked, like the Kotlin list replacement).
    private func updateSample(_ index: Int, _ transform: (StagedSample) -> StagedSample) {
        guard stagedSamples.indices.contains(index) else { return }
        stagedSamples[index] = transform(stagedSamples[index])
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

/// Hard 2–3pt corner used across the faceplate UI (mirrors the design's `border-radius:2px`).
private let panelRadius: CGFloat = 3

/// Number of page cells in the per-file SysEx progress strip (mirrors the design's 12-cell grid).
private let PAGE_CELLS = 12

private extension View {
    func importPanel(fill: Color, border: Color) -> some View {
        background(RoundedRectangle(cornerRadius: panelRadius).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: panelRadius).strokeBorder(border, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: panelRadius))
    }
}

/// Import screen: pick audio files from device storage and load them onto the EP-133.
///
/// The app shell (`EP133Scaffold`) would own the header + bottom nav; this renders the body
/// only. A faceplate ZStack over the theme tokens backs a drop-zone hint, a scrolling list of
/// `StagedSampleRow` items (each a tactile paged-SysEx strip), a protocol note, and the
/// pick/import primary action.
struct SampleImportScreen: View {
    @Environment(MIDIRepository.self) private var midi
    @State private var viewModel: SampleImportViewModel?

    var body: some View {
        ZStack {
            if let viewModel {
                SampleImportScreenBody(viewModel: viewModel)
            }
        }
        .onAppear {
            // Lazily constructed from the environment repository (same no-arg pattern as the
            // mounted screens), even though nothing mounts this module today.
            if viewModel == nil {
                viewModel = SampleImportViewModel(midi, manager: SampleImportManager(midi))
            }
        }
    }
}

private struct SampleImportScreenBody: View {
    @Environment(\.ep133Tokens) private var t
    let viewModel: SampleImportViewModel
    @State private var showPicker = false

    private var staged: [StagedSample] { viewModel.stagedSamples }

    var body: some View {
        ZStack(alignment: .bottom) {
            t.bg.ignoresSafeArea()

            VStack(spacing: 11) {
                statusHeader
                    .padding(.top, 14)
                dropZone
                stagedList
                pickButton
                    .padding(.bottom, 14)
            }
            .padding(.horizontal, 14)

            snackbarOverlay
        }
        .onAppear {
            // MainActivity wired the SAF launcher into onRequestPick; here the screen fills
            // the same seam with its fileImporter presentation flag.
            viewModel.onRequestPick = { showPicker = true }
        }
        .fileImporter(
            isPresented: $showPicker,
            allowedContentTypes: [.audio],
            allowsMultipleSelection: true
        ) { result in
            if case .success(let urls) = result {
                viewModel.onFilesPicked(urls: urls)
            }
        }
    }

    /// Batch status readout — mono eyebrow + live/done/err tint (count of done / errored files).
    private var statusHeader: some View {
        let doneCount = staged.filter { $0.isDone() }.count
        let errorCount = staged.filter { $0.isError() }.count
        let headLabel: String = if staged.isEmpty {
            "IDLE"
        } else if errorCount > 0 {
            "\(errorCount) ERR · \(doneCount) OK"
        } else if doneCount == staged.count {
            "ALL DONE"
        } else {
            "\(doneCount) / \(staged.count) OK"
        }
        let headColor: Color = if errorCount > 0 {
            t.accent
        } else if !staged.isEmpty && doneCount == staged.count {
            t.live
        } else {
            t.text3
        }
        return HStack {
            EP133SectionLabel(text: "Sample Import")
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(headLabel)
                .font(mono(9.5, .medium))
                .tracking(0.6)
                .foregroundStyle(headColor)
        }
    }

    /// Drop / pick zone — dashed inset panel, conversion is offline-capable.
    private var dropZone: some View {
        VStack(spacing: 6) {
            Text("DROP OR PICK · RIFF/PCM · s16 · ≤20s")
                .font(mono(10))
                .tracking(0.4)
                .multilineTextAlignment(.center)
                .foregroundStyle(t.text3)
            Text("pick WAVs to upload")
                .font(.system(size: 15, weight: .bold))
                .multilineTextAlignment(.center)
                .foregroundStyle(t.text)
        }
        .frame(maxWidth: .infinity)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: panelRadius).fill(t.inset))
        .overlay(
            // The design's `1px dashed` pick affordance, distinct from solid panels.
            RoundedRectangle(cornerRadius: panelRadius)
                .strokeBorder(t.rule, style: StrokeStyle(lineWidth: 1, dash: [6, 4]))
        )
    }

    /// Staged list — one tactile SysEx strip per file. Flexible so the action stays pinned at the
    /// bottom; when empty, the protocol note stays visible and absorbs the slack.
    @ViewBuilder private var stagedList: some View {
        if !staged.isEmpty {
            ScrollView {
                LazyVStack(spacing: 9) {
                    ForEach(Array(staged.enumerated()), id: \.offset) { _, sample in
                        StagedSampleRow(sample: sample)
                    }
                    ProtocolNote()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            VStack {
                ProtocolNote()
                Spacer(minLength: 0)
            }
            .frame(maxHeight: .infinity)
        }
    }

    /// Primary action — pick files / re-pick to stage more.
    private var pickButton: some View {
        EP133PrimaryButton(label: staged.isEmpty ? "PICK FILES" : "PICK MORE FILES") {
            viewModel.triggerPick()
        }
        .accessibilityIdentifier(TestTags.IMPORT_PICK_BUTTON)
    }

    @ViewBuilder private var snackbarOverlay: some View {
        if let message = viewModel.snackbarMessage {
            ImportSnackbarToast(message: message)
                .padding(.horizontal, 14)
                .padding(.bottom, 16)
                .task(id: message) {
                    try? await Task.sleep(nanoseconds: 2_500_000_000)
                    viewModel.dismissSnackbar()
                }
        }
    }
}

/// One row in the staged-import list — a tactile paged-SysEx strip: name + state code, a
/// 12-cell page grid that fills with accent (or terminal teal/accent), and a mono meta/percent
/// footer.
private struct StagedSampleRow: View {
    @Environment(\.ep133Tokens) private var t
    let sample: StagedSample

    var body: some View {
        let stateLabel: String = switch sample.state {
        case .pending: "PENDING"
        case .converting: "CONVERTING"
        case .loading: "LOADING"
        case .done: "DONE"
        case .error: "ERROR"
        }
        let stateColor: Color = switch sample.state {
        case .done: t.live
        case .error: t.accent
        default: t.text2
        }
        // Fill color for completed page cells; terminal states recolor the whole strip.
        let fillColor: Color = switch sample.state {
        case .done: t.live
        default: t.accent
        }
        // How many cells are "filled". Converting shows a small lead-in; Loading tracks progress.
        let filledCells: Int = switch sample.state {
        case .pending: 0
        case .converting: 1
        case .loading, .error: min(max(Int(sample.progress * Double(PAGE_CELLS)), 0), PAGE_CELLS)
        case .done: PAGE_CELLS
        }
        let pct = min(max(Int(sample.progress * 100), 0), 100)
        let footLabel: String = switch sample.state {
        case .pending: "QUEUED"
        case .converting: "DECODE → s16"
        case .loading: "\(pct)%"
        case .done: "100%"
        case .error: sample.errorMessage ?? "FAILED"
        }

        VStack(alignment: .leading, spacing: 9) {
            // File name + state code.
            HStack {
                Text(sample.name)
                    .font(mono(12, .semibold))
                    .foregroundStyle(t.text)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(stateLabel)
                    .font(mono(9.5, .semibold))
                    .tracking(0.4)
                    .foregroundStyle(stateColor)
            }

            // 12-cell paged-SysEx strip — each cell is a page ack.
            HStack(spacing: 3) {
                ForEach(0..<PAGE_CELLS, id: \.self) { i in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(i < filledCells ? fillColor : t.inset)
                        .frame(height: 7)
                }
            }

            // Meta on the left, percent / error on the right.
            HStack {
                Text("SysEx · paged")
                    .font(mono(9))
                    .tracking(0.3)
                    .foregroundStyle(t.text3)
                Spacer(minLength: 8)
                Text(footLabel)
                    .font(mono(9))
                    .tracking(0.3)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .multilineTextAlignment(.trailing)
                    .foregroundStyle(sample.isError() ? t.accent : t.text3)
            }
        }
        .padding(12)
        .importPanel(fill: t.panel, border: t.rule)
        .accessibilityIdentifier(TestTags.importRow(sample.name))
    }
}

/// The paged-SysEx explainer — inset panel with a live accent rail and a mono "i" marker.
private struct ProtocolNote: View {
    @Environment(\.ep133Tokens) private var t

    var body: some View {
        HStack(alignment: .top, spacing: 11) {
            Text("i")
                .font(mono(11, .bold))
                .foregroundStyle(t.live)
            Text("paged SysEx — each page waits for its ack. terminator on finish, so it never "
                 + "wedges the device.")
                .font(.system(size: 11.5))
                .lineSpacing(4)
                .foregroundStyle(t.text2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: panelRadius).fill(t.inset))
        // The design's `border-left:3px solid` rail, drawn inside the rounded clip.
        .overlay(alignment: .leading) { t.live.frame(width: 3) }
        .clipShape(RoundedRectangle(cornerRadius: panelRadius))
        .overlay(RoundedRectangle(cornerRadius: panelRadius).strokeBorder(t.rule, lineWidth: 1))
    }
}

// ── Snackbar analog — bottom toast panel (mirrors DeviceScreen's) ─────────────
private struct ImportSnackbarToast: View {
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
            .importPanel(fill: t.panel, border: t.rule)
            .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}
