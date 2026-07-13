#!/usr/bin/env bash
# Local multi-subagent code review over the current diff. Supplements the GitHub Copilot
# PR review by catching problems locally, before code reaches the PR.
#
# Advisory by design: it prints findings and always exits 0 (never blocks a push).
# It drives the `/local-review` Claude skill headlessly, which fans the diff out to the
# language-appropriate reviewer subagents (C++, Kotlin, JS/TS) plus silent-failure and
# comment-sync passes.
#
# Usage:
#   scripts/local-review.sh [base_ref]     # base defaults to origin/main
#
# Requires the `claude` CLI on PATH; if it's missing this is a no-op. For full oversight
# you can also just run `/local-review` inside an interactive Claude session instead.
set -uo pipefail

BASE="${1:-origin/main}"
cd "$(git rev-parse --show-toplevel)"

if ! command -v claude >/dev/null 2>&1; then
  echo "local-review: claude CLI not found - skipping. Run /local-review in a Claude session instead."
  exit 0
fi

# Nothing to review?
if git diff --quiet "$BASE"...HEAD 2>/dev/null && git diff --quiet HEAD 2>/dev/null; then
  echo "local-review: no changes vs $BASE - nothing to review."
  exit 0
fi

echo "local-review: running local subagent review vs $BASE (advisory)…"
# Whitelist only the tools the review needs so it runs headless without prompting, and
# without a blanket permission bypass. The reviewer subagents are read-only by contract.
claude -p "/local-review vs $BASE" \
  --allowedTools Task Read Grep Glob Bash \
  2>&1 || true

exit 0
