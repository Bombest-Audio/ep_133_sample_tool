#!/usr/bin/env bash
# One-time setup: point git at the repo's committed hooks.
# The relative hooksPath resolves per working tree, so a single install covers
# the main checkout and every standard git worktree sharing this repo. Exception:
# Claude Code worktrees set their own core.hooksPath, so these hooks do NOT fire
# there - run the checks manually inside such a worktree (see the notes below).
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
chmod +x scripts/git-hooks/* 2>/dev/null || true
git config core.hooksPath scripts/git-hooks
echo "installed: core.hooksPath -> scripts/git-hooks"
echo "pre-push now runs unit tests + androidTest compile (+ UI tests when a device is attached)"
echo "opt-in local subagent review: EP133_REVIEW=1 git push  (advisory, never blocks)"
echo "or run it any time:            scripts/local-review.sh [base]   (or /local-review in Claude)"
echo "note: Claude Code worktrees override core.hooksPath, so git hooks don't fire there -"
echo "      run scripts/local-review.sh or /local-review manually inside a worktree."
