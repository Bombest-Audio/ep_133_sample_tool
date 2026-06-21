---
quick_id: 260621-mju
slug: fix-pad0-mapping
completed: 2026-06-21
commit: e7dae79
files_changed:
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/EP133.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/EP133PadsTest.kt
---

# Quick Task 260621-mju: Fix A0/D0 pad mapping — Summary

Removed the `SPECIAL_PAD0` map and its two `resolveIncoming` branches that routed A0/D0 to note=60 ch=6/7. Hardware capture proved those notes never existed on the real device — A0 sends note=37 ch=0, matching the normal base+offset formula already documented in the file.

## What changed

**EP133.kt:**
- Deleted `SPECIAL_PAD0` map and its doc comment block.
- `padsForChannel` simplified to a uniform `Pad(label, note = channel.baseNote + offset, ...)` — no branch on suffix or channel.
- `resolveIncoming` lost the two `note == 60 && ch == 6/7` early-returns; now routes purely by note range.
- Added `@Suppress("UNUSED_PARAMETER")` on `ch` in `resolveIncoming` to keep the public signature stable while suppressing the now-correct compiler warning.
- Updated KDoc to remove the stale "Pad 0 on groups A & D is special" text.

**EP133PadsTest.kt (new):**
- A0 = note 37, A. = note 36, A1 = note 39.
- No A pad has note 60.
- D0 = note 73.
- No pad on ch 6 or 7 across all groups.
- `resolveIncoming(37, 0)` → PadChannel.A / "A0" pad.
- `resolveIncoming(48, 0)` → PadChannel.B / "B." pad.
- `resolveIncoming(60, 0)` → PadChannel.C / "C." pad (note 60 is now unambiguously C's base).
- Out-of-range notes return null.

## Verification

```
./gradlew :app:testDebugUnitTest  → BUILD SUCCESSFUL (25 tasks, all tests green)
./gradlew :app:assembleDebug      → BUILD SUCCESSFUL (46 tasks)
```

## Deviations

None — executed exactly as planned.
