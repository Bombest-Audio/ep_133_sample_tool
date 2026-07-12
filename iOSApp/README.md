# EP-133 Sample Tool — iOS

Native Swift/SwiftUI app for managing the Teenage Engineering EP-133 K.O. II from iPhone/iPad over USB-C. Feature parity with the Android app: native Pads, Samples (chop + kit builder), Projects, and Device screens on top of the same reverse-engineered SysEx protocol, with the compiled web tool available as a sheet for backup/restore/format.

## Requirements

| Tool | Version |
|------|---------|
| Xcode | 16+ |
| iOS Deployment Target | 17+ |
| Device | USB-C iPhone (15+) or USB-C iPad |
| Apple Developer Account | Required for device builds (free tier works) |

The iOS 17 floor is deliberate: the app targets USB-C devices only (every USB-C iPhone ships with iOS 17), and the codebase uses the Observation framework throughout.

## Build & Run

1. Open `EP133SampleTool.xcodeproj` in Xcode
2. Select your target device or simulator in the toolbar
3. Set your development team: **Signing & Capabilities** → **Team**
4. Press **⌘R** to build and run

No external package managers (CocoaPods, SPM, Carthage). Uses system frameworks only.

## Tests

```bash
# Unit tests (~280, mirrors the Android unit suite)
xcodebuild -project EP133SampleTool.xcodeproj -scheme EP133SampleTool \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  test -only-testing:EP133SampleToolTests

# UI tests (robot pattern, mirrors the Android instrumented suite)
xcodebuild -project EP133SampleTool.xcodeproj -scheme EP133SampleTool \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  test -only-testing:EP133SampleToolUITests
```

CI runs both plus an unsigned Release build: `.github/workflows/build-ios.yml`.

## Connecting to EP-133

1. Connect the EP-133 to your iPhone/iPad with a USB-C cable
2. Launch the app — CoreMIDI detects the device automatically
3. No hardware handy? Debug builds have a **SIMULATED EP-133** toggle on the Device screen that swaps in a wire-level protocol simulator

## Architecture

One shared domain core drives native SwiftUI screens; the legacy web tool rides along for backup/restore/format:

```
EP133SampleToolApp                  # creates MIDIManager → MIDIRepository once, injects via environment
  └── AppShell                      # PADS / SAMPLES / PROJ / DEVICE tabs (screens stay mounted)
        ├── PadsScreen              # multi-touch 4×3 grid, groups A–D, scale lock
        ├── KitScreen               # chop flow + kit flow (+ KitBuilderScreen: pack browsing, auditions)
        ├── ProjectsScreen          # slot browser, app-side names, backup library, share
        └── DeviceScreen            # stats, firmware banner, sim toggle, web-tool sheet (EP133WebView)
```

### Key directories

```
iOSApp/EP133SampleTool/
├── App/                    # entry point, environment wiring
├── UI/                     # AppShell, screens (ViewModels co-located), Theme/ (TE design system), TestTags
├── Domain/
│   ├── MIDI/               # SysExProtocol, MIDIRepository, FileWaiterRegistry, backup + import managers
│   ├── Audio/              # AudioDecoder, Resampler, WavEncoder, LoopSlicer
│   ├── Model/              # EP133 pads/scales/factory sounds
│   ├── Pack/               # sample-pack loader
│   ├── Project/            # app-side project names
│   └── Firmware/           # version parsing, TE release catalog
├── MIDI/                   # CoreMIDI MIDIManager, MIDIPort seam, EP133DeviceSimulator (DEBUG only)
└── WebView/                # WKWebView host for the compiled web tool + MIDI polyfill bridge
```

Every domain type carries a doc comment naming its Kotlin counterpart in `AndroidApp/` — the two apps are ports of the same hardware-verified protocol implementation. Protocol reference: [`docs/ep133-sysex-protocol.md`](../docs/ep133-sysex-protocol.md).

## Notes

- The web app bundle (`data/`) is included as a folder reference; the MIDI polyfill comes from `shared/MIDIBridgePolyfill.js`
- MIDI input uses the legacy CoreMIDI packet API on purpose — raw MIDI 1.0 bytes, no UMP SysEx7 re-framing
- Release builds contain zero simulator code (`#if DEBUG` gate, verified against the binary's symbol table)
