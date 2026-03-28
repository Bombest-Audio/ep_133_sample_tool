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

<!-- GSD:project-start source:PROJECT.md -->
## Project

**EP-133 Sample Tool — Mobile**

A native iOS and Android app for managing Teenage Engineering EP-133 K.O. II hardware from your phone. Connects via USB (OTG/Lightning) and provides full feature parity with the desktop Electron app — sample management, pattern editing, project library, and device configuration — in a purpose-built mobile UI.

Built for EP-133 users who want to manage their device on the go, and designed to ship publicly to the broader Teenage Engineering community.

**Core Value:** A connected EP-133 user can do everything on their phone that they can do on their desktop — no laptop required.

### Constraints

- **Compatibility (Android)**: Min API 29 (Android 10) — required for `android.media.midi` USB MIDI
- **Compatibility (iOS)**: iOS 16+ deployment target — set in Xcode project
- **Tech Stack**: Kotlin + Jetpack Compose for Android; Swift + SwiftUI for iOS — no cross-platform framework
- **Architecture**: Native UI talks directly to MIDI layer; WebView is fallback only, not primary UX
- **Web app source**: Compiled `data/index.js` only — cannot modify web app source as part of this milestone
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages
- JavaScript (ES module, minified/bundled) - Core web app in `data/index.js` (~1.75 MB compiled React bundle, 119 lines minified). Source is not in this repo -- only the compiled output ships.
- Kotlin 1.9.22 - Android app, 29 files, ~5,054 LOC across `AndroidApp/app/src/`
- Swift - iOS app, 5 files, ~480 LOC across `iOSApp/EP133SampleTool/`
- C++17 - JUCE plugin (source in `JucePlugin/Source/`, currently empty on `feature/mobile-apps` branch; 4 source files on the `copilot/convert-to-juce-plugin` branch)
- Bash - Build scripts, 5 files, ~883 LOC in `scripts/`
- HTML/CSS - Minimal: `data/index.html` (16 lines), `data/index.css` (1 line minified), `data/custom.js` (143 lines)
- JSON - Shared EP-133 data definitions in `shared/` (3 files: pads, sounds, scales)
## Runtime
- Electron ^31.4.0 (Chromium-based, Node.js embedded)
- Web MIDI API for device communication (native browser API, no polyfill needed)
- Node.js 18 (CI target per `.github/workflows/build.yml`)
- Android SDK 35 (compileSdk 34, targetSdk 34)
- Minimum API 29 (Android 10)
- JVM target: Java 17
- Kotlin compiler: 1.9.22
- iOS 16+ deployment target
- Xcode 15+ required
- Swift (version determined by Xcode toolchain)
- JUCE 8.0.4 (fetched via CMake FetchContent)
- CMake 3.22+ required
- C++17 standard
- Any HTTP server (e.g., `python3 -m http.server` at port 8000)
## Package Managers
- npm
- Lockfile: **missing** (no `package-lock.json`)
- Dependencies defined in `package.json`
- Gradle 8.5 (wrapper in `AndroidApp/gradle/`)
- Android Gradle Plugin 8.2.0
- Repositories: Google, Maven Central, Gradle Plugin Portal
- Settings: `AndroidApp/settings.gradle.kts`
- No third-party dependency manager (no CocoaPods, SPM, or Carthage). Pure Apple frameworks only.
- CMake FetchContent for JUCE 8.0.4 (no separate package manager)
## Frameworks
- React 18.2.0 - Web app UI framework (compiled into `data/index.js`)
- Jetpack Compose (BOM 2024.02.00) - Android native UI (`AndroidApp/`)
- SwiftUI - iOS app UI (`iOSApp/`)
- JUCE 8.0.4 - macOS AU/VST3 plugin framework (`JucePlugin/`)
- Electron ^31.4.0 - Desktop app shell (`main.js`, `preload.js`, `renderer.js`)
- Material 3 (`androidx.compose.material3:material3`)
- Material Icons Extended (`androidx.compose.material:material-icons-extended`)
- Navigation Compose 2.7.7 (`androidx.navigation:navigation-compose`)
- Activity Compose 1.8.2 (`androidx.activity:activity-compose`)
- Lifecycle ViewModel Compose 2.7.0
- Lifecycle Runtime Compose 2.7.0
- JUnit 4.13.2 - Android unit tests
- kotlinx-coroutines-test 1.7.3 - Coroutine testing
- Compose UI Test JUnit4 - Compose instrumented tests
- Espresso 3.5.1 - Android UI testing
- AndroidX Test JUnit 1.1.5 - Android test runner
- electron-builder ^24.13.3 - Electron packaging/distribution
- CMake 3.22+ - JUCE plugin builds
- Xcode 15+ - iOS builds
- Android Studio / Gradle 8.5 - Android builds
- Bombest Build Manager (BBM) scripts in `scripts/` - JUCE build automation
## Key Dependencies
- React 18.2.0 - UI framework
- Sentry - Error tracking (debug IDs embedded in bundle)
- Web MIDI API - Browser MIDI access
- WebAssembly modules in `data/`:
- `androidx.webkit:webkit:1.9.0` - WebViewAssetLoader for serving local assets
- `org.json:json:20231013` - JSON parsing for EP-133 protocol data
- `androidx.core:core-ktx:1.12.0` - Kotlin Android extensions
- `androidx.appcompat:appcompat:1.6.1` - Backward compatibility
- WebKit (WKWebView) - System framework, no external dependency
- CoreMIDI - System framework for USB MIDI
- `android.media.midi` - Android MIDI API (system)
- `android.hardware.usb` - Android USB Host API (system)
## Configuration
- No `.env` files detected. This is a fully offline application.
- No API keys, tokens, or secrets required.
- Android `local.properties` contains local SDK/JDK paths (not committed).
- `AndroidApp/gradle.properties`: JVM args, Java home path, AndroidX settings.
- `package.json` - Electron app config, version 1.2.0
- `AndroidApp/build.gradle.kts` - Android root (AGP 8.2.0, Kotlin 1.9.22)
- `AndroidApp/app/build.gradle.kts` - Android app module (Compose, deps, asset copy tasks)
- `AndroidApp/settings.gradle.kts` - Gradle settings with repository config
- `iOSApp/EP133SampleTool.xcodeproj/` - Xcode project (version 1.2.0, bundle build 1)
- `iOSApp/EP133SampleTool/App/Info.plist` - iOS app configuration
- `scripts/bbm-project.cfg` - Points BBM to `JucePlugin/` subdirectory
- `scripts/cmake/BBMPluginConfig.cmake` - Dev/alpha versioned plugin ID generation
- `.github/workflows/build.yml` - GitHub Actions CI for Electron
- Gradle task `copyWebAssets`: copies `data/` into `src/main/assets/data/` before build
- Gradle task `copyPolyfill`: copies `shared/MIDIBridgePolyfill.js` into assets
- Gradle task `copyEP133Data`: copies `shared/ep133-*.json` into assets root
- `androidResources.noCompress`: wasm, pak, hmls, woff, otf (served directly to WebView)
- `isMinifyEnabled = true` for release builds
- ProGuard rules file referenced but does not exist on disk
## Platform Requirements
- Node.js 18+ and npm for Electron development
- Android Studio with SDK 35, JDK 17 for Android
- Xcode 15+ on macOS for iOS
- CMake 3.22+ on macOS for JUCE plugin
- Git for version control
- Windows, macOS, or Linux (x64/arm64)
- USB port for EP-133 connection
- Web MIDI API support (Chromium provides this)
- Android 10+ (API 29)
- USB Host capability (declared as `android:required="true"`)
- MIDI support (declared as `android:required="true"`)
- USB-C / OTG cable for EP-133 connection
- iOS 16+
- USB connection to EP-133 (Lightning/USB-C adapter or direct USB-C)
- macOS DAW with AU or VST3 support
- EP-133 connected via USB MIDI
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

## Language-Specific Conventions
| Area | Language | Location |
|------|----------|----------|
| Web app (core) | JavaScript (ES5 in polyfill, compiled JS bundle) | `data/`, `shared/`, `main.js`, `preload.js`, `renderer.js` |
| Electron wrapper | Node.js / JavaScript | `main.js`, `preload.js`, `renderer.js` |
| iOS app | Swift / SwiftUI | `iOSApp/EP133SampleTool/` |
| Android app | Kotlin / Jetpack Compose | `AndroidApp/app/src/main/java/com/ep133/sampletool/` |
## Naming Patterns
### Files (Kotlin / Android)
- **PascalCase** for all Kotlin files: `MIDIManager.kt`, `BeatsScreen.kt`, `EP133WebViewSetup.kt`
- **Screen files** named `{Feature}Screen.kt`: `PadsScreen.kt`, `BeatsScreen.kt`, `SoundsScreen.kt`, `DeviceScreen.kt`
- **ViewModel co-located** in the same file as the Screen composable (not separate files), except `ChordsViewModel.kt` which is separate
- **Test files** named `{Feature}Test.kt` or `Test{Feature}.kt`: `NavigationTest.kt`, `PadsScreenTest.kt`, `TestMIDIRepository.kt`
### Files (Swift / iOS)
- **PascalCase**: `MIDIManager.swift`, `EP133WebView.swift`, `MIDIBridge.swift`
- Follows Apple convention with descriptive names
### Files (JavaScript)
- **camelCase** for JS files: `custom.js`
- **PascalCase** for polyfill: `MIDIBridgePolyfill.js`
- Electron files use lowercase: `main.js`, `preload.js`, `renderer.js`
### Classes / Types (Kotlin)
- **PascalCase** for classes, enums, data classes, objects: `MIDIRepository`, `PadChannel`, `DeviceState`, `TEColors`
- **Acronyms** keep their casing: `MIDI` (not `Midi`), `USB` (not `Usb`), `EP133` (not `Ep133`)
- **Companion object** constants are `SCREAMING_SNAKE_CASE`: `TAG`, `ACTION_USB_PERMISSION`, `STEP_COUNT`
- **Enum values** use `SCREAMING_SNAKE_CASE`: `BeatsMode.EDIT`, `BeatsMode.LIVE`
- **Data classes** for value types: `Pad`, `EP133Sound`, `DeviceState`, `MidiEvent`, `SeqTrack`
- **Sealed/enum classes** for closed hierarchies: `PadChannel`, `BeatsMode`, `ChordQuality`, `Vibe`
- **Object declarations** for singletons/utility: `EP133Pads`, `TEColors`, `EP133WebViewSetup`
### Functions (Kotlin)
- **camelCase**: `noteOn()`, `sendMidi()`, `refreshDeviceState()`
- **Verb-first** for actions: `playChord()`, `stopCurrentChord()`, `toggleStep()`
- **get-prefix** for queries: `getUSBDevices()`, `getDeviceName()`
- **set-prefix** for mutations: `setChannel()`, `setBpm()`, `setMode()`
- **on-prefix** for callbacks/handlers: `onMidiReceived`, `onDevicesChanged`
- **Private helpers** prefixed with underscore on backing StateFlows: `_deviceState`, `_selectedChannel`
### Variables (Kotlin)
- **camelCase** for all variables: `selectedChannel`, `pressedIndices`, `midiManager`
- **Private mutable StateFlow** backing fields: `_deviceState`, `_selectedChannel`, `_pressedIndices`
- **Public read-only** exposed as `StateFlow<T>` (not `MutableStateFlow`): `val deviceState: StateFlow<DeviceState>`
- **Constants** inside companion objects: `const val TAG = "EP133MIDI"`
### Package Structure (Kotlin)
## Code Style
### Formatting
- **No automated formatter configured** (no ktfmt, ktlint, eslint, prettier, swiftformat, or clang-format files detected)
- Kotlin indentation: **4 spaces** consistently
- Swift indentation: **4 spaces** consistently
- JavaScript indentation: **2 spaces** in `main.js`; **4 spaces** in `MIDIBridgePolyfill.js`
- Trailing commas used in Kotlin function calls and data class constructors
- Max line length: no enforced limit; lines typically under 120 chars but some go longer
### Linting
- **No linting tools configured** for any language
- No `.eslintrc`, `.prettierrc`, `.swiftlint.yml`, `.editorconfig`, or ktlint config
## Import Organization
### Kotlin
### Swift
### JavaScript
- `require()` calls at file top in Node.js (`main.js`)
- IIFE pattern in `MIDIBridgePolyfill.js` with `'use strict'`
## Error Handling
### Kotlin Patterns
### Swift Patterns
- `guard` statements for early return on failure
- Optional chaining (`self?.method()`) for weak references
- `[weak self]` in all closures that capture self
### JavaScript Patterns
- `try/catch` around listener callbacks in polyfill
- `console.error()` for caught exceptions
- `console.log()` for informational messages with `[EP133]` prefix
## Logging
### Android (Kotlin)
- Use `android.util.Log` with tag constants defined in companion objects
- Tags: `"EP133MIDI"` for MIDI layer, `"EP133APP"` for repository layer, `"EP133"` for WebView layer
- Log levels used correctly:
- Include context in messages: `"MIDI OUT: noteOn note=$note vel=$velocity ch=$ch port=$portId"`
### iOS (Swift)
- `print("[EP133] ...")` for all logging (no structured logging framework)
- Prefix messages with `[EP133]`
### JavaScript
- `console.log('[EP133] ...')` with `[EP133]` prefix for bridge messages
- `console.error(e)` for caught exceptions
## Comments
### When to Comment
- **KDoc/JSDoc** on all public classes and important public functions
- **Section markers** using `// MARK: -` in Swift and `// ── Section ──` in Kotlin
- **Inline comments** for non-obvious MIDI protocol details (byte masks, status codes, note mappings)
- **Hardware documentation** comments on EP-133 specifics (pad layout, MIDI note map, channel conventions)
### Documentation Style (Kotlin)
### Section Markers (Kotlin)
### Section Markers (Swift)
## Compose UI Patterns
### State Hoisting
### ViewModel Co-location
### Theme Usage
- Use `MaterialTheme.colorScheme.*` for standard colors
- Use `TEColors.*` (from `ui/theme/Color.kt`) for brand-specific colors (Orange, Teal, PadBlack)
- Use `MaterialTheme.typography.*` for text styles
- Wrap all screens in `EP133Theme { ... }`
### Modifier Chaining
### Animation
## Dependency Injection
## Git Workflow
### Commit Messages
- `feat(android): native Compose UI with bidirectional MIDI pad control`
- `feat(mobile): add iOS and Android native app wrappers`
### Branch Naming
- Feature branches: `feature/{short-description}` (current: `feature/mobile-apps`)
## Platform-Specific Conventions
### MIDI Polyfill (JavaScript)
- Written in **ES5** (no arrow functions, `var` only) for maximum WebView compatibility
- Uses IIFE pattern: `(function () { 'use strict'; ... })();`
- Platform detection via feature flags (`window.__JUCE__`, `window.EP133Bridge`, `window.webkit.messageHandlers`)
- Global callbacks on `window` object: `window.__ep133_onMidiIn`, `window.__ep133_onDevicesChanged`
### Web App Core (JavaScript)
- The `data/index.js` is a compiled/minified bundle (~1.75MB) -- do not edit directly
- `data/custom.js` is hand-written ES5 with `var` declarations (no `let`/`const`)
- Configuration via global variables at file top
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

## Pattern Overview
- A single compiled web app (`data/`) contains all sample management UI, MIDI-Sysex logic, and audio processing (WASM)
- Four platform targets (Electron, JUCE plugin, iOS, Android) each embed the web app in a WebView/browser component
- A shared JavaScript polyfill (`shared/MIDIBridgePolyfill.js`) bridges the Web MIDI API to each platform's native MIDI stack
- The Android app has a parallel native Compose UI that communicates directly with MIDI (no WebView for the primary screens)
- All MIDI communication follows the same pattern: JS polyfill intercepts `navigator.requestMIDIAccess()` and routes through the host platform's native bridge
## Layers
- Purpose: All sample management UI, MIDI-Sysex communication, audio processing
- Location: `data/`
- Contains: Compiled SPA (`index.js` ~1.75MB), WASM audio libs, factory sound packs, CSS, fonts, config
- Depends on: Browser Web MIDI API (or polyfilled equivalent)
- Used by: All four platform wrappers load this as their primary (or fallback) UI
- Purpose: Provides a unified Web MIDI API implementation that routes through native MIDI on each platform
- Location: `shared/MIDIBridgePolyfill.js`
- Contains: Platform detection, bridge abstraction for JUCE/Android/iOS, incoming MIDI dispatch
- Depends on: Platform-specific bridge objects (`window.__JUCE__`, `window.EP133Bridge`, `window.webkit.messageHandlers.midibridge`)
- Used by: iOS app (WKUserScript injection), Android app (HTML interception + injection), JUCE plugin (JS injection in PluginEditor.cpp)
- Purpose: JSON definitions of EP-133 hardware layout, factory sounds, and musical scales
- Location: `shared/ep133-pads.json`, `shared/ep133-sounds.json`, `shared/ep133-scales.json`
- Contains: Pad-to-MIDI-note mappings (4 channels x 12 pads), 999 factory sound definitions with categories, 11 musical scale definitions
- Depends on: Nothing
- Used by: Android Compose UI loads these from assets at runtime
- Purpose: Desktop app shell (Windows/macOS/Linux)
- Location: `main.js`, `preload.js`, `renderer.js`
- Contains: BrowserWindow creation, MIDI/sysex permission grants
- Depends on: Electron framework, native Web MIDI API (Chrome)
- Used by: End users on desktop; does NOT use the polyfill (Chrome's native Web MIDI works directly)
- Purpose: AU/VST3 plugin for macOS DAWs
- Location: `JucePlugin/` (source files not currently present in `JucePlugin/Source/` on this branch)
- Contains: WebBrowserComponent hosting, ResourceProvider for serving web assets, MIDI polyfill injection, stub AudioProcessor
- Depends on: JUCE 8 framework (fetched via CMake FetchContent), `data/` web assets, `shared/MIDIBridgePolyfill.js`
- Used by: DAW hosts via AU/VST3
- Purpose: Native iOS app embedding the web app via WKWebView
- Location: `iOSApp/EP133SampleTool/`
- Contains: SwiftUI app shell, WKWebView setup with polyfill injection, CoreMIDI USB device management
- Depends on: SwiftUI, WebKit, CoreMIDI
- Used by: iOS end users; presents the full web app UI
- Purpose: Native Compose UI with direct MIDI control (pads, beats sequencer, sounds browser, chords, device management)
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/`
- Contains: 5 Compose screens (Pads, Beats, Sounds, Chords, Device) with ViewModels, navigation via NavHost
- Depends on: Domain layer (`domain/`), MIDI layer (`midi/`)
- Used by: Primary Android user interface
- Purpose: Business logic for MIDI communication, sequencing, chord playback, and EP-133 data models
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/`
- Contains: `MIDIRepository` (typed MIDI message API), `SequencerEngine` (step sequencer), `ChordPlayer`, data models (`Pad`, `EP133Sound`, `Scale`, `ChordProgression`)
- Depends on: MIDI abstraction layer (`midi/MIDIPort` interface)
- Used by: UI ViewModels
- Purpose: Low-level Android MIDI device access via `android.media.midi`
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/midi/`
- Contains: `MIDIPort` interface (abstraction), `MIDIManager` (USB MIDI implementation with permission handling)
- Depends on: Android `MidiManager` system service, USB permission system
- Used by: Domain layer's `MIDIRepository`
- Purpose: Fallback WebView hosting for the original web app (sample management operations)
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/webview/`
- Contains: `EP133WebViewSetup` (WebView config, polyfill injection), `MIDIBridge` (`@JavascriptInterface`)
- Depends on: Android WebView, `MIDIPort` interface
- Used by: `SampleManagerActivity` (launched from Device screen for backup/restore/sync/format)
## Data Flow
- **Web app:** Internal state managed by the compiled SPA (opaque, not modifiable)
- **Android native UI:** Kotlin `StateFlow` and `SharedFlow` in ViewModels and domain objects, observed by Compose via `collectAsState()`
- **iOS/Electron/JUCE:** No additional state beyond what the web app manages internally
## Key Abstractions
- Purpose: Decouples MIDI hardware access from business logic for testability
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIPort.kt`
- Pattern: Interface with callbacks (`onMidiReceived`, `onDevicesChanged`) and operations (`sendMidi`, `getUSBDevices`, `startListening`)
- Implementations: `MIDIManager` (real USB MIDI), `TestMIDIRepository` (test double)
- Purpose: High-level MIDI API with typed operations (noteOn, noteOff, CC, Program Change, loadSoundToPad)
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt`
- Pattern: Wraps `MIDIPort` with channel tracking, device state as `StateFlow<DeviceState>`, incoming MIDI as `SharedFlow<MidiEvent>`
- Purpose: 16-step sequencer with drift-compensated timing, EDIT and LIVE modes
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt`
- Pattern: Coroutine-based loop on `Dispatchers.Default`, state exposed as `StateFlow<SeqState>`
- Purpose: Single JavaScript file that makes all four platforms appear to have Web MIDI API support
- Location: `shared/MIDIBridgePolyfill.js`
- Pattern: IIFE that overrides `navigator.requestMIDIAccess()`, auto-detects platform, with retry loop for async bridge availability
## Entry Points
- Location: `main.js`
- Triggers: `npm start` or packaged app launch
- Responsibilities: Create BrowserWindow, load `data/index.html`, grant MIDI/sysex permissions
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt`
- Triggers: App launch or USB device attachment (intent filter in manifest)
- Responsibilities: Initialize MIDI stack (`MIDIManager` -> `MIDIRepository` -> `SequencerEngine`), create ViewModels, set Compose content with `EP133App`, register USB broadcast receiver
- Location: `AndroidApp/app/src/main/java/com/ep133/sampletool/SampleManagerActivity.kt`
- Triggers: "Open Sample Manager" action from DeviceScreen
- Responsibilities: Host WebView with original web app for backup/restore/sync/format operations
- Location: `iOSApp/EP133SampleTool/App/EP133SampleToolApp.swift`
- Triggers: App launch
- Responsibilities: Present `ContentView` -> `EP133WebView` (full-screen WKWebView with polyfill)
## Error Handling
- Android `MIDIManager`: All port operations wrapped in try/catch, stale ports removed and re-opened on failure. Cached ports (`openInputPorts`) fallback to fresh open on send failure.
- Android `MIDIRepository`: Guards all sends with `?: return` on null output port, logs warnings for missing ports
- iOS `MIDIManager`: CoreMIDI status codes checked on setup, print-logged on failure
- Polyfill: Each listener callback wrapped in try/catch to prevent one handler from breaking others
- `SequencerEngine`: `CancellationException` caught and rethrown properly (per Kotlin coroutine conventions)
## Cross-Cutting Concerns
- Android: `android.util.Log` with TAG constants (`"EP133MIDI"`, `"EP133APP"`)
- iOS: `print()` statements with `[EP133]` prefix
- Web polyfill: `console.log()` / `console.error()` with `[EP133]` prefix
- Android: `BroadcastReceiver` for `USB_DEVICE_ATTACHED`/`DETACHED`, automatic USB permission requests, `MidiManager.DeviceCallback` for MIDI device add/remove
- iOS: CoreMIDI notification handler for `msgObjectAdded`/`msgObjectRemoved`/`msgSetupChanged`
- Android MIDI sends: `openInputPorts` uses `ConcurrentHashMap`, send operations are synchronous on the calling thread
- Android sequencer: Runs on `Dispatchers.Default`, MIDI sends are thread-safe through the port abstraction
- iOS: All JS evaluation dispatched to main thread via `DispatchQueue.main.async`
- Android WebView: All `evaluateJavascript` calls posted to WebView's thread via `webView.post {}`
- Android: Gradle `copyWebAssets`, `copyPolyfill`, and `copyEP133Data` tasks run at `preBuild` to copy `data/` and `shared/` into `app/src/main/assets/`
- iOS: `data/` directory bundled as a folder reference in Xcode project, polyfill loaded from bundle resources
- JUCE: Post-build CMake step copies `data/` into plugin bundle's `Contents/Resources/`
- Electron: Loads `data/index.html` directly from the project directory
<!-- GSD:architecture-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd:quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd:debug` for investigation and bug fixing
- `/gsd:execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd:profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
