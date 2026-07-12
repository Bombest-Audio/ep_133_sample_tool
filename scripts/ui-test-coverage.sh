#!/usr/bin/env bash
# UI-test coverage gap report (deterministic, no AI).
#
# Checks three things and prints a markdown report:
#   1. Screen coverage    — every ui/**/*Screen.kt has a matching androidTest class by convention.
#   2. Tag coverage       — every TestTags constant/helper is referenced by at least one
#                           robot or test (an untouched tag = a UI element no test exercises).
#   3. Regression drift   — files changed since the base ref under ui/ or domain/ whose
#                           matching test class did NOT change in the same range.
#
# Usage: scripts/ui-test-coverage.sh [--strict] [--base <ref>]
#   --strict   exit 1 when any gap is found (usable as a CI/hook gate)
#   --base     diff base for regression drift (default: origin/main)
set -euo pipefail

STRICT=0
BASE="origin/main"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --strict) STRICT=1; shift ;;
        --base)
            if [[ $# -lt 2 ]]; then
                echo "error: --base requires a ref argument" >&2
                exit 2
            fi
            BASE="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

MAIN_UI="AndroidApp/app/src/main/java/com/ep133/sampletool/ui"
TEST_DIR="AndroidApp/app/src/androidTest/java/com/ep133/sampletool"
GAPS=0

echo "# UI test coverage report"
echo

# ── 1. Screen coverage ────────────────────────────────────────────────────────
echo "## Screen coverage"
echo
while IFS= read -r screen; do
    name="$(basename "$screen" .kt)"   # e.g. PadsScreen
    feature="${name%Screen}"           # e.g. Pads
    test_file="$TEST_DIR/${feature}ScreenTest.kt"
    # SampleImportScreen tests live in SampleImportScreenTest; allow either exact or
    # feature-prefixed test classes (e.g. ImportFlowTest also counts for SampleImport).
    if [[ -f "$test_file" ]]; then
        echo "- [x] $name → ${feature}ScreenTest"
    elif ls "$TEST_DIR"/*"${feature}"*Test.kt >/dev/null 2>&1; then
        echo "- [x] $name → $(basename "$(ls "$TEST_DIR"/*"${feature}"*Test.kt | head -1)" .kt)"
    else
        echo "- [ ] **$name has no test class** (expected ${feature}ScreenTest.kt)"
        GAPS=$((GAPS + 1))
    fi
done < <(find "$MAIN_UI" -name "*Screen.kt" | sort)
echo

# ── 2. Tag coverage ───────────────────────────────────────────────────────────
echo "## TestTags coverage"
echo
TAGS_FILE="$MAIN_UI/TestTags.kt"
while IFS= read -r tag; do
    if grep -rq "TestTags.$tag" "$TEST_DIR"; then
        echo "- [x] $tag"
    else
        echo "- [ ] **TestTags.$tag is never used by a test**"
        GAPS=$((GAPS + 1))
    fi
done < <(grep -oE 'const val [A-Z_0-9]+|fun [a-zA-Z]+\(' "$TAGS_FILE" \
            | sed -E 's/const val //; s/fun //; s/\($//' | sort -u)
echo

# ── 3. Regression drift since base ────────────────────────────────────────────
echo "## Changed-without-test since $BASE"
echo
if ! git rev-parse --verify --quiet "$BASE" >/dev/null; then
    echo "_base ref $BASE not found — skipping drift check_"
else
    range="$BASE...HEAD"
    # Both suites count: instrumented UI tests and the JVM unit tests.
    changed_tests="$(git diff --name-only "$range" -- "$TEST_DIR" \
        "AndroidApp/app/src/test/java/com/ep133/sampletool" || true)"
    drift=0
    while IFS= read -r f; do
        [[ -z "$f" ]] && continue
        [[ -f "$f" ]] || continue   # deleted since base — nothing to test
        case "$f" in
            # Cross-cutting infra with no dedicated test class: tags are asserted by every
            # UI test; theme tokens/components are exercised through the screens that use them.
            */ui/TestTags.kt|*/ui/theme/*) echo "- [x] $f (infra — covered indirectly)"; continue ;;
        esac
        base_name="$(basename "$f" .kt)"
        feature="${base_name%Screen}"; feature="${feature%ViewModel}"
        [[ "$feature" == "EP133App" ]] && feature="AppNavigation"  # shell → AppNavigationTest
        if echo "$changed_tests" | grep -q "${feature}"; then
            echo "- [x] $f (tests touched)"
        else
            echo "- [ ] **$f changed but no matching test changed**"
            drift=$((drift + 1))
        fi
    done < <(git diff --name-only "$range" -- \
                "$MAIN_UI" "AndroidApp/app/src/main/java/com/ep133/sampletool/domain" \
                | grep '\.kt$' || true)
    [[ $drift -eq 0 ]] && echo "_no untested UI/domain changes_"
    GAPS=$((GAPS + drift))
fi
echo

echo "---"
echo "**Gaps found: $GAPS**"

if [[ $STRICT -eq 1 && $GAPS -gt 0 ]]; then
    exit 1
fi
