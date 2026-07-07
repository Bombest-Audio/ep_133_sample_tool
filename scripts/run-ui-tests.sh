#!/usr/bin/env bash
# Run the Compose UI test suite on the best available target:
# a connected device/emulator if present, else a headless Gradle Managed Device.
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/AndroidApp"

if command -v adb >/dev/null 2>&1 && adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | grep -q .; then
    echo "run-ui-tests: connected device found — connectedDebugAndroidTest"
    ./gradlew :app:connectedDebugAndroidTest
    echo "report: AndroidApp/app/build/reports/androidTests/connected/debug/index.html"
else
    echo "run-ui-tests: no device — Gradle Managed Device (first run downloads the image)"
    ./gradlew :app:pixel2Api33DebugAndroidTest
    echo "report: AndroidApp/app/build/outputs/androidTest-results/managedDevice/"
fi
