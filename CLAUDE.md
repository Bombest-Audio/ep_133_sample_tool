# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

EP-133 Sample Tool — an offline desktop app for managing samples on Teenage Engineering EP-133/EP-1320 synthesizers. Ships as an **Electron desktop app** (Windows/macOS/Linux), a **JUCE AU/VST3 plugin** (macOS DAWs), and native **iOS** and **Android** apps. All targets wrap the same web app (`data/`) which handles all UI and MIDI-Sysex business logic.

## Build Commands

### Electron App
```bash
npm install
npm start              # Run locally in dev mode
npm run package        # Build distributable (outputs to dist/)
```

### JUCE Plugin (macOS only, requires Xcode 15+ and CMake 3.22+)
```bash
cd JucePlugin
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
```
Output bundles:
- AU: `JucePlugin/build/EP133SampleTool_artefacts/Release/AU/EP-133 Sample Tool.component`
- VST3: `JucePlugin/build/EP133SampleTool_artefacts/Release/VST3/EP-133 Sample Tool.vst3`

### Quick Web Dev (no Electron needed)
```bash
cd data && python3 -m http.server   # http://localhost:8000
```

### iOS App (requires Xcode 15+, iOS 16+ deployment target)
```bash
open iOSApp/EP133SampleTool.xcodeproj
# Build and run on device/simulator from Xcode
# Set your development team in Signing & Capabilities
```

### Android App (requires Android Studio, SDK 35, min API 29)
```bash
cd AndroidApp
./gradlew assembleDebug     # Debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease   # Release APK (needs signing config)
```
The Gradle build auto-copies `data/` and `shared/MIDIBridgePolyfill.js` into assets.

### Build Scripts
- `scripts/build-dev.sh` — Dev build with version prompt, option to run locally
- `scripts/build-release.sh` — Release build (signs/notarizes on macOS)
- `scripts/build-alpha.sh` — Alpha/testing build

## Architecture

### Web App (`data/`)
The core application. All UI rendering, MIDI-Sysex communication, sample management, and audio processing (via WASM) live here. Key files:
- `index.html` / `index.js` / `index.css` — Main app (index.js is ~1.75MB compiled)
- `custom.js` — User-configurable color schemes and bank names
- `*.wasm` — libsamplerate, libsndfile, libtag for audio processing
- `*.pak` / `*.hmls` — Factory sound packs (~27MB, bundled offline)

### Electron Wrapper (`main.js`, `preload.js`, `renderer.js`)
Thin shell that loads `data/index.html` in a BrowserWindow. MIDI access uses the browser's native Web MIDI API. Minimal logic — the web app does all the work.

### JUCE Plugin (`JucePlugin/`)
Wraps the same web app inside a `juce::WebBrowserComponent` (JUCE 8). The critical integration is a **JavaScript MIDI polyfill** injected into `index.html` at load time (`PluginEditor.cpp`) that overrides `navigator.requestMIDIAccess()` and routes MIDI through JUCE's native `MidiInput`/`MidiOutput` APIs via `window.__JUCE__`.

Key files:
- `PluginEditor.cpp` — WebBrowserComponent hosting, ResourceProvider for serving web assets, MIDI bridge (~80-line JS polyfill)
- `PluginProcessor.cpp` — Stub AudioProcessor; accepts MIDI but does no audio processing
- `PluginBundlePath.mm` — macOS NSBundle helper to locate plugin's `Contents/Resources/data/`
- `CMakeLists.txt` — Fetches JUCE 8.0.4 via FetchContent; post-build copies `data/` into bundle

### iOS App (`iOSApp/`)
Native Swift/SwiftUI app embedding WKWebView. Uses CoreMIDI for USB MIDI. Same polyfill pattern as JUCE — injected as `WKUserScript` at document start. JS→Swift bridge via `WKScriptMessageHandler`, Swift→JS via `evaluateJavaScript`.

Key files:
- `EP133WebView.swift` — WKWebView setup, polyfill injection, asset loading via `loadFileURL`
- `MIDIBridge.swift` — WKScriptMessageHandler handling `getMidiDevices` and `sendMidi`
- `MIDIManager.swift` — CoreMIDI USB device discovery, send/receive, sysex support

### Android App (`AndroidApp/`)
Native Kotlin app embedding WebView. Uses `android.media.midi` for USB MIDI. Polyfill injected by intercepting `index.html` in `shouldInterceptRequest`. JS→Kotlin bridge via `@JavascriptInterface`, Kotlin→JS via `evaluateJavascript`.

Key files:
- `EP133WebViewSetup.kt` — WebView config, WebViewAssetLoader, polyfill injection
- `MIDIBridge.kt` — `@JavascriptInterface` for `getMidiDevices()` and `sendMidi()`
- `MIDIManager.kt` — Android MIDI API, USB device discovery, send/receive

### Shared MIDI Polyfill (`shared/MIDIBridgePolyfill.js`)
Multi-platform polyfill that overrides `navigator.requestMIDIAccess()`. Auto-detects the host platform (JUCE, Android, iOS, or browser) and routes MIDI through the appropriate native bridge. All four platform wrappers use this single polyfill.

### Data Flow (All Native Wrappers)
1. WebView/WebBrowserComponent serves web assets from the app bundle
2. Injected JS polyfill intercepts `navigator.requestMIDIAccess()`
3. JS calls platform bridge (`window.__JUCE__`, `window.EP133Bridge`, or `window.webkit.messageHandlers`) for MIDI output
4. Native code pushes incoming MIDI to JS via `window.__ep133_onMidiIn(portId, [bytes])`

## CI/CD
GitHub Actions workflow (`.github/workflows/build.yml`) builds the Electron app on all three platforms. Triggered manually via `workflow_dispatch`.
