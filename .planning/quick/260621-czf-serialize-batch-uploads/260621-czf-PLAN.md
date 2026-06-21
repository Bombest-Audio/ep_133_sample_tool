---
quick_id: 260621-czf
slug: serialize-batch-uploads
title: Serialize batch sample uploads to fix transfer-in-flight collisions
created: 2026-06-21
---

# Quick Task 260621-czf: Serialize batch sample uploads

Fix a real batch-import bug: multi-picking several Splice samples currently fails for all but
the first sample.

## Verified bug

- `SampleImportViewModel.onFilesPicked` (ui/import/SampleImportScreen.kt:155-158) launches **one coroutine per picked URI concurrently** — `uris.forEachIndexed { ... viewModelScope.launch { manager.importSample(...) } }`.
- Each `importSample` calls `MIDIRepository.putSampleFile`, which enforces a single in-flight transfer: `MIDIRepository.kt:528` `if (transferInFlight) throw IllegalStateException("transfer already in flight")`.
- Result: when N files are picked, the first transfer wins and the other N−1 throw `IllegalStateException("transfer already in flight")` → those rows show Error. The actual use case (import a batch of Splice samples) mostly fails.

## Fix — serialize device uploads in SampleImportManager

The device transfer is a serial resource. Serialize the upload step (not the whole pipeline —
decode/convert can still overlap) with a `Mutex` owned by `SampleImportManager`, so concurrent
imports queue instead of colliding.

**File:** `AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SampleImportManager.kt`

1. Add `import kotlinx.coroutines.sync.Mutex` and `import kotlinx.coroutines.sync.withLock`.
2. Add a private field `private val uploadMutex = Mutex()` to `SampleImportManager`.
3. In BOTH `importSample` and `importSampleBytes`, wrap ONLY the `midi.putSampleFile(safeName, wavBytes)` call in `uploadMutex.withLock { ... }`:
   ```kotlin
   val ok = try {
       uploadMutex.withLock { midi.putSampleFile(safeName, wavBytes) }
   } catch (e: CancellationException) {
       throw e
   } catch (e: Exception) { ... existing Error emit ... }
   ```
   Keep the existing CancellationException-rethrow-before-generic-catch ordering. The mutex must be released even on exception — `withLock` guarantees this.
   Rationale for wrapping only the upload: decode/convert (the expensive part) still overlaps across files for throughput; only the device transfer — the actual serial resource — is serialized. Bytes are already read into memory before the lock, so SAF-grant lifetime (Landmine 7) is unaffected.
4. Update the class KDoc to note that uploads are serialized via `uploadMutex` so concurrent batch imports queue at the device-transfer step rather than colliding with the repository's single-in-flight-transfer guard.

**Do NOT** modify `MIDIRepository` (its single-in-flight guard stays as the low-level invariant), `SysExProtocol`, `AudioDecoder`, `Resampler`, or `WavEncoder`. Do NOT restructure `onFilesPicked`'s per-file coroutines — the manager-level mutex makes them safe without UI changes.

## Test

**File:** `AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportConcurrencyTest.kt` (new)

Goal: prove concurrent imports all succeed instead of colliding.

- Build a fake `MIDIRepository` subclass (mirror `SampleImportFakeRepo`/`SampleImportSpyPort` from `SampleImportViewModelTest.kt`) that simulates the real in-flight guard: override `putSampleFile` to atomically check a `@Volatile`/`AtomicBoolean` `inFlight` flag — if already true, return `false` (or record a collision); otherwise set it true, `kotlinx.coroutines.yield()` (force interleaving under the test dispatcher), then clear it and return `true`. Track a `collisions` counter and a `successCount`.
- In `runTest` with `StandardTestDispatcher`, create one `SampleImportManager` and launch several `importSampleBytes(...)` flows concurrently (e.g. collect 5 flows via `launch { ... .collect() }`), advance the dispatcher, and assert: zero collisions and every flow reached `Done` (no `Error`). This passes only because `uploadMutex` serializes the calls.
- Optional sanity check documenting the bug: a second tiny test that calls the fake's `putSampleFile` directly from two overlapping coroutines WITHOUT the mutex and shows the collision — keep it only if it stays simple; the primary assertion above is what matters.

Use the testability seam `importSampleBytes` (no SAF/Context/decode needed) and a `connected` device state so the upload path runs.

**Verify after:** from `AndroidApp/`, `./gradlew :app:testDebugUnitTest` (full suite green, including the new SampleImportConcurrencyTest) and `./gradlew :app:assembleDebug` both BUILD SUCCESSFUL. Only `SampleImportManager.kt` + the new test file change.
