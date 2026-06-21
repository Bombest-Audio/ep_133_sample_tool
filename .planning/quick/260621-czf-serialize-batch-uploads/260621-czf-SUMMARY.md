---
quick_id: 260621-czf
slug: serialize-batch-uploads
title: Serialize batch sample uploads to fix transfer-in-flight collisions
created: 2026-06-21
completed: 2026-06-21
commit: 2808491
files_changed:
  modified:
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SampleImportManager.kt
  created:
    - AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportConcurrencyTest.kt
---

# Quick Task 260621-czf: Serialize batch sample uploads — Summary

## One-liner

Added `Mutex`-based upload serialization to `SampleImportManager` so concurrent batch imports queue at the device-transfer step rather than colliding with `MIDIRepository`'s single-in-flight-transfer guard.

## What was done

### SampleImportManager.kt

Added two imports (`kotlinx.coroutines.sync.Mutex`, `kotlinx.coroutines.sync.withLock`) and a private `uploadMutex = Mutex()` field. Wrapped the `midi.putSampleFile(safeName, wavBytes)` call in both `importSample` and `importSampleBytes` with `uploadMutex.withLock { ... }`. The `CancellationException` rethrow before the generic `catch` remains inside the `withLock` body — `withLock` guarantees lock release on any exit path including exception. Updated the class KDoc to document that concurrent batch imports queue at the device-transfer step via `uploadMutex`.

### SampleImportConcurrencyTest.kt (new)

Built a `ConcurrencyFakeRepo` that mirrors the real `MIDIRepository.transferInFlight` guard using an `AtomicBoolean` + `yield()`:
- If the flag is already set when a call arrives → collision recorded, returns false.
- Otherwise: set flag, `yield()` to force interleaving under `StandardTestDispatcher`, clear flag, return true.

Primary test (`concurrentBatchImports_zeroCollisions_allDone`): launches 5 `importSampleBytes` flows concurrently, advances the dispatcher, and asserts zero collisions and every flow terminates with `Done`. This passes only because `uploadMutex` serializes the calls.

Sanity test (`fakeRepo_detectsCollision_whenCalledConcurrentlyWithoutMutex`): calls `putSampleFile` directly from two concurrent coroutines without a mutex and asserts the fake records a collision — confirming the primary test is meaningful.

## Verification

Both build targets passed:
- `./gradlew :app:testDebugUnitTest` — BUILD SUCCESSFUL, full suite green (25 tasks, 6 executed)
- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL (46 tasks, 12 executed)

## Deviations

None — plan executed exactly as written.

## Self-Check

- `SampleImportManager.kt` modified: FOUND
- `SampleImportConcurrencyTest.kt` created: FOUND
- Commit `2808491` exists: FOUND
- `MIDIRepository.kt` unchanged: CONFIRMED (not in git diff)
- Existing tests unweakened: CONFIRMED (full suite green)

## Self-Check: PASSED
