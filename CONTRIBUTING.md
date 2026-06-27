# Contributing

Thanks for pitching in. By participating you agree to the
[Code of Conduct](CODE_OF_CONDUCT.md). Security issues go to
[SECURITY.md](SECURITY.md), not the public tracker.

Heads up on licensing: your contributions to **our** code ship under
[MIT](LICENSE). Don't modify or add to the Teenage Engineering assets under
`data/` — those aren't ours (see [NOTICE](NOTICE)).

## Repository Structure

```
ep_133_sample_tool/
├── data/              # Compiled web app — do not edit index.js
├── shared/            # Cross-platform MIDI polyfill + EP-133 data JSON
├── AndroidApp/        # Kotlin/Compose Android app
├── iOSApp/            # Swift/SwiftUI iOS app
├── JucePlugin/        # C++/JUCE AU+VST3 plugin
├── scripts/           # Build automation
└── docs/              # Architecture docs, screenshots
```

Each platform has its own README with build instructions.

---

## Branch Naming

| Type | Pattern | Example |
|------|---------|---------|
| Feature | `feature/{description}` | `feature/android-chord-builder` |
| Bug fix | `fix/{description}` | `fix/midi-stuck-notes` |
| Maintenance | `chore/{description}` | `chore/update-gradle-wrapper` |
| Docs | `docs/{description}` | `docs/android-readme` |

---

## Commit Format

```
type(scope): short description (under 72 chars)

Body explains WHY, not what (the diff shows what).
Reference issues: Fixes #123
```

**Types:** `feat` `fix` `refactor` `test` `docs` `chore` `ci` `perf` `style`

**Examples:**
```
feat(android): add offline chord preview via AudioTrack synthesizer
fix(midi): snapshot playedViaHardware to prevent stuck notes on disconnect
docs: add per-platform READMEs and architecture overview
ci: add Android unit test + APK build workflow
```

---

## Setup by Platform

### Desktop (Electron)
```bash
npm install
npm start
```
Requires: Node.js 18+

### Android
```bash
cd AndroidApp
./gradlew assembleDebug
```
Requires: Android Studio, SDK 35, JDK 17

### iOS
Open `iOSApp/EP133SampleTool.xcodeproj` in Xcode 15+. Set your development team.  
Requires: macOS, Xcode 15+

### JUCE Plugin
```bash
cd JucePlugin
cmake -B build && cmake --build build --config Release
```
Requires: macOS, CMake 3.22+, Xcode 15+

---

## PR Checklist

- [ ] Tests pass: `./gradlew :app:testDebugUnitTest` (Android), or relevant platform tests
- [ ] No build artifacts committed (APKs, `dist/`, `node_modules/`, `build/`, `DerivedData/`)
- [ ] No secrets, API keys, or `.env` files
- [ ] Commit messages follow conventional commit format
- [ ] `AndroidApp/local.properties` is **not** committed (it's in `.gitignore`)
- [ ] New Kotlin code follows existing patterns: `val` over `var`, `StateFlow` for UI state, `viewModelScope` for coroutines

---

## Code Style Notes

**Kotlin/Android:**
- `val` over `var` — immutability by default
- `StateFlow`/`SharedFlow` for reactive state; never expose `MutableStateFlow` publicly
- Coroutines: `viewModelScope` in ViewModels, `lifecycleScope` in Activities, never `GlobalScope`
- Always rethrow `CancellationException` — never swallow it
- `when` expressions over if/else chains

**Commits on `main`:** Protected. Open a PR — CI must pass before merge.
