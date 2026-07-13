# Copilot code review instructions - EP-133 Sample Tool

Review effort is set to **Low**, so spend the budget where it catches real bugs. Prefer a few
high-confidence findings over broad coverage. If nothing here is wrong, say so instead of
manufacturing nits.

## What this repo is (so you don't re-derive it)

One compiled web app in `data/` runs inside a WebView on four targets: Electron (desktop), a JUCE
AU/VST3 plugin (macOS), and native iOS and Android apps. A single shared polyfill
(`shared/MIDIBridgePolyfill.js`) overrides `navigator.requestMIDIAccess()` and routes MIDI through
whichever native bridge it detects. Android and iOS also have parallel native UIs (Compose /
SwiftUI); the plugin and Electron just wrap the web app.

## Do not comment on these (wasted budget)

- **`data/index.js` and `data/*.wasm`** are a compiled third-party bundle, not source. Never review
  or suggest edits to them. `data/custom.js` is the only hand-edited file in `data/`.
- **Formatting, import order, naming style, or "consider extracting"** refactors. Not worth a Low
  pass. Only raise style if it causes a real bug.
- **Missing tests as a blanket note.** Only call out a missing test if a specific changed branch is
  both risky and trivially testable.
- **Re-explaining the diff back** or praising it. Lead with problems.

## Where the real bugs live (spend budget here)

1. **Comment/code sync.** The most common defect in this repo: a changed value, threshold, byte
   offset, or behavior whose nearby comment or docstring still describes the old one. Flag any
   comment that no longer matches the code it sits on.
2. **MIDI SysEx byte correctness** (`AndroidApp/.../domain/midi/`, `iOSApp/.../Domain/MIDI/`,
   `shared/MIDIBridgePolyfill.js`). This is a wire protocol: 7-bit data bytes, an 11-bit request-id
   wire bound, node-ID paged acks, status byte before the 7-bit-packed body. Flag off-by-one byte
   offsets, values that exceed their bit width, or a frame parsed/built against the wrong layout.
   Ground truth is `docs/ep133-sysex-protocol.md`.
3. **`shared/MIDIBridgePolyfill.js` must stay ES5** (var only, no arrow functions, no let/const,
   no template literals) for old WebView compatibility, and platform branches (juce/android/ios)
   must stay behavior-equivalent. Flag ES6+ syntax and any change that touches one platform branch
   in a way that silently diverges the others.
4. **Android coroutine + state rules.** Flag `GlobalScope`, a swallowed `CancellationException`
   (must always rethrow), a publicly exposed `MutableStateFlow` (expose `StateFlow`/`SharedFlow`),
   and blocking work off `Dispatchers.IO`.
5. **Silent failures.** An empty `catch`, a best-effort fallback that leaves user-visible state
   wrong, or `catch (e) { log(e.message) }` where `e` may not be an `Error` (use `String(e)`).
6. **Cross-platform parity.** When a change alters shared MIDI/session behavior, check whether the
   Android and iOS sides need the matching change. Note the gap if one side was updated and the
   other wasn't.

## Voice for review comments

Direct and specific. Name the file, the line, the concrete failure (input → wrong result). No
"consider possibly", no praise padding, no em dashes (use hyphens).
