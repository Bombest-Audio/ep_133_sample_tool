---
phase: 1
slug: midi-foundation
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-28
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4 + `kotlinx-coroutines-test` 1.7.3 (unit); Compose UI test + `createComposeRule` (instrumented) |
| **Config file** | None — standard Gradle test runner; no `junit-platform.properties` |
| **Quick run command** | `./gradlew :app:test` |
| **Full suite command** | `./gradlew :app:connectedAndroidTest` |
| **Estimated runtime** | ~30 seconds (quick unit); ~3-5 minutes (full instrumented, requires emulator or device) |

> **iOS note:** iOS tests run via Xcode Test Navigator only (no Fastlane configured). iOS changes in this phase are surgical single-file fixes + one new file — manual build verification is sufficient.

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :app:test`
- **After every plan wave:** Run `./gradlew :app:connectedAndroidTest`
- **Before `/gsd:verify-work`:** Full unit suite green + DeviceScreen instrumented tests green
- **Max feedback latency:** 30 seconds (unit), 5 minutes (instrumented)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 1-01-01 | 01 Android threading | 0 | CONN-01 | unit | `./gradlew :app:test --tests "*.MIDIManagerThreadingTest"` | ❌ W0 | ⬜ pending |
| 1-01-02 | 01 Android threading | 0 | CONN-01 | unit | `./gradlew :app:test --tests "*.MIDIRepositoryTest"` | ❌ W0 | ⬜ pending |
| 1-01-03 | 01 Android threading | 0 | CONN-01 | unit | `./gradlew :app:test --tests "*.SequencerEngineScopeTest"` | ❌ W0 | ⬜ pending |
| 1-01-04 | 01 Android threading | 1 | CONN-01 | unit | `./gradlew :app:test --tests "*.MIDIManagerThreadingTest"` | ❌ W0 | ⬜ pending |
| 1-01-05 | 01 Android threading | 1 | CONN-01 | unit | `./gradlew :app:test --tests "*.SequencerEngineScopeTest"` | ❌ W0 | ⬜ pending |
| 1-02-01 | 02 iOS threading | 1 | CONN-01 | manual | Xcode build + run on simulator | N/A | ⬜ pending |
| 1-02-02 | 02 iOS threading | 1 | CONN-01 | manual | Xcode build + run on simulator | N/A | ⬜ pending |
| 1-02-03 | 02 iOS protocol | 1 | CONN-01 | manual | Xcode build (compile-time check) | N/A | ⬜ pending |
| 1-03-01 | 03 USB connection | 1 | CONN-02 | unit | `./gradlew :app:test --tests "*.MIDIManagerTest"` | ❌ W0 | ⬜ pending |
| 1-03-02 | 03 USB connection | 1 | CONN-02 | manual | Physical cable replug test | N/A | ⬜ pending |
| 1-03-03 | 03 USB connection | 1 | CONN-03 | unit | `./gradlew :app:test --tests "*.MIDIManagerTest"` | ❌ W0 | ⬜ pending |
| 1-03-04 | 03 USB connection | 1 | CONN-04 | instrumented | `./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ep133.sampletool.DeviceScreenTest` | ⚠️ partial | ⬜ pending |
| 1-03-05 | 03 USB connection | 1 | CONN-04 | instrumented | Same as above | ⚠️ partial | ⬜ pending |
| 1-03-06 | 03 USB connection | 1 | CONN-01 | instrumented | `./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ep133.sampletool.NavigationTest` | ⚠️ partial | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] Create `AndroidApp/app/src/test/java/com/ep133/sampletool/` directory (does not exist)
- [ ] `AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIManagerThreadingTest.kt` — stubs for CONN-01, CONN-02 threading correctness
- [ ] `AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIRepositoryTest.kt` — stubs for CONN-01 StateFlow emission, CONN-03 permission gating
- [ ] `AndroidApp/app/src/test/java/com/ep133/sampletool/SequencerEngineScopeTest.kt` — stubs for scope cancellation after `close()`
- [ ] Add awaiting/denied state test cases to `AndroidApp/app/src/androidTest/java/com/ep133/sampletool/TestMIDIRepository.kt` + `DeviceScreenTest.kt` (file partially exists; new test cases needed for CONN-04 three-state coverage)

*Framework install: Not required — JUnit 4 + `kotlinx-coroutines-test:1.7.3` are already in `testImplementation` dependencies in `app/build.gradle.kts`.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| App reconnects automatically after cable replug | CONN-02 | Android emulator cannot simulate USB attach/detach events; requires physical hardware | 1. Connect EP-133 via USB. 2. Confirm device shows in DeviceScreen. 3. Unplug cable. 4. Confirm disconnected state. 5. Replug cable. 6. Confirm app reconnects without requiring restart. |
| iOS MIDI callback arrives on main thread (no crash) | CONN-01 (iOS) | iOS unit tests not configured; CoreMIDI callback thread cannot be reliably tested in Xcode without physical MIDI device | 1. Build and run iOS app on simulator. 2. Confirm no `[Main Thread Checker]` violation logged in Xcode console. 3. On device with EP-133: trigger a MIDI event and confirm no crash. |
| iOS SysEx buffer fix (no crash on long payload) | CONN-01 (iOS) | Requires sending a SysEx message > base struct size; not testable without real MIDI device | 1. Connect EP-133 to iOS device. 2. Trigger any SysEx operation (e.g., device stat query in Phase 2). 3. Confirm no crash or silent corruption. Phase 1 smoke test: confirm basic note-on/note-off round trip. |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s (unit) / 5min (instrumented)
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
