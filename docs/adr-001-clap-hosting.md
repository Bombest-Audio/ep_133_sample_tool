# ADR-001: Where CLAP plugin processing lives

**Status:** Proposed
**Date:** 2026-07-15
**Issue:** #53 (run CLAP plugins on samples before they hit the device)

## Context

I want to run a sample through a CLAP plugin (EQ, saturation, whatever) right before it loads onto the EP-133, without a DAW round trip. The app has no native audio host today:

- The desktop app is Electron + WebView. All business logic is JS in `data/`.
- The JUCE plugin (issue #14) is a MIDI-only stub. It wraps the same web app and does no audio processing.
- Android/iOS wrap the web app too, plus Android has native Compose screens. Neither can host desktop CLAP binaries at all (wrong ABI, wrong platform - CLAP plugins ship as desktop `.clap` packages: a macOS bundle, a Windows DLL, or a Linux .so).

So this is a desktop-only feature by nature, and the host has to be native code.

## Options

### 1. JUCE offline render context inside the plugin/standalone (blocked on #14)

Extend the JUCE wrapper with `juce::AudioPluginFormatManager` + the clap-juce-extensions or a CLAP hosting shim, render offline, hand the result back to the web app.

- Pro: JUCE is the stack I already ship; one native codebase.
- Con: the JUCE wrapper is a plugin, not an app - hosting plugins inside a plugin is awkward, and JUCE has no first-party CLAP *hosting* support (only CLAP *building* via clap-juce-extensions). #14 isn't merged yet.

### 2. Electron native addon (N-API) linking clap-host

A small C++ N-API module in the Electron app that loads a CLAP, runs an offline render on a WAV buffer, returns the processed buffer to the renderer.

- Pro: lands exactly where the desktop import flow already lives; no dependency on #14; the clap-host reference implementation is small and permissively licensed.
- Con: native build matrix (mac arm64/x64, Windows) in the Electron packaging pipeline; crash isolation - a bad plugin takes down the app unless the addon runs out-of-process.

### 3. Separate render CLI, spawned by Electron

A standalone `clap-render` binary (CMake, clap-host based): `clap-render --plugin X.clap --preset p --in in.wav --out out.wav`. Electron spawns it per render.

- Pro: full crash isolation (a misbehaving plugin kills the CLI, not the app); trivially testable from the shell; reusable from CI and from the JUCE wrapper later; no N-API/electron-rebuild matrix.
- Con: process-spawn latency per render (fine for offline sample prep); parameter UI has to be either generic (parameter list → sliders) or headless preset-only in v1.

## Decision

**Option 3: separate render CLI.** Crash isolation is the deciding factor - third-party plugin code must not be able to take down the app - and offline sample prep doesn't need low latency. The CLI is also the piece every other wrapper can reuse later.

V1 scope: load a `.clap`, enumerate parameters, apply a parameter set or preset, offline-render a WAV at 46875 Hz mono/stereo, exit non-zero on any plugin fault. Electron side: file picker for the plugin, generic parameter form, preview render, then feed the processed WAV into the existing import path.

Out of scope for v1: plugin GUI embedding, live preview streaming, Android/iOS anything.

## Consequences

- Needs a new `tools/clap-render/` CMake project vendoring the clap and clap-host headers (MIT).
- Electron packaging gains a per-platform binary artifact.
- #53 stops being blocked on #14; they proceed independently.
- If plugin GUI embedding is ever wanted, revisit option 2 as an addition, not a replacement.
