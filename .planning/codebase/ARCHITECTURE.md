# Architecture

**Analysis Date:** 2026-03-27

## Pattern Overview

**Overall:** Monorepo with multi-platform wrappers around a shared web application

**Key Characteristics:**
- A single compiled web app (`data/`) contains all sample management UI, MIDI-Sysex logic, and audio processing (WASM)
- Four platform targets (Electron, JUCE plugin, iOS, Android) each embed the web app in a WebView/browser component
- A shared JavaScript polyfill (`shared/MIDIBridgePolyfill.js`) bridges the Web MIDI API to each platform's native MIDI stack
- The Android app has a parallel native Compose UI that communicates directly with MIDI (no WebView for the primary screens)
- All MIDI communication follows the same pattern: JS polyfill intercepts `navigator.requestMIDIAccess()` and routes through the host platform's native bridge

## Layers

**Web App (Core Business Logic):**
- Purpose: All sample management UI, MIDI-Sysex communication, audio processing
- Location: `data/`
- Contains: Compiled SPA (`index.js` ~1.75MB), WASM audio libs, factory sound packs, CSS, fonts, config
- Depends on: Browser Web MIDI API (or polyfilled equivalent)
- Used by: All four platform wrappers load this as their primary (or fallback) UI

**Shared MIDI Polyfill:**
- Purpose: Provides a unified Web MIDI API implementation that routes through native MIDI on each platform
- Location: `shared/MIDIBridgePolyfill.js`
- Contains: Platform detection, bridge abstraction for JUCE/Android/iOS, incoming MIDI dispatch
- Depends on: Platform-specific bridge objects (`window.__JUCE__`, `window.EP133Bridge`, `window.webkit.messageHandlers.midibridge`)
- Used by: iOS app (WKUserScript injection), Android app (HTML interception + injection), JUCE plugin (JS injection in PluginEditor.cpp)

**Shared EP-133 Protocol Data:**
- Purpose: JSON definitions of EP-133 hardware layout, factory sounds, and musical scales
- Location: `shared/ep133-pads.json`, `shared/ep133-sounds.json`, `shared/ep133-scales.json`
- Contains: Pad-to-MIDI-note mappings (4 channels x 12 pads), 999 factory sound definitions with categories, 11 musical scale definitions
- Depends on: Nothing
- Used by: Android Compose UI loads these from assets at runtime

**Electron Wrapper:**
- Purpose: Desktop app shell (Windows/macOS/Linux)
- Location: `main.js`, `preload.js`, `renderer.js`
- Contains: BrowserWindow creation, MIDI/sysex permission grants
- Depends on: Electron framework, native Web MIDI API (Chrome)
- Used by: End users on desktop; does NOT use the polyfill (Chrome's native Web MIDI works directly)

**JUCE Plugin Wrapper:**
- Purpose: AU/VST3 plugin for macOS DAWs
- Location: `JucePlugin/` (source files not currently present in `JucePlugin/Source/` on this branch)
- Contains: WebBrowserComponent hosting, ResourceProvider for serving web assets, MIDI polyfill injection, stub AudioProcessor
- Depends on: JUCE 8 framework (fetched via CMake FetchContent), `data/` web assets, `shared/MIDIBridgePolyfill.js`
- Used by: DAW hosts via AU/VST3

**iOS App Wrapper:**
- Purpose: Native iOS app embedding the web app via WKWebView
- Location: `iOSApp/EP133SampleTool/`
- Contains: SwiftUI app shell, WKWebView setup with polyfill injection, CoreMIDI USB device management
- Depends on: SwiftUI, WebKit, CoreMIDI
- Used by: iOS end users; presents the full web app UI

**Android App - Native UI Layer:**
- Purpose: Native Compose UI with direct MIDI control (pads, beats sequencer, sounds browser, chords, device management)
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/`
- Contains: 5 Compose screens (Pads, Beats, Sounds, Chords, Device) with ViewModels, navigation via NavHost
- Depends on: Domain layer (`domain/`), MIDI layer (`midi/`)
- Used by: Primary Android user interface

**Android App - Domain Layer:**
- Purpose: Business logic for MIDI communication, sequencing, chord playback, and EP-133 data models
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/`
- Contains: `MIDIRepository` (typed MIDI message API), `SequencerEngine` (step sequencer), `ChordPlayer`, data models (`Pad`, `EP133Sound`, `Scale`, `ChordProgression`)
- Depends on: MIDI abstraction layer (`midi/MIDIPort` interface)
- Used by: UI ViewModels

**Android App - MIDI Layer:**
- Purpose: Low-level Android MIDI device access via `android.media.midi`
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/midi/`
- Contains: `MIDIPort` interface (abstraction), `MIDIManager` (USB MIDI implementation with permission handling)
- Depends on: Android `MidiManager` system service, USB permission system
- Used by: Domain layer's `MIDIRepository`

**Android App - WebView Layer:**
- Purpose: Fallback WebView hosting for the original web app (sample management operations)
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/webview/`
- Contains: `EP133WebViewSetup` (WebView config, polyfill injection), `MIDIBridge` (`@JavascriptInterface`)
- Depends on: Android WebView, `MIDIPort` interface
- Used by: `SampleManagerActivity` (launched from Device screen for backup/restore/sync/format)

## Data Flow

**Sample Management (All Platforms via Web App):**

1. User interacts with the compiled web app UI (`data/index.js`) in a WebView/BrowserWindow
2. Web app calls `navigator.requestMIDIAccess({sysex: true})` for MIDI device access
3. Polyfill intercepts the call and queries native bridge for device list (`getMidiDevices`)
4. User triggers sample operation (backup/restore/sync); web app constructs MIDI-Sysex messages
5. Web app calls `output.send(data)` which polyfill routes to native MIDI send
6. Native code forwards incoming MIDI to JS via `window.__ep133_onMidiIn(portId, [bytes])`

**Android Native Pad Control:**

1. User touches a pad in `PadsScreen` (Compose UI)
2. `PadsViewModel` calls `MIDIRepository.noteOn(note, velocity, channel)`
3. `MIDIRepository` constructs MIDI bytes `[0x90|ch, note, velocity]` and calls `MIDIPort.sendMidi(portId, bytes)`
4. `MIDIManager` sends bytes via Android `MidiInputPort.send()`
5. EP-133 responds via MIDI output; `MidiReceiver.onSend()` fires in `MIDIManager`
6. Bytes propagate back: `MIDIManager.onMidiReceived` -> `MIDIRepository.parseMidiInput` -> `_incomingMidi` SharedFlow -> ViewModel -> UI update

**Android Step Sequencer:**

1. `SequencerEngine.play()` launches a coroutine on `Dispatchers.Default`
2. Loop iterates 16 steps at BPM-derived intervals with drift compensation
3. Each step fires `midi.noteOn()` for active tracks, schedules `midi.noteOff()` at 80% step duration
4. `SeqState` exposed as `StateFlow` drives real-time UI updates in `BeatsScreen`
5. LIVE mode: incoming MIDI events from `MIDIRepository.incomingMidi` are recorded into a grid

**Polyfill Platform Detection & Routing:**

1. Polyfill IIFE executes at document start (injected before page loads)
2. `detectPlatform()` checks for bridge objects: `window.__JUCE__` -> `window.EP133Bridge` -> `window.webkit.messageHandlers.midibridge`
3. If no bridge found, retries every 100ms up to 50 times (5 seconds), then falls through to native Web MIDI
4. Once detected, `installBridge()` overrides `navigator.requestMIDIAccess` with platform-specific routing
5. JUCE additionally registers a `midiIn` event listener for incoming MIDI push

**State Management:**

- **Web app:** Internal state managed by the compiled SPA (opaque, not modifiable)
- **Android native UI:** Kotlin `StateFlow` and `SharedFlow` in ViewModels and domain objects, observed by Compose via `collectAsState()`
- **iOS/Electron/JUCE:** No additional state beyond what the web app manages internally

## Key Abstractions

**MIDIPort Interface (Android):**
- Purpose: Decouples MIDI hardware access from business logic for testability
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIPort.kt`
- Pattern: Interface with callbacks (`onMidiReceived`, `onDevicesChanged`) and operations (`sendMidi`, `getUSBDevices`, `startListening`)
- Implementations: `MIDIManager` (real USB MIDI), `TestMIDIRepository` (test double)

**MIDIRepository (Android):**
- Purpose: High-level MIDI API with typed operations (noteOn, noteOff, CC, Program Change, loadSoundToPad)
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt`
- Pattern: Wraps `MIDIPort` with channel tracking, device state as `StateFlow<DeviceState>`, incoming MIDI as `SharedFlow<MidiEvent>`

**SequencerEngine (Android):**
- Purpose: 16-step sequencer with drift-compensated timing, EDIT and LIVE modes
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt`
- Pattern: Coroutine-based loop on `Dispatchers.Default`, state exposed as `StateFlow<SeqState>`

**MIDI Bridge Polyfill (Cross-platform):**
- Purpose: Single JavaScript file that makes all four platforms appear to have Web MIDI API support
- Location: `shared/MIDIBridgePolyfill.js`
- Pattern: IIFE that overrides `navigator.requestMIDIAccess()`, auto-detects platform, with retry loop for async bridge availability

## Entry Points

**Electron:**
- Location: `main.js`
- Triggers: `npm start` or packaged app launch
- Responsibilities: Create BrowserWindow, load `data/index.html`, grant MIDI/sysex permissions

**Android - Main Activity:**
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt`
- Triggers: App launch or USB device attachment (intent filter in manifest)
- Responsibilities: Initialize MIDI stack (`MIDIManager` -> `MIDIRepository` -> `SequencerEngine`), create ViewModels, set Compose content with `EP133App`, register USB broadcast receiver

**Android - Sample Manager Activity:**
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/SampleManagerActivity.kt`
- Triggers: "Open Sample Manager" action from DeviceScreen
- Responsibilities: Host WebView with original web app for backup/restore/sync/format operations

**iOS:**
- Location: `iOSApp/EP133SampleTool/App/EP133SampleToolApp.swift`
- Triggers: App launch
- Responsibilities: Present `ContentView` -> `EP133WebView` (full-screen WKWebView with polyfill)

## Error Handling

**Strategy:** Fail-safe with logging; never crash on MIDI errors

**Patterns:**
- Android `MIDIManager`: All port operations wrapped in try/catch, stale ports removed and re-opened on failure. Cached ports (`openInputPorts`) fallback to fresh open on send failure.
- Android `MIDIRepository`: Guards all sends with `?: return` on null output port, logs warnings for missing ports
- iOS `MIDIManager`: CoreMIDI status codes checked on setup, print-logged on failure
- Polyfill: Each listener callback wrapped in try/catch to prevent one handler from breaking others
- `SequencerEngine`: `CancellationException` caught and rethrown properly (per Kotlin coroutine conventions)

## Cross-Cutting Concerns

**Logging:**
- Android: `android.util.Log` with TAG constants (`"EP133MIDI"`, `"EP133APP"`)
- iOS: `print()` statements with `[EP133]` prefix
- Web polyfill: `console.log()` / `console.error()` with `[EP133]` prefix

**USB Device Discovery:**
- Android: `BroadcastReceiver` for `USB_DEVICE_ATTACHED`/`DETACHED`, automatic USB permission requests, `MidiManager.DeviceCallback` for MIDI device add/remove
- iOS: CoreMIDI notification handler for `msgObjectAdded`/`msgObjectRemoved`/`msgSetupChanged`

**Thread Safety:**
- Android MIDI sends: `openInputPorts` uses `ConcurrentHashMap`, send operations are synchronous on the calling thread
- Android sequencer: Runs on `Dispatchers.Default`, MIDI sends are thread-safe through the port abstraction
- iOS: All JS evaluation dispatched to main thread via `DispatchQueue.main.async`
- Android WebView: All `evaluateJavascript` calls posted to WebView's thread via `webView.post {}`

**Asset Bundling:**
- Android: Gradle `copyWebAssets`, `copyPolyfill`, and `copyEP133Data` tasks run at `preBuild` to copy `data/` and `shared/` into `app/src/main/assets/`
- iOS: `data/` directory bundled as a folder reference in Xcode project, polyfill loaded from bundle resources
- JUCE: Post-build CMake step copies `data/` into plugin bundle's `Contents/Resources/`
- Electron: Loads `data/index.html` directly from the project directory

---

*Architecture analysis: 2026-03-27*
