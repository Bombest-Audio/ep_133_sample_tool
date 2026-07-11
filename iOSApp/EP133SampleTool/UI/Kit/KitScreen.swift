import SwiftUI
import UniformTypeIdentifiers

/// SwiftUI port of `AndroidApp/.../ui/kit/KitScreen.kt` (screen + co-located `KitViewModel`
/// + chop flow). The SAMPLES tab: loop chopper and drum-kit builder, two workflows behind a
/// CHOP|KIT designation switch, sharing one `GroupSession` with the embedded Kit Builder.
///
/// Android adaptations:
/// - SAF launchers (`OpenDocument` / `OpenMultipleDocuments` / `OpenDocumentTree`, wired in
///   MainActivity) → SwiftUI `.fileImporter`s wired in `KitScreenBody`, feeding the same
///   ViewModel callbacks (`onLoopFilePicked` / `onKitFilesPicked` / `onPackPicked`).
/// - StateFlow pipelines → `@Observable` computed properties (same names/shapes).
/// - `viewModelScope.launch` → tracked main-actor Tasks, awaited by tests via `awaitIdle()`.

// ── Domain constants ──────────────────────────────────────────────────────────

/// Maximum number of pads per group (12 physical pads).
let MAX_SLICES = 12

/// Default slice count for the loop-chopper mode (must be in 1...`MAX_SLICES`).
let DEFAULT_SLICE_COUNT = 8

/// EP-133 per-sample cap: 20s stereo OR 40s mono @ 46875 (firmware 2.5).
/// Byte budget = 20 * 46875 * 2ch * 2bytes.
///
/// The cap is a BYTE budget, not a flat duration — firmware 2.5 explicitly allows mono
/// samples up to 40s (and lower sample rates allow even longer). Guard on slice byte size,
/// never on a flat `frames > 20*sampleRate`, which would wrongly reject valid 20–40s mono.
let MAX_SAMPLE_BYTES = 3_750_000

/// Pad fill order: slice/one-shot index `i` → device pad grid index, so pads populate in the
/// natural EP-133 numeric order **`. 0 ENT 1 2 3 4 5 6 7 8 9`** (bottom row up), not the raw
/// top-left-to-bottom-right grid order.
///
/// The device pad-node index (0-based) maps to physical labels via `EP133Pads.GRID_ORDER`:
/// `0→7, 1→8, 2→9, 3→4, 4→5, 5→6, 6→1, 7→2, 8→3, 9→., 10→0, 11→ENT`
/// (hardware-verified: pad nodes 1–4 = labels 7,8,9,4). Inverting that for the desired label
/// order `[., 0, ENT, 1,2,3,4,5,6,7,8,9]` gives the grid indices below.
let PAD_FILL_ORDER = [9, 10, 11, 6, 7, 8, 3, 4, 5, 0, 1, 2]

// ── UI state model ────────────────────────────────────────────────────────────

/// A loop the user picked but hasn't chopped yet (chop mode) — staged so the slice count can be
/// adjusted before chopping.
struct StagedLoop: Equatable {
    let url: URL
    let name: String
}

enum KitItemState {
    case pending, working, done, error
}

/// State for a single item (one file / one slice assignment) in the result list.
struct KitResultItem: Equatable {
    let label: String
    var state: KitItemState = .pending
    var errorMessage: String? = nil

    /// True iff the item completed successfully.
    var isDone: Bool { state == .done }

    /// True iff the item failed.
    var isError: Bool { state == .error }
}

/// Per-group chop working state (see `KitViewModel.groups`). The group's designation (CHOP/KIT)
/// and choke setting live in `GroupSession` — the source of truth shared with the Kit Builder.
struct GroupState {
    var sliceCountText: String = String(DEFAULT_SLICE_COUNT)
    var stagedLoop: StagedLoop? = nil
    var items: [KitResultItem] = []
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/// ViewModel for the kit screen (loop-chopper + drum-kit builder).
///
/// Co-located with `KitScreen` per project conventions (see CLAUDE.md).
///
/// **Chop mode:** one picked file → slice PCM into N equal byte arrays → for each slice:
/// `putSampleFile` → `assignSampleToPad` with FULL trim (start=0, end=sliceFrameCount).
/// This mirrors kit mode's per-file upload path and respects the EP-133's per-sample size cap
/// (`MAX_SAMPLE_BYTES`): each short slice is well under the limit even when the source loop
/// is not.
///
/// Before any device write, a byte-budget guard fires: if the largest slice exceeds the device's
/// per-sample size cap (`MAX_SAMPLE_BYTES` — 20s stereo / 40s mono @ 46875), all rows are set to
/// Error and no bytes are sent. The cap is a BYTE budget, not a flat duration.
///
/// The domain call sequence is:
///   1. `SampleImportManager.convert` to decode/resample the file to s16/46875.
///   2. `LoopSlicer.slicePcmBytes` to split PCM into N equal byte arrays.
///   3. Byte-budget guard: if any slice > `MAX_SAMPLE_BYTES`, error all rows and return.
///   4. For i in 0..<sliceCount: `MIDIRepository.putSampleFile` with slice bytes → sliceNodeId,
///      then `MIDIRepository.assignSampleToPad` with full trim (start=0, end=sliceFrameCount).
///
/// **Kit mode:** N picked files → for each: convert → putSampleFile → assignSampleToPad with
/// full-trim (start=0, end=frames). Device writes are serialized via `deviceMutex`; decode
/// can overlap across files.
///
/// Entry points:
/// - `onLoopFilePicked`: single-file picker callback for chop mode.
/// - `onKitFilesPicked`: multi-file picker callback for kit mode.
/// - `onSliceCountChange`, `onGroupChange`, `onModeChange`: UI control callbacks.
/// - `chopFromPcm` / `kitFromPcm`: testability seams (no picker, no AudioDecoder).
@MainActor
@Observable
final class KitViewModel {
    private let midi: MIDIRepository
    @ObservationIgnored private let manager: SampleImportManager
    let session: GroupSession

    // Per-group chop working state: each A/B/C/D keeps its own staged loop, slice count, and
    // chop progress. Starting a chop on A and switching to B leaves A running and persisted.
    private var groups: [PadChannel: GroupState]

    // Transient toast — global (not per-group).
    var snackbarMessage: String?

    /// Serializes device-upload + pad-assign calls so concurrent kit uploads don't race on the
    /// single-in-flight transfer guard inside `MIDIRepository`.
    @ObservationIgnored let deviceMutex = AsyncMutex()

    /// Picker callback — set by KitScreen; presents the single-file picker for chop mode.
    @ObservationIgnored var onRequestLoopPick: (() -> Void)?

    /// Picker callback — set by KitScreen; presents the multi-file picker for kit mode.
    @ObservationIgnored var onRequestKitPick: (() -> Void)?

    /// In-flight work, awaited by tests via `awaitIdle()` (the advanceUntilIdle seam).
    @ObservationIgnored private var pendingTasks: [UUID: Task<Void, Never>] = [:]

    /// A nil `session` gets a fresh in-memory one (the Kotlin default-argument analog; a
    /// main-actor default value can't be evaluated in Swift's nonisolated default-arg context).
    init(_ midi: MIDIRepository, _ manager: SampleImportManager, session: GroupSession? = nil) {
        self.midi = midi
        self.manager = manager
        self.session = session ?? GroupSession()
        groups = PadChannel.allCases.reduce(into: [:]) { $0[$1] = GroupState() }
    }

    // ── Derived state (the Kotlin combine/stateIn pipelines as computed properties) ──

    /// Which group's state the UI is showing/editing — owned by the shared GroupSession so the
    /// Kit Builder sees the same selection.
    var selectedGroup: PadChannel { session.selected }

    /// The selected group's designation (CHOP/KIT) — drives which workflow the page shows.
    var mode: KitMode { session.designationFor(session.selected) }

    /// The selected group's choke setting.
    var chokeGroup: Bool { session.chokeFor(session.selected) }

    /// Per-group designations for the whole bar (so each A/B/C/D chip can show its mode tag).
    var designations: [PadChannel: KitMode] { session.designations }

    var sliceCountText: String { groupOf(session.selected).sliceCountText }
    var items: [KitResultItem] { groupOf(session.selected).items }
    var stagedLoop: StagedLoop? { groupOf(session.selected).stagedLoop }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private func groupOf(_ g: PadChannel) -> GroupState { groups[g] ?? GroupState() }

    private func updateGroup(_ g: PadChannel, _ block: (inout GroupState) -> Void) {
        var gs = groups[g] ?? GroupState()
        block(&gs)
        groups[g] = gs
    }

    private func updateCurrent(_ block: (inout GroupState) -> Void) {
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

    // ── UI control callbacks ──────────────────────────────────────────────────

    /// Re-designate the SELECTED group as a CHOP or KIT group.
    func onModeChange(_ mode: KitMode) { session.designate(session.selected, mode) }
    func onGroupChange(_ group: PadChannel) { session.select(group) }
    func onChokeGroupChange(_ on: Bool) { session.setChoke(session.selected, on) }
    func onSliceCountChange(_ text: String) { updateCurrent { $0.sliceCountText = text } }
    func dismissSnackbar() { snackbarMessage = nil }

    /// Trigger the appropriate picker based on the current group's designation.
    func triggerPick() {
        switch session.designationFor(session.selected) {
        case .chop: onRequestLoopPick?()
        case .kit: onRequestKitPick?()
        }
    }

    // ── Chop mode ─────────────────────────────────────────────────────────────

    /// Single-file picker callback (chop mode): stage the picked loop. Chopping is deferred to
    /// `chopStagedLoop` so the user can change the slice count after picking. The fileImporter
    /// URL's security scope is re-entered by `SampleImportManager.convert` at read time, so the
    /// staged URL stays readable until they tap CHOP (the SAF activity-session grant analog).
    func onLoopFilePicked(_ url: URL) {
        let rawName = url.lastPathComponent.isEmpty ? "loop.wav" : url.lastPathComponent
        updateCurrent {
            $0.stagedLoop = StagedLoop(url: url, name: rawName)
            $0.items = []
        }
    }

    /// Chop the selected group's staged loop into its slice count. No-op if nothing is staged.
    func chopStagedLoop() {
        let group = session.selected
        guard let staged = groupOf(group).stagedLoop else { return }
        runChop(group: group, url: staged.url, rawName: staged.name)
    }

    /// Convert → slice → per-slice upload+assign the staged loop.
    ///
    /// Steps:
    /// 1. `SampleImportManager.convert` to decode/resample.
    /// 2. Downmix to mono, then `LoopSlicer.slicePcmBytes` to split PCM into N equal byte arrays.
    /// 3. Byte-budget + storage preflight guards.
    /// 4. For each slice: `MIDIRepository.putSampleFile` → `MIDIRepository.assignSampleToPad`
    ///    (fill order via `PAD_FILL_ORDER`, full trim).
    private func runChop(group: PadChannel, url: URL, rawName: String) {
        let sliceCount = resolvedSliceCountFor(group)
        let chokeOn = session.chokeFor(group)

        // Seed N rows — one per slice (no separate upload row; each row covers its own upload+assign).
        let sliceLabels = (0..<sliceCount).map { "slice \(group.rawValue)\($0 + 1)" }

        // A nil sanitized name means no safe ASCII device filename can be derived — fail up
        // front rather than feeding the raw name into putSampleFile (same contract as
        // SampleImportManager.importSample).
        guard let safeName = manager.sanitizeName(rawName) else {
            let msg = "Unusable file name — rename the file using letters or digits"
            setItems(group, sliceLabels.map { KitResultItem(label: $0, state: .error, errorMessage: msg) })
            snackbarMessage = msg
            return
        }

        setItems(group, sliceLabels.map { KitResultItem(label: $0) })

        track { [weak self] in
            guard let self else { return }
            // Decode/convert while the picked URL is still readable (Landmine 7).
            let converted: ConvertedSample
            do {
                // enforceMaxLength = false: a loop is meant to exceed the single-sample cap; we
                // slice the full PCM and size-check each slice against the device budget instead.
                converted = try await self.manager.convert(url: url, enforceMaxLength: false)
            } catch is CancellationError {
                return
            } catch {
                let msg = importErrorMessage(error) ?? "Convert failed"
                self.setItems(group, sliceLabels.map {
                    KitResultItem(label: $0, state: .error, errorMessage: msg)
                })
                self.snackbarMessage = "Convert failed: \(msg)"
                return
            }

            // Chop uploads MONO to halve the device sample-memory footprint (a drum-kit slice
            // rarely needs stereo). The whole-loop import path keeps the source channels; only
            // chop downmixes here.
            let chopPcm: Data
            let chopChannels: Int
            if converted.channels == 2 {
                chopPcm = LoopSlicer.downmixStereoToMono(Data(converted.pcm))
                chopChannels = 1
            } else {
                chopPcm = Data(converted.pcm)
                chopChannels = converted.channels
            }

            // Slice the PCM bytes into N equal pieces on frame boundaries.
            let slices = LoopSlicer.slicePcmBytes(chopPcm, channels: chopChannels, count: sliceCount)
            let bytesPerFrame = chopChannels * 2

            // Byte-budget guard: reject before any device write if the largest slice exceeds the
            // device's per-sample size cap (a BYTE budget, not a flat duration — see MAX_SAMPLE_BYTES).
            // The slice byte count IS its byte size (sliceFrames * channels * 2).
            let maxSliceBytes = slices.map(\.count).max() ?? 0
            if maxSliceBytes > MAX_SAMPLE_BYTES {
                let msg = "slice exceeds the device's per-sample size limit (~20s stereo / 40s mono) — "
                    + "add more slices or shorten the loop"
                self.setItems(group, sliceLabels.map {
                    KitResultItem(label: $0, state: .error, errorMessage: msg)
                })
                self.snackbarMessage = msg
                return
            }

            // Storage preflight: the device rejects with "not enough space" once /sounds fills.
            // Sum all slices and fail fast with a clear message rather than uploading a partial
            // batch (availableStorageBytes() is nil when device stats are unknown → skip the check).
            let totalUploadBytes = slices.reduce(Int64(0)) { $0 + Int64($1.count) }
            if let availBytes = self.midi.availableStorageBytes(), totalUploadBytes > availBytes {
                let msg = "not enough space on device — chop needs \(totalUploadBytes / 1024) KB, "
                    + "\(availBytes / 1024) KB free. Delete samples on the device or use fewer slices."
                self.setItems(group, sliceLabels.map {
                    KitResultItem(label: $0, state: .error, errorMessage: msg)
                })
                self.snackbarMessage = msg
                return
            }

            // Per-slice upload + assign — same path as kit mode.
            for i in slices.indices {
                let slicePcm = slices[i]
                let sliceFrames = slicePcm.count / bytesPerFrame
                let sliceName = "\(substringBeforeLastDot(safeName))_\(i + 1).wav"
                self.updateItem(group, i) { $0.state = .working }

                let sliceNodeId: Int?
                do {
                    sliceNodeId = try await self.deviceMutex.withLock {
                        try await self.midi.putSampleFile(
                            name: sliceName, pcmBytes: [UInt8](slicePcm),
                            channels: chopChannels, sampleRate: converted.sampleRate)
                    }
                } catch is CancellationError {
                    return
                } catch {
                    let msg = importErrorMessage(error) ?? "Upload failed"
                    self.updateItem(group, i) { $0.state = .error; $0.errorMessage = msg }
                    self.snackbarMessage = "Slice \(i + 1) upload failed: \(msg)"
                    continue
                }

                guard let nodeId = sliceNodeId else {
                    self.updateItem(group, i) {
                        $0.state = .error; $0.errorMessage = "Upload rejected by device"
                    }
                    self.snackbarMessage = "Slice \(i + 1) upload rejected — reconnect and retry"
                    continue
                }

                let ok: Bool
                do {
                    ok = try await self.deviceMutex.withLock {
                        try await self.midi.assignSampleToPad(
                            group: group,
                            gridIndex: i < PAD_FILL_ORDER.count ? PAD_FILL_ORDER[i] : i,
                            sampleNodeId: nodeId, sampleStart: 0, sampleEnd: sliceFrames,
                            muteGroup: chokeOn)
                    }
                } catch is CancellationError {
                    return
                } catch {
                    let msg = importErrorMessage(error) ?? "Assign failed"
                    self.updateItem(group, i) { $0.state = .error; $0.errorMessage = msg }
                    self.snackbarMessage = "Slice \(i + 1) assign failed: \(msg)"
                    continue
                }

                if ok {
                    self.updateItem(group, i) { $0.state = .done }
                } else {
                    self.updateItem(group, i) {
                        $0.state = .error; $0.errorMessage = "Assign rejected by device"
                    }
                }
            }

            let doneCount = self.groupOf(group).items.filter(\.isDone).count
            self.snackbarMessage = "Assigned \(doneCount) / \(sliceCount) slices to group \(group.rawValue)"
        }
    }

    // ── Kit mode ──────────────────────────────────────────────────────────────

    /// Multi-file picker callback (kit mode).
    ///
    /// For each file: convert → putSampleFile → assignSampleToPad(full trim, start=0, end=frames).
    /// Device writes are serialized via `deviceMutex`; decode can overlap across files.
    ///
    /// - Parameter urls: audio file URLs (read within the picker grant; capped to MAX_SLICES).
    func onKitFilesPicked(_ urls: [URL]) {
        if urls.isEmpty { return }
        let capped = Array(urls.prefix(MAX_SLICES))
        let group = session.selected
        let chokeOn = session.chokeFor(group)
        let names = capped.map { $0.lastPathComponent.isEmpty ? "sample.wav" : $0.lastPathComponent }
        setItems(group, names.map { KitResultItem(label: $0) })

        for (i, url) in capped.enumerated() {
            let rawName = names[i]
            track { [weak self] in
                guard let self else { return }
                self.updateItem(group, i) { $0.state = .working }

                // Decode — can overlap with other files' decodes.
                let converted: ConvertedSample
                do {
                    converted = try await self.manager.convert(url: url)
                } catch is CancellationError {
                    return
                } catch {
                    let msg = importErrorMessage(error) ?? "Convert failed"
                    self.updateItem(group, i) { $0.state = .error; $0.errorMessage = msg }
                    self.snackbarMessage = "Convert failed for \(rawName): \(msg)"
                    return
                }

                // Nil sanitized name = no safe device filename derivable — error this item
                // rather than uploading the raw name.
                guard let safeName = self.manager.sanitizeName(rawName) else {
                    self.updateItem(group, i) {
                        $0.state = .error; $0.errorMessage = "Unusable file name"
                    }
                    self.snackbarMessage = "Unusable file name: \(rawName) — rename it using letters or digits"
                    return
                }
                let frames = converted.pcm.count / 2 / converted.channels

                await self.uploadAndAssign(
                    group: group, index: i, name: safeName, pcm: Data(converted.pcm),
                    channels: converted.channels, sampleRate: converted.sampleRate,
                    frames: frames, chokeOn: chokeOn, notifyViaSnackbar: true)
            }
        }
    }

    // ── Testability seams ─────────────────────────────────────────────────────

    /// Testability seam for chop mode: exercise the ViewModel state-machine without the picker
    /// or AudioDecoder. Pre-converted PCM is passed directly to the per-slice upload + assign
    /// pipeline.
    ///
    /// Mirrors `chopStagedLoop` exactly: slices PCM via `LoopSlicer.slicePcmBytes`, applies the
    /// byte-budget guard (`MAX_SAMPLE_BYTES`), then for each slice calls
    /// `MIDIRepository.putSampleFile` → `MIDIRepository.assignSampleToPad` with full trim
    /// (start=0, end=sliceFrameCount).
    ///
    /// - Parameters:
    ///   - name: Sample name (will be sanitized).
    ///   - pcm: Raw s16 LE PCM bytes (no RIFF header).
    ///   - channels: 1 or 2.
    ///   - sampleRate: Device sample rate (default 46875).
    func chopFromPcm(name: String, pcm: Data, channels: Int = 1, sampleRate: Int = 46875) {
        let group = session.selected
        let sliceCount = resolvedSliceCountFor(group)
        let chokeOn = session.chokeFor(group)

        let sliceLabels = (0..<sliceCount).map { "slice \(group.rawValue)\($0 + 1)" }

        // Same contract as runChop: a nil sanitized name is an error, not a fallback.
        guard let safeName = manager.sanitizeName(name) else {
            let msg = "Unusable file name — rename the file using letters or digits"
            setItems(group, sliceLabels.map { KitResultItem(label: $0, state: .error, errorMessage: msg) })
            snackbarMessage = msg
            return
        }

        setItems(group, sliceLabels.map { KitResultItem(label: $0) })

        track { [weak self] in
            guard let self else { return }
            // Slice the PCM bytes into N equal pieces on frame boundaries.
            let slices = LoopSlicer.slicePcmBytes(pcm, channels: channels, count: sliceCount)
            let bytesPerFrame = channels * 2

            // Byte-budget guard: reject before any device write if the largest slice exceeds the
            // device's per-sample size cap (a BYTE budget, not a flat duration — see MAX_SAMPLE_BYTES).
            // The slice byte count IS its byte size (sliceFrames * channels * 2).
            let maxSliceBytes = slices.map(\.count).max() ?? 0
            if maxSliceBytes > MAX_SAMPLE_BYTES {
                let msg = "slice exceeds the device's per-sample size limit (~20s stereo / 40s mono) — "
                    + "add more slices or shorten the loop"
                self.setItems(group, sliceLabels.map {
                    KitResultItem(label: $0, state: .error, errorMessage: msg)
                })
                self.snackbarMessage = msg
                return
            }

            // Per-slice upload + assign.
            for i in slices.indices {
                let slicePcm = slices[i]
                let sliceFrames = slicePcm.count / bytesPerFrame
                let sliceName = "\(substringBeforeLastDot(safeName))_\(i + 1).wav"
                self.updateItem(group, i) { $0.state = .working }

                let sliceNodeId: Int?
                do {
                    sliceNodeId = try await self.deviceMutex.withLock {
                        try await self.midi.putSampleFile(
                            name: sliceName, pcmBytes: [UInt8](slicePcm),
                            channels: channels, sampleRate: sampleRate)
                    }
                } catch is CancellationError {
                    return
                } catch {
                    let msg = importErrorMessage(error) ?? "Upload failed"
                    self.updateItem(group, i) { $0.state = .error; $0.errorMessage = msg }
                    self.snackbarMessage = "Slice \(i + 1) upload failed: \(msg)"
                    continue
                }

                guard let nodeId = sliceNodeId else {
                    self.updateItem(group, i) {
                        $0.state = .error; $0.errorMessage = "Upload rejected by device"
                    }
                    self.snackbarMessage = "Slice \(i + 1) upload rejected — reconnect and retry"
                    continue
                }

                let ok: Bool
                do {
                    ok = try await self.deviceMutex.withLock {
                        try await self.midi.assignSampleToPad(
                            group: group,
                            gridIndex: i < PAD_FILL_ORDER.count ? PAD_FILL_ORDER[i] : i,
                            sampleNodeId: nodeId, sampleStart: 0, sampleEnd: sliceFrames,
                            muteGroup: chokeOn)
                    }
                } catch is CancellationError {
                    return
                } catch {
                    let msg = importErrorMessage(error) ?? "Assign failed"
                    self.updateItem(group, i) { $0.state = .error; $0.errorMessage = msg }
                    continue
                }
                if ok {
                    self.updateItem(group, i) { $0.state = .done }
                } else {
                    self.updateItem(group, i) {
                        $0.state = .error; $0.errorMessage = "Assign rejected by device"
                    }
                }
            }
        }
    }

    /// Testability seam for kit mode: exercise the ViewModel state-machine without the picker
    /// or AudioDecoder. Pre-converted PCM list is passed directly to the upload + assign pipeline.
    ///
    /// - Parameters:
    ///   - files: List of (name, pcm, channels).
    ///   - sampleRate: Device sample rate (default 46875).
    func kitFromPcm(_ files: [(String, Data, Int)], sampleRate: Int = 46875) {
        if files.isEmpty { return }
        let capped = Array(files.prefix(MAX_SLICES))
        let group = session.selected
        let chokeOn = session.chokeFor(group)
        setItems(group, capped.map { KitResultItem(label: $0.0) })

        for (i, file) in capped.enumerated() {
            let (rawName, pcm, channels) = file
            let frames = pcm.count / 2 / channels
            track { [weak self] in
                guard let self else { return }
                // Same contract as onKitFilesPicked: a nil sanitized name is an error, not a fallback.
                guard let safeName = self.manager.sanitizeName(rawName) else {
                    self.updateItem(group, i) {
                        $0.state = .error; $0.errorMessage = "Unusable file name"
                    }
                    return
                }
                self.updateItem(group, i) { $0.state = .working }

                await self.uploadAndAssign(
                    group: group, index: i, name: safeName, pcm: pcm,
                    channels: channels, sampleRate: sampleRate, frames: frames,
                    chokeOn: chokeOn, notifyViaSnackbar: false)
            }
        }
    }

    /// Shared kit-mode tail: putSampleFile → assignSampleToPad(full trim) for one file, driving
    /// item `index`'s state. `notifyViaSnackbar` matches the Kotlin split: the picker path
    /// surfaces failures as toasts, the PCM test seam only marks the row.
    private func uploadAndAssign(
        group: PadChannel,
        index: Int,
        name: String,
        pcm: Data,
        channels: Int,
        sampleRate: Int,
        frames: Int,
        chokeOn: Bool,
        notifyViaSnackbar: Bool
    ) async {
        // Upload + assign serialized via deviceMutex.
        let sampleNodeId: Int?
        do {
            sampleNodeId = try await deviceMutex.withLock {
                try await self.midi.putSampleFile(
                    name: name, pcmBytes: [UInt8](pcm), channels: channels, sampleRate: sampleRate)
            }
        } catch is CancellationError {
            return
        } catch {
            let msg = importErrorMessage(error) ?? "Upload failed"
            updateItem(group, index) { $0.state = .error; $0.errorMessage = msg }
            if notifyViaSnackbar { snackbarMessage = "Upload failed for \(name): \(msg)" }
            return
        }

        guard let nodeId = sampleNodeId else {
            updateItem(group, index) { $0.state = .error; $0.errorMessage = "Upload rejected by device" }
            if notifyViaSnackbar { snackbarMessage = "Upload rejected for \(name) — reconnect and retry" }
            return
        }

        let ok: Bool
        do {
            ok = try await deviceMutex.withLock {
                try await self.midi.assignSampleToPad(
                    group: group,
                    gridIndex: index < PAD_FILL_ORDER.count ? PAD_FILL_ORDER[index] : index,
                    sampleNodeId: nodeId, sampleStart: 0, sampleEnd: frames, muteGroup: chokeOn)
            }
        } catch is CancellationError {
            return
        } catch {
            let msg = importErrorMessage(error) ?? "Assign failed"
            updateItem(group, index) { $0.state = .error; $0.errorMessage = msg }
            if notifyViaSnackbar { snackbarMessage = "Assign failed for \(name): \(msg)" }
            return
        }

        if ok {
            updateItem(group, index) { $0.state = .done }
        } else {
            updateItem(group, index) { $0.state = .error; $0.errorMessage = "Assign rejected by device" }
        }
    }

    // ── Slice count / items plumbing ──────────────────────────────────────────

    /// Slice count for `group`, clamped to [1, MAX_SLICES]. MAX_SLICES on parse failure.
    private func resolvedSliceCountFor(_ group: PadChannel) -> Int {
        Int(groupOf(group).sliceCountText).map { min(max($0, 1), MAX_SLICES) } ?? MAX_SLICES
    }

    /// Slice count for the currently-selected group (used by the UI labels).
    func resolvedSliceCount() -> Int { resolvedSliceCountFor(session.selected) }

    /// Replace `group`'s progress items.
    private func setItems(_ group: PadChannel, _ items: [KitResultItem]) {
        updateGroup(group) { $0.items = items }
    }

    /// Update a single item of `group` by index.
    func updateItem(_ group: PadChannel, _ index: Int, _ transform: (inout KitResultItem) -> Void) {
        updateGroup(group) { gs in
            guard gs.items.indices.contains(index) else { return }
            transform(&gs.items[index])
        }
    }
}

/// Kotlin `substringBeforeLast('.')` for slice-name derivation.
private func substringBeforeLastDot(_ s: String) -> String {
    guard let idx = s.lastIndex(of: ".") else { return s }
    return String(s[..<idx])
}

/// Short human-readable message for a convert/upload failure (the Kotlin `e.message` analog).
/// Nil when the error carries no useful text — callers substitute their step-specific default.
/// Message for a known domain error, or nil so the caller can supply a context-specific
/// fallback ("Convert failed" / "Upload failed" / "Assign failed"). Messages live on the
/// error enums via `LocalizedError`.
private func importErrorMessage(_ error: Error) -> String? {
    (error as? LocalizedError)?.errorDescription
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

private let panelRadius: CGFloat = 3

private extension View {
    func kitPanel(fill: Color, border: Color, radius: CGFloat = panelRadius) -> some View {
        background(RoundedRectangle(cornerRadius: radius).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: radius).strokeBorder(border, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: radius))
    }
}

/// One pad in the slice selector: its printed label and its 1-based fill-order rank.
private struct SlicePad {
    let label: String
    let order: Int
}

/// The EP-133 pad cluster in device layout (top→bottom, left→right), each pad tagged with its
/// position in the fill order — ranks 1..12 = `. 0 ENT 1 2 3 4 5 6 7 8 9`, matching
/// `PAD_FILL_ORDER`. Tapping a pad sets the slice count to its rank, so the grid previews
/// exactly which pads the slices land on and in what order.
private let SLICE_PAD_GRID = [
    SlicePad(label: "7", order: 10), SlicePad(label: "8", order: 11), SlicePad(label: "9", order: 12),
    SlicePad(label: "4", order: 7), SlicePad(label: "5", order: 8), SlicePad(label: "6", order: 9),
    SlicePad(label: "1", order: 4), SlicePad(label: "2", order: 5), SlicePad(label: "3", order: 6),
    SlicePad(label: ".", order: 1), SlicePad(label: "0", order: 2), SlicePad(label: "ENT", order: 3),
]

// Ink on a filled (accent) pad — a warm near-black for contrast, regardless of app theme.
private let padFilledInk = Color(argb: 0xFF1A1206)
// Label on an empty (dark) pad — a fixed light, since the pad face is always dark like the device.
private let padEmptyInk = Color(argb: 0xFFE2E3E4)

/// The MainActivity wiring for the SAMPLES page, bundled so KitScreen can own it as one
/// `@State`: ONE `SampleImportManager` and ONE `GroupSession` (persisted in the
/// "ep133_group_session" defaults suite) shared by both ViewModels — group selection,
/// per-group CHOP/KIT designation, and choke are a single source of truth.
@MainActor
@Observable
final class KitScreenModel {
    let kitViewModel: KitViewModel
    let builderViewModel: KitBuilderViewModel

    init(midi: MIDIRepository) {
        let manager = SampleImportManager(midi)
        // Production persists the group session (designation + choke) in the shared defaults
        // suite; a UI-test launch uses a fresh in-memory session so a prior test's KIT/choke
        // state can't leak into the next launch (the Android tests' `GroupSession()` analog).
        let session: GroupSession
        #if DEBUG
        session = UITestConfig.useEphemeralGroupSession
            ? GroupSession()
            : GroupSession(defaults: UserDefaults(suiteName: "ep133_group_session"))
        #else
        session = GroupSession(defaults: UserDefaults(suiteName: "ep133_group_session"))
        #endif
        kitViewModel = KitViewModel(midi, manager, session: session)
        builderViewModel = KitBuilderViewModel(midi, manager, session: session)
        #if DEBUG
        // Seed a deterministic pack so the Kit Builder browser is reachable out-of-process
        // (the `loadPack(testPack())` seam the Android instrumented test uses in-process).
        if UITestConfig.seedKitBuilderPack {
            builderViewModel.loadPack(UITestConfig.makeTestPack())
        }
        #endif
    }
}

/// Kit screen: loop chopper and drum-kit builder, two modes behind a segmented control.
///
/// The app shell owns the header + bottom nav; this renders the body only. The shell keeps
/// this tab mounted across switches, so the `@State` model (both ViewModels + the shared
/// GroupSession) survives like Android's activity-scoped ViewModels.
struct KitScreen: View {
    @Environment(MIDIRepository.self) private var midi
    @State private var model: KitScreenModel?

    var body: some View {
        ZStack {
            if let model {
                KitScreenBody(
                    viewModel: model.kitViewModel,
                    builderViewModel: model.builderViewModel
                )
            }
        }
        .onAppear {
            if model == nil { model = KitScreenModel(midi: midi) }
        }
    }
}

private struct KitScreenBody: View {
    @Environment(\.ep133Tokens) private var t
    let viewModel: KitViewModel
    let builderViewModel: KitBuilderViewModel

    @State private var showLoopPicker = false
    @State private var showKitPicker = false
    @State private var showPackPicker = false

    var body: some View {
        let mode = viewModel.mode
        let selectedGroup = viewModel.selectedGroup
        let items = viewModel.items
        let stagedLoop = viewModel.stagedLoop
        let builderState = builderViewModel.state

        ZStack(alignment: .bottom) {
            t.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                // Header + mode switch stay pinned above both modes.
                VStack(spacing: 11) {
                    headerRow(mode: mode, items: items)
                    modeSwitch(mode: mode)
                    // Shared group + choke bar — ONE control backed by the shared GroupSession,
                    // so both workflows always agree on the selected group and its choke. Each
                    // chip is tagged with its group's designation; selecting a group flips the
                    // page into that group's mode.
                    EP133GroupChokeBar(
                        channels: PadChannel.allCases,
                        group: selectedGroup,
                        onGroupChange: { viewModel.onGroupChange($0) },
                        chokeOn: viewModel.chokeGroup,
                        onChokeChange: { viewModel.onChokeGroupChange($0) },
                        label: { $0.rawValue },
                        tagFor: { viewModel.designations[$0]?.rawValue },
                        testTagFor: { TestTags.groupChip($0.rawValue) }
                    )
                }
                .padding(.horizontal, 14)
                .padding(.top, 14)

                if mode == .kit {
                    // KIT mode = the Kit Builder (pack browser + kit canvas), embedded
                    // edge-to-edge. The push action lives in the shared PUSH TO DEVICE below.
                    KitBuilderScreen(viewModel: builderViewModel)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .padding(.top, 11)
                } else {
                    // Scrollable chop content; the PUSH button below stays pinned so it's
                    // always reachable.
                    ScrollView {
                        VStack(spacing: 11) {
                            // The pad grid is both the chop slice-count SELECTOR (idle) and the
                            // upload PROGRESS indicator (once a chop or kit build is
                            // running/done). One grid, two modes.
                            if !items.isEmpty {
                                ChopProgress(items: items)
                            } else {
                                SlicePadSelector(
                                    count: Int(viewModel.sliceCountText)
                                        .map { min(max($0, 1), MAX_SLICES) } ?? DEFAULT_SLICE_COUNT,
                                    onCountChange: { viewModel.onSliceCountChange(String($0)) }
                                )
                            }
                            pickPanel(mode: mode, stagedLoop: stagedLoop, selectedGroup: selectedGroup)
                        }
                        .padding(.horizontal, 14)
                        .padding(.top, 11)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }

                // Shared PUSH TO DEVICE button — pinned below both workflows. In a CHOP group it
                // picks then chops the staged loop; in a KIT group it picks a pack then loads the
                // staged kit.
                pushButton(mode: mode, stagedLoop: stagedLoop,
                           selectedGroup: selectedGroup, builderState: builderState)
                    .padding(.horizontal, 14)
                    .padding(.top, 11)
                    .padding(.bottom, 14)
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
        .onAppear {
            // The MainActivity SAF-launcher wiring: the ViewModel callbacks present SwiftUI
            // file importers.
            viewModel.onRequestLoopPick = { showLoopPicker = true }
            viewModel.onRequestKitPick = { showKitPicker = true }
            builderViewModel.onRequestPackPick = { showPackPicker = true }
        }
        // Each importer is attached to its own host view — SwiftUI supports only one
        // presentation modifier per view.
        .background(
            Color.clear.fileImporter(
                isPresented: $showLoopPicker,
                allowedContentTypes: [.audio]
            ) { result in
                if case .success(let url) = result { viewModel.onLoopFilePicked(url) }
            }
        )
        .background(
            Color.clear.fileImporter(
                isPresented: $showKitPicker,
                allowedContentTypes: [.audio],
                allowsMultipleSelection: true
            ) { result in
                if case .success(let urls) = result { viewModel.onKitFilesPicked(urls) }
            }
        )
        .background(
            Color.clear.fileImporter(
                isPresented: $showPackPicker,
                allowedContentTypes: [.folder]
            ) { result in
                if case .success(let url) = result { builderViewModel.onPackPicked(url) }
            }
        )
    }

    // ── Header row: section label + batch status (chop-only status) ───────────

    private func headerRow(mode: KitMode, items: [KitResultItem]) -> some View {
        let doneCount = items.filter(\.isDone).count
        let errorCount = items.filter(\.isError).count
        let headLabel: String = if items.isEmpty {
            "IDLE"
        } else if errorCount > 0 {
            "\(errorCount) ERR · \(doneCount) OK"
        } else if doneCount == items.count {
            "ALL DONE"
        } else {
            "\(doneCount) / \(items.count) OK"
        }
        let headColor: Color = if errorCount > 0 {
            t.accent
        } else if !items.isEmpty && doneCount == items.count {
            t.live
        } else {
            t.text3
        }

        return HStack {
            EP133SectionLabel(text: mode == .chop ? "Loop Chopper" : "Kit Builder")
            Spacer()
            if mode == .chop {
                Text(headLabel)
                    .font(mono(9.5, .medium))
                    .tracking(0.6)
                    .foregroundStyle(headColor)
            }
        }
    }

    // ── Designation segmented control: CHOP | KIT — sets what the SELECTED group is ──

    private func modeSwitch(mode: KitMode) -> some View {
        HStack(spacing: 6) {
            ForEach(KitMode.allCases, id: \.self) { m in
                let selected = mode == m
                Text(m.rawValue)
                    .font(mono(11, .bold))
                    .foregroundStyle(selected ? t.onAccent : t.text2)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 9)
                    .kitPanel(fill: selected ? t.accent : t.inset,
                              border: selected ? t.accent : t.rule)
                    .onTapGesture { viewModel.onModeChange(m) }
                    .accessibilityIdentifier(m == .chop ? TestTags.KIT_MODE_CHOP : TestTags.KIT_MODE_KIT)
            }
        }
    }

    // ── Drop / pick hint panel — tappable (it reads as a button), triggers the same pick ──

    private func pickPanel(mode: KitMode, stagedLoop: StagedLoop?, selectedGroup: PadChannel) -> some View {
        let hint: String
        let headline: String
        if mode == .chop, let staged = stagedLoop {
            hint = "READY · CHOP INTO \(viewModel.resolvedSliceCount()) SLICES → GROUP \(selectedGroup.rawValue)"
            headline = staged.name
        } else if mode == .chop {
            hint = "PICK ONE LOOP · CHOP INTO \(viewModel.resolvedSliceCount()) SLICES → GROUP \(selectedGroup.rawValue)"
            headline = "pick loop to chop"
        } else {
            hint = "PICK UP TO \(MAX_SLICES) ONE-SHOTS → GROUP \(selectedGroup.rawValue)"
            headline = "pick one-shots to build kit"
        }

        return VStack(spacing: 6) {
            Text(hint)
                .font(mono(10))
                .tracking(0.4)
                .foregroundStyle(t.text3)
                .multilineTextAlignment(.center)

            Text(headline)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(t.text)
                .multilineTextAlignment(.center)

            if mode == .chop, stagedLoop != nil {
                Text("TAP TO PICK A DIFFERENT LOOP")
                    .font(mono(8))
                    .tracking(1)
                    .foregroundStyle(t.text3)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: panelRadius).fill(t.inset))
        .overlay(
            RoundedRectangle(cornerRadius: panelRadius)
                .strokeBorder(t.rule, style: StrokeStyle(lineWidth: 1, dash: [6, 4]))
        )
        .contentShape(RoundedRectangle(cornerRadius: panelRadius))
        .onTapGesture { viewModel.triggerPick() }
        .accessibilityIdentifier(TestTags.KIT_PICK_PANEL)
    }

    // ── Shared PUSH TO DEVICE button ───────────────────────────────────────────

    private func pushButton(
        mode: KitMode,
        stagedLoop: StagedLoop?,
        selectedGroup: PadChannel,
        builderState: KitBuilderState
    ) -> some View {
        let chopReady = stagedLoop != nil
        let pushLabel: String = if mode == .chop {
            chopReady
                ? "PUSH TO DEVICE · \(viewModel.resolvedSliceCount()) SLICES → \(selectedGroup.rawValue)"
                : "PICK LOOP"
        } else if builderState.packLoading {
            "READING PACK…"
        } else if builderState.pack == nil {
            "PICK PACK FOLDER"
        } else if builderState.loading {
            "PUSHING…"
        } else if builderState.assignments.isEmpty {
            "ASSIGN PADS FIRST"
        } else {
            "PUSH TO DEVICE · \(builderState.assignments.count) PADS → \(selectedGroup.rawValue)"
        }

        return EP133PrimaryButton(label: pushLabel) {
            if mode == .chop {
                if chopReady { viewModel.chopStagedLoop() } else { viewModel.triggerPick() }
            } else if builderState.packLoading || builderState.loading {
                // In-flight — ignore taps.
            } else if builderState.pack == nil {
                builderViewModel.triggerPackPick()
            } else if !builderState.assignments.isEmpty {
                builderViewModel.onLoadKit()
            }
        }
        .accessibilityIdentifier(TestTags.KIT_PUSH_BUTTON)
    }
}

// ── Slice-count selector (implements the "Slice Pad Selector" design) ──────────

/// Visual slice-count selector: a big count readout with ± steppers plus a 4×3 mock of the
/// device pads: pads whose fill-order rank ≤ `count` render "filled" (accent), the pad at
/// rank == count gets a teal edge ring, and tapping any pad sets the count to its rank.
/// Range is 1...`MAX_SLICES`.
private struct SlicePadSelector: View {
    @Environment(\.ep133Tokens) private var t
    let count: Int
    let onCountChange: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Count readout + ± steppers.
            Text("SLICE COUNT")
                .font(mono(9))
                .tracking(1.6)
                .foregroundStyle(t.text3)
            HStack(alignment: .bottom, spacing: 10) {
                Text(String(format: "%02d", count))
                    .font(mono(54, .bold))
                    .tracking(-1)
                    .foregroundStyle(t.accent)
                    .accessibilityIdentifier(TestTags.KIT_SLICE_COUNT_READOUT)
                Text("SLICES")
                    .font(mono(11))
                    .tracking(1.6)
                    .foregroundStyle(t.text)
                    .padding(.bottom, 10)
            }
            HStack(spacing: 10) {
                SliceStepperButton(label: "−", enabled: count > 1) {
                    onCountChange(max(count - 1, 1))
                }
                .accessibilityIdentifier(TestTags.KIT_SLICE_COUNT_DEC)
                SliceStepperButton(label: "+", enabled: count < MAX_SLICES) {
                    onCountChange(min(count + 1, MAX_SLICES))
                }
                .accessibilityIdentifier(TestTags.KIT_SLICE_COUNT_INC)
                Text("TAP A PAD OR USE ±\nRANGE 1–\(MAX_SLICES)")
                    .font(mono(9))
                    .tracking(1)
                    .foregroundStyle(t.text3)
            }

            // 4×3 pad-cluster preview, housed like the device's pad panel.
            VStack(spacing: 8) {
                ForEach(0..<4, id: \.self) { row in
                    HStack(spacing: 8) {
                        ForEach(0..<3, id: \.self) { col in
                            let pad = SLICE_PAD_GRID[row * 3 + col]
                            SlicePadCell(pad: pad, count: count) { onCountChange(pad.order) }
                                .accessibilityIdentifier(TestTags.kitSlicePad(pad.order))
                        }
                    }
                }
            }
            .padding(12)
            .kitPanel(fill: t.inset, border: t.ruleSoft, radius: 8)

            Text("PADS FILL ↖ FROM BOTTOM-LEFT")
                .font(mono(8))
                .tracking(1.2)
                .foregroundStyle(t.text3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .kitPanel(fill: t.panel, border: t.rule)
    }
}

private struct SlicePadCell: View {
    @Environment(\.ep133Tokens) private var t
    let pad: SlicePad
    let count: Int
    let onTap: () -> Void

    var body: some View {
        let filled = pad.order <= count
        let isEdge = pad.order == count
        ZStack {
            RoundedRectangle(cornerRadius: 14)
                .fill(filled ? t.accent : t.padFace)
            if isEdge {
                RoundedRectangle(cornerRadius: 14).strokeBorder(t.live, lineWidth: 2)
            }
            Text(pad.label)
                .font(mono(pad.label.count > 1 ? 16 : 24, .semibold))
                .tracking(0.5)
                .foregroundStyle(filled ? padFilledInk : padEmptyInk)
            if filled {
                Text(String(pad.order))
                    .font(mono(10, .bold))
                    .foregroundStyle(padFilledInk.opacity(0.62))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .padding(.top, 6)
                    .padding(.trailing, 8)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .contentShape(RoundedRectangle(cornerRadius: 14))
        .onTapGesture(perform: onTap)
    }
}

private struct SliceStepperButton: View {
    @Environment(\.ep133Tokens) private var t
    let label: String
    let enabled: Bool
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            Text(label)
                .font(mono(20))
                .foregroundStyle(enabled ? padEmptyInk : padEmptyInk.opacity(0.4))
                .frame(width: 46, height: 42)
                .background(RoundedRectangle(cornerRadius: 8).fill(t.padFace))
                .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

// ── Chop/kit upload progress (the pad grid as a single progress indicator) ──────

/// Upload state of one pad in the progress grid.
private enum PadProgressState {
    case inactive, queued, uploading, done, error
}

private extension KitItemState {
    var progress: PadProgressState {
        switch self {
        case .pending: .queued
        case .working: .uploading
        case .done: .done
        case .error: .error
        }
    }
}

// Progress-mode colors from the "Chop Progress" design (device-accurate, theme-independent).
private let progTealCore = Color(argb: 0xFF141B1B)
private let progError = Color(argb: 0xFFD0021B)
private let progErrorInk = Color(argb: 0xFFFFE8E5)
private let progInactiveInk = Color(argb: 0xFF4A4B4C)

/// The pad grid as a single upload-progress indicator (implements the "Chop Progress" design).
/// Reuses `SLICE_PAD_GRID`: slice i lands on the pad at fill-order rank i+1, so item i's state
/// drives that pad; pads beyond the slice count are inactive. A status line summarizes the run.
private struct ChopProgress: View {
    @Environment(\.ep133Tokens) private var t
    let items: [KitResultItem]

    // One animation clock drives both the live-pad sweep and the status-dot blink.
    @State private var sweepAngle: Double = 0
    @State private var dotDimmed = false

    var body: some View {
        let total = items.count
        let done = items.filter { $0.state == .done }.count
        let working = items.filter { $0.state == .working }.count
        let errors = items.filter { $0.state == .error }.count
        let inFlight = items.contains { $0.state == .working || $0.state == .pending }
        let statusText: String
        let statusColor: Color
        if inFlight {
            statusText = "UPLOADING · \(pad2(done + working)) / \(total)"
            statusColor = t.live
        } else if errors > 0 {
            statusText = "\(pad2(errors)) FAILED · \(done) OK"
            statusColor = progError
        } else {
            statusText = "DONE · \(pad2(done)) / \(total)"
            statusColor = t.accent
        }

        return VStack(spacing: 14) {
            // Status line.
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(statusColor)
                    .frame(width: 8, height: 8)
                    .opacity(inFlight && dotDimmed ? 0.35 : 1)
                Text(statusText)
                    .font(mono(11, .bold))
                    .tracking(1.6)
                    .foregroundStyle(statusColor)
                Spacer()
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 9)
            .kitPanel(fill: t.inset, border: t.ruleSoft, radius: 5)
            .accessibilityIdentifier(TestTags.KIT_PROGRESS_STATUS)

            // Pad grid — each pad's state from its slice (fill-order rank r → item r-1), else inactive.
            VStack(spacing: 8) {
                ForEach(0..<4, id: \.self) { row in
                    HStack(spacing: 8) {
                        ForEach(0..<3, id: \.self) { col in
                            let pad = SLICE_PAD_GRID[row * 3 + col]
                            let state: PadProgressState = pad.order > total
                                ? .inactive
                                : items[pad.order - 1].state.progress
                            SliceProgressPadCell(pad: pad, state: state, sweepAngle: sweepAngle)
                                .accessibilityIdentifier(TestTags.kitProgressPad(pad.order))
                        }
                    }
                }
            }
            .padding(12)
            .kitPanel(fill: t.inset, border: t.ruleSoft, radius: 8)
        }
        .padding(16)
        .kitPanel(fill: t.panel, border: t.rule)
        .onAppear {
            withAnimation(.linear(duration: 1.1).repeatForever(autoreverses: false)) {
                sweepAngle = 360
            }
            withAnimation(.easeInOut(duration: 1.0).repeatForever(autoreverses: true)) {
                dotDimmed = true
            }
        }
    }

    private func pad2(_ n: Int) -> String { String(format: "%02d", n) }
}

private struct SliceProgressPadCell: View {
    @Environment(\.ep133Tokens) private var t
    let pad: SlicePad
    let state: PadProgressState
    let sweepAngle: Double

    var body: some View {
        ZStack {
            if state == .uploading {
                // Rotating teal sweep (a ~95° arc) behind a dark core — a spinner on the live pad.
                AngularGradient(
                    stops: [
                        .init(color: t.live, location: 0.00),
                        .init(color: t.live, location: 0.26),
                        .init(color: t.live.opacity(0.12), location: 0.27),
                        .init(color: t.live.opacity(0.12), location: 1.00),
                    ],
                    center: .center
                )
                .rotationEffect(.degrees(sweepAngle))
                RoundedRectangle(cornerRadius: 11)
                    .fill(progTealCore)
                    .padding(3)
            } else {
                let bg: Color = switch state {
                case .done: t.accent
                case .error: progError
                default: t.padFace   // inactive / queued
                }
                RoundedRectangle(cornerRadius: 14).fill(bg)
                if state == .queued {
                    RoundedRectangle(cornerRadius: 14)
                        .strokeBorder(t.accent.opacity(0.5), lineWidth: 1.5)
                }
            }

            let labelInk: Color = switch state {
            case .done: padFilledInk
            case .error: progErrorInk
            case .inactive: progInactiveInk
            default: padEmptyInk
            }
            Text(pad.label)
                .font(mono(pad.label.count > 1 ? 16 : 24, .semibold))
                .tracking(0.5)
                .foregroundStyle(labelInk)

            if state == .done || state == .error {
                Text(state == .done ? "✓" : "✕")
                    .font(mono(11, .bold))
                    .foregroundStyle(state == .error ? progErrorInk : padFilledInk.opacity(0.7))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .padding(.top, 6)
                    .padding(.trailing, 8)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

// ── Snackbar analog — bottom toast panel (shared with the Kit Builder pane) ────
struct KitSnackbarToast: View {
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
            .kitPanel(fill: t.panel, border: t.rule)
            .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}
