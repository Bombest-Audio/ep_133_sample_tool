---
name: local-review
description: Run a local multi-subagent code review over the current diff before pushing or opening a PR. Routes each changed file to the language-appropriate reviewer subagent (C++, Kotlin, JS/TS), plus cross-cutting silent-failure and comment-sync passes, then aggregates findings ranked by severity. Use when the user says "review my changes", "local review", "/local-review", or wants a review pass that supplements the Copilot/PR review.
---

# Local review

Supplements the GitHub Copilot PR review with a local pass using specialist subagents, so problems
get caught before code reaches the PR. Runs entirely on the local diff.

## 1. Determine the diff

Pick the review target, in this order:
- If the user names a base (`/local-review vs main`), diff against that: `git diff --name-only main...HEAD` plus `git diff main...HEAD`.
- Else if there are staged changes, review those: `git diff --cached`.
- Else review the working tree + committed-but-unpushed: `git diff @{push}... 2>/dev/null || git diff HEAD~5`.

Get the changed file list and the actual diff. If there are no changes, say so and stop.

**Never review `data/index.js` or `data/*.wasm`** - compiled third-party bundle. `data/custom.js` is fair game.

## 2. Route files to reviewers

Group changed files by type and dispatch one subagent per group, **all in parallel** (single message, multiple Agent calls). Give each the specific file list and the diff hunks for its files.

| Changed files | Subagent (`subagent_type`) |
|---|---|
| `JucePlugin/**/*.{cpp,h}`, `*.mm` | `everything-claude-code:cpp-reviewer` |
| `AndroidApp/**/*.kt` | `everything-claude-code:kotlin-reviewer` |
| `shared/*.js`, `data/custom.js`, `**/*.{ts,tsx,js}` | `everything-claude-code:typescript-reviewer` |
| `iOSApp/**/*.swift` | `everything-claude-code:code-reviewer` (no Swift specialist; general reviewer) |
| any of the above (cross-cutting) | `everything-claude-code:silent-failure-hunter` |
| any of the above (cross-cutting) | `everything-claude-code:comment-analyzer` |

Always run the two cross-cutting passes when any code changed - comment/code drift and silent
failures are this repo's two most common defects.

In each subagent prompt, include the repo-specific rules from `.github/copilot-instructions.md`
("Where the real bugs live"): MIDI SysEx byte correctness, ES5-only polyfill, Android coroutine/
StateFlow rules, cross-platform parity. Tell each subagent to return findings as a JSON-ish list of
`{file, line, severity, category, problem, fix}` and to report nothing if the diff is clean.

## 3. Aggregate and present

Collect all subagent findings. Dedup by file+line. Rank by severity, and within severity tag each
finding by category: **correctness / performance / style / nit** (this is the review format Thomas
expects - top issues first, don't bury the lede). Present as:

```
## Local review - <N> findings

### Correctness
- `file:line` - <problem>. Fix: <fix>.

### Performance / Style / Nit
...
```

If clean, say "Local review: no findings" and stop.

## 4. Optional: post to the PR

If the user asked to post (or this is running against an open PR), offer to post the confirmed
findings as PR review comments. Do not post automatically - confirm first (per Thomas's git
workflow rules). Apply fixes only when asked; commit atomically, one logical fix per commit.
