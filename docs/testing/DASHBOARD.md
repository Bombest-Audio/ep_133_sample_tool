# EP-133 Sample Tool — Test Dashboard

| Field | Value |
|-------|-------|
| Commit SHA | `e39e738` |
| Commit date | 2026-07-05T21:56:40-07:00 |
| Branch | `android-ui-test-framework` |
| Dashboard generated at | 2026-07-06T06:15:39Z |

## Test Suite Summary

| Suite | Test Classes | Test Methods | Result |
|-------|--------------|---------------|--------|
| Unit tests (`src/test`, `:app:testDebugUnitTest`) | 43 | 274 (0 failures, 0 errors, 13 skipped) | BUILD SUCCESSFUL |
| androidTest (instrumented, `src/androidTest`, physical Pixel 10) | 10 | 79 (79 failures, 0 errors, 0 skipped) | BUILD FAILED |

**Note (physical-device run, current):** The instrumented/androidTest suite was executed on 2026-07-06T06:12Z against the connected **physical** device `5C060DLCR000Y0` (Pixel 10, Android 17 / API 37, USB-attached, `adb devices -l` reports `usb:0-1 product:frankel model:Pixel_10 device:frankel`), scoped via `ANDROID_SERIAL=5C060DLCR000Y0 ./gradlew :app:connectedDebugAndroidTest`. A second device, `emulator-5554` (Pixel_Tablet AVD), was simultaneously connected but **did not participate** — confirmed explicitly (not assumed): the Gradle log shows `Starting 79 tests on Pixel 10 - 17` and contains zero mentions of `emulator-5554`/`Pixel_Tablet`/`emu64a` anywhere in the full build output, and only one device-named output directory was produced (`androidTest-results/connected/debug/Pixel 10 - 17/`) with no sibling tablet/emulator directory. Stale output directories were deleted before this run so the JUnit XML parsed is guaranteed fresh.

**Result: BUILD FAILED — all 79 test methods failed on real hardware**, across all 10 instrumented test classes (`AppNavigationTest` 7/7, `DeviceScreenTest` 11/11, `FirmwareBannerTest` 5/5, `KitBuilderScreenTest` 8/8, `KitScreenTest` 17/17, `PadsScreenTest` 7/7, `ProjectsScreenTest` 7/7, `SampleImportScreenTest` 6/6, `ScaleLockFlowTest` 3/3, `SimulatedDeviceTest` 8/8 — all failing). This is reported honestly rather than hidden: every single failure shares the identical root cause, verified verbatim from the JUnit XML:

```
java.lang.RuntimeException: java.util.concurrent.ExecutionException: java.lang.RuntimeException: java.lang.NoSuchMethodException: android.hardware.input.InputManager.getInstance []
  at androidx.test.espresso.Espresso.onIdle(Espresso.java:18)
  at androidx.compose.ui.test.junit4.EspressoLink_androidKt.runEspressoOnIdle(EspressoLink.android.kt:92)
  ...
Caused by: java.lang.NoSuchMethodException: android.hardware.input.InputManager.getInstance []
  at androidx.test.espresso.base.InputManagerEventInjectionStrategy.initialize(InputManagerEventInjectionStrategy.java:5)
```

This is a **framework/environment incompatibility**, not an application logic bug: Espresso's `InputManagerEventInjectionStrategy` reflects on the internal static method `android.hardware.input.InputManager.getInstance()`, which no longer exists in this signature on Android 17 (API 37) — a very recent OS release. Every Compose UI test that reaches `onIdle()` (i.e. all of them, since `createComposeRule()`/`AndroidComposeTestRule` drives idling through Espresso under the hood) fails identically regardless of what the test itself asserts. The prior emulator run below used an older AVD image (API 15 target) where this reflection path still resolves, which is why it passed. **Likely fix path (not yet applied):** bump `androidx.test.espresso:espresso-core` / `androidx.compose.ui:ui-test-junit4` to versions with Android 15+/16+ reflection compatibility, or pin CI/local physical-device runs to an OS image where the older Espresso version still works, pending upstream Espresso support for the newer `InputManager` API shape.

**Historical note (prior run, superseded as the current result):** The instrumented/androidTest suite was previously executed on 2026-07-06T04:52Z against the emulator `emulator-5554` (AVD target `ep133_test(AVD) - 15`, commit `e39e738`) via the same unscoped command, and all 79 tests passed (0 failures, 0 errors, 0 skipped, BUILD SUCCESSFUL, 1m 55s). That result is preserved here for the record but is no longer the current/primary result now that a physical-device run has surfaced a real hardware-specific incompatibility the emulator could not catch.

## Android Version(s) Tested

| Field | Value |
|-------|-------|
| `compileSdk` | 34 |
| `minSdk` | 29 |
| `targetSdk` | 34 |
| Physical device tested (androidTest, current) | Pixel 10, Android 17 (`ro.build.version.release`), API 37 (`ro.build.version.sdk`), serial `5C060DLCR000Y0`, USB-attached |
| Headless UI-test managed device | Pixel 2, API 33 (`aosp-atd` system image, via `./gradlew :app:pixel2Api33DebugAndroidTest`) |

## Coverage Gap Report (scripts/ui-test-coverage.sh output)

### UI test coverage report

#### Screen coverage

- [x] DeviceScreen → DeviceScreenTest
- [x] SampleImportScreen → SampleImportScreenTest
- [x] KitScreen → KitScreenTest
- [x] KitBuilderScreen → KitBuilderScreenTest
- [x] PadsScreen → PadsScreenTest
- [x] ProjectsScreen → ProjectsScreenTest

#### TestTags coverage

- [x] DEVICE_FIRMWARE_BANNER
- [x] DEVICE_PERMISSION_ACTION
- [x] DEVICE_RESTORE_CONFIRM_DIALOG
- [x] DEVICE_ROOT_DROPDOWN
- [x] DEVICE_SCALE_DROPDOWN
- [x] DEVICE_STATS_GRID
- [x] GROUP_CHOKE_TOGGLE
- [x] HEADER_CONNECTION
- [x] HEADER_SKU_BADGE
- [x] HEADER_THEME_TOGGLE
- [x] HEADER_TITLE
- [x] IMPORT_PICK_BUTTON
- [x] KB_ASSIGNED_COUNT
- [x] KB_CLEAR_CONFIRM_DIALOG
- [x] KB_CLEAR_PAD_BUTTON
- [x] KB_LOAD_BANNER
- [x] KB_SAMPLE_LIST
- [x] KB_SWITCH_PACK_BUTTON
- [x] KIT_MODE_CHOP
- [x] KIT_MODE_KIT
- [x] KIT_PICK_PANEL
- [x] KIT_PROGRESS_STATUS
- [x] KIT_PUSH_BUTTON
- [x] KIT_SLICE_COUNT_DEC
- [x] KIT_SLICE_COUNT_INC
- [x] KIT_SLICE_COUNT_READOUT
- [x] NAV_DEVICE
- [x] NAV_KIT
- [x] NAV_PADS
- [x] NAV_PROJECTS
- [x] PADS_GRID
- [x] PAD_STATE_IDLE
- [x] PAD_STATE_IN_SCALE
- [x] PAD_STATE_PRESSED
- [x] PROJECTS_RESTORE_CONFIRM_DIALOG
- [x] PROJECTS_SLOT_LIST
- [x] backupCard
- [x] groupChip
- [x] importRow
- [x] kbAuditionButton
- [x] kbCategoryTab
- [x] kbPadCell
- [x] kbSampleRow
- [x] kitProgressPad
- [x] kitSlicePad
- [x] pad
- [x] projectSlot

#### Changed-without-test since origin/main

- [x] AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt (tests touched)
- [x] AndroidApp/app/src/main/java/com/ep133/sampletool/ui/EP133App.kt (tests touched)
- [x] AndroidApp/app/src/main/java/com/ep133/sampletool/ui/TestTags.kt (infra — covered indirectly)
- [x] AndroidApp/app/src/main/java/com/ep133/sampletool/ui/device/DeviceScreen.kt (tests touched)
- [x] AndroidApp/app/src/main/java/com/ep133/sampletool/ui/import/SampleImportScreen.kt (tests touched)
- [x] AndroidApp/app/src/main/java/com/ep133/sampletool/ui/kit/KitScreen.kt (tests touched)
- [x] AndroidApp/app/src/main/java/com/ep133/sampletool/ui/kitbuilder/KitBuilderScreen.kt (tests touched)
- [x] AndroidApp/app/src/main/java/com/ep133/sampletool/ui/pads/PadsScreen.kt (tests touched)
- [x] AndroidApp/app/src/main/java/com/ep133/sampletool/ui/projects/ProjectsScreen.kt (tests touched)
- [x] AndroidApp/app/src/main/java/com/ep133/sampletool/ui/theme/EP133Components.kt (infra — covered indirectly)
_no untested UI/domain changes_

---
**Gaps found: 0**

## How to Reproduce

```bash
# Git/build metadata
git rev-parse --short HEAD
git log -1 --format=%cI
git branch --show-current
grep -n -E "compileSdk|minSdk|targetSdk" AndroidApp/app/build.gradle.kts
grep -n -A6 "managedDevices" AndroidApp/app/build.gradle.kts

# Unit test suite (real, executed)
cd AndroidApp && ./gradlew :app:testDebugUnitTest

# Parse authoritative XML results (sums tests/failures/errors/skipped per <testsuite>)
# Uses defusedxml if installed (protects against XXE/entity-expansion attacks on
# untrusted XML); falls back to stdlib ElementTree for these locally-generated,
# trusted Gradle test reports.
python3 - <<'PY'
import glob
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
files = glob.glob("app/build/test-results/testDebugUnitTest/TEST-*.xml")
total_tests = total_failures = total_errors = total_skipped = 0
for f in files:
    root = ET.parse(f).getroot()
    total_tests += int(root.attrib.get("tests", 0))
    total_failures += int(root.attrib.get("failures", 0))
    total_errors += int(root.attrib.get("errors", 0))
    total_skipped += int(root.attrib.get("skipped", 0))
print(len(files), total_tests, total_failures, total_errors, total_skipped)
PY

# androidTest suite (real, executed against a specific connected device)
# IMPORTANT: if multiple devices/emulators are attached simultaneously (check with
# `adb devices -l` first), an unscoped `connectedDebugAndroidTest` fans out to ALL of
# them. Scope to a single physical/virtual device with ANDROID_SERIAL:
# Clear stale output first so results are guaranteed fresh:
rm -rf AndroidApp/app/build/outputs/androidTest-results/connected AndroidApp/app/build/reports/androidTests/connected
cd AndroidApp && ANDROID_SERIAL=5C060DLCR000Y0 ./gradlew :app:connectedDebugAndroidTest
# Verify scoping worked (don't just assume ANDROID_SERIAL took effect): the Gradle log
# should show "Starting N tests on <only-your-device>" and the output directory should
# contain exactly one device-named subdirectory, not one per attached device.
# grep -i "Starting.*tests on" for confirmation; grep -ci "emulator-5554\|Pixel_Tablet"
# on the full log should be 0 if you intended to exclude the emulator.

# Parse authoritative instrumented-test XML results (recursive glob -- AGP may
# nest output under a device-specific subdirectory, e.g. "ep133_test(AVD) - 15/testlog/")
python3 - <<'PYEOF'
import glob
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
files = glob.glob("app/build/outputs/androidTest-results/connected/**/TEST-*.xml", recursive=True)
total_tests = total_failures = total_errors = total_skipped = 0
for f in files:
    root = ET.parse(f).getroot()
    total_tests += int(root.attrib.get("tests", 0))
    total_failures += int(root.attrib.get("failures", 0))
    total_errors += int(root.attrib.get("errors", 0))
    total_skipped += int(root.attrib.get("skipped", 0))
print(len(files), total_tests, total_failures, total_errors, total_skipped)
PYEOF

# Coverage gap report
scripts/ui-test-coverage.sh

# Alternative target when no device/emulator is connected (headless managed device):
scripts/run-ui-tests.sh
# or directly:
cd AndroidApp && ./gradlew :app:pixel2Api33DebugAndroidTest
```
