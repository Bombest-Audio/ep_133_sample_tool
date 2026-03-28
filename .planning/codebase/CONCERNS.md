# Codebase Concerns

**Analysis Date:** 2026-03-27

## Tech Debt

**Unscoped CoroutineScope in MainActivity:**
- Issue: `MainActivity` creates `CoroutineScope(Dispatchers.Main)` directly rather than using `lifecycleScope`. This scope is never cancelled on `onDestroy()` -- only `screenOnJob` is cancelled, but the scope itself can leak coroutines.
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt` (line 39)
- Impact: Coroutines launched in `scope` could outlive the Activity if not manually tracked. Currently `observeScreenOnState()` launches via this scope, but any future additions may silently leak.
- Fix approach: Replace `CoroutineScope(Dispatchers.Main)` with `lifecycleScope` from `androidx.lifecycle:lifecycle-runtime-ktx`.

**Unscoped CoroutineScope in SequencerEngine:**
- Issue: `SequencerEngine` creates `CoroutineScope(Dispatchers.Default)` with no `SupervisorJob` and no cancellation mechanism. The scope is never cleaned up.
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt` (line 77)
- Impact: When `SequencerEngine` is garbage-collected, the coroutine scope does not cancel. The `playLoop()` and `liveCaptureLoop()` jobs are cancelled individually via `playJob?.cancel()` and `liveJob?.cancel()`, but the scope itself leaks. Also, note-off coroutines launched inside `playLoop()` via `scope.launch { ... }` (line 191) are fire-and-forget and could accumulate if the loop runs for a long time.
- Fix approach: Add a `SupervisorJob()` to the scope, add a `close()` or `destroy()` method that cancels the scope. Call it from `MainActivity.onDestroy()`.

**Manual Dependency Injection in MainActivity:**
- Issue: All ViewModels, the `MIDIRepository`, `SequencerEngine`, and `ChordPlayer` are manually constructed in `MainActivity.onCreate()`. This creates tight coupling and makes testing the Activity itself difficult.
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/MainActivity.kt` (lines 55-69)
- Impact: Adding new features requires editing `MainActivity` for wiring. ViewModels are not lifecycle-aware (not created via `ViewModelProvider`), so they are recreated on every Activity recreation (configuration changes, though `singleTask` launch mode mitigates this).
- Fix approach: Introduce a DI framework (Hilt recommended for Android) or at minimum use `ViewModelProvider.Factory` to create ViewModels with proper lifecycle management.

**Hardcoded Device Statistics in DeviceScreen:**
- Issue: Storage percentage ("42% used"), sample count ("128"), project count ("8"), and firmware version ("v1.3.2") are hardcoded placeholder strings, not read from the actual device.
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt` (lines 187-209)
- Impact: Users see misleading device information. The UI gives the impression of real telemetry but displays fabricated data.
- Fix approach: Either implement MIDI sysex queries to fetch real device stats, or clearly label these as "placeholder" / remove them until real data is available.

**SampleManagerPanel Creates WebView Without MIDI Bridge:**
- Issue: The inline `SampleManagerPanel` composable in `DeviceScreen.kt` creates a bare `WebView` with `settings.javaScriptEnabled = true` but does not inject the MIDI polyfill or register the `EP133Bridge` JavaScript interface. The web app loaded will lack MIDI connectivity.
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt` (lines 478-516)
- Impact: The Sample Manager panel opened from the Device screen cannot communicate with the EP-133. Users attempting Backup/Restore/Sync operations through this panel will encounter a non-functional MIDI layer.
- Fix approach: Either delegate to `SampleManagerActivity.launch(context)` (which properly configures the MIDI bridge), or replicate the full `EP133WebViewSetup.configure()` call within the panel.

**Version Number Drift:**
- Issue: `package.json` declares version `1.2.0`, `data/custom.js` declares `v 1.1.0`, and the Android app is at `2.0.0`. There is no single source of truth for version numbers.
- Files: `package.json` (line 3), `data/custom.js` (line 1), `AndroidApp/app/build.gradle.kts` (line 15), `data/index.html` (line 4 -- "1.2.0" in title)
- Impact: Users see different version strings depending on which platform or screen they check. Confusing for bug reports and support.
- Fix approach: Establish a single version source (e.g., `package.json` or a dedicated `VERSION` file) and have all build scripts and config files read from it.

**Deprecated API Usage (Android):**
- Issue: Multiple `@Suppress("DEPRECATION")` annotations for deprecated Android APIs including `MidiManager.registerDeviceCallback()`, `MidiManager.openDevice()`, `WebSettings.allowFileAccessFromFileURLs`, `WebSettings.allowUniversalAccessFromFileURLs`, and `UsbDevice.getParcelableExtra()`.
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt` (lines 75, 84, 304), `AndroidApp/app/src/main/java/com/ep133/sampletool/webview/EP133WebViewSetup.kt` (lines 38-41), `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt` (line 504)
- Impact: These APIs may be removed in future Android SDK versions. `allowFileAccessFromFileURLs` and `allowUniversalAccessFromFileURLs` are deprecated for security reasons and were removed from non-deprecated APIs.
- Fix approach: For MIDI APIs, use the newer overloads with `Executor` parameter. For WebView file access, rely on `WebViewAssetLoader` (already in use) rather than enabling universal file access.

**Preload Script is Entirely Commented Out:**
- Issue: The Electron `preload.js` has its entire functional body wrapped in a block comment.
- Files: `preload.js` (lines 1-19)
- Impact: No practical impact currently (the web app doesn't need Node.js APIs), but a maintenance smell -- dead code that looks intentional.
- Fix approach: Either delete the commented-out code or uncomment if needed.

## Security Considerations

**WebView Universal File Access Enabled:**
- Risk: `allowFileAccessFromFileURLs = true` and `allowUniversalAccessFromFileURLs = true` let the WebView's JavaScript read arbitrary files on the device filesystem.
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/webview/EP133WebViewSetup.kt` (lines 39, 41), `AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt` (line 505)
- Current mitigation: The WebView loads only local assets via `WebViewAssetLoader`, not remote URLs. `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW` is set. `usesCleartextTraffic="false"` is set in the manifest.
- Recommendations: Remove `allowFileAccessFromFileURLs` and `allowUniversalAccessFromFileURLs` since `WebViewAssetLoader` serves assets via the `https://appassets.androidplatform.net` scheme, which should not require these flags. Test to confirm the web app still loads correctly without them.

**Electron Auto-Grants All MIDI/SysEx Permissions:**
- Risk: The Electron wrapper auto-grants `midi` and `midiSysex` permissions without user confirmation for any origin.
- Files: `main.js` (lines 20-38)
- Current mitigation: Only local file content is loaded (no remote origins). The web app itself is trusted first-party code.
- Recommendations: Acceptable for current architecture. If remote content is ever loaded, add origin checking to the permission handlers.

**iOS/Android JS Bridge Injection Without Content Verification:**
- Risk: The MIDI polyfill is injected into every page matching `index.html` or `mobile.html` by URL path check only, with no integrity verification on the HTML content.
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/webview/EP133WebViewSetup.kt` (lines 64-71), `iOSApp/EP133SampleTool/WebView/EP133WebView.swift` (lines 22-29)
- Current mitigation: Assets are loaded from the app bundle, not from the network. The attack surface is limited to compromised APK/IPA files.
- Recommendations: Low priority. Consider adding a CSP (Content-Security-Policy) meta tag to `index.html` to prevent script injection from external sources.

**iOS JSON String Escaping in JS Bridge:**
- Risk: The `escapeJS()` function in `MIDIBridge.swift` manually escapes strings for JavaScript evaluation. Manual escaping is prone to bypass if unexpected characters appear in device names.
- Files: `iOSApp/EP133SampleTool/WebView/MIDIBridge.swift` (lines 103-108)
- Current mitigation: Device names come from CoreMIDI properties, which are unlikely to contain malicious content. The escaping covers backslash, single/double quotes, and newlines.
- Recommendations: Consider using `JSONSerialization` to encode data as proper JSON before passing to `evaluateJavaScript()`, which is more robust than manual string escaping.

## Performance Concerns

**1.75MB Compiled index.js Loaded in WebView:**
- Problem: The core web app is a 1.75MB minified JavaScript bundle (119 lines, extremely long lines). This is loaded into WebView on every app launch.
- Files: `data/index.js` (1,750,742 bytes)
- Cause: The web app appears to be compiled from a framework (likely Svelte or similar) with all dependencies bundled into a single file. The WASM modules (libsamplerate, libsndfile, libtag) add additional load time.
- Improvement path: This is third-party upstream code and not easily modified. Consider lazy-loading WASM modules if the web app source becomes available. On Android, the native Compose screens bypass this entirely for most features.

**36MB Data Directory Copied into Android APK:**
- Problem: The entire `data/` directory (~36MB including the 27MB factory content `.pak` file) is copied into Android assets at build time.
- Files: `AndroidApp/app/build.gradle.kts` (lines 94-115), `data/ep-133-factory-content-DRyE_DHC.pak` (27MB)
- Cause: The `copyWebAssets` Gradle task copies everything. The native Compose screens (Pads, Beats, Chords) do not need these web assets -- only the Sample Manager WebView uses them.
- Improvement path: Make the web asset copy conditional or move the Sample Manager to a downloadable expansion. Alternatively, exclude `.pak` and `.hmls` files from the default copy if the native app can function without factory restore features.

**MIDIBridgePolyfill Retries 50 Times on Startup:**
- Problem: When no native bridge is detected, the polyfill retries `tryInstall()` every 100ms up to 50 times (5 seconds total), polling via `setInterval`.
- Files: `shared/MIDIBridgePolyfill.js` (lines 240-249)
- Cause: Bridge objects (`window.__JUCE__`, `window.EP133Bridge`) may not be available at `atDocumentStart` injection time.
- Improvement path: Acceptable for platform detection. The retry only runs when no platform is detected (i.e., in a regular browser). Not a real bottleneck.

## Fragile Areas

**MIDI Port ID String Parsing:**
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt` (lines 164-167, 191-198, 234-238)
- Why fragile: Port IDs are constructed as `"${deviceId}_in_${portNumber}"` or `"${deviceId}_out_${portNumber}"` and later parsed by splitting on `"_"`. If a device ID or port number ever contains an underscore, the parsing breaks silently (early return with no error).
- Safe modification: Always validate the parsed parts. Consider using a structured type (data class) for port IDs instead of string concatenation.
- Test coverage: No unit tests for port ID parsing/construction.

**iOS MIDIManager Raw Pointer Arithmetic:**
- Files: `iOSApp/EP133SampleTool/MIDI/MIDIManager.swift` (lines 108-131, 147-183)
- Why fragile: The `sendMIDI()` and `handleMIDIInput()` methods use `UnsafeMutablePointer`, `UnsafeBufferPointer`, and manual `UInt32` word packing/unpacking for MIDI event lists. Incorrect byte ordering or buffer sizing could cause crashes.
- Safe modification: Test with various message sizes including edge cases (0 bytes, >4 bytes, sysex). The legacy `MIDIPacketList` path used for sysex (`sendRawBytes()`) allocates a fixed-capacity=1 pointer that may not handle extremely long sysex messages.
- Test coverage: Zero tests for iOS MIDI layer.

**Cross-Platform Polyfill Relies on Specific Global Names:**
- Files: `shared/MIDIBridgePolyfill.js` (lines 30-36)
- Why fragile: Platform detection depends on `window.__JUCE__`, `window.EP133Bridge`, and `window.webkit.messageHandlers.midibridge` existing at exactly the right time. If any native bridge changes its injection timing or global name, the polyfill silently falls through to "no bridge detected."
- Safe modification: When changing any native bridge setup, always verify the polyfill's platform detection still works. The detection order matters -- JUCE is checked first.
- Test coverage: No automated tests for the polyfill. Manual testing on each platform required.

**DeviceState Statechange Notification Race:**
- Files: `shared/MIDIBridgePolyfill.js` (lines 206-227)
- Why fragile: `__ep133_onDevicesChanged()` calls `navigator.requestMIDIAccess()` (which it previously overrode) to rebuild device maps. This re-enters the polyfill's own code. If native code calls `__ep133_onDevicesChanged` rapidly (e.g., USB hotplug), multiple concurrent rebuilds could cause inconsistent state as `lastMIDIAccess` is overwritten without synchronization.
- Safe modification: Add a debounce or guard flag to prevent concurrent re-entry.

## Scaling Limits

**Android MIDI Port Map Concurrency:**
- Current capacity: Uses `ConcurrentHashMap` for `openInputPorts` but plain `mutableMapOf()` for `openOutputPorts` and `openDevices`.
- Limit: Concurrent access from the main thread and MIDI callback threads could cause `ConcurrentModificationException` on `openOutputPorts` and `openDevices`.
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt` (lines 41-42)
- Scaling path: Use `ConcurrentHashMap` for all three maps, or synchronize access explicitly.

**Sequencer Step Grid Fixed at 16 Steps:**
- Current capacity: 16 steps per track, hardcoded.
- Limit: Users cannot create patterns longer than one bar of 16th notes.
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt` (line 62)
- Scaling path: Make `STEP_COUNT` configurable. Requires UI changes to handle variable grid widths.

## Dependencies at Risk

**Electron 31.x (Electron Desktop App):**
- Risk: Electron 31 was released mid-2024 and major versions are superseded roughly every 8 weeks. Running an older major version means missing security patches for Chromium.
- Impact: Potential browser-engine vulnerabilities in the desktop app.
- Files: `package.json` (line 33)
- Migration plan: Update to latest Electron stable. Test MIDI API compatibility after upgrade.

**Kotlin 1.9.22 / Compose Compiler 1.5.8 / AGP 8.2.0:**
- Risk: These are from early 2024. Current stable Kotlin is 2.x with the new Compose compiler plugin. The `compose-bom:2024.02.00` BOM is also early-2024 vintage.
- Impact: Missing Compose performance improvements, bug fixes, and newer Material3 components. The Kotlin compiler extension version is tightly coupled to the Kotlin version -- upgrading one requires upgrading both.
- Files: `AndroidApp/build.gradle.kts` (lines 2-3), `AndroidApp/app/build.gradle.kts` (lines 44, 55)
- Migration plan: Upgrade to Kotlin 2.0+ with the Compose compiler Gradle plugin. Update Compose BOM to 2025.x. Update AGP to 8.5+.

**Android compileSdk/targetSdk 34 (CLAUDE.md says SDK 35):**
- Risk: `app/build.gradle.kts` targets SDK 34 but `CLAUDE.md` claims SDK 35. Google Play requires targeting the latest SDK within ~1 year of release. SDK 34 targeting may block Play Store submissions.
- Impact: Documentation mismatch causes confusion. May need SDK 35 features.
- Files: `AndroidApp/app/build.gradle.kts` (lines 8, 13)
- Migration plan: Update `compileSdk` and `targetSdk` to 35. Test for behavior changes.

## Missing Critical Features

**No iOS Native UI:**
- Problem: The iOS app is a bare WKWebView wrapper around `data/index.html` with no native screens. Android has a full native Compose UI (Pads, Beats, Sounds, Chords, Device).
- Files: `iOSApp/EP133SampleTool/App/ContentView.swift` (entire file -- just wraps `EP133WebView`)
- Blocks: iOS users do not get the native pad controller, sequencer, chord builder, or sound browser that Android users have.

**No Unit Tests for Domain Logic:**
- Problem: There are no unit tests in `AndroidApp/app/src/test/`. All existing tests are instrumented UI tests in `androidTest/`. Core business logic (MIDIRepository, SequencerEngine, ChordPlayer, EP133Pads) has zero unit test coverage.
- Files: `AndroidApp/app/src/androidTest/java/com/ep133/sampletool/` (5 test files, all Compose UI tests)
- Blocks: Refactoring domain logic is risky without unit tests to catch regressions.

**No CI for Mobile Apps:**
- Problem: The GitHub Actions workflow only builds the Electron app. There is no CI pipeline for the Android Gradle build or iOS Xcode build.
- Files: `.github/workflows/build.yml` (entire file -- Electron only)
- Blocks: Regressions in mobile builds are not caught automatically.

## Test Coverage Gaps

**MIDI Layer (Android):**
- What's not tested: `MIDIManager.kt` (USB device enumeration, port opening, send/receive, permission flow), `MIDIBridge.kt` (JS interface serialization), `EP133WebViewSetup.kt` (polyfill injection, HTML interception).
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt`, `AndroidApp/app/src/main/java/com/ep133/sampletool/webview/MIDIBridge.kt`
- Risk: MIDI communication bugs (wrong byte ordering, dropped messages, port ID parsing failures) would not be caught until runtime on a physical device.
- Priority: High -- MIDI is the core functionality.

**Sequencer and Chord Engine:**
- What's not tested: `SequencerEngine` (timing accuracy, step toggling, BPM changes during playback, live capture), `ChordPlayer` (note resolution, progression playback).
- Files: `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/sequencer/SequencerEngine.kt`, `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/ChordPlayer.kt`
- Risk: Timing drift, stuck notes, or incorrect MIDI messages during playback.
- Priority: Medium -- these are user-facing features.

**iOS App (Entire):**
- What's not tested: No test files exist for the iOS app. No unit tests, no UI tests.
- Files: `iOSApp/EP133SampleTool/` (all source files)
- Risk: MIDIManager CoreMIDI integration, WKWebView polyfill injection, and MIDI bridge message handling are all untested.
- Priority: Medium -- iOS is currently just a WebView wrapper, but the MIDI bridge is critical.

**JUCE Plugin:**
- What's not tested: The JUCE plugin source appears to have been removed or is not present in the repository (empty `JucePlugin/` at the glob level). The build scripts reference it but source files were not found.
- Files: `JucePlugin/` directory (no source files found by glob)
- Risk: Plugin may be stale or unmaintained. If source exists elsewhere, it has no test coverage.
- Priority: Low -- desktop plugin is a secondary target.

**Web App (data/index.js):**
- What's not tested: The compiled web app has no visible test infrastructure. The source is not in this repository (only the compiled bundle).
- Files: `data/index.js` (compiled, 1.75MB)
- Risk: Core business logic (sample management, sysex communication, audio processing) lives in untestable compiled code.
- Priority: High impact but likely out of scope -- the web app source appears to be maintained separately.

---

*Concerns audit: 2026-03-27*
