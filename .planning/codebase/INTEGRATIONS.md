# External Integrations

**Analysis Date:** 2026-03-27

## APIs & External Services

**Sentry (Error Tracking):**
- Embedded in the compiled web app bundle `data/index.js`
- Sentry release ID: `e5b13e9f5e05b016d968a7372f0e73fec24a887e`
- Debug ID: `49412a22-3dcc-4d43-9e6d-b4334d99c155`
- No runtime configuration needed -- baked into the compiled bundle
- Only active when the web app is loaded (all platforms)

**No other external APIs or cloud services.** This is a fully offline application. No network requests, no backend, no databases, no authentication.

## Data Storage

**Databases:**
- None. No database of any kind.

**File Storage:**
- Local filesystem only.
- Web app uses browser `localStorage` / `domStorage` for settings persistence.
- Android: `settings.domStorageEnabled = true` in `AndroidApp/app/src/main/java/com/ep133/sampletool/webview/EP133WebViewSetup.kt`
- Factory sound data bundled as static files in `data/` (`.pak`, `.hmls` files).

**Caching:**
- None. All assets are local/bundled.

## Authentication & Identity

- None. No auth provider, no user accounts, no login.

## Hardware Integrations

### MIDI / USB Hardware

The entire application exists to communicate with Teenage Engineering EP-133 and EP-1320 synthesizers over USB MIDI. This is the central integration.

**Target Hardware:**
- Teenage Engineering EP-133 K.O. II (USB Vendor ID: 0x2367 / 9063)
- Teenage Engineering EP-1320
- USB device filter: `AndroidApp/app/src/main/res/xml/usb_device_filter.xml`

**MIDI Protocol:**
- MIDI 1.0 (Note On, Note Off, Control Change, Program Change, SysEx)
- Pad groups A-D mapped to MIDI notes 36-83 (defined in `shared/ep133-pads.json`)
- Factory sound library of 999 sounds (defined in `shared/ep133-sounds.json`)
- Sound loading via Bank Select MSB (CC 0) + Bank Select LSB (CC 32) + Program Change
- SysEx for backup/restore/sync operations (handled by `data/index.js`)

### Platform MIDI Implementations

**Electron (Desktop):**
- Uses the browser's native Web MIDI API directly
- MIDI and SysEx permissions auto-granted in `main.js` via `setPermissionRequestHandler`
- No polyfill needed -- Chromium has native Web MIDI support
- File: `main.js` (lines 20-38)

**Android:**
- `android.media.midi.MidiManager` system API
- `android.hardware.usb.UsbManager` for USB device discovery and permissions
- Implementation: `AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt` (~350 lines)
- Abstraction interface: `AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIPort.kt`
- High-level repository: `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt`
- USB permission flow: auto-request on device connection via `BroadcastReceiver`
- Auto-launch on USB device attach via intent filter in `AndroidManifest.xml`

**iOS:**
- CoreMIDI framework (MIDI 1.0 protocol via `MIDIEventList`)
- Implementation: `iOSApp/EP133SampleTool/MIDI/MIDIManager.swift` (~240 lines)
- Uses `MIDIClientCreateWithBlock` for device notifications
- `MIDIInputPortCreateWithProtocol` (._1_0) for receiving
- `MIDISendEventList` for short messages, legacy `MIDISend` for SysEx
- Auto-connects to all sources on setup and device change

**JUCE Plugin (macOS):**
- JUCE `MidiInput` / `MidiOutput` APIs
- Source in `JucePlugin/Source/PluginEditor.cpp` (on `copilot/convert-to-juce-plugin` branch)
- Routes through `window.__JUCE__` bridge

## WebView / Native Bridge Pattern

All four platforms wrap the same web app (`data/index.html`) and use a shared polyfill to bridge MIDI.

### Shared Polyfill

**File:** `shared/MIDIBridgePolyfill.js` (252 lines)

**What it does:**
- Overrides `navigator.requestMIDIAccess()` with a platform-aware implementation
- Auto-detects the host platform at runtime (JUCE, Android, iOS, or browser fallback)
- Routes MIDI output through the detected native bridge
- Receives incoming MIDI via `window.__ep133_onMidiIn(portId, [bytes])`
- Handles device hot-plug via `window.__ep133_onDevicesChanged()`
- iOS async callback resolution via `window.__ep133_resolveCallback(callbackId, json)`
- Retry loop (50 attempts, 100ms interval) if bridge not immediately available

**Platform detection order:**
1. `window.__JUCE__` -- JUCE plugin
2. `window.EP133Bridge` -- Android `@JavascriptInterface`
3. `window.webkit.messageHandlers.midibridge` -- iOS `WKScriptMessageHandler`
4. `null` -- Falls through to native Web MIDI API (Electron/Chrome)

### Android Bridge

**JS-to-Kotlin:**
- Interface object: `window.EP133Bridge` (registered via `addJavascriptInterface`)
- `getMidiDevices()` -- returns JSON string: `{"inputs":[...],"outputs":[...]}`
- `sendMidi(portId, dataJson)` -- sends MIDI bytes to device
- Implementation: `AndroidApp/app/src/main/java/com/ep133/sampletool/webview/MIDIBridge.kt`

**Kotlin-to-JS:**
- `webView.evaluateJavascript("window.__ep133_onMidiIn('portId', [bytes])", null)`
- `webView.evaluateJavascript("window.__ep133_onDevicesChanged()", null)`
- All JS calls dispatched via `webView.post { ... }` to ensure main thread execution

**Polyfill Injection:**
- `AndroidApp/app/src/main/java/com/ep133/sampletool/webview/EP133WebViewSetup.kt`
- Intercepts `index.html` and `mobile.html` requests in `shouldInterceptRequest`
- Reads `MIDIBridgePolyfill.js` from assets, wraps in `<script>` tag
- Injects into `<head>` of the HTML before serving to WebView
- Uses `WebViewAssetLoader` for all other assets (host: `appassets.androidplatform.net`)

### iOS Bridge

**JS-to-Swift:**
- Message handler: `window.webkit.messageHandlers.midibridge.postMessage({...})`
- Actions: `getMidiDevices` (async with callback ID) and `sendMidi`
- Implementation: `iOSApp/EP133SampleTool/WebView/MIDIBridge.swift`

**Swift-to-JS:**
- `webView.evaluateJavaScript("window.__ep133_resolveCallback('cbId', 'json')")`
- `webView.evaluateJavaScript("window.__ep133_onMidiIn('portId', [bytes])")`
- All JS calls dispatched via `DispatchQueue.main.async`

**Polyfill Injection:**
- `iOSApp/EP133SampleTool/WebView/EP133WebView.swift`
- Loads `MIDIBridgePolyfill.js` from app bundle
- Injects as `WKUserScript` at `.atDocumentStart` (before page loads)
- Web assets loaded via `loadFileURL` from bundle's `data/` directory

### JUCE Bridge

**JS-to-C++:**
- `window.__JUCE__.invoke('getMidiDevices')` / `window.__JUCE__.invoke('sendMidi', portId, data)`
- Implementation: `JucePlugin/Source/PluginEditor.cpp` (on separate branch)

**C++-to-JS:**
- JUCE event system: `window.__JUCE__.addEventListener('midiIn', handler)`

**Polyfill Injection:**
- Injected into `index.html` at load time via `PluginEditor.cpp`
- Web assets served via JUCE `ResourceProvider` from plugin bundle

### Android Native Compose Screens

In addition to the WebView-based sample manager, the Android app has native Compose screens that communicate directly with EP-133 over MIDI (bypassing the web app):

**Screens:**
- Pads (`AndroidApp/app/src/main/java/com/ep133/sampletool/ui/pads/PadsScreen.kt`) - Drum pad grid
- Beats (`AndroidApp/app/src/main/java/com/ep133/sampletool/ui/beats/BeatsScreen.kt`) - Step sequencer
- Sounds (`AndroidApp/app/src/main/java/com/ep133/sampletool/ui/sounds/SoundsScreen.kt`) - Factory sound browser
- Chords (`AndroidApp/app/src/main/java/com/ep133/sampletool/ui/chords/ChordsScreen.kt`) - Chord builder/player
- Device (`AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt`) - Connection status, launches WebView for backup/restore

**Domain Layer:**
- `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt` - High-level MIDI operations (Note On/Off, CC, Program Change, sound loading)
- `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/ChordPlayer.kt` - Chord voicing and playback
- `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt` - Step sequencer engine
- `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/EP133.kt` - Data models (Pad, Sound, Scale, etc.)

## File Format Dependencies

### Binary Assets in `data/`

**WebAssembly Modules:**
- `libsamplerate.wasm` (1.4 MB) - Sample rate conversion library
- `libsndfile.wasm` (446 KB) - Audio file reading/writing (WAV, AIFF, FLAC, etc.)
- `libtag.wasm` (723 KB) - Audio metadata tagging (ID3, etc.)
- `libtag_c.wasm` (31 KB) - C interface for libtag
- `resample.wasm` (209 KB) - Additional resampling utility

**EP-133 Data Files:**
- `ep-133-factory-content-DRyE_DHC.pak` (26 MB) - Factory sound pack (proprietary format)
- `EP-133_hmls-base_01.hmls` (5.5 MB) - HMLS base data (proprietary format, for custom sound restoration)

**Fonts:**
- `TE20T.woff` - Teenage Engineering font
- `font_1.otf`, `font_2.otf`, `font_3.otf` - Additional custom fonts

**Images:**
- `bg.png`, `bg_1320.png`, `bg_x2.png`, `bg_x2_1320.png` - Device background images
- `favicon.ico` - Browser favicon

### Shared Data Files in `shared/`

- `ep133-pads.json` - Pad layout: channels A-D, MIDI notes 36-83, default sounds
- `ep133-sounds.json` - Factory sound library: 999+ sounds with categories (kicks, snares, cymbals, percussion, bass, melodic, fx, vocal)
- `ep133-scales.json` - Musical scales: major, minor, dorian, phrygian, lydian, mixolydian, locrian, pentatonic, blues, chromatic with root notes

### Android Asset Handling

- Binary assets (`.wasm`, `.pak`, `.hmls`, `.woff`, `.otf`) are configured as `noCompress` in the APK via `AndroidApp/app/build.gradle.kts` so WebView can load them directly
- Gradle copy tasks sync `data/`, `shared/MIDIBridgePolyfill.js`, and `shared/ep133-*.json` into the Android assets directory at build time

## Monitoring & Observability

**Error Tracking:**
- Sentry (in the compiled web app bundle only)
- No native-side error tracking on Android or iOS

**Logs:**
- Android: `android.util.Log` with tag `"EP133MIDI"` and `"EP133APP"` (in MIDIManager, MIDIRepository)
- Android: `android.util.Log.e("EP133", ...)` in WebViewSetup
- iOS: `print("[EP133] ...")` statements (no structured logging)
- Electron: `console.log` for MIDI permission events
- Web app: `console.log('[EP133] ...')` in polyfill

## CI/CD & Deployment

**CI Pipeline:**
- GitHub Actions (`.github/workflows/build.yml`)
- Builds Electron app on Ubuntu, Windows, macOS (matrix strategy)
- Triggered manually via `workflow_dispatch` (no auto-trigger on push)
- Uses Node.js 18, `electron-builder --publish=never`
- Uploads build artifacts per-platform

**Build Scripts (JUCE):**
- `scripts/build-dev.sh` - Development build with version tagging
- `scripts/build-release.sh` - Release build (signs/notarizes on macOS)
- `scripts/build-alpha.sh` - Alpha/testing build
- `scripts/common.sh` - Shared build functions (CMake, Projucer, Xcode, packaging)
- Supports CMake, Projucer, and Airwindows project types

**No automated deployment.** Builds are distributed manually.

## Webhooks & Callbacks

**Incoming:**
- None

**Outgoing:**
- None

---

*Integration audit: 2026-03-27*
