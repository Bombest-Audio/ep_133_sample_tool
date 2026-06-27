---
quick_id: 260625-iug
slug: oss-gift-setup
status: complete
date: 2026-06-25
commits:
  - 3998ba0 feat(oss): MIT license + NOTICE carving out TE assets
  - c7704bb docs(oss): CoC, security, changelog, GitHub templates
  - b0e1a25 docs(readme): gift framing, license badge, credits section
  - 256291b docs(design): Claude Design brief
---

# Quick Task 260625-iug: Make EP-133 Sample Tool a proper community gift — SUMMARY

Turned the repo into a presentable public open-source project from Bombest Audio and
produced the Claude Design brief. Executed inline (not via worktree subagent) because the
work is prose-heavy and voice-critical — authored directly with `voice.md` + the
hardware-verified protocol facts in context.

## What shipped (4 atomic commits)

**Legal (3998ba0)** — `LICENSE` (MIT, © 2026 Bombest, Inc.), `NOTICE` drawing the line
between our MIT code and TE's proprietary `data/` bundle (compiled app, WASM, `.pak`/
`.hmls`, fonts) with upstream credit to `garrettjwilke`; `package.json` gains `license`,
a real `description`, a Bombest author, a `bugs` URL, and Bombest-Audio org links.

**Community/process (c7704bb)** — `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1, contact
thomas@bom.best), `SECURITY.md` (private reporting), `CHANGELOG.md` (Keep a Changelog,
seeded with honest tag dates: 1.1.0 → 2024-10-10, 1.2.0 → 2025-05-28, native apps + import
+ protocol docs + OSS setup under Unreleased), `.github/ISSUE_TEMPLATE/*` (bug + feature
forms, config), `.github/pull_request_template.md` (prose, no LLM scaffolding),
`.github/FUNDING.yml` (commented placeholder).

**README (b0e1a25)** — CI badges repointed to the Bombest-Audio org, MIT badge, the
gift-framing line up top, a Credits & licensing section spelling out the MIT-our-code /
TE-owns-`data/` split, and links to CoC / SECURITY / CHANGELOG. CONTRIBUTING gets the same
CoC/SECURITY/licensing note.

**Claude Design brief (256291b)** — `design/claude-design-brief.md`: a paste-ready prompt
that produces one TE-grade design system across a landing page, a designed SysEx protocol
reference page, and an app UI redesign concept. Grounded in the real color tokens
(`#EF4E27` orange, `#00A69C` teal, faceplate grays, `#222323` pad black) and the exact
hardware-verified protocol facts.

## Voice
All human-facing prose in Thomas's voice (warm, direct, "a gift to the community"). Only
the MIT body and Contributor Covenant left as standard boilerplate.

## Verification
- `package.json` parses; `license=MIT`, Bombest author + org links confirmed.
- All four issue/funding YAML files parse under `yaml.safe_load`.
- All 11 new files present on disk and committed.

## Out of scope (follow-ups)
Signed tag-triggered release workflow (downloadable binaries); porting the Page-3 design
concept into Android Compose; standing up GitHub Pages from the Claude Design output.
Unaffected: backlog 999.3 (firmware flash) and 999.4 (SysEx dispatch refactor).

## Next manual step for Thomas
Paste `design/claude-design-brief.md` into Claude Design to generate the site + protocol
page + app concept. Then open a PR to `main` (prose title).
