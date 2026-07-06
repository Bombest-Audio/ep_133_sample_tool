#!/usr/bin/env bash
# One-time setup: point git at the repo's committed hooks.
# The relative hooksPath resolves per working tree, so a single install covers
# the main checkout and every worktree sharing this repo.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
chmod +x scripts/git-hooks/* 2>/dev/null || true
git config core.hooksPath scripts/git-hooks
echo "installed: core.hooksPath -> scripts/git-hooks"
echo "pre-push now runs unit tests + androidTest compile (+ UI tests when a device is attached)"
