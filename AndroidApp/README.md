# EP-133 Sample Tool — Android

Native Kotlin/Jetpack Compose app for managing the Teenage Engineering EP-133 K.O. II from Android. Connects via USB OTG.

## Requirements

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| Android SDK | 34 (compile), 34 (target) |
| JDK | 17 |
| Min Android | 10 (API 29) — required for `android.media.midi` |

## Build

```bash
# Debug APK
./gradlew assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (requires signing config)
./gradlew assembleRelease

# Unit tests
./gradlew :app:testDebugUnitTest

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Connecting to EP-133

1. Enable USB debugging on your Android device
2. Connect EP-133 to the Android device via USB-C OTG cable (or USB-A OTG adapter)
3. Launch the app — a permission dialog will appear for the MIDI device
4. Grant permission; the **Device** tab will show connected status

USB Host capability and MIDI support are declared as required in `AndroidManifest.xml`.

## Testing without hardware (debug builds)

Debug builds include a **Simulated EP-133** toggle at the bottom of the **Device** screen. It's hidden in release builds — gated by `SimulatedPortProvider.available`, which reports `false` outside debug builds, so the toggle and its wiring never install there.

Flipping it on hot-swaps the MIDI stack from real USB to a wire-level `EP133DeviceSimulator` that speaks the actual EP-133 SysEx protocol — not a stub or mock — so pads, kit, projects, and device flows can all be exercised without a physical device attached. Real USB is always the default at launch; the toggle can be flipped on or off at any time while the app is running.

![Device screen with the toggle off, no hardware connected](../docs/assets/android_device_no_hw_sim_toggle.png)
![Device screen with the toggle on, connected to the simulator](../docs/assets/android_device_sim_connected.png)

## Architecture

```
MainActivity
  └── NavHost (Jetpack Compose)
        ├── PadsScreen       — 16-pad grid, multi-touch velocity
        ├── KitScreen        — CHOP/KIT modes, sample chopping + kit building
        │     └── KitBuilderScreen — Custom kit builder (KIT mode, inline composition)
        ├── ProjectsScreen   — Project backup/restore, device slot management
        └── DeviceScreen     — Device stats, PAK backup/restore
              └── SampleManagerActivity (WebView) — legacy sample manager
```

### Domain Layer

| Class | Purpose |
|-------|---------|
| `MIDIRepository` | Typed MIDI API — noteOn/Off, CC, programChange, loadSoundToPad, SysEx |
| `SequencerEngine` | 16-step sequencer, drift-compensated coroutine loop on `Dispatchers.Default` |
| `ChordPlayer` | Routes to EP-133 (MIDI) when connected, `LocalSynth` (AudioTrack) when offline |
| `BackupManager` | PAK backup/restore via EP-133 SysEx FILE_LIST + FILE_GET + FILE_PUT |

### Key Files

```
app/src/main/java/com/ep133/sampletool/
├── MainActivity.kt              # Entry point, USB broadcast receiver
├── SampleManagerActivity.kt     # WebView host for sample manager
├── domain/
│   ├── midi/
│   │   ├── MIDIRepository.kt    # Typed MIDI API, SysEx accumulator
│   │   ├── ChordPlayer.kt       # Chord routing (MIDI or offline synth)
│   │   ├── LocalSynth.kt        # AudioTrack offline synthesizer
│   │   └── SynthEngine.kt       # Interface for LocalSynth (testable)
│   ├── model/                   # Data classes: EP133Sound, Pad, ChordProgression, etc.
│   └── sequencer/
│       └── SequencerEngine.kt
├── midi/
│   ├── MIDIPort.kt              # Interface — abstracts android.media.midi
│   └── MIDIManager.kt           # USB MIDI implementation
└── ui/
    ├── beats/, chords/, device/, pads/, sounds/
    └── theme/                   # Material 3 theme, TEColors
```

## Tests

Unit tests live in `app/src/test/`. Instrumented tests in `app/src/androidTest/`.

```bash
./gradlew :app:testDebugUnitTest          # All JVM unit tests
./gradlew :app:testDebugUnitTest --tests "com.ep133.sampletool.ChordsViewModelTest"  # Single class
./gradlew :app:connectedDebugAndroidTest  # Instrumented (requires device/emulator)
```
