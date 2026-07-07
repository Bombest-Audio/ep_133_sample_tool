# EP-133 Sample Tool — Test Dashboard

| Field | Value |
|-------|-------|
| Commit SHA | `aaedf27` (+ uncommitted espresso-core 3.7.0 bump in `AndroidApp/app/build.gradle.kts`) |
| Commit date | 2026-07-05T23:17:14-07:00 |
| Branch | `android-ui-test-framework` |
| Dashboard generated at | 2026-07-06T18:34:00Z |

## Test Suite Summary

| Suite | Test Classes | Test Methods | Result |
|-------|--------------|---------------|--------|
| Unit tests (`src/test`, `:app:testDebugUnitTest`) | 43 | 274 (0 failures, 0 errors, 13 skipped) | BUILD SUCCESSFUL |
| androidTest (instrumented, `src/androidTest`, physical Pixel 10) | 10 | 79 (0 failures, 0 errors, 0 skipped) | BUILD SUCCESSFUL |

**Note (physical-device run, current):** The instrumented/androidTest suite was executed on 2026-07-06T18:14Z against the connected **physical** device `5C060DLCR000Y0` (Pixel 10, Android 17 / API 37, USB-attached), scoped via `ANDROID_SERIAL=5C060DLCR000Y0 ./gradlew :app:connectedDebugAndroidTest`. A second device, `emulator-5554` (Pixel_Tablet AVD), was simultaneously connected but **did not participate** — confirmed explicitly (not assumed): the Gradle log shows `Starting 79 tests on Pixel 10 - 17` and contains zero mentions of `emulator-5554`/`Pixel_Tablet`/`emu64a` anywhere in the full build output, and only one device-named output directory was produced (`androidTest-results/connected/debug/Pixel 10 - 17/`) with no sibling tablet/emulator directory. Stale output directories were deleted before this run so the JUnit XML parsed is guaranteed fresh.

**Result: BUILD SUCCESSFUL — all 79 test methods passed on real hardware** (1m 44s), across all 10 instrumented test classes (`AppNavigationTest` 7/7, `DeviceScreenTest` 11/11, `FirmwareBannerTest` 5/5, `KitBuilderScreenTest` 8/8, `KitScreenTest` 17/17, `PadsScreenTest` 7/7, `ProjectsScreenTest` 7/7, `SampleImportScreenTest` 6/6, `ScaleLockFlowTest` 3/3, `SimulatedDeviceTest` 8/8 — all passing). Verified from the real JUnit XML (`app/build/outputs/androidTest-results/connected/debug/TEST-Pixel 10 - 17-_app-.xml`), not just Gradle's summary line: `tests=79 failures=0 errors=0 skipped=0`.

This resolves the previously-documented failure (see historical note below): every one of the 79 failures shared an identical root cause,

```
java.lang.RuntimeException: java.util.concurrent.ExecutionException: java.lang.RuntimeException: java.lang.NoSuchMethodException: android.hardware.input.InputManager.getInstance []
  at androidx.test.espresso.Espresso.onIdle(Espresso.java:18)
  at androidx.compose.ui.test.junit4.EspressoLink_androidKt.runEspressoOnIdle(EspressoLink.android.kt:92)
  ...
Caused by: java.lang.NoSuchMethodException: android.hardware.input.InputManager.getInstance []
  at androidx.test.espresso.base.InputManagerEventInjectionStrategy.initialize(InputManagerEventInjectionStrategy.java:5)
```

which was a **framework/environment incompatibility**, not an application logic bug: `espresso-core` 3.5.1's `InputManagerEventInjectionStrategy` reflected on the internal static method `android.hardware.input.InputManager.getInstance()`, which no longer resolves in this signature on Android 15+ (confirmed failing on Android 17 / API 37). **Fix applied:** bumped `androidx.test.espresso:espresso-core` from `3.5.1` to `3.7.0` in `AndroidApp/app/build.gradle.kts` — AndroidX Test's upstream fix (landed in 3.6.1) replaces the reflective call with `Context.getSystemService(InputManager.class)`. `androidx.compose.ui:ui-test-junit4`, the compose-bom (`2024.02.00`), and `androidx.test.ext:junit` (`1.1.5`) were left untouched; the failure signature and upstream changelog pointed specifically at espresso-core, and no other `androidx.test.*` dependencies are declared standalone in this module. Verification also surfaced and worked around an unrelated environment issue: this physical device is actively used day-to-day, and a couple of verification attempts hit spurious "Process crashed" / "Unable to find instrumentation target package" failures caused by a second, competing `am instrument` invocation racing against the Gradle-driven run on the same device serial (confirmed via logcat — no real app crash, no FATAL EXCEPTION). Ensuring a single, uncontested instrumentation session against the device eliminated this entirely; it required no code change.

**Historical note (prior run, superseded as the current result):** The instrumented/androidTest suite was previously executed on 2026-07-06T06:12Z against this same physical device and **all 79 tests failed**, 100% reproducibly, with the `InputManager.getInstance()` `NoSuchMethodException` above (`espresso-core` was pinned at `3.5.1` at that time, commit `e39e738`). That result is preserved here for the record; it is superseded by the fixed, passing run above once `espresso-core` was bumped to `3.7.0`.

**Historical note (emulator run, superseded):** The instrumented/androidTest suite was also previously executed on 2026-07-06T04:52Z against the emulator `emulator-5554` (AVD target `ep133_test(AVD) - 15`, commit `e39e738`) via the same unscoped command, and all 79 tests passed (0 failures, 0 errors, 0 skipped, BUILD SUCCESSFUL, 1m 55s). Preserved for the record; the physical-device pass above is now the current, primary result for real-hardware confidence.

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
