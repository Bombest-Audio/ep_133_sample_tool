---
phase: 04-project-management
plan: 01
type: execute
wave: 0
depends_on: []
files_modified:
  - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/MultiChunkGetTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/SysExDispatchTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectsViewModelTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/BackupLibraryTest.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/ShareIntentTest.kt
  - AndroidApp/app/src/main/res/xml/file_paths.xml
  - AndroidApp/app/src/main/AndroidManifest.xml
autonomous: true
requirements: [PROJ-01, PROJ-02, PROJ-03, PROJ-04]
must_haves:
  truths:
    - "Running the unit suite executes (not skips) the six new Phase 4 test classes"
    - "A FileProvider with authority com.ep133.sampletool.fileprovider is declared and resolvable"
  artifacts:
    - path: "AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt"
      provides: "Protocol frame + FILE_LIST parse test scaffold"
      contains: "class ProjectProtocolTest"
    - path: "AndroidApp/app/src/test/java/com/ep133/sampletool/MultiChunkGetTest.kt"
      provides: "Paged GET loop test scaffold"
      contains: "class MultiChunkGetTest"
    - path: "AndroidApp/app/src/test/java/com/ep133/sampletool/SysExDispatchTest.kt"
      provides: "Multi-response request lifecycle test scaffold"
      contains: "class SysExDispatchTest"
    - path: "AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectsViewModelTest.kt"
      provides: "ViewModel slot-mapping test scaffold"
      contains: "class ProjectsViewModelTest"
    - path: "AndroidApp/app/src/test/java/com/ep133/sampletool/BackupLibraryTest.kt"
      provides: "Backup library enumeration test scaffold"
      contains: "class BackupLibraryTest"
    - path: "AndroidApp/app/src/test/java/com/ep133/sampletool/ShareIntentTest.kt"
      provides: "Share intent construction test scaffold"
      contains: "class ShareIntentTest"
    - path: "AndroidApp/app/src/main/res/xml/file_paths.xml"
      provides: "FileProvider path config"
      contains: "external-files-path"
  key_links:
    - from: "AndroidManifest.xml"
      to: "res/xml/file_paths.xml"
      via: "FILE_PROVIDER_PATHS meta-data resource reference"
      pattern: "@xml/file_paths"
---

<objective>
Create the Wave 0 test scaffold and the FileProvider OS configuration that every downstream Phase 4 plan depends on. Per the Nyquist rule, each later task points its `<verify><automated>` at one of these test classes; they must exist (compiling, executing — not silently skipped) before Wave 1 starts. This plan also lands the install-time FileProvider declaration so the Wave 3 share intent has a registered authority to grant against.

Purpose: Establish the feedback-sampling substrate (04-VALIDATION.md) and the OS-registered share config so no later task has a `MISSING` automated verify.
Output: Six test classes with executing stub assertions (real assertions filled in by the plan that implements the behavior under test), plus `res/xml/file_paths.xml` and the AndroidManifest `<provider>` block.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/STATE.md
@.planning/phases/04-project-management/04-VALIDATION.md
@.planning/phases/04-project-management/04-PATTERNS.md
@.planning/phases/04-project-management/04-RESEARCH.md

<interfaces>
<!-- Existing test analogs and constants the executor needs. From the codebase. -->
From AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt:
- object SysExProtocol with: CMD_PRODUCT_SPECIFIC=127, TE_SYSEX_FILE=5, TE_SYSEX_FILE_GET=3, TE_SYSEX_FILE_PUT=2, TE_SYSEX_FILE_LIST=4, STATUS_OK=0, STATUS_SPECIFIC_SUCCESS_START=64
- fun pack7bit(ByteArray): ByteArray ; fun unpack7bit(ByteArray): ByteArray ; fun buildFrame(deviceId, command, requestId, payload): ByteArray
- Frame layout: [8]=command, packed payload at [9..size-2], trailing 0xF7

Test analogs to mirror (read them, do not edit them):
- AndroidApp/app/src/test/java/com/ep133/sampletool/SysExProtocolTest.kt — frame-byte assertion idiom (frame[8] command, copyOfRange(9, size-1) then unpack7bit)
- AndroidApp/app/src/test/java/com/ep133/sampletool/ChordsViewModelTest.kt — SpyMIDIPort + FakeMIDIRepo test doubles; StandardTestDispatcher + Dispatchers.setMain/resetMain in @Before/@After; runTest{}
- AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIRepositoryStatsTest.kt and BackupRestoreTest.kt — the @Ignore convention for timing/hardware-dependent tests

FileProvider authority: com.ep133.sampletool.fileprovider (applicationId com.ep133.sampletool, build.gradle.kts line 11)
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: Create the six Phase 4 unit test classes as executing stubs</name>
  <read_first>
    - AndroidApp/app/src/test/java/com/ep133/sampletool/SysExProtocolTest.kt (frame-byte + pack7bit round-trip idiom)
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ChordsViewModelTest.kt (SpyMIDIPort/FakeMIDIRepo doubles, coroutine harness)
    - AndroidApp/app/src/test/java/com/ep133/sampletool/MIDIRepositoryStatsTest.kt (the @Ignore justification-string convention)
    - .planning/phases/04-project-management/04-VALIDATION.md (per-task verification map)
    - .planning/phases/04-project-management/04-PATTERNS.md (ProjectProtocolTest / ProjectsViewModelTest sections)
  </read_first>
  <files>AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/MultiChunkGetTest.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/SysExDispatchTest.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectsViewModelTest.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/BackupLibraryTest.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/ShareIntentTest.kt</files>
  <action>
    Create six JUnit 4 test classes in package com.ep133.sampletool, each with `import org.junit.Test` and `import org.junit.Assert.*`. Each class must contain at least one currently-passing placeholder test so the class compiles and the runner executes it (e.g. assert a SysExProtocol constant has its known value, or assert a pure helper returns expected output). DO NOT write assertions against code that does not exist yet — those are filled in by Waves 1-3. Mark the placeholder explicitly with a `// TODO(04-project-management-NN): replace placeholder with real behavior assertions` comment naming the implementing plan.

    Class-to-behavior mapping (one stub method per row in 04-VALIDATION.md):
    - ProjectProtocolTest: GET/PUT INIT/DATA frame-byte layout, FILE_LIST response parse {nodeId,flags,size,name}. Placeholder: assert SysExProtocol.TE_SYSEX_FILE_GET == 3 and a pack7bit/unpack7bit round-trip on a 256-byte binary blob (mirror SysExProtocolTest pack7bit test). Wave 1 fills frame-byte assertions.
    - MultiChunkGetTest: paged assembly reaches fileSize, page-mismatch throws, empty-data terminates, CRC32. Placeholder: assert CRC32 of a known byte array equals its expected value (use java.util.zip.CRC32 directly so the test is real now). Wave 1 fills the loop assertions.
    - SysExDispatchTest: SUCCESS_START keeps request pending, STATUS_OK completes. Any test needing virtual-time/timeout simulation or a SysEx response simulator MUST be `@Ignore`d with a justification string matching the MIDIRepositoryStatsTest convention ("validated on hardware — JVM cannot simulate device timing"). Placeholder: assert STATUS_SPECIFIC_SUCCESS_START == 64 and STATUS_OK == 0.
    - ProjectsViewModelTest: listProjects() maps 9 entries + marks active slot. Copy SpyMIDIPort + FakeMIDIRepo from ChordsViewModelTest and the StandardTestDispatcher harness. Placeholder: construct FakeMIDIRepo and assert it constructs. Wave 2/3 fills slot-mapping assertions.
    - BackupLibraryTest: enumerate .tar files in a temp dir sorted by lastModified desc. This is hardware-free and pure — write a real test now that creates two temp .tar files with different mtimes and asserts descending sort order against a small enumeration helper. If the helper does not exist yet, assert against an inline File.listFiles+sortedByDescending expression so the test is green and Wave 2 swaps in the real helper.
    - ShareIntentTest: ShareCompat/FileProvider intent construction. Per repo convention, an intent that needs a real Context/FileProvider is `@Ignore`d (Robolectric not in the dep set) with the hardware/instrumented justification string; add one executing placeholder asserting the MIME constant string the share builder will use ("application/octet-stream").

    Use `val` over `var`; keep acronym casing (MIDI, USB, EP133).
  </action>
  <verify>
    <automated>JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home bash -c 'cd AndroidApp && ./gradlew :app:testDebugUnitTest --tests "*.ProjectProtocolTest" --tests "*.MultiChunkGetTest" --tests "*.SysExDispatchTest" --tests "*.ProjectsViewModelTest" --tests "*.BackupLibraryTest" --tests "*.ShareIntentTest"'</automated>
  </verify>
  <acceptance_criteria>
    - All six test classes compile and the gradle command above reports them RUN (not "No tests found"); the suite is GREEN.
    - Every test that depends on unimplemented production code or device timing is `@Ignore`d with a justification string, not failing.
    - Each class carries a TODO comment naming the plan that fills its real assertions.
    - No `@Test` method asserts against a symbol that does not yet exist in main source (would fail compilation).
  </acceptance_criteria>
  <done>Six executing test classes exist; quick unit suite green; later plans have concrete `--tests` targets.</done>
</task>

<task type="auto">
  <name>Task 2: Declare the FileProvider and its path config</name>
  <read_first>
    - AndroidApp/app/src/main/AndroidManifest.xml (the &lt;application&gt; block + existing &lt;activity&gt; entries)
    - AndroidApp/app/src/main/res/xml/usb_device_filter.xml (only existing res/xml sibling — structure reference)
    - AndroidApp/app/build.gradle.kts (confirm applicationId com.ep133.sampletool)
    - .planning/phases/04-project-management/04-RESEARCH.md (Pattern 4 manifest + file_paths block; Pitfall 6 filesDir fallback)
  </read_first>
  <files>AndroidApp/app/src/main/res/xml/file_paths.xml, AndroidApp/app/src/main/AndroidManifest.xml</files>
  <action>
    Create res/xml/file_paths.xml with an `external-files-path` entry: name="backups", path="backups/" (matching the future getExternalFilesDir("backups") write location). Add a `files-path` entry name="backups_internal", path="backups/" as the Pitfall 6 internal-storage fallback target.

    In AndroidManifest.xml, add a `<provider>` inside `<application>` with android:name="androidx.core.content.FileProvider", android:authorities="${applicationId}.fileprovider", android:exported="false", android:grantUriPermissions="true", and a `<meta-data>` android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths". Do not add any new permissions. Do not touch the existing USB/MIDI intent filters.
  </action>
  <verify>
    <automated>JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home bash -c 'cd AndroidApp && ./gradlew :app:processDebugManifest :app:lintDebug'</automated>
  </verify>
  <acceptance_criteria>
    - `processDebugManifest` succeeds; the merged manifest contains a provider with authority resolving to com.ep133.sampletool.fileprovider.
    - file_paths.xml contains an `external-files-path` with path="backups/".
    - `lintDebug` reports no new errors introduced by the provider/xml.
  </acceptance_criteria>
  <done>FileProvider registered and lint-clean; share intent in Wave 3 has a grantable authority.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| app → OS share subsystem | App will later vend a content:// URI to arbitrary receiving apps via the share sheet |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-04-01 | Information disclosure | FileProvider authority scope | mitigate | `exported="false"` + `file_paths.xml` scoped to the `backups/` subdir only — no broad root path; URIs grant READ only at share time (enforced in Wave 3) |
| T-04-02 | Elevation of privilege | over-broad FileProvider paths | mitigate | Path config limited to `external-files-path` + `files-path` rooted at `backups/`; no `root-path` or `external-path` wildcard entries |
| T-04-SC | Tampering | npm/pip/cargo installs | accept | No package installs in this phase (RESEARCH Package Legitimacy Audit: zero new packages); nothing to verify |
</threat_model>

<verification>
- Quick unit suite green with all six new classes executing.
- `processDebugManifest` + `lintDebug` clean.
</verification>

<success_criteria>
- Six Wave 0 test classes exist and run (Nyquist substrate in place).
- FileProvider + file_paths.xml registered and lint-clean.
</success_criteria>

<output>
Create `.planning/phases/04-project-management/04-01-SUMMARY.md` when done.
</output>
