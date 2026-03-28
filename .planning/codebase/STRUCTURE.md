# Codebase Structure

**Analysis Date:** 2026-03-27

## Directory Layout

```
ep_133_sample_tool/
├── .claude/                    # Claude Code configuration
├── .github/workflows/          # CI/CD (build.yml for Electron)
├── .planning/codebase/         # Architecture & analysis docs
├── AndroidApp/                 # Android native app (Kotlin/Compose)
│   ├── app/
│   │   ├── build.gradle.kts    # App-level Gradle config with asset copy tasks
│   │   └── src/
│   │       ├── androidTest/    # Instrumented tests (Compose UI + MIDI)
│   │       ├── main/
│   │       │   ├── AndroidManifest.xml
│   │       │   ├── assets/     # Web assets copied at build time (gitignored)
│   │       │   │   ├── data/           # Copied from root data/
│   │       │   │   ├── ep133-*.json    # Copied from shared/
│   │       │   │   ├── mobile.html     # Native mobile HTML layout
│   │       │   │   └── packs/          # Factory sound packs
│   │       │   ├── java/com/ep133/sampletool/
│   │       │   │   ├── MainActivity.kt
│   │       │   │   ├── SampleManagerActivity.kt
│   │       │   │   ├── domain/         # Business logic layer
│   │       │   │   │   ├── midi/       # MIDIRepository, ChordPlayer
│   │       │   │   │   ├── model/      # Data models (Pad, Sound, Scale, Chord)
│   │       │   │   │   └── sequencer/  # SequencerEngine
│   │       │   │   ├── midi/           # Low-level MIDI abstraction
│   │       │   │   │   ├── MIDIPort.kt     # Interface
│   │       │   │   │   └── MIDIManager.kt  # USB MIDI implementation
│   │       │   │   ├── ui/             # Compose UI screens
│   │       │   │   │   ├── EP133App.kt     # Root composable + navigation
│   │       │   │   │   ├── beats/      # Step sequencer screen
│   │       │   │   │   ├── chords/     # Chord progression player
│   │       │   │   │   ├── components/ # Shared UI components (empty)
│   │       │   │   │   ├── device/     # Device connection + sample manager
│   │       │   │   │   ├── pads/       # Pad grid controller
│   │       │   │   │   ├── sounds/     # Factory sound browser
│   │       │   │   │   └── theme/      # Material 3 theme (Color, Type, Theme)
│   │       │   │   └── webview/        # WebView setup + JS bridge
│   │       │   │       ├── EP133WebViewSetup.kt
│   │       │   │       └── MIDIBridge.kt
│   │       │   └── res/            # Android resources (icons, layouts, values)
│   │       └── test/               # Unit tests (empty)
│   ├── build.gradle.kts        # Root Gradle config (plugin versions)
│   └── gradle/                 # Gradle wrapper
├── JucePlugin/                 # JUCE AU/VST3 plugin (macOS)
│   └── Source/                 # C++ source (empty on this branch)
├── data/                       # Compiled web app (the core application)
│   ├── index.html              # SPA entry point
│   ├── index.js                # Compiled app (~1.75MB, 119 lines minified)
│   ├── index.css               # Styles
│   ├── custom.js               # User-configurable colors and bank names
│   ├── *.wasm                  # Audio processing (libsamplerate, libsndfile, libtag)
│   ├── *.pak                   # Factory sound pack (~27MB)
│   ├── *.hmls                  # Factory sound data (~5.8MB)
│   ├── *.woff / *.otf          # Fonts (TE20T + 3 others)
│   └── bg*.png / favicon.ico   # Images
├── design/                     # Design mockups
│   └── mobile-concept.html     # Mobile UI concept/prototype
├── iOSApp/                     # iOS native app (Swift/SwiftUI)
│   ├── EP133SampleTool.xcodeproj/
│   └── EP133SampleTool/
│       ├── App/                # SwiftUI app entry + ContentView
│       │   ├── EP133SampleToolApp.swift
│       │   ├── ContentView.swift
│       │   ├── Info.plist
│       │   └── Assets.xcassets/
│       ├── MIDI/               # CoreMIDI integration
│       │   └── MIDIManager.swift
│       ├── Resources/          # Bundle resources (empty — data/ is a folder ref)
│       └── WebView/            # WKWebView + MIDI bridge
│           ├── EP133WebView.swift
│           └── MIDIBridge.swift
├── scripts/                    # Build scripts
│   ├── build-dev.sh            # Dev build
│   ├── build-release.sh        # Release build (signs/notarizes)
│   ├── build-alpha.sh          # Alpha build
│   ├── common.sh               # Shared build functions (~20KB)
│   ├── bbm-project.cfg         # Build config
│   └── cmake/                  # CMake helpers
├── shared/                     # Cross-platform shared assets
│   ├── MIDIBridgePolyfill.js   # Multi-platform MIDI bridge polyfill
│   ├── ep133-pads.json         # Pad layout (4 channels x 12 pads, MIDI notes)
│   ├── ep133-sounds.json       # Factory sound library (999 sounds, 8 categories)
│   └── ep133-scales.json       # Musical scales (11 scales, 12 root notes)
├── main.js                     # Electron main process
├── preload.js                  # Electron preload script (mostly commented out)
├── renderer.js                 # Electron renderer script (empty/placeholder)
├── package.json                # npm/Electron config
├── build.sh                    # Legacy build script
├── styles.css                  # Electron shell CSS (minimal)
├── .gitignore                  # Ignores node_modules, build outputs, copied assets
├── .version_number             # Version tracking file
├── CLAUDE.md                   # Project instructions for Claude Code
├── README.md                   # Project readme
└── *.png / *.ico / *.icns      # App icons and screenshots
```

## Directory Purposes

**`data/`:**
- Purpose: The compiled web application — the core of the entire project
- Contains: Minified JS SPA, CSS, HTML entry point, WASM audio processing libraries, factory sound packs, fonts, images
- Key files: `index.html` (entry), `index.js` (compiled app logic), `custom.js` (user config), `*.wasm` (audio libs)
- Note: This is a pre-compiled artifact. The source that generates `index.js` is not in this repo.

**`shared/`:**
- Purpose: Assets shared across multiple platform targets
- Contains: The MIDI bridge polyfill used by iOS/Android/JUCE, EP-133 hardware protocol data in JSON
- Key files: `MIDIBridgePolyfill.js` (251 lines), `ep133-pads.json`, `ep133-sounds.json`, `ep133-scales.json`

**`AndroidApp/app/src/main/java/com/ep133/sampletool/`:**
- Purpose: Full Android application with native Compose UI and WebView fallback
- Contains: Activities, domain logic, MIDI abstraction, UI screens, WebView integration
- Key files: `MainActivity.kt` (app entry), `domain/midi/MIDIRepository.kt` (MIDI API), `domain/sequencer/SequencerEngine.kt`

**`AndroidApp/app/src/main/java/com/ep133/sampletool/domain/`:**
- Purpose: Business logic independent of Android framework (mostly)
- Contains: MIDI operations, step sequencer, chord player, data models
- Key files: `midi/MIDIRepository.kt`, `sequencer/SequencerEngine.kt`, `midi/ChordPlayer.kt`, `model/EP133.kt`, `model/ChordProgressions.kt`

**`AndroidApp/app/src/main/java/com/ep133/sampletool/midi/`:**
- Purpose: Low-level MIDI hardware abstraction
- Contains: Interface definition and Android USB MIDI implementation
- Key files: `MIDIPort.kt` (interface, 19 lines), `MIDIManager.kt` (implementation, 349 lines)

**`AndroidApp/app/src/main/java/com/ep133/sampletool/ui/`:**
- Purpose: All Compose UI code with MVVM pattern
- Contains: Root navigation (`EP133App.kt`), 5 feature screens with ViewModels, theme
- Key files: `EP133App.kt` (navigation + bottom bar), `pads/PadsScreen.kt`, `beats/BeatsScreen.kt`, `sounds/SoundsScreen.kt`, `chords/ChordsScreen.kt`, `device/DeviceScreen.kt`

**`AndroidApp/app/src/main/java/com/ep133/sampletool/webview/`:**
- Purpose: WebView hosting for the legacy web app (sample management operations)
- Contains: WebView configuration with polyfill injection, JavaScript bridge interface
- Key files: `EP133WebViewSetup.kt` (130 lines), `MIDIBridge.kt` (84 lines)

**`iOSApp/EP133SampleTool/`:**
- Purpose: iOS app that wraps the web app in WKWebView
- Contains: SwiftUI shell, WebView with polyfill, CoreMIDI integration
- Key files: `WebView/EP133WebView.swift`, `WebView/MIDIBridge.swift`, `MIDI/MIDIManager.swift`

**`JucePlugin/`:**
- Purpose: JUCE-based AU/VST3 plugin wrapper
- Contains: Source directory exists but is empty on this branch (source may live on main)
- Note: The JUCE plugin wraps the same web app via `juce::WebBrowserComponent`

**`scripts/`:**
- Purpose: Build automation for Electron packaging
- Contains: Dev/release/alpha build scripts, shared build functions
- Key files: `common.sh` (main build logic, ~20KB), `build-release.sh`, `build-dev.sh`

## Key File Locations

**Entry Points:**
- `main.js`: Electron main process — creates BrowserWindow, grants MIDI permissions
- `AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt`: Android app entry with MIDI init and Compose UI
- `AndroidApp/app/src/main/java/com/ep133/sampletool/SampleManagerActivity.kt`: Android WebView activity for sample management
- `iOSApp/EP133SampleTool/App/EP133SampleToolApp.swift`: iOS SwiftUI app entry
- `data/index.html`: Web app HTML entry loaded by all platforms

**Configuration:**
- `package.json`: Electron + electron-builder config, app metadata
- `AndroidApp/app/build.gradle.kts`: Android build config, dependencies, asset copy tasks
- `AndroidApp/build.gradle.kts`: Root Gradle config (Kotlin 1.9.22, AGP 8.2.0)
- `iOSApp/EP133SampleTool/App/Info.plist`: iOS app config (v1.2.0, orientations, capabilities)
- `AndroidApp/app/src/main/AndroidManifest.xml`: Android manifest (USB host, MIDI features, activities)
- `data/custom.js`: User-configurable color scheme and bank names for the web app
- `.gitignore`: Excludes node_modules, build outputs, copied Android assets

**Core Logic:**
- `data/index.js`: Compiled web app with all sample management logic (~1.75MB)
- `shared/MIDIBridgePolyfill.js`: Multi-platform MIDI bridge (251 lines)
- `AndroidApp/.../domain/midi/MIDIRepository.kt`: Typed MIDI API (noteOn, noteOff, CC, PC, loadSoundToPad)
- `AndroidApp/.../domain/sequencer/SequencerEngine.kt`: 16-step sequencer with drift compensation
- `AndroidApp/.../domain/midi/ChordPlayer.kt`: Chord playback and progression sequencing
- `AndroidApp/.../midi/MIDIManager.kt`: Android USB MIDI device management
- `iOSApp/.../MIDI/MIDIManager.swift`: iOS CoreMIDI device management

**Testing:**
- `AndroidApp/app/src/androidTest/java/com/ep133/sampletool/NavigationTest.kt`: Navigation instrumented test
- `AndroidApp/app/src/androidTest/java/com/ep133/sampletool/PadsScreenTest.kt`: Pads screen test
- `AndroidApp/app/src/androidTest/java/com/ep133/sampletool/BeatsScreenTest.kt`: Beats screen test
- `AndroidApp/app/src/androidTest/java/com/ep133/sampletool/SoundsScreenTest.kt`: Sounds screen test
- `AndroidApp/app/src/androidTest/java/com/ep133/sampletool/DeviceScreenTest.kt`: Device screen test
- `AndroidApp/app/src/androidTest/java/com/ep133/sampletool/TestMIDIRepository.kt`: Test double for MIDIRepository

## Naming Conventions

**Files:**
- Kotlin: PascalCase matching class name — `MIDIManager.kt`, `BeatsScreen.kt`, `EP133App.kt`
- Swift: PascalCase matching type name — `MIDIManager.swift`, `EP133WebView.swift`, `MIDIBridge.swift`
- JavaScript: PascalCase for the polyfill — `MIDIBridgePolyfill.js`; lowercase for web app — `index.js`, `custom.js`
- JSON data: lowercase with hyphens — `ep133-pads.json`, `ep133-sounds.json`
- Build configs: lowercase with dots — `build.gradle.kts`, `package.json`

**Directories:**
- Android packages: lowercase reverse-domain — `com/ep133/sampletool/domain/midi/`
- iOS: PascalCase functional grouping — `App/`, `MIDI/`, `WebView/`
- Feature screens (Android): lowercase singular — `beats/`, `chords/`, `pads/`, `sounds/`, `device/`

**Kotlin Classes:**
- Screens: `{Feature}Screen` (e.g., `PadsScreen`, `BeatsScreen`)
- ViewModels: `{Feature}ViewModel` (e.g., `PadsViewModel`, `BeatsViewModel`)
- Data models: PascalCase descriptive — `Pad`, `EP133Sound`, `Scale`, `ChordProgression`
- Interfaces: No `I` prefix — `MIDIPort`

## Where to Add New Code

**New Android Feature Screen:**
- Create directory: `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/{feature}/`
- Add screen: `{Feature}Screen.kt` (Composable)
- Add ViewModel: `{Feature}ViewModel.kt`
- Register route in: `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt` (add to `NavRoute` enum and `NavHost`)
- Create ViewModel in: `AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt` (add to `onCreate`)
- Add instrumented test: `AndroidApp/app/src/androidTest/java/com/ep133/sampletool/{Feature}ScreenTest.kt`

**New Android Domain Logic:**
- MIDI-related: `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/`
- New domain concept: Create `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/{concept}/`
- Data models: `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/`

**New Shared EP-133 Protocol Data:**
- Add JSON file to: `shared/` with `ep133-` prefix
- Update Gradle copy task in: `AndroidApp/app/build.gradle.kts` (`copyEP133Data` task copies `ep133-*.json`)
- Add to iOS Xcode project as bundle resource

**New iOS Feature:**
- Swift source: `iOSApp/EP133SampleTool/` in an appropriate subdirectory
- MIDI-related: `iOSApp/EP133SampleTool/MIDI/`
- WebView-related: `iOSApp/EP133SampleTool/WebView/`

**Modifying the MIDI Polyfill:**
- Edit: `shared/MIDIBridgePolyfill.js`
- Test on all platforms — iOS, Android, and JUCE all consume this file
- Changes auto-copy to Android assets at build time via Gradle `copyPolyfill` task

**New Shared UI Component (Android):**
- Place in: `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/components/` (currently empty, intended for shared composables)

## Special Directories

**`AndroidApp/app/src/main/assets/data/`:**
- Purpose: Web app assets copied from root `data/` directory
- Generated: Yes (Gradle `copyWebAssets` task at preBuild)
- Committed: No (gitignored in `.gitignore`)

**`AndroidApp/app/src/main/assets/` (ep133-*.json, mobile.html):**
- Purpose: Shared JSON data and mobile HTML copied from `shared/` and `design/`
- Generated: Partially (JSON copied by Gradle `copyEP133Data`; mobile.html manually placed)
- Committed: The copied files in assets/ are gitignored; originals in `shared/` are committed

**`data/`:**
- Purpose: Pre-compiled web application
- Generated: Yes (built from an external source repo, not present in this repo)
- Committed: Yes (committed as a build artifact so all platform wrappers can bundle it)

**`JucePlugin/build/`:**
- Purpose: CMake build output for AU/VST3 plugin
- Generated: Yes
- Committed: No (gitignored)

**`dist/`:**
- Purpose: Electron packaged app output
- Generated: Yes (by electron-builder)
- Committed: No (gitignored)

**`design/`:**
- Purpose: UI design prototypes and mockups
- Contains: `mobile-concept.html` — standalone HTML prototype of the mobile UI (~43KB)
- Generated: No
- Committed: Yes

---

*Structure analysis: 2026-03-27*
