---
phase: 04-project-management
plan: 03
type: execute
wave: 2
depends_on: ["04-project-management-02"]
files_modified:
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt
  - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/ProjectBackupManager.kt
  - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt
autonomous: false
requirements: [PROJ-01, PROJ-02]
must_haves:
  truths:
    - "The app resolves /projects to a node ID and enumerates 9 project slots with name, size, and active marker"
    - "Backing up one slot downloads its .tar archive via the paged GET and writes it to a named file in app storage"
    - "Restore of a single slot uploads a validated P{NN}.tar archive via the paged PUT (gated on hardware confirmation)"
  artifacts:
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/ProjectBackupManager.kt"
      provides: "Single-project archive backup/restore + library enumeration over the paged protocol"
      contains: "class ProjectBackupManager"
      min_lines: 60
    - path: "AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt"
      provides: "resolveNodeId + listProjects enumeration"
      contains: "fun listProjects"
  key_links:
    - from: "MIDIRepository.listProjects"
      to: "FILE_LIST on resolved /projects node"
      via: "resolveNodeId then list that node"
      pattern: "resolveNodeId"
    - from: "ProjectBackupManager.backupProject"
      to: "MIDIRepository.getProjectArchive"
      via: "paged GET then writeBytes to getExternalFilesDir(backups)"
      pattern: "getProjectArchive"
---

<objective>
Build the domain layer for project management: enumerate the 9 EP-133 project slots (PROJ-01) and back up / restore one project as an opaque `.tar` archive over the Wave 1 paged transfer (PROJ-02). A project backup is an opaque device blob — download it, write it, done; no ZIP re-archiving (unlike Phase 2's `.pak`). Two items are hardware-gated and surface as explicit checkpoints: the path-vs-nodeId FILE_LIST addressing (RESEARCH Open Q1) and whether single-project restore ships this phase (Open Q2 — default: ship behind the existing restore-confirm AlertDialog, gated on a hardware pass).

Purpose: PROJ-01 enumeration + PROJ-02 backup/restore domain logic.
Output: `resolveNodeId` + `listProjects()` in MIDIRepository; a new `ProjectBackupManager` with flow-emitting `backupProject`, `restoreProject`, and `listBackups` reading app-specific external storage.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/phases/04-project-management/04-RESEARCH.md
@.planning/phases/04-project-management/04-PATTERNS.md
@.planning/phases/04-project-management/04-02-SUMMARY.md

<interfaces>
From MIDIRepository.kt (Wave 1 extended it):
- getProjectArchive(nodeId: Int): ByteArray — paged GET download
- putProjectArchive(parentNodeId, slotNodeId, tarBytes) — paged PUT
- data class FileListEntry(path, nodeId); _fileListEntries SharedFlow; fileListEntryCount + pendingFileListCountDeferred (lines 77-89)
- buildFileListFrame(deviceId, path, requestId) — existing path-string FILE_LIST (Phase 2 /sounds)
- queryDeviceStatsInner orchestration skeleton (lines 319-357); statsQueryInFlight guard
- deviceState: StateFlow<DeviceState>; DeviceState has outputPortId

From BackupManager.kt (analog for ProjectBackupManager):
- constructor (private val midi: MIDIRepository); sealed class BackupProgress { Progress(current,total); Done; Error(message) } (lines 21-28)
- createBackup(): Flow — flow{} structure, guard on deviceState.value.outputPortId==null → emit(Error), emit(Progress) loop, emit(Done) (lines 70-150)
- suggestedBackupFilename(): "EP133-...-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)...}" (lines 59-62)

FILE_LIST response entry layout (RESEARCH lines 138-146): nodeId=(a[0]<<8)|a[1]; flags=a[2]; fileSize=a[3..6] uint32 BE; fileName=null-term from a[7]
File-type flags (RESEARCH lines 148-157): FILE=1, DIR=2; capabilities READ=4, WRITE=8, DELETE=16
Slots: /projects/P00 .. /projects/P08 (9). Active slot from /projects metadata "active" nodeId.
Storage: context.getExternalFilesDir("backups") (null-check → filesDir fallback, Pitfall 6). Backup file name: EP133-P{NN}-{timestamp}.tar. Restore filename validation regex: P(\d{2})\.tar
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Add resolveNodeId + listProjects enumeration to MIDIRepository</name>
  <read_first>
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt (FileListEntry + file-list flow lines 82-89; dispatchFileResponse FILE_LIST branch 245-262; queryDeviceStatsInner 319-357)
    - .planning/phases/04-project-management/04-RESEARCH.md (Node-ID resolution lines 124-136; FILE_LIST response lines 138-157; Pattern 1 lines 287-296; List-the-9-slots example lines 466-478; Open Question 1 lines 522-525)
    - .planning/phases/04-project-management/04-PATTERNS.md (MIDIRepository.kt section — node-id resolution)
    - AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt
  </read_first>
  <files>AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt, AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectProtocolTest.kt</files>
  <behavior>
    - A FILE_LIST response byte buffer with N concatenated entries parses into N records of {nodeId, flags, fileSize, fileName} with fileSize as uint32 BE and fileName null-terminated
    - listProjects() maps the /projects children to ProjectSlot(nodeId, name, sizeBytes, isActive); the slot whose nodeId == the /projects "active" pointer has isActive=true
  </behavior>
  <action>
    Add `data class ProjectSlot(val nodeId: Int, val name: String, val sizeBytes: Long, val isActive: Boolean)`. Add a pure `parseFileListEntries(body: ByteArray): List<…>` helper decoding concatenated entries per the layout (nodeId uint16, flags, fileSize uint32 BE, null-terminated name) — make it pure/testable. Add `resolveNodeId(path: String): Int` that lists from root (nodeId 0) and walks segments matching child names (per RESEARCH Node-ID resolution). Add `suspend fun listProjects(): List<ProjectSlot>` mirroring the queryDeviceStatsInner orchestration: resolve /projects → nodeId, read its metadata "active" pointer, FILE_LIST that node, map entries to ProjectSlot. Because RESEARCH Open Q1 is unresolved on hardware, implement node-ID resolution as the primary path but keep the existing path-string buildFileListFrame available; add a `// HARDWARE-VERIFY (Open Q1): confirm /projects lists by nodeId vs path string` comment and structure the code so a path-string fallback is a one-line switch. Guard overlapping queries with the statsQueryInFlight idiom. Never expose mutable flows; "EP133APP" logging; rethrow CancellationException.

    Fill ProjectProtocolTest with the FILE_LIST entry-parse assertions: feed a hand-built multi-entry body, assert the decoded {nodeId, flags, sizeBytes, name} list.
  </action>
  <verify>
    <automated>JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home bash -c 'cd AndroidApp && ./gradlew :app:testDebugUnitTest --tests "*.ProjectProtocolTest"'</automated>
  </verify>
  <acceptance_criteria>
    - ProjectProtocolTest passes the FILE_LIST multi-entry parse assertion (nodeId/flags/size BE/null-term name).
    - resolveNodeId, listProjects, ProjectSlot, and parseFileListEntries exist; parseFileListEntries is pure and unit-covered.
    - HARDWARE-VERIFY (Open Q1) comment present; path-string fallback reachable via a single switch.
    - No mutable flow exposed; CancellationException rethrown.
  </acceptance_criteria>
  <done>Slot enumeration domain logic implemented and parse-tested; PROJ-01 data path ready for the ViewModel.</done>
</task>

<task type="auto">
  <name>Task 2: Create ProjectBackupManager — single-project backup/restore + library enumeration</name>
  <read_first>
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/BackupManager.kt (sealed progress classes 21-28; createBackup flow{} 70-150; suggestedBackupFilename 59-62)
    - AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/MIDIRepository.kt (getProjectArchive/putProjectArchive from Wave 1; listProjects from Task 1)
    - .planning/phases/04-project-management/04-RESEARCH.md (Don't Hand-Roll lines 370-381 opaque-archive insight; Pattern 3 storage lines 319-330; Multi-chunk FILE_PUT lines 209-215; Pitfall 6 lines 428-431; Open Question 2 lines 527-529)
    - .planning/phases/04-project-management/04-PATTERNS.md (ProjectBackupManager.kt section)
  </read_first>
  <files>AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/ProjectBackupManager.kt</files>
  <action>
    Create ProjectBackupManager(private val midi: MIDIRepository). Define `sealed class ProjectBackupProgress { data class Progress(val current: Int, val total: Int); data class Done(val file: java.io.File); data class Error(val message: String) }` mirroring BackupProgress. Implement:
    - `backupProject(slot: ProjectSlot, context: Context): Flow<ProjectBackupProgress>` — flow{}: guard deviceState.value.outputPortId==null → emit(Error); emit(Progress(0, slot.sizeBytes.toInt())); call midi.getProjectArchive(slot.nodeId) (opaque blob, NO ZipOutputStream); resolve dir = context.getExternalFilesDir("backups") ?: context.filesDir.resolve("backups").also{ it.mkdirs() } (Pitfall 6); under withContext(Dispatchers.IO) write archive bytes to File(dir, suggestedProjectFilename(slot)); emit(Done(file)).
    - `suggestedProjectFilename(slot): String` — "EP133-P%02d-%s.tar".format(slotIndex, SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())); reuse the BackupManager date format string.
    - `listBackups(context): List<BackupItem>` — `data class BackupItem(val file: File, val name: String, val timestamp: Long)`; enumerate dir.listFiles { it.extension == "tar" }?.sortedByDescending { it.lastModified() }; pure enough to test against a temp dir.
    - `restoreProject(file: File, context: Context): Flow<ProjectBackupProgress>` — validate file.name matches Regex("\\w*P(\\d{2})\\.tar") (RESEARCH Security V5 / Open Q2); reject non-matching with emit(Error); read bytes under Dispatchers.IO; call midi.putProjectArchive(...). Because Open Q2 is hardware-gated, add a `// HARDWARE-GATE (Open Q2): restore wired but the user-facing button is gated on a hardware pass; default = ship behind the restore-confirm AlertDialog` comment. The destructive PUT requires the AlertDialog confirmation (added in Wave 3) before invocation.
    Use Dispatchers.IO for all file I/O; Log.e("EP133APP", msg, throwable) includes the throwable; val over var; never inspect inside the .tar.
  </action>
  <verify>
    <automated>JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/thomasphillips/.valdi/android_home bash -c 'cd AndroidApp && ./gradlew :app:testDebugUnitTest :app:lintDebug'</automated>
  </verify>
  <acceptance_criteria>
    - ProjectBackupManager compiles; backupProject emits Progress→Done with an opaque archive write (no ZipOutputStream import).
    - restoreProject rejects filenames not matching P(\d{2})\.tar before any PUT.
    - Storage uses getExternalFilesDir("backups") with a filesDir fallback; all file I/O under Dispatchers.IO.
    - HARDWARE-GATE (Open Q2) comment present at the restore path.
    - Full unit suite + lintDebug green.
  </acceptance_criteria>
  <done>Single-project backup/restore + library enumeration domain logic complete; opaque-archive handling per research; restore gated on hardware.</done>
</task>

</tasks>

<hardware_checkpoints>
Two items cannot be confirmed without a physical EP-133 and are recorded for the phase-gate UAT (autonomous: false on this plan reflects them):

1. **FILE_LIST addressing (RESEARCH Open Q1):** Confirm whether `/projects` enumerates by resolved nodeId (reference-impl path) or accepts the Phase 2 path string. Code ships node-ID resolution with a one-line path-string fallback; the HARDWARE-VERIFY comment marks the switch.
2. **Single-project restore (RESEARCH Open Q2):** Restore (PUT) is implemented and validated by filename, but the user-facing button is gated on a hardware round-trip pass. Default decision: ship restore behind the existing restore-confirm AlertDialog pattern (wired in Wave 3), enabled after a successful hardware backup→restore round-trip.
</hardware_checkpoints>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| backup file (user/filesystem) → device (restore PUT) | A restore reads a .tar from storage and writes it to a live device slot — destructive |
| device FILE_LIST response → app | Slot metadata parsed into UI-facing records |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-04-06 | Tampering | restoring an arbitrary/corrupt .tar overwrites a live project slot | mitigate | Validate filename against `P(\d{2})\.tar` before PUT; size sanity-check; require the Wave 3 AlertDialog confirmation before invoking restoreProject (RESEARCH Security Domain) |
| T-04-07 | Tampering / DoS | malformed FILE_LIST entry (truncated name/size) overruns the parse | mitigate | parseFileListEntries bounds-checks each field read; stop on truncated entry rather than reading past the buffer |
| T-04-08 | Information disclosure | path traversal in a restore filename ("../") escaping the backups dir | mitigate | Restore reads from a File already constrained to getExternalFilesDir("backups"); filename regex rejects path separators; never construct the target from untrusted name segments |
| T-04-SC | Tampering | npm/pip/cargo installs | accept | No package installs this phase (RESEARCH Package Legitimacy Audit: zero new packages) |
</threat_model>

<verification>
- `--tests "*.ProjectProtocolTest"` green (FILE_LIST parse).
- `cd AndroidApp && ./gradlew :app:testDebugUnitTest :app:lintDebug` green.
- Hardware checkpoints recorded for phase-gate UAT.
</verification>

<success_criteria>
- 9-slot enumeration with active marker (PROJ-01 domain).
- Single-project opaque-archive backup written to app storage; restore validated + hardware-gated (PROJ-02 domain).
</success_criteria>

<output>
Create `.planning/phases/04-project-management/04-03-SUMMARY.md` when done. List both hardware checkpoints as open UAT items.
</output>
