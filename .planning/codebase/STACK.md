# Technology Stack

**Analysis Date:** 2026-03-27

## Languages

**Primary:**
- JavaScript (ES module, minified/bundled) - Core web app in `data/index.js` (~1.75 MB compiled React bundle, 119 lines minified). Source is not in this repo -- only the compiled output ships.
- Kotlin 1.9.22 - Android app, 29 files, ~5,054 LOC across `AndroidApp/app/src/`
- Swift - iOS app, 5 files, ~480 LOC across `iOSApp/EP133SampleTool/`

**Secondary:**
- C++17 - JUCE plugin (source in `JucePlugin/Source/`, currently empty on `feature/mobile-apps` branch; 4 source files on the `copilot/convert-to-juce-plugin` branch)
- Bash - Build scripts, 5 files, ~883 LOC in `scripts/`
- HTML/CSS - Minimal: `data/index.html` (16 lines), `data/index.css` (1 line minified), `data/custom.js` (143 lines)
- JSON - Shared EP-133 data definitions in `shared/` (3 files: pads, sounds, scales)

## Runtime

**Electron (Desktop):**
- Electron ^31.4.0 (Chromium-based, Node.js embedded)
- Web MIDI API for device communication (native browser API, no polyfill needed)
- Node.js 18 (CI target per `.github/workflows/build.yml`)

**Android:**
- Android SDK 35 (compileSdk 34, targetSdk 34)
- Minimum API 29 (Android 10)
- JVM target: Java 17
- Kotlin compiler: 1.9.22

**iOS:**
- iOS 16+ deployment target
- Xcode 15+ required
- Swift (version determined by Xcode toolchain)

**JUCE Plugin (macOS):**
- JUCE 8.0.4 (fetched via CMake FetchContent)
- CMake 3.22+ required
- C++17 standard

**Web Dev (standalone):**
- Any HTTP server (e.g., `python3 -m http.server` at port 8000)

## Package Managers

**Node/Electron:**
- npm
- Lockfile: **missing** (no `package-lock.json`)
- Dependencies defined in `package.json`

**Android:**
- Gradle 8.5 (wrapper in `AndroidApp/gradle/`)
- Android Gradle Plugin 8.2.0
- Repositories: Google, Maven Central, Gradle Plugin Portal
- Settings: `AndroidApp/settings.gradle.kts`

**iOS:**
- No third-party dependency manager (no CocoaPods, SPM, or Carthage). Pure Apple frameworks only.

**JUCE:**
- CMake FetchContent for JUCE 8.0.4 (no separate package manager)

## Frameworks

**Core:**
- React 18.2.0 - Web app UI framework (compiled into `data/index.js`)
- Jetpack Compose (BOM 2024.02.00) - Android native UI (`AndroidApp/`)
- SwiftUI - iOS app UI (`iOSApp/`)
- JUCE 8.0.4 - macOS AU/VST3 plugin framework (`JucePlugin/`)
- Electron ^31.4.0 - Desktop app shell (`main.js`, `preload.js`, `renderer.js`)

**Android Compose Stack:**
- Material 3 (`androidx.compose.material3:material3`)
- Material Icons Extended (`androidx.compose.material:material-icons-extended`)
- Navigation Compose 2.7.7 (`androidx.navigation:navigation-compose`)
- Activity Compose 1.8.2 (`androidx.activity:activity-compose`)
- Lifecycle ViewModel Compose 2.7.0
- Lifecycle Runtime Compose 2.7.0

**Testing:**
- JUnit 4.13.2 - Android unit tests
- kotlinx-coroutines-test 1.7.3 - Coroutine testing
- Compose UI Test JUnit4 - Compose instrumented tests
- Espresso 3.5.1 - Android UI testing
- AndroidX Test JUnit 1.1.5 - Android test runner

**Build/Dev:**
- electron-builder ^24.13.3 - Electron packaging/distribution
- CMake 3.22+ - JUCE plugin builds
- Xcode 15+ - iOS builds
- Android Studio / Gradle 8.5 - Android builds
- Bombest Build Manager (BBM) scripts in `scripts/` - JUCE build automation

## Key Dependencies

**Critical (Web App - compiled into `data/index.js`):**
- React 18.2.0 - UI framework
- Sentry - Error tracking (debug IDs embedded in bundle)
- Web MIDI API - Browser MIDI access
- WebAssembly modules in `data/`:
  - `libsamplerate.wasm` (1.4 MB) - Sample rate conversion
  - `libsndfile.wasm` (446 KB) - Audio file I/O
  - `libtag.wasm` (723 KB) - Audio metadata/tagging
  - `libtag_c.wasm` (31 KB) - C bindings for libtag
  - `resample.wasm` (209 KB) - Additional resampling

**Critical (Android):**
- `androidx.webkit:webkit:1.9.0` - WebViewAssetLoader for serving local assets
- `org.json:json:20231013` - JSON parsing for EP-133 protocol data
- `androidx.core:core-ktx:1.12.0` - Kotlin Android extensions
- `androidx.appcompat:appcompat:1.6.1` - Backward compatibility

**Critical (iOS):**
- WebKit (WKWebView) - System framework, no external dependency
- CoreMIDI - System framework for USB MIDI

**Infrastructure:**
- `android.media.midi` - Android MIDI API (system)
- `android.hardware.usb` - Android USB Host API (system)

## Configuration

**Environment:**
- No `.env` files detected. This is a fully offline application.
- No API keys, tokens, or secrets required.
- Android `local.properties` contains local SDK/JDK paths (not committed).
- `AndroidApp/gradle.properties`: JVM args, Java home path, AndroidX settings.

**Build Configuration Files:**
- `package.json` - Electron app config, version 1.2.0
- `AndroidApp/build.gradle.kts` - Android root (AGP 8.2.0, Kotlin 1.9.22)
- `AndroidApp/app/build.gradle.kts` - Android app module (Compose, deps, asset copy tasks)
- `AndroidApp/settings.gradle.kts` - Gradle settings with repository config
- `iOSApp/EP133SampleTool.xcodeproj/` - Xcode project (version 1.2.0, bundle build 1)
- `iOSApp/EP133SampleTool/App/Info.plist` - iOS app configuration
- `scripts/bbm-project.cfg` - Points BBM to `JucePlugin/` subdirectory
- `scripts/cmake/BBMPluginConfig.cmake` - Dev/alpha versioned plugin ID generation
- `.github/workflows/build.yml` - GitHub Actions CI for Electron

**Android Asset Pipeline:**
- Gradle task `copyWebAssets`: copies `data/` into `src/main/assets/data/` before build
- Gradle task `copyPolyfill`: copies `shared/MIDIBridgePolyfill.js` into assets
- Gradle task `copyEP133Data`: copies `shared/ep133-*.json` into assets root
- `androidResources.noCompress`: wasm, pak, hmls, woff, otf (served directly to WebView)

**Android ProGuard:**
- `isMinifyEnabled = true` for release builds
- ProGuard rules file referenced but does not exist on disk

## Platform Requirements

**Development:**
- Node.js 18+ and npm for Electron development
- Android Studio with SDK 35, JDK 17 for Android
- Xcode 15+ on macOS for iOS
- CMake 3.22+ on macOS for JUCE plugin
- Git for version control

**Production (Electron):**
- Windows, macOS, or Linux (x64/arm64)
- USB port for EP-133 connection
- Web MIDI API support (Chromium provides this)

**Production (Android):**
- Android 10+ (API 29)
- USB Host capability (declared as `android:required="true"`)
- MIDI support (declared as `android:required="true"`)
- USB-C / OTG cable for EP-133 connection

**Production (iOS):**
- iOS 16+
- USB connection to EP-133 (Lightning/USB-C adapter or direct USB-C)

**Production (JUCE Plugin):**
- macOS DAW with AU or VST3 support
- EP-133 connected via USB MIDI

---

*Stack analysis: 2026-03-27*
