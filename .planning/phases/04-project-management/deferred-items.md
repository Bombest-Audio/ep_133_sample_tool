# Phase 4 — Deferred / Out-of-Scope Items

Discoveries logged during execution that are NOT caused by the current plan's
changes. Tracked here, not fixed (scope boundary).

## Pre-existing lint error (not introduced by 04-01)

- **File:** `AndroidApp/app/src/main/java/com/ep133/sampletool/midi/MIDIManager.kt:159`
- **Rule:** `MutableImplicitPendingIntent` (lint error)
- **Detail:** `PendingIntent.getBroadcast(..., FLAG_MUTABLE)` with an implicit
  `Intent(ACTION_USB_PERMISSION)`. Android 14+ disallows mutable implicit
  PendingIntents. Fix: make the intent explicit (set package) or use
  `FLAG_IMMUTABLE`.
- **Status:** Pre-existing at the Task 1 commit (confirmed via `git show HEAD:...`).
  `:app:lintDebug` fails ONLY on this, unrelated to the FileProvider/file_paths.xml
  added in plan 04-01. Left for a dedicated fix (USB permission flow, not project
  management).
