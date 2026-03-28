# Coding Conventions

**Analysis Date:** 2026-03-27

## Language-Specific Conventions

This is a multi-platform codebase with four distinct code areas, each with different conventions:

| Area | Language | Location |
|------|----------|----------|
| Web app (core) | JavaScript (ES5 in polyfill, compiled JS bundle) | `data/`, `shared/`, `main.js`, `preload.js`, `renderer.js` |
| Electron wrapper | Node.js / JavaScript | `main.js`, `preload.js`, `renderer.js` |
| iOS app | Swift / SwiftUI | `iOSApp/EP133SampleTool/` |
| Android app | Kotlin / Jetpack Compose | `AndroidApp/app/src/main/java/com/ep133/sampletool/` |

The Android app is the most actively developed native target and has the richest conventions.

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

```
com.ep133.sampletool/
  domain/
    midi/       # MIDIRepository, ChordPlayer
    model/      # Data classes, enums, constants (EP133.kt, ChordProgressions.kt)
    sequencer/  # SequencerEngine
  midi/         # Platform MIDI (MIDIManager, MIDIPort interface)
  ui/
    beats/      # BeatsScreen, BeatsViewModel
    chords/     # ChordsScreen, ChordBuilderScreen, ChordsViewModel
    device/     # DeviceScreen, DeviceViewModel
    pads/       # PadsScreen, PadsViewModel
    sounds/     # SoundsScreen, SoundsViewModel, PadPickerSheet
    theme/      # Color.kt, Theme.kt, Type.kt
  webview/      # EP133WebViewSetup, MIDIBridge
```

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

Imports follow this order (observed across all files):
1. `android.*` / platform imports
2. `androidx.*` / Jetpack/Compose imports
3. Third-party libraries (`org.json`, etc.)
4. Project imports (`com.ep133.sampletool.*`)
5. `kotlinx.*` (coroutines, flow)

No blank lines between groups. Imports are listed individually (no wildcard `*` imports).

### Swift

1. Apple frameworks (`SwiftUI`, `WebKit`, `CoreMIDI`, `Foundation`)
2. No third-party dependencies

### JavaScript

- `require()` calls at file top in Node.js (`main.js`)
- IIFE pattern in `MIDIBridgePolyfill.js` with `'use strict'`

## Error Handling

### Kotlin Patterns

**Use `try/finally` for cleanup (good):**
```kotlin
// MIDIRepository.kt
fun refreshDeviceState() {
    if (isRefreshing) return
    isRefreshing = true
    try {
        midiManager.refreshDevices()
    } finally {
        isRefreshing = false
    }
    ...
}
```

**Catch specific exceptions, but use empty catch blocks for cleanup:**
```kotlin
// MIDIManager.kt - closing resources
for ((_, port) in openInputPorts) {
    try { port.close() } catch (_: Exception) {}
}
```

**Return early on missing data (null-safe):**
```kotlin
// MIDIRepository.kt
fun noteOn(note: Int, velocity: Int = 100, ch: Int = channel) {
    val portId = _deviceState.value.outputPortId ?: run {
        Log.w("EP133APP", "MIDI OUT: no output port! note=$note ch=$ch")
        return
    }
    ...
}
```

**CancellationException always rethrown in suspend functions:**
```kotlin
// ChordPlayer.kt
} catch (e: CancellationException) {
    stopCurrentChord()
    onStep(-1)
    throw e
}
```

**CancellationException caught but not rethrown in non-suspend sequencer loops:**
```kotlin
// SequencerEngine.kt - catches to cleanly exit the loop
} catch (_: CancellationException) {}
```

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
  - `Log.d(TAG, ...)` for debug/trace info
  - `Log.i(TAG, ...)` for significant events (device connected, port opened)
  - `Log.w(TAG, ...)` for warnings (no output port)
  - `Log.e(TAG, ...)` for errors (send failures, injection failures)
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

```kotlin
/**
 * High-level MIDI interface for the EP-133.
 *
 * Wraps a [MIDIPort] implementation with typed helpers for Note On/Off, CC,
 * and Program Change. Exposes device state as a [StateFlow] for Compose observation.
 */
open class MIDIRepository(private val midiManager: MIDIPort) {
```

```kotlin
/** One track in the sequencer. */
data class SeqTrack(...)
```

### Section Markers (Kotlin)

```kotlin
// ── MIDI message senders ──

// ── EDIT mode ───────────────────────────────────────

// ── Internal loops ──────────────────────────────────
```

### Section Markers (Swift)

```swift
// MARK: - Setup
// MARK: - Device Enumeration
// MARK: - Send MIDI
// MARK: - Helpers
```

## Compose UI Patterns

### State Hoisting

ViewModels expose state as `StateFlow<T>` and provide event callbacks. Composables collect state and delegate actions:

```kotlin
// ViewModel exposes state
val selectedChannel: StateFlow<PadChannel> = _selectedChannel.asStateFlow()

// Composable collects it
@Composable
fun PadsScreen(viewModel: PadsViewModel) {
    val selectedChannel by viewModel.selectedChannel.collectAsState()
    ...
}
```

### ViewModel Co-location

ViewModels are defined in the same file as their Screen composable. The pattern is:
1. ViewModel class at top of file
2. Main `@Composable fun {Feature}Screen(viewModel: ...)` next
3. Private helper composables below

### Theme Usage

- Use `MaterialTheme.colorScheme.*` for standard colors
- Use `TEColors.*` (from `ui/theme/Color.kt`) for brand-specific colors (Orange, Teal, PadBlack)
- Use `MaterialTheme.typography.*` for text styles
- Wrap all screens in `EP133Theme { ... }`

### Modifier Chaining

Standard order: layout modifiers first, then visual, then interactive:
```kotlin
Modifier
    .fillMaxWidth()
    .padding(horizontal = 16.dp, vertical = 8.dp)
    .background(color, shape)
    .clickable { ... }
```

### Animation

Use `animateColorAsState` and `animateDpAsState` with `tween()` for UI state transitions:
```kotlin
val backgroundColor by animateColorAsState(
    targetValue = if (isPressed) Color(0xFF3A2018) else TEColors.PadBlack,
    animationSpec = tween(durationMillis = 40),
    label = "padBg",
)
```

## Dependency Injection

**Manual construction** -- no DI framework (no Hilt, Koin, or Dagger). Dependencies are wired in `MainActivity.onCreate()`:

```kotlin
val systemMidiManager = getSystemService(Context.MIDI_SERVICE) as MidiManager
midiManager = MIDIManager(this, systemMidiManager)
midiRepo = MIDIRepository(midiManager)
sequencer = SequencerEngine(midiRepo)

val padsViewModel = PadsViewModel(midiRepo)
val beatsViewModel = BeatsViewModel(sequencer, midiRepo)
```

ViewModels receive dependencies via constructor parameters (not `@Inject`).

## Git Workflow

### Commit Messages

Recent commits follow **conventional commit** format:
- `feat(android): native Compose UI with bidirectional MIDI pad control`
- `feat(mobile): add iOS and Android native app wrappers`

Older commits are informal: `fixed readme`, `new update testing`, `fixed defaults`

Use `feat(scope): description` for new work. Scope by platform: `android`, `mobile`, `ios`.

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

---

*Convention analysis: 2026-03-27*
