# Testing Patterns

**Analysis Date:** 2026-03-27

## Test Framework

### Android (Primary Test Target)

**Runner:**
- JUnit 4 with AndroidX Test Extensions
- Compose UI Test (`androidx.compose.ui:ui-test-junit4`)
- Config: `AndroidApp/app/build.gradle.kts` line 17: `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`

**Assertion Library:**
- Compose UI test matchers: `assertIsDisplayed()`, `onNodeWithText()`
- JUnit assertions (standard)

**Run Commands:**
```bash
cd AndroidApp
./gradlew connectedAndroidTest    # Run instrumented tests on device/emulator
./gradlew test                     # Run unit tests (JVM)
```

### Electron / Web App

- **No test framework configured** -- no jest, vitest, mocha, or similar
- No test scripts in `package.json`
- The web app core (`data/index.js`) is a compiled bundle with no test infrastructure

### iOS

- **No test files detected** -- no XCTest or Swift Testing files found
- No `*Tests.swift` files in the `iOSApp/` directory

### JUCE Plugin

- **No test files** -- `JucePlugin/Source/` directory is empty (source not committed)

## Test File Organization

### Location

Tests live exclusively in the Android instrumented test directory:
```
AndroidApp/app/src/androidTest/java/com/ep133/sampletool/
  BeatsScreenTest.kt       # Beats/sequencer screen UI tests
  DeviceScreenTest.kt      # Device info screen UI tests
  NavigationTest.kt        # Bottom navigation tab switching tests
  PadsScreenTest.kt        # Pad grid screen UI tests
  SoundsScreenTest.kt      # Sound browser screen UI tests
  TestMIDIRepository.kt    # Test double (fake MIDIRepository)
```

**Unit tests directory exists but is empty:**
```
AndroidApp/app/src/test/java/com/    # No .kt files
```

### Naming

- Screen tests: `{Feature}ScreenTest.kt` (e.g., `PadsScreenTest.kt`, `BeatsScreenTest.kt`)
- Navigation tests: `NavigationTest.kt`
- Test doubles: `Test{Component}.kt` (e.g., `TestMIDIRepository.kt`)
- Test methods: `snake_case` describing feature and expected behavior:
  - `padGrid_displaysChannelALabels()`
  - `bottomNav_switchToBeatsTab()`
  - `soundList_displaysFactorySounds()`

## Test Structure

### Suite Organization

Each test class follows this pattern:

```kotlin
class PadsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Private setUp helper — creates Compose content with a test double
    private fun setUpPads(midi: MIDIRepository = TestMIDIRepository()) {
        composeTestRule.setContent {
            EP133Theme {
                PadsScreen(viewModel = PadsViewModel(midi))
            }
        }
    }

    @Test
    fun padGrid_displaysChannelALabels() {
        setUpPads()
        composeTestRule.onNodeWithText("A.").assertIsDisplayed()
        composeTestRule.onNodeWithText("A2").assertIsDisplayed()
        composeTestRule.onNodeWithText("A5").assertIsDisplayed()
    }
}
```

**Key patterns:**
- `createComposeRule()` as a `@get:Rule` for Compose testing (no Activity needed)
- Private `setUp{Feature}()` method that creates content with injectable test doubles
- Each test calls setUp first, then performs assertions
- Tests are focused on **single UI assertions** (one concern per test)

### Navigation Test Pattern

Navigation tests create the full `EP133App` composable with all ViewModels:

```kotlin
class NavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setUpApp(midi: MIDIRepository) {
        val sequencer = SequencerEngine(midi)
        composeTestRule.setContent {
            EP133App(
                padsViewModel = PadsViewModel(midi),
                beatsViewModel = BeatsViewModel(sequencer, midi),
                soundsViewModel = SoundsViewModel(midi),
                deviceViewModel = DeviceViewModel(midi),
            )
        }
    }

    @Test
    fun bottomNav_switchToBeatsTab() {
        setUpApp(TestMIDIRepository())
        composeTestRule.onNodeWithText("BEATS").performClick()
        composeTestRule.onNodeWithText("BPM").assertIsDisplayed()
    }
}
```

## Mocking / Test Doubles

### Framework

**No mocking framework** (no Mockito, MockK, or Turbine). Test doubles are hand-written.

### Test Double Pattern

The `TestMIDIRepository` is a **fake** that implements the `MIDIPort` interface with no-op methods:

```kotlin
// TestMIDIRepository.kt
class TestMIDIRepository : MIDIRepository(NoOpMIDIPort())

private class NoOpMIDIPort : MIDIPort {
    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null
    override fun getUSBDevices() = MIDIPort.Devices(emptyList(), emptyList())
    override fun sendMidi(portId: String, data: ByteArray) {}
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}
```

**Key design:**
- `MIDIPort` is an **interface** (`AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIPort.kt`) specifically to enable testability
- `MIDIRepository` is **open** (not final) -- `TestMIDIRepository` extends it with a no-op port
- The fake returns empty device lists and silently discards all MIDI sends
- Device state stays disconnected (no connected devices)

### What to Mock

- **Always mock:** MIDI hardware access (`MIDIPort` interface)
- MIDI sends, USB device enumeration, and permission requests are all no-ops in tests

### What NOT to Mock

- **ViewModels** are created with real implementations
- **SequencerEngine** is instantiated with the test MIDI repository
- **Domain logic** (pad mappings, chord progressions) uses real code
- **Compose UI** is tested as rendered (not mocked)

## Test Data

Tests rely on the real `EP133Pads` and `EP133Sounds` data objects for expected values:

```kotlin
// Tests assert real pad labels from EP133Pads
composeTestRule.onNodeWithText("KICK").assertIsDisplayed()
composeTestRule.onNodeWithText("SNARE").assertIsDisplayed()
composeTestRule.onNodeWithText("MICRO KICK").assertIsDisplayed()
```

No test fixtures directory or factory functions beyond `TestMIDIRepository`.

## Coverage

**Requirements:** None enforced. No coverage thresholds configured.

**Current coverage map:**

| Component | Tested | Type |
|-----------|--------|------|
| PadsScreen UI | Yes | Instrumented (Compose) |
| BeatsScreen UI | Yes | Instrumented (Compose) |
| SoundsScreen UI | Yes | Instrumented (Compose) |
| DeviceScreen UI | Yes | Instrumented (Compose) |
| Bottom navigation | Yes | Instrumented (Compose) |
| ChordsScreen UI | **No** | -- |
| PadPickerSheet UI | **No** | -- |
| MIDIRepository logic | **No** | -- |
| SequencerEngine logic | **No** | -- |
| ChordPlayer logic | **No** | -- |
| MIDIManager (Android) | **No** | -- |
| MIDIBridge (Android) | **No** | -- |
| EP133WebViewSetup | **No** | -- |
| iOS (all) | **No** | -- |
| Electron (all) | **No** | -- |
| MIDI Polyfill (JS) | **No** | -- |
| Web app core | **No** | -- |

## Test Types

### Instrumented / UI Tests (Only type present)

- **Scope:** Compose UI rendering and interaction
- **Approach:** Render composables in isolation with `createComposeRule()`, assert visible text and perform clicks
- **Location:** `AndroidApp/app/src/androidTest/java/com/ep133/sampletool/`
- **Count:** 6 test files, ~20 individual test methods

**What they verify:**
- Default UI state renders correctly (labels, sound names, BPM values)
- Navigation between tabs works (click tab, verify destination content)
- Channel/bank switching updates the grid
- Factory data (sounds, pad labels) displays correctly

### Unit Tests

- **Not present.** The `src/test/` directory exists but contains no test files.
- Dependencies are declared in `build.gradle.kts`:
  ```
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
  ```
  These are configured but unused.

### E2E Tests

- **Not present** for any platform.

## CI/CD Test Configuration

The GitHub Actions workflow (`.github/workflows/build.yml`) does **not** run any tests:

```yaml
steps:
  - uses: actions/checkout@v4
  - uses: actions/setup-node@v4
    with:
      node-version: '18'
  - run: npm install
  - run: npm run package -- --publish=never
  - uses: actions/upload-artifact@v4
```

- Build-only CI -- no test step for Electron, Android, or iOS
- Triggered manually via `workflow_dispatch`
- No Android emulator setup or `./gradlew connectedAndroidTest` step
- No Android unit test step (`./gradlew test`)

## Common Patterns

### Async Testing (Available but unused)

The `kotlinx-coroutines-test` dependency is declared but no tests use it. Future unit tests for `SequencerEngine` or `MIDIRepository` should use:

```kotlin
@Test
fun sequencer_toggleStep_updatesState() = runTest {
    val midi = TestMIDIRepository()
    val engine = SequencerEngine(midi)
    engine.toggleStep(0, 0)
    assertEquals(1, engine.state.value.tracks[0].steps[0])
}
```

### Adding New Screen Tests

Follow the established pattern:
1. Create `{Feature}ScreenTest.kt` in `androidTest/java/com/ep133/sampletool/`
2. Use `createComposeRule()` as `@get:Rule`
3. Write a `setUp{Feature}()` helper that sets compose content with `TestMIDIRepository()`
4. Name tests: `featureArea_expectedBehavior()`
5. Use `onNodeWithText()` for assertions (the primary matcher in this codebase)

### Adding Unit Tests

Place in `AndroidApp/app/src/test/java/com/ep133/sampletool/`:
1. Use JUnit 4 (`@Test`, `assertEquals`)
2. Use `kotlinx-coroutines-test` (`runTest`, `TestDispatcher`) for Flow/coroutine testing
3. Use `TestMIDIRepository` as the MIDI fake (it is in `androidTest` -- would need to be moved to a shared test fixtures module or duplicated)

**Note:** `TestMIDIRepository.kt` currently lives in `androidTest/` only. To use it in JVM unit tests, extract the `MIDIPort` fake to a shared test source set or create a duplicate in `test/`.

---

*Testing analysis: 2026-03-27*
