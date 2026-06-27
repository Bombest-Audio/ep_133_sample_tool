---
quick_id: 260626-vvp
slug: add-tag-triggered-android-release-workfl
description: Add tag-triggered Android release workflow and bump version to 2.0.0
status: planned
branch: chore/release-2.0.0
created: 2026-06-26
---

# Quick Task 260626-vvp — Release workflow + 2.0.0 version bump

## Goal

Stand up a repeatable, tag-triggered GitHub Release workflow that builds the
Android APK and publishes it as a downloadable asset, and align the project
version on `2.0.0` for the first Bombest community release.

## Context

PR #2 merged the cross-platform rewrite + OSS setup into `main`. The repo has no
downloadable build — a KO-II owner arriving from the YouTube comment would have to
build from source. The highest-leverage gap before sharing is an installable APK.

Version is `2.0.0` (decided): the Android build already declares
`versionName "2.0.0"`, and it sits cleanly above the inherited upstream `1.1.0` /
`1.2.0` tags. `package.json` still says `1.2.0` and needs to catch up.

Signing: the `release` build type has no `signingConfig`, so `assembleRelease` is
unsigned and won't install. `assembleDebug` is debug-signed, shares the
`com.ep133.sampletool` id, and already builds green in CI — the right call for a
first sideloadable community drop. Proper release-keystore signing is a follow-up
that needs a keystore + repo secrets (owner's to set up).

## Tasks

### Task 1 — release.yml
- **files:** `.github/workflows/release.yml` (new)
- **action:** Tag-triggered (`*.*.*`) + `workflow_dispatch`. `contents: write`.
  JDK 17 / gradle cache matching `build-android.yml`. Build `:app:assembleDebug`,
  rename to `EP133SampleTool-<tag>.apk`, extract the matching `## [<tag>]` section
  from `CHANGELOG.md` as release notes (+ a short install footer), publish via
  `softprops/action-gh-release@v2` with the APK attached. Pass tag/event data
  through `env:` into shell steps (no direct `${{ }}` interpolation in `run:`).
- **verify:** `actionlint`/yaml parse; steps mirror the proven Android CI build.
- **done:** workflow exists and is syntactically valid.

### Task 2 — version bump
- **files:** `package.json`
- **action:** `"version": "1.2.0"` → `"2.0.0"`.
- **done:** version reads `2.0.0`, JSON still valid.

### Task 3 — changelog
- **files:** `CHANGELOG.md`
- **action:** Promote `## [Unreleased]` → `## [2.0.0] - 2026-06-26` (body verbatim),
  add a fresh empty `## [Unreleased]` above it, fix link refs (`[2.0.0]` compare
  `1.2.0...2.0.0`, `[Unreleased]` compare `2.0.0...HEAD`).
- **done:** changelog reflects 2.0.0 as released; links resolve.

## Out of scope
- git tag / push / release publish (orchestrator handles after merge).
- Release-keystore signing (follow-up; needs owner keystore + secrets).
- Electron / iOS release artifacts (Android is the headline; fast-follow).
