# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

EP-133 Sample Tool — an offline desktop app for managing samples on Teenage Engineering EP-133/EP-1320 synthesizers. Ships as an **Electron desktop app** (Windows/macOS/Linux), a **JUCE AU/VST3 plugin** (macOS DAWs), and native **iOS** and **Android** apps. All targets wrap the same compiled web app (`data/`) which handles all UI and MIDI-Sysex business logic.

## Build Commands

### Electron
```bash
npm install
npm start              # Run locally in dev mode
npm run package        # Build distributable (outputs to dist/)
```

### Android (requires Android Studio, SDK 35, JDK 17)
```bash
cd AndroidApp
./gradlew assembleDebug                  # Debug APK → app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest         # All unit tests
./gradlew :app:testDebugUnitTest --tests "com.ep133.sampletool.ChordsViewModelTest"  # Single test class
./gradlew :app:connectedDebugAndroidTest # Compose UI tests (device/emulator attached)
./gradlew :app:pixel2Api33DebugAndroidTest  # UI tests, headless Gradle Managed Device
```
The Gradle build auto-copies `data/` and `shared/MIDIBridgePolyfill.js` into assets via `preBuild` tasks.

#### Android UI tests (androidTest)
- **Robot pattern, AAA.** Tests never touch `onNodeWith*` directly — they go through the
  method-chaining robots in `androidTest/.../robots/` (`AppRobot`, `PadsRobot`, `DeviceRobot`,
  `ProjectsRobot`, `ImportRobot`), with `// Arrange` / `// Act` / `// Assert` sections.
- **Selectors** come from `ui/TestTags.kt` (`{screen}_{element}` snake_case; helpers for
  indexed items). User-visible labels stay text assertions; note `Ep133PrimaryButton`/
  `Ep133GhostButton` render labels UPPERCASE. Pad visual states are asserted via
  `stateDescription` (`pressed` / `in_scale` / `idle`).
- **Fakes, no mocks:** `TestMIDIRepository` (inert, seeds base `_deviceState` — do not
  override `deviceState`), `ScriptedMIDIRepository` (canned slots/uploads, records MIDI
  frames), `FakeFirmwareCatalog`, and the `launchEP133App` harness (mirrors MainActivity
  wiring; SAF/updater callbacks recorded).
- **Device simulator (`support/sim/EP133DeviceSimulator`):** a wire-level MIDIPort fake
  speaking the full SysEx protocol from `docs/ep133-sysex-protocol.md` (greet, FILE_INIT,
  node-ID LIST/INFO/GET/PUT with paged acks, METADATA, the unterminated-PUT wedge).
  `MIDIRepository(EP133DeviceSimulator())` + `launchEP133App` runs the REAL protocol
  stack end-to-end with no hardware (see `SimulatedDeviceTest`); fault injection via
  `failPutDataAtPage` / `wedge` / `silent`. Prefer `ScriptedMIDIRepository` for cheap UI
  branch pinning, the simulator for protocol-touching E2E flows.
- **Scope:** UI tests cover rendering/interaction/navigation/state branches; ViewModel and
  protocol logic belongs in `src/test` (do not duplicate).
- **Pre-push gate:** run `scripts/install-hooks.sh` once; the hook runs unit tests +
  androidTest compile always, and the UI suite when a device is attached (or `EP133_GMD=1`).
  `scripts/run-ui-tests.sh` picks connected-vs-managed-device automatically.
- **Regression tooling:** `scripts/ui-test-coverage.sh [--strict]` reports coverage gaps
  (screens without tests, unused TestTags, changed-without-test drift);
  `scripts/generate-ui-tests.sh` drafts robot-based tests for gaps via `claude -p` into
  `androidTest/.../generated/` for human review.

### iOS (requires Xcode 15+, iOS 16+ deployment target)
```bash
open iOSApp/EP133SampleTool.xcodeproj
# Build and run from Xcode; set your development team in Signing & Capabilities
```

### JUCE Plugin (macOS only, requires CMake 3.22+ and Xcode 15+)
```bash
cd JucePlugin
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
```
Output: `build/EP133SampleTool_artefacts/Release/AU/` and `.../VST3/`

### Web App Standalone
```bash
cd data && python3 -m http.server   # http://localhost:8000
```

## Architecture

### The Core Pattern
One compiled web app (`data/`) runs inside a WebView on every platform. A single shared MIDI polyfill (`shared/MIDIBridgePolyfill.js`) overrides `navigator.requestMIDIAccess()` and auto-detects the host (JUCE, Android, iOS, or browser) to route MIDI through the appropriate native bridge.

**Electron** does not use the polyfill — Chromium's native Web MIDI API works directly.

### Data Flow (native wrappers)
1. WebView serves web assets from the app bundle
2. Injected polyfill intercepts `navigator.requestMIDIAccess()`
3. JS calls platform bridge (`window.__JUCE__`, `window.EP133Bridge`, or `window.webkit.messageHandlers`)
4. Native code pushes incoming MIDI back via `window.__ep133_onMidiIn(portId, [bytes])`

### Android Two-Layer Architecture
Android has a **parallel native Compose UI** in addition to the WebView fallback:
- **Native UI** (`ui/`) — 5 Compose screens (Pads, Beats, Sounds, Chords, Device) using ViewModels + StateFlow, communicating directly with the MIDI domain layer. This is the primary UX.
- **WebView fallback** (`webview/`) — `SampleManagerActivity` hosts the original web app for backup/restore/sync/format operations only.

**Android MIDI stack:** `MIDIPort` interface → `MIDIManager` (USB impl) → `MIDIRepository` (typed API: noteOn, noteOff, CC, Program Change) → ViewModels. `MIDIPort` is the testability seam — `TestMIDIRepository` is the test double.

### Key Files
| File | Role |
|------|------|
| `shared/MIDIBridgePolyfill.js` | Cross-platform MIDI polyfill (ES5, IIFE, platform auto-detect) |
| `data/index.js` | Compiled web app (~1.75MB) — **do not edit directly** |
| `data/custom.js` | Hand-written ES5 config for colors/bank names — **editable** |
| `AndroidApp/.../MainActivity.kt` | Entry point; wires MIDI stack → ViewModels → Compose |
| `AndroidApp/.../domain/midi/MIDIRepository.kt` | High-level typed MIDI API |
| `AndroidApp/.../domain/sequencer/SequencerEngine.kt` | 16-step sequencer, coroutine-based, drift-compensated |
| `iOSApp/.../EP133WebView.swift` | WKWebView setup + polyfill injection |
| `JucePlugin/Source/PluginEditor.cpp` | WebBrowserComponent + MIDI polyfill injection |

## Project Constraints

- **Android min API 29** — required for `android.media.midi` USB MIDI
- **iOS 16+** — set in Xcode project
- **No cross-platform framework** — Kotlin/Compose for Android, Swift/SwiftUI for iOS
- **`data/index.js` is read-only** — compiled bundle; source is not in this repo

## Conventions

### Android / Kotlin
- `val` over `var`; `StateFlow`/`SharedFlow` for reactive state; never expose `MutableStateFlow` publicly
- Private backing fields use underscore prefix: `_deviceState: MutableStateFlow<T>` → public `val deviceState: StateFlow<T>`
- Acronyms keep casing: `MIDI`, `USB`, `EP133` (not `Midi`, `Usb`, `Ep133`)
- ViewModels are co-located in the same file as their Screen composable (exception: `ChordsViewModel.kt` is separate)
- Screen composables are `{Feature}Screen.kt`; test files are `{Feature}Test.kt` or `Test{Feature}.kt`
- Brand colors: `TEColors.Orange`, `TEColors.Teal`, `TEColors.PadBlack` (from `ui/theme/Color.kt`)
- Coroutines: `viewModelScope` in ViewModels, `lifecycleScope` in Activities; always rethrow `CancellationException`
- Log tags: `"EP133MIDI"` (MIDI layer), `"EP133APP"` (repository), `"EP133"` (WebView)

### MIDI Polyfill (`shared/MIDIBridgePolyfill.js`)
- Written in **ES5** (no arrow functions, `var` only) for maximum WebView compatibility
- Global callbacks: `window.__ep133_onMidiIn`, `window.__ep133_onDevicesChanged`
- Platform detection: `window.__JUCE__` → JUCE, `window.EP133Bridge` → Android, `window.webkit.messageHandlers` → iOS

### `data/custom.js`
- Hand-written ES5 with `var` only (no `let`/`const`). Configuration via global variables at file top.

## CI/CD

- `.github/workflows/build-electron.yml` — Electron build on ubuntu/windows/macos; triggers on push/PR to `main`
- `.github/workflows/build-android.yml` — Unit tests + debug APK, JDK 17; triggers on push/PR to `main`

## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd:quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd:debug` for investigation and bug fixing
- `/gsd:execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
