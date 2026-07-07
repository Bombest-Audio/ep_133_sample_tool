# UI test generation prompt

You are generating Compose UI tests for the EP-133 Sample Tool Android app. Draft ONE test
class that closes the coverage gap named at the bottom of this prompt. Output ONLY the
Kotlin file content — no prose, no code fences.

## House style (non-negotiable)

- JUnit4 + `createComposeRule()`, Kotlin, package `com.ep133.sampletool.generated`.
- **Robot pattern with method chaining.** Never call `onNodeWithText`/`onNodeWithTag`
  directly in a test — go through the robots in `com.ep133.sampletool.robots`:
  `AppRobot` (navigation/header), `PadsRobot`, `DeviceRobot`, `ProjectsRobot`, `ImportRobot`.
  If a needed action/assertion is missing, note it in a `// TODO(robot):` comment instead
  of inlining a selector.
- **Arrange–Act–Assert.** Mark the sections with `// Arrange`, `// Act`, `// Assert`
  comments; chained calls may combine Act+Assert.
- Fakes only, no mocking framework:
  - `ScriptedMIDIRepository` (in `com.ep133.sampletool.support`) — scriptable
    `listProjects`, `putSampleFile`, stats, active group; records sent MIDI frames.
  - `TestMIDIRepository.connectedState(...)` for connected-device states.
  - `FakeFirmwareCatalog` for firmware checks.
  - Full-app tests: `composeTestRule.launchEP133App(repo, catalog)` returns a
    `TestAppContext` with the ViewModels and recorded SAF/updater callback invocations.
- Selectors come from `com.ep133.sampletool.ui.TestTags`. Button labels render UPPERCASE
  (`Ep133PrimaryButton`/`Ep133GhostButton` uppercase their labels).
- Pad visual states are asserted via `PadsRobot.assertPadState(index, state)` with
  `TestTags.PAD_STATE_PRESSED` / `PAD_STATE_IN_SCALE` / `PAD_STATE_IDLE`.
- Async flows: use the robots' `waitForRowState`/`waitFor*` helpers, never `Thread.sleep`.

## Scope rules — do not duplicate existing coverage

UI tests assert **rendering, interaction, navigation, and state-driven UI branches**.
Do NOT re-test ViewModel/protocol logic — the JVM unit suite owns note math, SysEx codecs,
backup tar handling, firmware version comparison, and sample-name sanitization.

Existing test methods (do not re-test these behaviors):

{{EXISTING_TESTS}}

## Gap to close

{{GAP}}
