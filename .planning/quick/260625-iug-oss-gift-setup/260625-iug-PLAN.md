---
quick_id: 260625-iug
slug: oss-gift-setup
status: in-progress
date: 2026-06-25
---

# Quick Task 260625-iug: Make EP-133 Sample Tool a proper community gift

Turn the repo into a presentable public open-source project from Bombest Audio, and
produce the Claude Design brief. Full approved plan:
`~/.claude/plans/snappy-dreaming-crescent.md`.

## Decisions (locked)
- License **our original code MIT** (`Copyright (c) 2026 Bombest, Inc.`); add a **NOTICE**
  carving out TE's proprietary `data/` bundle as included-for-interop, not relicensed;
  credit `garrettjwilke` upstream.
- Claude Design produces one design system: **landing page + SysEx protocol reference
  page + app UI redesign concept**.
- **All human-facing prose in Thomas's voice** (`voice.md`): warm, direct, peer; no
  corporate/LLM tells. Boilerplate legal text (MIT body, Contributor Covenant) stays standard.

## Tasks (atomic commits)
1. **Legal** — `LICENSE` (MIT), `NOTICE` (TE-asset carve-out + upstream credit),
   `package.json` (`license`, real `description`, Bombest author, `bugs`).
2. **Community/process** — `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1),
   `SECURITY.md`, `CHANGELOG.md` (Keep a Changelog), `.github/ISSUE_TEMPLATE/*`,
   `.github/pull_request_template.md`, `.github/FUNDING.yml`.
3. **README** — license badge, gift framing, Credits & licensing section, links;
   `CONTRIBUTING.md` link to CoC/SECURITY.
4. **Claude Design brief** — `design/claude-design-brief.md` (paste-ready prompt).

## Out of scope
Signed release workflow; porting the design concept into Compose; touching `data/`.

## Verify
`npm install` still valid (package.json parses); new files present; README renders;
NOTICE separates our code from TE's and credits upstream; brief matches the approved plan.
