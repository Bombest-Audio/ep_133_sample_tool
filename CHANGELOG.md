# Changelog

All notable changes to this project are tracked here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims for
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

The big one: this release turns a desktop-only tool into a real cross-platform app
and opens the whole thing up as a community project.

### Added
- Native **Android** app — Kotlin + Jetpack Compose. Pads, Beats, Sounds, Chords,
  Device, Projects, and Sample Import screens talking straight to the MIDI layer,
  with the original web app kept as a WebView fallback for backup/restore/sync.
- Native **iOS** app (Swift/SwiftUI WKWebView wrapper) and a **JUCE** AU/VST3
  plugin wrapper for the DAW.
- **Import your own WAV samples over USB** straight to the device's `/sounds` — the
  hard part. Built on a from-scratch reverse-engineering of the EP-133 file-transfer
  protocol, verified on real hardware.
- `docs/ep133-sysex-protocol.md` — the full reverse-engineered EP-133 / EP-1320
  USB-MIDI SysEx file protocol: frame format, the session handshake, 7-bit packing,
  the node tree, paged uploads, and every gotcha that cost real time.
- Open-source project setup: MIT `LICENSE` for our code, a `NOTICE` that draws the
  line around Teenage Engineering's bundled assets, `CODE_OF_CONDUCT.md`,
  `SECURITY.md`, this changelog, and GitHub issue/PR templates.

### Changed
- `package.json` now declares its MIT license and points at the Bombest-Audio org.

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

[Unreleased]: https://github.com/Bombest-Audio/ep_133_sample_tool/compare/1.2.0...HEAD
[1.2.0]: https://github.com/Bombest-Audio/ep_133_sample_tool/compare/1.1.0...1.2.0
[1.1.0]: https://github.com/Bombest-Audio/ep_133_sample_tool/releases/tag/1.1.0
