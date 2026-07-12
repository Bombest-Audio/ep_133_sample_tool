# Changelog

All notable changes to this project are tracked here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims for
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.0.1] - 2026-07-12

A privacy fix and a big iOS addition since 2.0.0. The Android APK below is rebuilt from
current `main`; the native iOS app ships separately (TestFlight or build from source).

### Security / Privacy
- **Disabled leftover telemetry.** The bundled Teenage Engineering web tool still had its
  Sentry error-reporting live (a real DSN plus `enabled: true`). It's now fully disabled, so
  the app sends no crash or analytics data anywhere. This makes the "no telemetry" promise
  actually true, verified with the network inspector.
- Added a [privacy policy](https://bombest-audio.github.io/ep_133_sample_tool/privacy.html),
  an iOS privacy manifest, and the iOS export-compliance flag.

### Added
- **Native iOS app** rebuilt in Swift/SwiftUI, replacing the old WebView-only wrapper. Native
  Pads, Device, Projects, Kit, and Sample Import screens talk straight to the MIDI layer, with
  the web tool kept as a backup/restore sheet.

### Changed
- Large internal clean-up pass across the Android app (extracted file-transfer client and
  pad-assignment service, typed port ids, decomposed screens) with no change to behavior. The
  same hardening landed on iOS.

## [2.0.0] - 2026-06-26

The big one: this release turns a desktop-only tool into a real cross-platform app
and opens the whole thing up as a community project.

### Added
- Native **Android** app built in Kotlin + Jetpack Compose. Pads, Device, Projects,
  and Sample Import screens talking straight to the MIDI layer, with the original
  web app kept as a WebView fallback for backup/restore/sync. (Beats, Sounds, and
  Chords are sitting out this first release until their hardware behavior is solid.)
- A **Teenage-Engineering-grade redesign** of the whole Android UI: every screen
  rebuilt on a hardware-faithful design system (faceplate grays, rubberized pads,
  mono labels, hairline rules). Light and dark themes, plus an EP-133 ↔ EP-1320
  rust SKU you flip right from the header.
- **Signed release builds** for Android, so the APK installs clean as a sideload.
- Native **iOS** app (Swift/SwiftUI WKWebView wrapper) and a **JUCE** AU/VST3
  plugin wrapper for the DAW.
- **Import your own WAV samples over USB** straight to the device's `/sounds`, the
  hard part. Built on a from-scratch reverse-engineering of the EP-133 file-transfer
  protocol, verified on real hardware.
- `docs/ep133-sysex-protocol.md`, the full reverse-engineered EP-133 / EP-1320
  USB-MIDI SysEx file protocol: frame format, the session handshake, 7-bit packing,
  the node tree, paged uploads, and every gotcha that cost real time.
- Open-source project setup: MIT `LICENSE` for our code, a `NOTICE` that draws the
  line around Teenage Engineering's bundled assets, `CODE_OF_CONDUCT.md`,
  `SECURITY.md`, this changelog, and GitHub issue/PR templates.

### Changed
- `package.json` now declares its MIT license and points at the Bombest-Audio org.

### Fixed
- Rebuilt how the app handles EP-133 file responses: every reply is now matched to
  its request by request id, instead of guessing from in-flight flags. The old way
  dropped reqId-matching replies under rapid or overlapping transfers, silently
  breaking sample import, truncating backups, and forcing active-group sync off.
  It's race-proof now.
- **Backups no longer truncate.** Multi-chunk sample files download in full instead
  of stopping after the first chunk (older backups came out with barely more than a
  metadata file).
- Device→app **active-group sync** works again: tap a group on the hardware and the
  app follows along.

## [1.2.0] - 2025-05-28

### Added
- "Projects Only" backup/restore — faster than a full backup and keeps your base
  sounds untouched.
- Better zoom into the parts of the UI that matter.
- Custom color schemes and sample-group names via `data/custom.js`.

### Changed
- Device serial number stripped from the UI, backup filenames, and `meta.json` so
  backups can be shared freely.

### Removed
- All telemetry and tracking calls.

## [1.1.0] - 2024-10-10

### Added
- First fully offline build of the EP-133 sample tool — factory pack and all
  WebAssembly audio libraries bundled, no internet required.

[Unreleased]: https://github.com/Bombest-Audio/ep_133_sample_tool/compare/2.0.0...HEAD
[2.0.0]: https://github.com/Bombest-Audio/ep_133_sample_tool/compare/1.2.0...2.0.0
[1.2.0]: https://github.com/Bombest-Audio/ep_133_sample_tool/compare/1.1.0...1.2.0
[1.1.0]: https://github.com/Bombest-Audio/ep_133_sample_tool/releases/tag/1.1.0
