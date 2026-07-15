# clap-render

Standalone offline CLAP render CLI for the EP-133 Sample Tool. Loads a `.clap`
plugin, runs a WAV file through it block by block, and writes the processed
result as PCM16. The Electron app spawns this binary per operation so
third-party plugin code never runs inside the app process. Decision record:
`docs/adr-001-clap-hosting.md`.

## Build

```bash
cd tools/clap-render
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
```

Requires CMake 3.22+ and a C++17 compiler. The CLAP headers
(github.com/free-audio/clap, MIT) are fetched at configure time via
FetchContent; nothing is vendored into this repo.

## Usage

```bash
# List parameters (tab-separated: id, name, min, max, default)
clap-render --plugin "/Library/Audio/Plug-Ins/CLAP/Some Plugin.clap" --list-params

# Render with defaults
clap-render --plugin Some.clap --in in.wav --out out.wav

# Set parameters by numeric id or exact name, activate at the EP-133's rate
clap-render --plugin Some.clap \
  --param "Output Gain=0.25" --param 1706566249=0.5 \
  --in in.wav --out out.wav --sample-rate 46875
```

Behavior and limits:

- Input WAV: PCM16 or float32, mono or stereo. Output: PCM16, same channel
  count and length as the input.
- The first plugin declaring the `audio-effect` feature in the factory is
  instantiated (falls back to the factory's first plugin).
- Mono input into a stereo-only plugin duplicates the channel; extra plugin
  output channels are dropped past the input's channel count.
- `--sample-rate` overrides the plugin activation rate and the output header
  rate. No resampling is performed; a note is printed when the input WAV's
  rate differs.
- Parameter values are validated against the plugin's declared min/max.
- Any plugin fault (load, init, activate, process) exits non-zero with a
  message on stderr. stdout is reserved for `--list-params` output.
- Reverb/delay tails past the input length are not rendered in v1; append
  silence to the input if you need the tail.

## Electron integration

`main.js` registers `clap:*` IPC handlers backed by `clap-render-bridge.js`,
exposed to the page as `window.clapTools` via `preload.js`. The CLAP FX panel
in `data/custom.js` lets you pick a plugin and a WAV, tweak parameters,
preview, and drag the rendered file onto a pad to import it.

In dev the app resolves the binary at `tools/clap-render/build/clap-render`.
Packaging the binary into the distributable app (per-platform artifact in the
electron-builder config) is a follow-up.
