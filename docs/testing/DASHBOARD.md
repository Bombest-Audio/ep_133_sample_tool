# EP-133 Sample Tool — Test Dashboard

| Field | Value |
|-------|-------|
| Commit SHA | `8a6677d` |
| Commit date | 2026-07-05T20:49:15-07:00 |
| Branch | `android-ui-test-framework` |
| Dashboard generated at | 2026-07-06T04:56:00Z |

## Test Suite Summary

| Suite | Test Classes | Test Methods | Result |
|-------|--------------|---------------|--------|
| Unit tests (`src/test`, `:app:testDebugUnitTest`) | 43 | 274 (0 failures, 0 errors, 13 skipped) | BUILD SUCCESSFUL |
| androidTest (instrumented, `src/androidTest`) | 10 | 79 (0 failures, 0 errors, 0 skipped) | BUILD SUCCESSFUL |

**Note:** The instrumented/androidTest suite WAS executed in this environment on 2026-07-06T04:52Z against the connected device `emulator-5554` (adb reported `device` state), via `./gradlew :app:connectedDebugAndroidTest`. Gradle ran the suite against AVD target `ep133_test(AVD) - 15` and completed in 1m 55s (BUILD SUCCESSFUL). All 79 test methods across the 10 instrumented test classes (`AppNavigationTest`, `DeviceScreenTest`, `FirmwareBannerTest`, `KitBuilderScreenTest`, `KitScreenTest`, `PadsScreenTest`, `ProjectsScreenTest`, `SampleImportScreenTest`, `ScaleLockFlowTest`, `SimulatedDeviceTest`) passed — 0 failures, 0 errors, 0 skipped. Stale output directories (`androidTest-results/connected`, `reports/androidTests/connected`) were deleted before this run so the JUnit XML parsed below is guaranteed fresh, not a residual from an earlier session.

## Android Version(s) Tested

| Field | Value |
|-------|-------|
| `compileSdk` | 34 |
| `minSdk` | 29 |
| `targetSdk` | 34 |
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

# androidTest suite (real, executed against connected device emulator-5554)
# Clear stale output first so results are guaranteed fresh:
rm -rf AndroidApp/app/build/outputs/androidTest-results/connected AndroidApp/app/build/reports/androidTests/connected
cd AndroidApp && ./gradlew :app:connectedDebugAndroidTest

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
