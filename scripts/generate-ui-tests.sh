#!/usr/bin/env bash
# Assisted regression-test generation (review-gated).
#
# 1. Runs ui-test-coverage.sh to find gaps.
# 2. For each gap, invokes `claude -p` with docs/testing/ui-test-prompt.md — the template
#    embeds the robot API, TestTags, the AAA/chaining house style, and every existing test
#    method name with a do-not-duplicate instruction.
# 3. Drafts land in androidTest/.../generated/ for HUMAN REVIEW — never auto-committed.
# 4. Finishes with an androidTest compile so a non-building draft is flagged immediately.
#
# Usage: scripts/generate-ui-tests.sh [--base <ref>]
set -euo pipefail

BASE="origin/main"
if [[ "${1:-}" == "--base" ]]; then
    if [[ $# -lt 2 ]]; then
        echo "error: --base requires a ref argument" >&2
        exit 2
    fi
    BASE="$2"
fi

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

TEST_DIR="AndroidApp/app/src/androidTest/java/com/ep133/sampletool"
GEN_DIR="$TEST_DIR/generated"
PROMPT_TEMPLATE="docs/testing/ui-test-prompt.md"

if ! command -v claude >/dev/null 2>&1; then
    echo "generate-ui-tests: claude CLI not found — install Claude Code first" >&2
    exit 1
fi

echo "generate-ui-tests: collecting coverage gaps…"
report="$(bash scripts/ui-test-coverage.sh --base "$BASE" || true)"
gaps="$(echo "$report" | grep -E '^\- \[ \]' | sed -E 's/^- \[ \] //; s/\*\*//g' || true)"

if [[ -z "$gaps" ]]; then
    echo "generate-ui-tests: no gaps — nothing to generate"
    exit 0
fi

echo "generate-ui-tests: gaps:"
echo "$gaps" | sed 's/^/  - /'

# Existing test method names — the no-redundancy guarantee for the prompt.
existing_tests="$(grep -rhE '^\s*fun [a-zA-Z0-9_]+\(' "$TEST_DIR" --include='*Test.kt' \
    | sed -E 's/^\s*fun ([a-zA-Z0-9_]+)\(.*/- \1/' | sort -u)"

mkdir -p "$GEN_DIR"

i=0
while IFS= read -r gap; do
    [[ -z "$gap" ]] && continue
    i=$((i + 1))
    out="$GEN_DIR/GeneratedRegressionTest$i.kt"
    echo "generate-ui-tests: drafting $out"
    # Python (not sed) does the actual {{GAP}}/{{EXISTING_TESTS}} substitution below —
    # stdin-safe for the multiline existing-test list.
    prompt="$(python3 - "$PROMPT_TEMPLATE" "$gap" <<'EOF' "$existing_tests"
import sys
template_path, gap = sys.argv[1], sys.argv[2]
existing = sys.argv[3]
text = open(template_path).read()
print(text.replace("{{EXISTING_TESTS}}", existing).replace("{{GAP}}", gap))
EOF
)"
    claude -p "$prompt" > "$out" || { echo "  draft failed for: $gap" >&2; rm -f "$out"; continue; }
done <<< "$gaps"

echo "generate-ui-tests: compiling drafts…"
if (cd AndroidApp && ./gradlew --quiet :app:compileDebugAndroidTestKotlin); then
    echo "generate-ui-tests: drafts compile. Review them in $GEN_DIR before committing."
else
    echo "generate-ui-tests: a draft does not compile — fix or delete it before committing." >&2
    exit 1
fi
