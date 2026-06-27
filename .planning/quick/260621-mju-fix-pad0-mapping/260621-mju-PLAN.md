---
quick_id: 260621-mju
slug: fix-pad0-mapping
title: Remove wrong A0/D0 pad special-case (note 60) — confirmed by hardware capture
created: 2026-06-21
---

# Quick Task 260621-mju: Fix A0/D0 pad mapping

## Bug + hardware evidence

In the app, tapping pad **A0** triggers a group **C** pad on the EP-133; all other A pads are correct.

Root cause: `EP133Pads.SPECIAL_PAD0` (in `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/EP133.kt`) overrides the "0" pad of groups A and D to `note = 60, midiChannel = 6/7`. Note 60 is group C's base note, so A0 plays a C pad. This special-case dates to the first Compose-UI commit and was never hardware-verified.

Live device capture (adb logcat, EP-133 connected to a Pixel) proves it is wrong in BOTH directions:
- App tap A0 → `MIDI OUT: noteOn note=60 ch=6` (wrong — lands in group C).
- Pressing A0 on the EP-133 hardware → `MIDI IN: note=37 ch=0`.

So the device itself uses the NORMAL mapping for A0: `note = A.baseNote(36) + "0"-offset(1) = 37`, channel 0. The official MIDI map already documented in EP133.kt (`A=36–47 … all ch 1`, `"0" = base+1`) agrees. There is no note-60/ch-6 behavior on the real device.

## Task 1 — Remove the special-case (both directions)

**File:** `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/model/EP133.kt`

1. Delete the `SPECIAL_PAD0` map (and its doc comment block at ~lines 99-107).
2. In `padsForChannel`, drop the special branch so every pad is uniform:
   ```kotlin
   fun padsForChannel(channel: PadChannel): List<Pad> =
       GRID_ORDER.map { (suffix, offset) ->
           val label = "${channel.name}$suffix"
           Pad(label, note = channel.baseNote + offset, defaultSound = DEFAULT_DRUM_MAP[label])
       }
   ```
   (Result: A0 → note 37, D0 → note 73, both on the default channel — matching the device.)
3. In `resolveIncoming`, delete the two `note == 60 && ch == 6/7` special branches (~lines 122-130). The normal range logic already resolves note 37 correctly: `37 in 36..47 → PadChannel.A`, `offset = 37-36 = 1`, `GRID_ORDER.indexOfFirst { it.second == 1 }` → the "0" pad.
4. Update the surrounding KDoc to remove the stale "Pad 0 on groups A & D is special / note 60 / ch 6-7" description. The official map comment (`A=36–47`, `"0" = base+1`, all ch 1) becomes the whole truth.

Do NOT change `GRID_ORDER`, `PadChannel` base notes, `DEFAULT_DRUM_MAP`, or anything outside this file.

## Task 2 — Regression test

**File:** `AndroidApp/app/src/test/java/com/ep133/sampletool/EP133PadsTest.kt` (new)

Pure JVM unit tests (no Android deps — `EP133Pads` is a plain object):
- `padsForChannel(PadChannel.A)`: the pad labeled `"A0"` has `note == 37`; the `"A."` pad has `note == 36`; `"A1"` has `note == 39`. None of A's pads have `note == 60`.
- `padsForChannel(PadChannel.D)`: `"D0"` has `note == 73` (not 60).
- All pads across A/B/C/D use the default `midiChannel` (no pad on channel 6 or 7).
- `resolveIncoming(37, 0)` → `PadChannel.A` paired with the index of the `"0"` pad in `GRID_ORDER`.
- `resolveIncoming(48, 0)` → `PadChannel.B` to the `"."` pad index.
- `resolveIncoming(60, 0)` → `PadChannel.C` to the `"."` pad index (note 60 is now unambiguously C's base, no special handling).

## Verify

From `AndroidApp/` (JAVA_HOME=$(/usr/libexec/java_home -v 17); local.properties exists):
- `./gradlew :app:testDebugUnitTest` — full suite green incl. the new EP133PadsTest.
- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL.

Scope: `EP133.kt` + new `EP133PadsTest.kt` only.
