import SwiftUI
import UIKit

/// SwiftUI port of `AndroidApp/.../ui/pads/PadsScreen.kt` (screen + co-located `PadsViewModel`).
///
/// The Android screen does multi-touch through a grid-level `pointerInteropFilter` with
/// coordinate→index math (D-18, D-20, RESEARCH.md Pattern 5). SwiftUI gestures cannot track
/// several simultaneous fingers independently, so the same grid-level approach is ported as a
/// transparent `UIViewRepresentable` touch layer over the grid: `isMultipleTouchEnabled` UIKit
/// touches, identical coordinate math, one `UITouch` → pad-index map standing in for Android's
/// pointer-id map.

// ── ViewModel ─────────────────────────────────────────────────────────────────

@MainActor
@Observable
class PadsViewModel {
    @ObservationIgnored private let midi: MIDIRepository

    private(set) var selectedChannel: PadChannel = .A
    private(set) var pressedIndices: Set<Int> = []

    // D-17: scale state delegated from MIDIRepository (single source of truth)
    var selectedScale: Scale? { midi.selectedScale }
    var selectedRootNote: String { midi.selectedRootNote }

    /// The incoming-MIDI listener (Kotlin `viewModelScope.launch { midi.incomingMidi.collect }`).
    @ObservationIgnored private var incomingMidiTask: Task<Void, Never>?

    init(_ midi: MIDIRepository) {
        self.midi = midi
        // Listen for incoming MIDI: auto-switch group + flash the matching pad.
        // The 0xC0 Program-Change group branch has been removed — the device does not
        // send Program Change for group changes; real group sync goes through FILE_METADATA.
        // The 0x90 noteOn auto-switch is retained (confirmed on hardware).
        let stream = midi.incomingMidi
        incomingMidiTask = Task { [weak self] in
            for await event in stream {
                guard let self else { return }
                self.handleIncoming(event)
            }
        }
    }

    private func handleIncoming(_ event: MIDIRepository.MIDIEvent) {
        guard event.status == 0x90, event.velocity > 0,
              let (group, index) = EP133Pads.resolveIncoming(note: event.note, ch: event.channel)
        else { return }

        if group != selectedChannel {
            selectedChannel = group
            pressedIndices = []
        }

        pressedIndices.insert(index)
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(120))
            self?.pressedIndices.remove(index)
        }
    }

    func selectChannel(_ channel: PadChannel) {
        // Optimistic: update UI immediately so the tap feels instant.
        let changed = channel != selectedChannel
        selectedChannel = channel
        pressedIndices = []
        // Async: propagate to device via FILE_METADATA SET — only when the group
        // actually changed, so re-tapping the active chip doesn't spam redundant writes.
        guard changed, let index = PadChannel.allCases.firstIndex(of: channel) else { return }
        Task { [midi] in
            _ = try? await midi.setActiveGroup(index: index)
        }
    }

    /// Poll the device for the current active group and reconcile with the UI.
    /// Called from the PadsScreen scene-active poll loop every 1500 ms.
    /// No-op if the device is not connected or returns nil.
    func refreshActiveGroupFromDevice() {
        Task { [weak self] in
            guard let self else { return }
            EP133Log.debug(.app, "MIDI META: poll tick → refreshActiveGroupFromDevice")
            // try? flattens the Int?? from `async throws -> Int?` — nil on error OR no device.
            guard let idx = try? await self.midi.getActiveGroupIndex(),
                  PadChannel.allCases.indices.contains(idx)
            else { return }
            let deviceGroup = PadChannel.allCases[idx]
            if deviceGroup != self.selectedChannel {
                self.selectedChannel = deviceGroup
                self.pressedIndices = []
            }
        }
    }

    /// Send noteOn with velocity (D-19). Default velocity is 100 for backward compatibility.
    func padDown(_ index: Int, velocity: Int = 100) {
        let pads = EP133Pads.padsForChannel(selectedChannel)
        guard pads.indices.contains(index) else { return }
        let pad = pads[index]
        pressedIndices.insert(index)
        midi.noteOn(note: pad.note, velocity: min(max(velocity, 1), 127), ch: pad.midiChannel)
    }

    func padUp(_ index: Int) {
        let pads = EP133Pads.padsForChannel(selectedChannel)
        guard pads.indices.contains(index) else { return }
        let pad = pads[index]
        pressedIndices.remove(index)
        midi.noteOff(note: pad.note, ch: pad.midiChannel)
    }

    deinit {
        incomingMidiTask?.cancel()
    }
}

/// Compute the set of pitch classes (0-11) in the given scale starting at `rootNoteName`.
/// Returns empty set if root note is not recognized.
func computeInScaleSet(scale: Scale, rootNoteName: String) -> Set<Int> {
    guard let rootIndex = EP133Scales.ROOT_NOTES.firstIndex(of: rootNoteName) else { return [] }
    return Set(scale.intervals.map { (rootIndex + $0) % 12 })
}

// ── Screen ────────────────────────────────────────────────────────────────────

/// Entry point kept no-arg: AppShell mounts `PadsScreen()` once and keeps it alive across tab
/// switches, so the `@State`-owned ViewModel persists like Android's activity-scoped VM. The
/// repository comes from the environment (see ContentView), and the VM is created on first
/// appearance because `@Environment` values aren't available in `init`.
struct PadsScreen: View {
    @Environment(MIDIRepository.self) private var midi
    @State private var viewModel: PadsViewModel?

    var body: some View {
        ZStack {
            if let viewModel {
                PadsScreenBody(viewModel: viewModel)
            }
        }
        .onAppear {
            if viewModel == nil { viewModel = PadsViewModel(midi) }
        }
    }
}

private struct PadsScreenBody: View {
    @Environment(\.ep133Tokens) private var t
    @Environment(\.scenePhase) private var scenePhase
    let viewModel: PadsViewModel

    /// 3 columns × 4 rows — matches physical EP-133 calculator-style pad layout.
    private let columns = 3

    var body: some View {
        let pads = EP133Pads.padsForChannel(viewModel.selectedChannel)
        // Scale lock: compute set of in-scale pitch classes
        let inScaleSet: Set<Int> = {
            guard let scale = viewModel.selectedScale else { return [] }
            return computeInScaleSet(scale: scale, rootNoteName: viewModel.selectedRootNote)
        }()

        VStack(alignment: .leading, spacing: 13) {
            groupSelector
            padGrid(pads: pads, inScaleSet: inScaleSet)
            legend
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(t.bg)
        // Device→app active-group sync — the Android `repeatOnLifecycle(RESUMED)` loop: while
        // the scene is active, refresh immediately (so the UI never shows a stale active group)
        // then poll every 1500 ms. A scenePhase change cancels and restarts the task, and the
        // repo guards re-entrancy with its poll-in-flight flag, so slow ticks can't pile up.
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                viewModel.refreshActiveGroupFromDevice()
                try? await Task.sleep(for: .milliseconds(1500))
            }
        }
    }

    // ── Group selector — A / B / C / D ────────────────────────────────────────
    private var groupSelector: some View {
        HStack(spacing: 6) {
            ForEach(PadChannel.allCases, id: \.self) { channel in
                EP133GroupChip(
                    label: channel.rawValue,
                    selected: channel == viewModel.selectedChannel,
                    onClick: { viewModel.selectChannel(channel) }
                )
                .accessibilityIdentifier(TestTags.groupChip(channel.rawValue))
            }
        }
    }

    // ── Pad grid (multi-touch via the grid-level UIKit touch layer) ───────────
    private func padGrid(pads: [Pad], inScaleSet: Set<Int>) -> some View {
        let rowCount = (pads.count + columns - 1) / columns
        return VStack(spacing: 8) {
            ForEach(0..<rowCount, id: \.self) { rowIdx in
                HStack(spacing: 8) {
                    ForEach(0..<columns, id: \.self) { colIdx in
                        let index = rowIdx * columns + colIdx
                        if index < pads.count {
                            padCell(pads[index], index: index, inScaleSet: inScaleSet)
                        } else {
                            // Fill remaining columns if row is short
                            Color.clear.aspectRatio(1, contentMode: .fit)
                        }
                    }
                }
            }
        }
        .overlay {
            PadGridTouchLayer(
                columns: columns,
                rows: rowCount,
                padCount: pads.count,
                onPadDown: { index, velocity in viewModel.padDown(index, velocity: velocity) },
                onPadUp: { index in viewModel.padUp(index) }
            )
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(TestTags.PADS_GRID)
    }

    private func padCell(_ pad: Pad, index: Int, inScaleSet: Set<Int>) -> some View {
        let isPressed = viewModel.pressedIndices.contains(index)
        let scaleLockActive = !inScaleSet.isEmpty
        let isInScale = inScaleSet.isEmpty || inScaleSet.contains(pad.note % 12)
        let state: PadState = if isPressed {
            .pressed
        } else if scaleLockActive && isInScale {
            .inScale
        } else if pad.defaultSound != nil {
            .loaded
        } else {
            .empty
        }
        // accessibilityValue exposes the visual PadState (glow/hairline are color-only and
        // invisible to the accessibility tree) for UI tests — Android's `stateDescription`.
        let stateDesc = switch state {
        case .pressed: TestTags.PAD_STATE_PRESSED
        case .inScale: TestTags.PAD_STATE_IN_SCALE
        default: TestTags.PAD_STATE_IDLE
        }
        return PadCell(id: pad.label, name: pad.defaultSound ?? "—", state: state)
            .aspectRatio(1, contentMode: .fit)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(pad.label)
            .accessibilityValue(stateDesc)
            .accessibilityIdentifier(TestTags.pad(index))
    }

    // ── Legend ────────────────────────────────────────────────────────────────
    private var legend: some View {
        HStack(spacing: 9) {
            LegendItem(dotColor: t.live, label: "IN SCALE = TEAL HAIRLINE")
            LegendItem(dotColor: t.accent, label: "PRESSED = GLOW")
        }
    }
}

// ── Pad cell — square rubber pad filling its aspect-ratio slot ────────────────
/// Same variant mapping as `EP133Pad` (Theme/EP133Components.swift), but with a top-leading
/// content anchor and a face that fills the whole square cell. `EP133Pad` wraps its intrinsic
/// text height so it can't reproduce Android's `weight(1f).aspectRatio(1f)` square rubber pad,
/// and the component kit is shared/frozen — hence this screen-local cell (the same pattern as
/// DeviceScreen's private components).
private struct PadCell: View {
    @Environment(\.ep133Tokens) private var t
    let id: String
    let name: String
    let state: PadState

    var body: some View {
        let idColor = state.idColor(t)
        let border = state.borderColor(t)
        let face = state.faceColor(t)

        VStack(alignment: .leading, spacing: 3) {
            Text(id)
                .font(mono(11, .bold))
                .tracking(0.3)
                .foregroundStyle(idColor)
            Text(name.uppercased())
                .font(mono(8))
                .tracking(0.3)
                .foregroundStyle(t.padName)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(.vertical, 11)
        .padding(.horizontal, 8)
        .background(RoundedRectangle(cornerRadius: 3).fill(face))
        .overlay(RoundedRectangle(cornerRadius: 3).strokeBorder(border, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 3))
        // "PRESSED = GLOW" (legend): accent halo while held, matching the design's pressed pad.
        .shadow(color: state == .pressed ? t.padGlow : .clear, radius: 7)
    }
}

private struct LegendItem: View {
    @Environment(\.ep133Tokens) private var t
    let dotColor: Color
    let label: String

    var body: some View {
        HStack(spacing: 6) {
            EP133StatusDot(color: dotColor, size: 7)
            Text(label)
                .font(mono(9))
                .tracking(0.6)
                .foregroundStyle(t.text3)
        }
    }
}

// ── Multi-touch layer — Android's grid-level pointerInteropFilter ─────────────

/// Pure pressure→velocity mapping: normalized force (0..1) → MIDI velocity 1...127. Extracted
/// from the touch layer so the curve is unit-testable without a live `UITouch`. Android maps
/// `MotionEvent.pressure` through the same `max(Int(pressure * 127), 1)`.
enum PadVelocity {
    static func fromNormalizedForce(_ force: CGFloat) -> Int {
        let normalized = min(max(force, 0), 1)
        return max(Int(normalized * 127), 1)
    }
}

/// Transparent UIKit layer over the pad grid handling true multi-touch: several simultaneous
/// fingers each trigger their own noteOn/noteOff, exactly like Android's grid-level
/// `pointerInteropFilter`. Same coordinate math (uniform column/row division of the grid
/// bounds — valid because every row has equal height); moves are ignored, matching Android's
/// no-op on `ACTION_MOVE` (no slide-retrigger).
private struct PadGridTouchLayer: UIViewRepresentable {
    let columns: Int
    let rows: Int
    let padCount: Int
    let onPadDown: (Int, Int) -> Void  // (index, velocity 1–127)
    let onPadUp: (Int) -> Void

    func makeUIView(context: Context) -> MultiTouchGridView {
        let view = MultiTouchGridView()
        update(view)
        return view
    }

    func updateUIView(_ uiView: MultiTouchGridView, context: Context) {
        update(uiView)
    }

    private func update(_ view: MultiTouchGridView) {
        view.columns = columns
        view.rows = rows
        view.padCount = padCount
        view.onPadDown = onPadDown
        view.onPadUp = onPadUp
    }
}

private final class MultiTouchGridView: UIView {
    var columns = 3
    var rows = 4
    var padCount = 12
    var onPadDown: ((Int, Int) -> Void)?
    var onPadUp: ((Int) -> Void)?

    /// Active-touch → pad-index map (Android's `pointerToPad`). `UITouch` instances are stable
    /// for the lifetime of a touch, so object identity is the pointer id.
    private var touchToPad: [ObjectIdentifier: Int] = [:]

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = .clear
        isAccessibilityElement = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    /// Android's `coordToIndex`: uniform division of the grid bounds into columns × rows.
    private func padIndex(at point: CGPoint) -> Int? {
        guard bounds.width > 0, bounds.height > 0, rows > 0 else { return nil }
        let col = min(max(Int(point.x / (bounds.width / CGFloat(columns))), 0), columns - 1)
        let row = min(max(Int(point.y / (bounds.height / CGFloat(rows))), 0), rows - 1)
        let index = row * columns + col
        return index < padCount ? index : nil
    }

    /// Android maps `event.pressure` (0..1) → velocity 1–127; most touchscreens report ~1.0.
    /// iOS `UITouch.force` is 0 on non-3D-Touch hardware and at touch start, so when there is
    /// no usable force reading this falls back to the default velocity (100) — the same value
    /// Kotlin's `padDown` defaults to.
    private func velocity(for touch: UITouch) -> Int {
        guard touch.maximumPossibleForce > 0, touch.force > 0 else { return 100 }
        return PadVelocity.fromNormalizedForce(touch.force / touch.maximumPossibleForce)
    }

    // ACTION_DOWN / ACTION_POINTER_DOWN
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            guard let index = padIndex(at: touch.location(in: self)) else { continue }
            touchToPad[ObjectIdentifier(touch)] = index
            onPadDown?(index, velocity(for: touch))
        }
    }

    // ACTION_POINTER_UP / ACTION_UP
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        release(touches)
    }

    // ACTION_CANCEL
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        release(touches)
    }

    private func release(_ touches: Set<UITouch>) {
        for touch in touches {
            if let index = touchToPad.removeValue(forKey: ObjectIdentifier(touch)) {
                onPadUp?(index)
            }
        }
    }
}
