---
quick_id: 260626-vvp
slug: add-tag-triggered-android-release-workfl
description: Add tag-triggered Android release workflow and bump version to 2.0.0
status: complete
branch: chore/release-2.0.0
completed: 2026-06-26
---

# Summary — 260626-vvp

Stood up the release pipeline and aligned the version for the first Bombest
community release (2.0.0).

## What changed

- **`.github/workflows/release.yml`** (new) — push a `*.*.*` tag (or use
  `workflow_dispatch` against an existing tag) and it builds `:app:assembleDebug`,
  renames the APK to `EP133SampleTool-<tag>.apk`, pulls the matching `## [<tag>]`
  section from `CHANGELOG.md` as release notes plus an install footer, and
  publishes a GitHub Release with the APK attached via
  `softprops/action-gh-release@v2`. Reuses the JDK 17 / gradle setup from
  `build-android.yml`. Tag/event data flows through `env:`; the dispatch tag input
  is validated to a semver shape before it reaches checkout `ref:` or the filename
  (ref/path-injection guard).
- **`package.json`** — `1.2.0` → `2.0.0`.
- **`CHANGELOG.md`** — promoted `[Unreleased]` to `[2.0.0] - 2026-06-26`, added a
  fresh empty `[Unreleased]`, wired the compare links.

## Commits (branch `chore/release-2.0.0`, off merged `main`)
- `f85687f` ci: add tag-triggered Android release workflow
- `8bc63fb` chore: bump version to 2.0.0
- `836283b` docs(changelog): cut the 2.0.0 release

## Decisions / notes
- **Version 2.0.0**, not the originally-floated 1.0.0 — matches the Android
  `versionName` and sits above the inherited upstream `1.1.0`/`1.2.0` tags.
- **Debug-signed APK** for the first drop: the `release` build type has no
  `signingConfig`, so `assembleRelease` is unsigned and won't install.
  `assembleDebug` shares the `com.ep133.sampletool` id and already builds green.
  Proper release-keystore signing is a follow-up (needs an owner-generated keystore
  + repo secrets).
- Validated locally: YAML parses, `package.json` is valid JSON at 2.0.0, and the
  changelog awk extraction + install footer render cleanly.

## Out of scope / follow-ups
- git tag / push / release publish — handled by orchestrator after the PR merges.
- Release-keystore signing.
- Electron (mac/win/linux) + iOS release artifacts — Android is the headline drop.

## Execution note
Run via `/gsd:quick` (task scaffolded by `init.quick`). Executed inline on a clean
`chore/release-2.0.0` branch rather than via a worktree-isolated executor — the
session is already nested in a worktree, and inline execution sidesteps the known
executor wrong-checkout hazard while still producing atomic commits + these
artifacts.
