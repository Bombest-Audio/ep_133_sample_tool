# Phase 4: Project Management (Android) - Research

**Researched:** 2026-06-19
**Domain:** EP-133 SysEx file protocol (project archives), Android SAF/MediaStore, FileProvider share intents, Jetpack Compose
**Confidence:** HIGH on the blocking unknown (extracted directly from `data/index.js`); HIGH on Android share/storage mechanics

---

## VERDICT ON THE BLOCKING UNKNOWN: FEASIBLE

**Single-project enumerate / backup / restore is fully supported by the device.** The evidence is unambiguous in `data/index.js`.

The EP-133 exposes a filesystem with a top-level `/projects` directory that sits alongside `/sounds`. Each of the 9 project slots is its own directory node `/projects/P00` … `/projects/P08`, and **a whole project downloads as a single `.tar` archive via one FILE_GET on that node**. Restore is the inverse: a single FILE_PUT of the `P{NN}.tar` archive to `/projects`. No whole-device dump is required, and the `/sounds` library is a completely separate node tree.

### Evidence (matched strings from `data/index.js`)

| Finding | Matched string | Implication |
|---------|----------------|-------------|
| Top-level projects dir exists, peer of `/sounds` | path literals `"/projects"`, `"/sounds"` | `/projects` is FILE_LIST-able exactly like `/sounds` (Phase 2's known path) |
| 9 slots, named `P00`–`P08` | regex `/\.*\/projects\/(P\d{2})(\.tar\|\/[A-D]\/pads\.json)/` | Slot directory naming is `P` + 2 digits → 9 fixed slots |
| Whole project = one archive download | `downloadProjectArchive(o){…const j=await this.getNodeIdByPath(o);return await this.get(this.device.serial,j,_)}` called as `downloadProjectArchive("/projects/${name}")` | A single project is one node; `get()` on it returns the full project as a `.tar`. **This is the unit of "one project."** |
| Project backup file = `P{NN}.tar` | `Bt=`/projects/P${Jt}.tar`,Yt=new File([...Ot.data],Bt)` | The on-disk backup file is a single tar named `P{NN}.tar` |
| Restore is single-project, single-file | `uploadProjectArchive(o){const j=o.name.match(/\w*P(\d{2})\.tar/)…const _e=await this.getNodeIdByPath("/projects"),et=await this.getNodeIdByPath(`/projects/${$}`)…await this.put(this.device.serial,tt,$,_e,et,…)}` | Validates the `P{NN}.tar` name, resolves the `/projects` parent + target slot node, PUTs the tar. Whole-device restore is NOT required. |
| Per-project internal structure | `/projects/${o}/groups/${j}/${$}` with `GROUPS=["A","B","C","D"]`, `PADS=["01"…"12"]`; `pads.json` under `/projects/PNN/[A-D]/` | A project contains 4 groups × 12 pads of metadata — usable for a content summary |
| Active-project pointer | `getActiveProject(){…getNodeIdByPath("/projects")…getMetadata(...).active}` and `setMetadata(serial,j,{active:_})` | `/projects` directory metadata carries an `active` nodeId — which slot is currently loaded on the device |
| Project carries a name in metadata | `meta.name` (4 occurrences); `getMetadata(serial, nodeId)` per node | Each slot node has metadata including a `name` field for the content summary |

### Answers to the three blocking questions

1. **How are the 9 projects stored?** As 9 sibling directory nodes under a top-level `/projects` dir: `/projects/P00` … `/projects/P08`. The existing FILE_LIST command targets `/projects` (resolved to a node ID) and returns these 9 child nodes with their names, flags, and sizes — identical mechanics to the `/sounds` listing Phase 2 already implements.

2. **Can a single project be enumerated / backed up / restored independently?** Yes, all three:
   - **Enumerate:** FILE_LIST on the `/projects` node → 9 entries; FILE_LIST on `/projects/P{NN}` → its groups/pads for a summary.
   - **Backup:** FILE_GET on the `/projects/P{NN}` node → the project's `.tar` archive bytes. Independent of the other 8 slots and of `/sounds`.
   - **Restore:** FILE_PUT the `P{NN}.tar` archive to the `/projects` parent / target slot node.

3. **On-disk unit of "one project":** A single directory node (`/projects/P{NN}`) that the firmware serializes to / deserializes from **one `.tar` archive** on FILE_GET / FILE_PUT. The byte range is "the whole archive returned by the chunked GET" — see the multi-chunk protocol below.

**No fallback needed.** The positive conclusion is well-supported; this is not a forced result.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PROJ-01 | Browse all 9 EP-133 project slots with names + content overview | FILE_LIST on `/projects` node → 9 child nodes (`P00`–`P08`) each with `fileName`, `flags`, `fileSize`. Per-slot `name` from FILE_METADATA; content summary from FILE_LIST/METADATA on `/projects/P{NN}/groups/[A-D]`. Active slot from `/projects` metadata `active` pointer. |
| PROJ-02 | Backup a single project (not full device) to phone storage as a named file | FILE_GET (INIT + DATA multi-chunk) on the `/projects/P{NN}` node → `.tar` archive bytes, written to a `P{NN}.tar` (or `EP133-P{NN}-{timestamp}.tar`) file. Reuses the device file-transfer plumbing in `MIDIRepository`/`BackupManager`. |
| PROJ-03 | Backup library: scrollable list with timestamps + filenames | Local file enumeration of the backup directory; timestamps from file `lastModified()` or the `EP133-…` filename convention. New Compose screen + ViewModel mirroring `DeviceScreen`. |
| PROJ-04 | Share any backup file via Android share intent | `androidx.core` `ShareCompat.IntentBuilder` + a `FileProvider` (`content://` URI) `ACTION_SEND` with the backup MIME type. `androidx.core:core-ktx:1.12.0` already present. |
</phase_requirements>

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **Android only.** Do not touch `iOSApp/`.
- Build on the existing Phase 2 stack (`MIDIRepository`, `BackupManager`, `SysExProtocol`, `DeviceScreen` screen+ViewModel+SAF pattern) — not a parallel implementation.
- Project browser = new Compose screen + ViewModel mirroring the Pads/Beats/Sounds/Device pattern.
- Backup library = local file enumeration (app storage dir / SAF) with timestamps from file metadata or the `EP133-YYYY-MM-DD-HHmm` naming convention.
- If single-project backup proved infeasible, surface a fallback. **It is feasible — no fallback used.**

### Claude's Discretion
- Exact Compose layout of the project browser and library list.
- File storage location (app-specific dir vs SAF tree) — pick what makes share + library enumeration simplest. **Research recommends app-specific external dir — see Architectural Responsibility Map and Pitfall 4.**

### Deferred Ideas (OUT OF SCOPE)
- All iOS project management (SwiftUI Projects screen, fileExporter/fileImporter, ShareLink, security-scoped resources, custom UTType).
- Project-level restore hardening / multi-chunk transfer was flagged as "Phase 4 hardening" in BackupManager — **this phase MUST address the multi-chunk GET/PUT** because project backup depends on it (see Pitfall 1).
</user_constraints>

---

## Summary

The blocking unknown resolves cleanly: the EP-133 stores its 9 projects as individual directory nodes under a top-level `/projects` path, peer to the `/sounds` path Phase 2 already drives. A single project backs up as one `.tar` archive via a FILE_GET on its node, and restores via a FILE_PUT — both independent of the other slots and of the sound library. The upstream web app's `downloadProjectArchive` / `uploadProjectArchive` functions are the exact reference for the Android implementation.

The one real landmine is that Phase 2's `SysExProtocol.buildFileGetFrame` / `buildFilePutFrame` are a **simplified, incorrect single-byte-chunkIndex model** that does not match the device's actual two-phase INIT/DATA paging protocol. Phase 2's `BackupManager` documents this honestly ("Phase 4 will add robust multi-chunk handling"). Project archives are multi-kilobyte tars, so Phase 4 **must** implement the real protocol: a `GET_TYPE_INIT` request that returns `{fileSize, fileName}`, then a loop of `GET_TYPE_DATA` page requests until `fileSize` bytes are received, with per-page `nextPage` chaining and CRC32. PUT is the mirror (INIT then paged DATA). This is the highest-risk task in the phase and should be its own wave with unit tests against the response-parsing logic.

The Android UI/storage side is low-risk and uses standard platform primitives: a new Compose screen + ViewModel mirroring `DeviceScreen` for the browser and library, app-specific external storage for backup files (so both library enumeration and sharing are trivial), and `ShareCompat` + `FileProvider` for the share intent.

**Primary recommendation:** Plan three waves — (1) **real multi-chunk FILE_GET/PUT** in `SysExProtocol`/`MIDIRepository` (replace the simplified model, unit-tested); (2) **project enumeration + single-project backup** (FILE_LIST `/projects`, project archive download, write `P{NN}.tar`); (3) **backup library screen + share intent** (local enumeration + FileProvider/ShareCompat). Wave 1 is the gate; nothing downstream works without it.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Project slot enumeration (PROJ-01) | Domain (`MIDIRepository` SysEx) | UI (`ProjectsViewModel`) | FILE_LIST is a device protocol concern; VM only maps to UI state |
| Multi-chunk file transfer (PROJ-02 core) | Domain (`SysExProtocol` + `MIDIRepository`) | — | Pure protocol; must be device-tier, fully unit-testable without UI |
| Single-project archive backup (PROJ-02) | Domain (`BackupManager` or new `ProjectBackupManager`) | UI (progress state) | Orchestrates protocol; UI only observes progress |
| Backup file persistence | App-specific external storage (`Context.getExternalFilesDir`) | — | Local, no SAF picker needed for app-owned files; enables trivial enumeration + share |
| Backup library list (PROJ-03) | UI (`ProjectsViewModel` + Compose) | Storage (file enumeration) | Pure local file listing; no device involvement |
| Share intent (PROJ-04) | Android system (FileProvider + ShareCompat) | UI (trigger) | OS owns the share sheet; app grants a `content://` URI |

---

## EP-133 Project Protocol (Reverse-Engineered from data/index.js)

### Confidence: HIGH — extracted directly from the compiled web app

### Filesystem layout
```
/                       (root, nodeId 0)
├── /sounds             (Phase 2 already drives this)
└── /projects           ← directory metadata holds { active: <nodeId> } (current slot)
    ├── /projects/P00    ← one project = one directory node
    │   ├── A/pads.json      groups A–D
    │   ├── B/pads.json
    │   ├── C/pads.json
    │   ├── D/pads.json
    │   └── groups/{A,B,C,D}/{01..12}   per-pad metadata
    ├── /projects/P01
    ├── …
    └── /projects/P08   (9 slots total)
```
- `GROUPS = ["A","B","C","D"]`, `PADS = ["01".."12"]` → a project = 4 groups × 12 pads.
- Whole-project download = FILE_GET on the `/projects/P{NN}` node, returning a `.tar` archive.

### Node-ID resolution (correction to Phase 2's path-based model)
The device's FILE_LIST and FILE_GET operate on **numeric node IDs, not path strings**. The web app resolves a path to a node ID by walking segments:

```
getNodeIdByPath(path):
  if cached → return
  if "/" → nodeId 0
  parent = getNodeIdByPath(path-minus-last-segment)
  for child in iterNodes(serial, parentNodeId):   // = FILE_LIST paginated
     if child.name == lastSegment → cache + return child.id
```

`SysExFileListRequest` carries `(page: uint16, nodeId: uint16)` — **it lists a node ID, not a path.** Phase 2's `buildFileListFrame(path)` that embeds an ASCII path is not how the device actually works; it appears to have functioned for `/sounds` only incidentally or via a firmware convenience. **Plan to resolve `/projects` → nodeId first (FILE_LIST from root), then list that nodeId.** Treat this as a correction the planner must verify against hardware.

### FILE_LIST response (directory entries)
`SysExFileListResponse` per entry:
```
nodeId   = (a[0]<<8) | a[1]
flags    = a[2]                 // file type / capabilities
fileSize = a[3..6] (uint32 BE)
fileName = null-terminated string from a[7]
```
Multiple entries are concatenated; `SysExFileListResponse.iter()` walks them. Listing `/projects` yields the 9 slot entries with names + sizes — directly feeds PROJ-01.

### File type / capability flags
```
TE_SYSEX_FILE_FILE_TYPE_FILE = 1
TE_SYSEX_FILE_FILE_TYPE_DIR  = 2
TE_SYSEX_FILE_CAPABILITY_READ     = 4
TE_SYSEX_FILE_CAPABILITY_WRITE    = 8
TE_SYSEX_FILE_CAPABILITY_DELETE   = 16
TE_SYSEX_FILE_CAPABILITY_MOVE     = 32
TE_SYSEX_FILE_CAPABILITY_PLAYBACK = 64
```

### Multi-chunk FILE_GET (the real protocol — REPLACES Phase 2's simplified model)

GET is a two-phase sub-protocol under `TE_SYSEX_FILE_GET = 3`:

```
TE_SYSEX_FILE_GET_TYPE_INIT = 0
TE_SYSEX_FILE_GET_TYPE_DATA = 1
```

**Phase 1 — INIT** (`SysExGetFileInitRequest`), payload bytes:
```
[0] = TE_SYSEX_FILE_GET (3)
[1] = TE_SYSEX_FILE_GET_TYPE_INIT (0)
[2..3] = fileId / nodeId (uint16 BE)
[4..7] = offset (uint32 BE)         // 0 for a full project
( optional 8 extra bytes for capability args, e.g. READ )
```
**INIT response** (`SysexGetFileInitResponse`):
```
fileId   = a[0]<<8 | a[1]
flags    = a[2]
fileSize = a[3..6] (uint32 BE)      // total archive size — loop terminator
fileName = null-terminated from a[7]
```

**Phase 2 — DATA loop** (`SysExGetFileDataRequest`), repeated until `received >= fileSize`:
```
[0] = TE_SYSEX_FILE_GET (3)
[1] = TE_SYSEX_FILE_GET_TYPE_DATA (1)
[2..3] = page (uint16 BE)
```
**DATA response** (`SysExGetFileDataResponse`):
```
page     = a[0]<<8 | a[1]
nextPage = (page + 1) & 0xFFFF
data     = a.subarray(2)            // empty data → end of file
```
Loop logic from the web app's `iterGet`:
```
init → fileSize; received=0; page=0
while received < fileSize:
    resp = sendDataRequest(page)
    assert resp.page == page          // throws "unexpected page" on mismatch
    if resp.data.length == 0: break
    yield resp.data; received += resp.data.length
    page = resp.nextPage
crc32 accumulated across chunks
```
**Continuation signal:** intermediate responses carry `STATUS_SPECIFIC_SUCCESS_START (64)`; the final carries `STATUS_OK (0)`. The SysEx client resets the per-request timeout on each `SUCCESS_START` and keeps the request registered (`status < SUCCESS_START` deletes it). The Android dispatcher must keep the request pending while status ≥ 64.

### Multi-chunk FILE_PUT (restore)
Mirror of GET under `TE_SYSEX_FILE_PUT = 2`:
```
TE_SYSEX_FILE_PUT_TYPE_INIT = 0
TE_SYSEX_FILE_PUT_TYPE_DATA = 1
```
`uploadProjectArchive` validates the filename `P(\d{2})\.tar`, resolves `/projects` + `/projects/P{NN}` node IDs, then PUTs the tar bytes (capability `READ`, a 15s timeout per the web app), then re-inits the file handler. Restore is **single-project**.

### Project name + content summary (PROJ-01 overview)
- Slot name: FILE_LIST gives `fileName` (`P00`…); a friendlier user-set name lives in `meta.name` via `FILE_METADATA` (`TE_SYSEX_FILE_METADATA_GET`) on the slot node.
- Content overview: FILE_LIST / FILE_METADATA on `/projects/P{NN}/groups/[A-D]` to count assigned pads, or read `pads.json` for richer detail. Recommend a **lightweight summary** for v1 (e.g. "Group A–D, N pads assigned") to avoid downloading the full archive just to render the browser.
- Active slot: `FILE_METADATA` on the `/projects` dir → `active` nodeId; mark that slot in the browser.

---

## Standard Stack

### Core (all already in project — no new dependencies)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `android.media.midi` | System (API 29+) | SysEx I/O | Already drives Phase 2 protocol |
| `androidx.core:core-ktx` | 1.12.0 | `ShareCompat.IntentBuilder` for PROJ-04 | Already a dependency; canonical share API |
| `androidx.core.content.FileProvider` | (in core) | `content://` URI for sharing app files | The supported way to share files cross-app on API 24+ |
| Jetpack Compose BOM | 2024.02.00 | Projects browser + library UI | Existing UI stack |
| Navigation Compose | 2.7.7 | Register the new Projects tab | Existing nav pattern (`EP133App.kt`) |
| `kotlinx.coroutines` | (via BOM) | Async transfer + flows | Existing |
| `kotlinx.coroutines-test` | 1.7.3 | Unit tests for the chunk protocol | Existing test dep |

### New Dependencies Required
**None.** FileProvider, ShareCompat, and app-specific external storage are all platform/AndroidX primitives already on the classpath.

### Package Legitimacy Audit
Not applicable — this phase installs **no external packages**. All capabilities use the Android platform and AndroidX libraries already declared in `AndroidApp/app/build.gradle.kts`. (slopcheck/registry verification skipped: zero new packages.)

---

## Architecture Patterns

### System Architecture Diagram
```
[Projects tab tap]
      │
      ▼
ProjectsViewModel ──(observe)── MIDIRepository.deviceState (connected?)
      │
      ├──► enumerate slots:  MIDIRepository.listProjects()
      │         │  FILE_LIST(nodeId=/projects) ──SysEx──► EP-133
      │         ◄── 9 entries {nodeId,name,size}  +  meta.active
      │         └──► ProjectSlot[] → UI list (PROJ-01)
      │
      ├──► backup one slot:  ProjectBackupManager.backupProject(slotNode)
      │         │  GET_INIT(nodeId) → {fileSize,fileName}
      │         │  loop GET_DATA(page) ◄── chunks ── until fileSize  (multi-chunk)
      │         │  assemble .tar bytes
      │         └──► write P{NN}-{ts}.tar to getExternalFilesDir("backups") (PROJ-02)
      │
      ├──► library:  enumerate getExternalFilesDir("backups")/*.tar
      │         └──► [{name, lastModified}] → scrollable list (PROJ-03)
      │
      └──► share:  FileProvider.getUriForFile(file)
                └──► ShareCompat.IntentBuilder.setStream(uri).startChooser() (PROJ-04)
```

### Recommended Project Structure
```
AndroidApp/app/src/main/java/com/ep133/sampletool/
├── domain/midi/
│   ├── SysExProtocol.kt          # ADD: GET_INIT/DATA + PUT_INIT/DATA builders + response parsers
│   ├── MIDIRepository.kt         # ADD: listProjects(), node-id resolution, multi-chunk get/put dispatch
│   └── ProjectBackupManager.kt   # NEW: single-project archive backup/restore over multi-chunk GET/PUT
├── ui/projects/
│   ├── ProjectsScreen.kt         # NEW: 9-slot browser + backup library (mirror DeviceScreen)
│   └── ProjectsViewModel.kt      # NEW: co-located, mirrors DeviceViewModel
└── ui/EP133App.kt                # EDIT: register Projects nav destination
res/xml/file_paths.xml            # NEW: FileProvider path config
AndroidManifest.xml               # EDIT: declare the FileProvider
```

### Pattern 1: Node-ID-based FILE_LIST for `/projects`
**What:** Resolve `/projects` to a node ID (FILE_LIST from root nodeId 0, find child named "projects"), then FILE_LIST that node ID to get the 9 slots.
**When:** PROJ-01 enumeration, before any per-slot operation.
```kotlin
// Source: data/index.js getNodeIdByPath + SysExFileListRequest(page, nodeId)
// FILE_LIST request payload: [TE_SYSEX_FILE, GET? no — list]:
//   asBytes: setUint8(0, TE_SYSEX_FILE_LIST); setUint16(1, page); setUint16(3, nodeId)
// Parse each SysExFileListResponse: nodeId(2) | flags(1) | size(4 BE) | name(null-term)
```
**Note:** This corrects Phase 2's path-string FILE_LIST. The planner must verify whether the device also accepts a path string (Phase 2 shipped that for `/sounds`); if so, `/projects` may work the same way, but the node-ID model is what the reference implementation uses.

### Pattern 2: Multi-chunk archive download (the gate task)
**What:** INIT → paged DATA loop, accumulating chunks into the `.tar` byte array.
**When:** PROJ-02 backup. See the protocol section for exact byte layouts.
```kotlin
// Source: data/index.js iterGet
suspend fun getFile(nodeId: Int): ByteArray {
    val init = sendGetInit(nodeId)              // → fileSize, fileName
    val out = ByteArrayOutputStream(init.fileSize)
    var page = 0
    while (out.size() < init.fileSize) {
        val resp = sendGetData(page)            // suspend until response
        require(resp.page == page) { "unexpected page ${resp.page}, expected $page" }
        if (resp.data.isEmpty()) break
        out.write(resp.data)
        page = resp.nextPage                    // (page + 1) & 0xFFFF
    }
    return out.toByteArray()
}
```
Each DATA response that is intermediate carries status `STATUS_SPECIFIC_SUCCESS_START` (64); keep the pending deferred alive while status ≥ 64 and resolve on `STATUS_OK`.

### Pattern 3: App-specific storage for backups (enables trivial library + share)
**What:** Write backups to `context.getExternalFilesDir("backups")` rather than a SAF tree.
**Why:** App-owned files need no SAF picker to re-enumerate (PROJ-03 just lists the directory) and FileProvider can vend a `content://` URI for them directly (PROJ-04). SAF `CreateDocument` would scatter files into user-chosen locations that are painful to enumerate for a library.
```kotlin
val dir = context.getExternalFilesDir("backups")!!   // /Android/data/com.ep133.sampletool/files/backups
val file = File(dir, "EP133-P0$slot-${timestamp}.tar")
file.writeBytes(archiveBytes)
// Library:
val backups = dir.listFiles { f -> f.extension == "tar" }
    ?.sortedByDescending { it.lastModified() }
    ?.map { BackupItem(it.name, it.lastModified()) } ?: emptyList()
```

### Pattern 4: Share via FileProvider + ShareCompat
**Manifest:**
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```
**`res/xml/file_paths.xml`:**
```xml
<paths>
    <external-files-path name="backups" path="backups/" />
</paths>
```
**Share call:**
```kotlin
// Source: androidx.core ShareCompat (CITED: developer.android.com/training/sharing/send)
val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
ShareCompat.IntentBuilder(context)
    .setType("application/x-tar")        // or application/octet-stream
    .setStream(uri)
    .setChooserTitle("Share EP-133 project backup")
    .startChooser()
```

### Anti-Patterns to Avoid
- **Reusing Phase 2's `buildFileGetFrame`/`buildFilePutFrame` for projects.** Their single-byte `chunkIndex` model does not match the device INIT/DATA protocol and only "worked" for the simplified Phase 2 backup that dropped chunks. Project tars are multi-page — this WILL truncate them.
- **Downloading the full project archive just to render the browser summary.** Use FILE_LIST/METADATA for the overview; only download on explicit backup.
- **Sharing a `file://` URI.** Throws `FileUriExposedException` on API 24+. Always go through FileProvider.
- **Writing backups via SAF `CreateDocument` for an in-app library.** Makes PROJ-03 enumeration and PROJ-04 sharing far harder than app-specific storage.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Sharing a file to other apps | Custom intent + manual URI grants | `ShareCompat.IntentBuilder` + `FileProvider` | Handles URI permission grants, chooser, MIME correctly across OEMs |
| `content://` URI for an app file | Hand-built `Uri` | `FileProvider.getUriForFile` | Only supported way to expose app files post-API-24 |
| Backup library storage | Custom DB of backup metadata | `File.listFiles()` + `lastModified()` | Filesystem already holds name + timestamp; no schema needed |
| Multi-chunk transfer framing | Ad-hoc chunk loop with magic offsets | Port the web app's `iterGet`/`iterPut` INIT+DATA state machine | The device protocol is precise (page chaining, CRC, status continuation); the reference is canonical |
| `.tar` parsing | A tar reader | None — store/restore the archive opaque | The app never needs to read inside the project tar; it's a device blob. (Contrast Phase 2's `.pak`/ZIP, which the app did assemble.) |

**Key insight:** A project backup is an opaque device archive. Unlike Phase 2's sound backup (which assembled a ZIP from individual WAV downloads), a project is already a single archived node — download the blob, write it, share it. Don't re-archive or inspect it.

---

## Runtime State Inventory

Not a rename/refactor phase, but it adds persistent state and new OS-registered config:

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | Project backup `.tar` files in `getExternalFilesDir("backups")` | New directory; no migration. Library reads it directly. |
| Live service config | None — offline app | None |
| OS-registered state | New `FileProvider` declared in AndroidManifest with authority `${applicationId}.fileprovider` | Manifest edit + `res/xml/file_paths.xml`; install-time registration only |
| Secrets/env vars | None — fully offline | None |
| Build artifacts | `app/src/main/assets/data/` auto-copied by Gradle (unchanged) | None |

---

## Common Pitfalls

### Pitfall 1: Phase 2's simplified FILE_GET model truncates project archives
**What goes wrong:** `SysExProtocol.buildFileGetFrame(deviceId, path, chunkIndex, requestId)` sends a 2-byte chunk index and `BackupManager.createBackup` reads exactly one response chunk (`fileChunks.first()`). A project `.tar` spans many pages; this returns only the first page → corrupt/partial backup.
**Why it happens:** Phase 2 explicitly shipped a "simplified single-chunk model" (documented in `BackupManager` header and the dropped-chunk comment) and deferred robust handling to Phase 4.
**How to avoid:** Implement the real INIT/DATA paging (Pattern 2) before any project backup task. Make it the phase's gating wave.
**Warning signs:** Backed-up `.tar` is a few hundred bytes; restore fails or device rejects the archive.

### Pitfall 2: FILE_LIST takes a node ID, not a path
**What goes wrong:** Sending `/projects` as an ASCII path may not enumerate slots if the firmware expects a node ID (the reference resolves paths to IDs first).
**Why it happens:** Phase 2's `buildFileListFrame(path)` embeds a path string; the web app's `SysExFileListRequest` carries `(page, nodeId)`.
**How to avoid:** Resolve `/projects` → nodeId by listing root (nodeId 0) and matching the child named `projects`, then list that node ID. Verify on hardware whether path strings also work (Phase 2's `/sounds` may have relied on a firmware convenience).
**Warning signs:** Empty project list despite a connected device with projects.

### Pitfall 3: Multi-chunk request times out because the dispatcher drops it on the first response
**What goes wrong:** The current `MIDIRepository` dispatch resolves a `CompletableDeferred` on the first matching response. For paged transfers, intermediate responses carry `STATUS_SPECIFIC_SUCCESS_START (64)` and more pages follow.
**Why it happens:** The web app keeps the request registered while `status >= SUCCESS_START` and resets a per-chunk timeout; it only deletes the request on `status < SUCCESS_START` (i.e. `STATUS_OK` or error).
**How to avoid:** Model the paged transfer as a flow/channel, not a single deferred. Keep the pending handler alive across `SUCCESS_START` responses; complete on `STATUS_OK`. Reset the timeout on each chunk.
**Warning signs:** Transfer of large projects times out mid-way; only the first page arrives.

### Pitfall 4: Sharing a `file://` URI crashes on modern Android
**What goes wrong:** `Uri.fromFile(...)` in an `ACTION_SEND` throws `FileUriExposedException` (API 24+).
**How to avoid:** Always `FileProvider.getUriForFile` with `grantUriPermissions` + the `file_paths.xml` `<external-files-path>` entry matching `getExternalFilesDir("backups")`.
**Warning signs:** Crash the moment the share sheet is invoked.

### Pitfall 5: 7-bit packing must wrap the binary archive payload
**What goes wrong:** The `.tar` bytes are arbitrary 8-bit data; SysEx payloads must be ≤ 0x7F. Phase 2's `pack7bit`/`unpack7bit` already exist and are correct, but the planner must ensure the DATA-page payloads are decoded with `unpack7bit` and PUT payloads encoded with `pack7bit`.
**How to avoid:** Route all project archive bytes through the existing codec; unit-test a round-trip on a known binary blob.
**Warning signs:** CRC mismatch; restored project rejected by device.

### Pitfall 6: Writing to `getExternalFilesDir` returns null before storage is mounted
**What goes wrong:** `getExternalFilesDir()` can return null if external storage is unavailable.
**How to avoid:** Null-check and fall back to `context.filesDir` (internal); update `file_paths.xml` with a `<files-path>` entry too if you support the fallback.
**Warning signs:** NPE on backup with no SD/emulated storage.

---

## Code Examples

### FILE_GET INIT/DATA frame builders (to ADD to SysExProtocol.kt)
```kotlin
// Source: data/index.js SysExGetFileInitRequest / SysExGetFileDataRequest
// Payload is the inner file payload; buildFrame() will 7-bit pack it.

fun buildFileGetInitPayload(nodeId: Int, offset: Int = 0): ByteArray =
    byteArrayOf(
        TE_SYSEX_FILE.toByte(),
        TE_SYSEX_FILE_GET.toByte(),          // 3
        0,                                    // GET_TYPE_INIT
        (nodeId shr 8).toByte(), (nodeId and 0xFF).toByte(),       // uint16 BE
        (offset shr 24).toByte(), (offset shr 16).toByte(),
        (offset shr 8).toByte(), (offset and 0xFF).toByte(),       // uint32 BE
    )

fun buildFileGetDataPayload(page: Int): ByteArray =
    byteArrayOf(
        TE_SYSEX_FILE.toByte(),
        TE_SYSEX_FILE_GET.toByte(),
        1,                                    // GET_TYPE_DATA
        (page shr 8).toByte(), (page and 0xFF).toByte(),
    )

// INIT response (after unpack7bit, skipping the file-subcommand header):
// fileId = b[0]<<8|b[1]; flags = b[2]; fileSize = b[3..6] BE; fileName = null-term from b[7]
// DATA response: page = b[0]<<8|b[1]; data = b[2..]; nextPage = page+1
```
NOTE: confirm the exact byte offsets after the `TE_SYSEX_FILE, GET, TYPE` prefix against hardware — the reference parses the response body after the protocol header is stripped.

### List the 9 project slots (to ADD to MIDIRepository.kt)
```kotlin
// Source: data/index.js iterNodes(serial, nodeId) over SysExFileListRequest(page, nodeId)
data class ProjectSlot(val nodeId: Int, val name: String, val sizeBytes: Long, val isActive: Boolean)

suspend fun listProjects(): List<ProjectSlot> {
    val projectsNode = resolveNodeId("/projects")        // FILE_LIST from root, find "projects"
    val activeNode = getMetadata(projectsNode)["active"]?.toIntOrNull()
    return listNode(projectsNode).map { entry ->
        ProjectSlot(entry.nodeId, entry.name, entry.sizeBytes, entry.nodeId == activeNode)
    }
}
```

### Share a backup file (PROJ-04)
```kotlin
fun shareBackup(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    ShareCompat.IntentBuilder(context)
        .setType("application/octet-stream")
        .setStream(uri)
        .setChooserTitle("Share EP-133 project backup")
        .startChooser()
}
```

---

## State of the Art

| Old Approach (Phase 2) | Current Approach (Phase 4) | Why |
|------------------------|----------------------------|-----|
| Simplified single-chunk FILE_GET, drops continuation pages | Real INIT/DATA paged transfer with `nextPage` chaining + CRC | Project archives are multi-page; truncation = corrupt backup |
| Path-string FILE_LIST (`buildFileListFrame("/sounds")`) | Node-ID resolution then FILE_LIST by node ID | Matches the device's actual addressing model |
| Whole-`/sounds` backup assembled into a ZIP `.pak` | Single project as an opaque `.tar` device archive | Projects are already archived nodes; no re-archiving |
| SAF `CreateDocument` per backup | App-specific external dir + FileProvider share | Enables a self-contained backup library + share |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Slots are `P00`–`P08` (9 dirs) and FILE_LIST on `/projects` returns exactly these | Verdict / Pattern 1 | Browser shows wrong count; low risk — regex `P\d{2}` + "9 slots" requirement align |
| A2 | FILE_LIST requires a node ID, and `/sounds` path-string in Phase 2 worked via firmware convenience | Pitfall 2 | If the device accepts paths uniformly, node resolution is unnecessary (harmless extra step). If it requires IDs, Phase 2's `/sounds` path may break — verify. |
| A3 | Exact response-body byte offsets after the `[FILE, GET, TYPE]` header | Code Examples | Off-by-header parsing of INIT/DATA; must validate on hardware before shipping |
| A4 | `meta.name` is the user-facing project name field | PROJ-01 | Summary shows slot id (`P00`) instead of a friendly name; cosmetic fallback exists |
| A5 | A project content summary can be built from FILE_LIST/METADATA without downloading the archive | PROJ-01 | If metadata is insufficient, summary may need a partial archive read — slower browser |
| A6 | PUT mirrors GET's INIT/DATA paging for restore | Multi-chunk FILE_PUT | Restore could need a different framing; verify against `iterPut` on hardware |

**These are HIGH-confidence on structure (matched strings) but the exact response byte offsets and the path-vs-nodeId question need one hardware verification pass before the chunk codec is locked.**

---

## Open Questions

1. **Path string vs node ID for FILE_LIST/FILE_GET.**
   - Known: the web app resolves paths to node IDs and lists/gets by ID. Phase 2 shipped path-string frames for `/sounds`.
   - Unclear: whether the firmware also accepts path strings directly (making node resolution optional).
   - Recommendation: implement node-ID resolution (matches the reference), but add a hardware check early; if path strings work, keep them for simplicity and document it.

2. **Does PROJ-02 restore (project upload) ship this phase, or only backup?**
   - CONTEXT scope lists backup, library, share — restore of a single project is implied by "project management" but not explicit in PROJ-02..04.
   - Recommendation: implement and unit-test the PUT path (it's the inverse of GET and the reference exists), but gate the user-facing restore button on a hardware test. Surface as a planner decision.

3. **Content-summary depth for the browser (PROJ-01).**
   - Known: groups A–D, 12 pads each, `pads.json` per group, `meta.name`.
   - Unclear: how much detail to render without a full archive download.
   - Recommendation: v1 = slot name + active marker + "N pads assigned" (from group metadata). Defer deep summaries.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Android Studio + SDK 35, JDK 17, Gradle 8.x | Build/test | Assumed (project builds) | per CLAUDE.md | — |
| `androidx.core:core-ktx` (ShareCompat/FileProvider) | PROJ-04 | ✓ | 1.12.0 | — |
| Physical EP-133 device | PROJ-01/02 SysEx + multi-chunk validation | Unknown | — | No emulator for USB MIDI; protocol correctness needs hardware |
| Android device with USB host (API 29+) | All device features | Unknown | — | Emulator cannot do USB MIDI |

**Missing dependencies with no fallback:** Physical EP-133 + USB-host Android device — required to validate the multi-chunk GET/PUT and project enumeration. Unit tests cover frame building, response parsing, chunk-loop logic, file enumeration, and share-intent construction without hardware.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2 + kotlinx-coroutines-test 1.7.3 |
| Config file | none — standard Android unit test runner |
| Quick run command | `cd AndroidApp && ./gradlew :app:testDebugUnitTest` |
| Full suite command | `cd AndroidApp && ./gradlew :app:testDebugUnitTest :app:lintDebug` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PROJ-02 | GET INIT payload bytes correct (nodeId/offset BE) | unit | `./gradlew :app:testDebugUnitTest --tests "*.ProjectProtocolTest"` | ❌ Wave 0 |
| PROJ-02 | GET DATA loop assembles fileSize bytes across pages | unit | `./gradlew :app:testDebugUnitTest --tests "*.MultiChunkGetTest"` | ❌ Wave 0 |
| PROJ-02 | Page mismatch throws; empty data terminates | unit | `./gradlew :app:testDebugUnitTest --tests "*.MultiChunkGetTest"` | ❌ Wave 0 |
| PROJ-02 | SUCCESS_START keeps request pending; OK completes | unit | `./gradlew :app:testDebugUnitTest --tests "*.SysExDispatchTest"` | ❌ Wave 0 |
| PROJ-01 | FILE_LIST response parses {nodeId,flags,size,name} per entry | unit | `./gradlew :app:testDebugUnitTest --tests "*.ProjectProtocolTest"` | ❌ Wave 0 |
| PROJ-01 | listProjects() maps 9 entries + marks active slot | unit | `./gradlew :app:testDebugUnitTest --tests "*.ProjectsViewModelTest"` | ❌ Wave 0 |
| PROJ-03 | Library enumerates `.tar` files sorted by mtime desc | unit | `./gradlew :app:testDebugUnitTest --tests "*.BackupLibraryTest"` | ❌ Wave 0 |
| PROJ-04 | Share builds FileProvider content:// URI + ACTION_SEND | unit (Robolectric or intent assert) | `./gradlew :app:testDebugUnitTest --tests "*.ShareIntentTest"` | ❌ Wave 0 |
| — | 7-bit pack/unpack round-trips a binary archive blob | unit | `./gradlew :app:testDebugUnitTest --tests "*.SysExProtocolTest"` | partial (Phase 2) |

### Sampling Rate
- **Per task commit:** `cd AndroidApp && ./gradlew :app:testDebugUnitTest`
- **Per wave merge:** `cd AndroidApp && ./gradlew :app:testDebugUnitTest :app:lintDebug`
- **Phase gate:** full unit suite green + manual UAT on physical EP-133 (enumerate 9 slots, back up one project, restore it, share the file) before `/gsd:verify-work`.

### Wave 0 Gaps
- [ ] `ProjectProtocolTest.kt` — GET/PUT INIT/DATA frame bytes, FILE_LIST response parsing
- [ ] `MultiChunkGetTest.kt` — paged assembly, page-mismatch, empty-terminator, CRC
- [ ] `SysExDispatchTest.kt` — multi-response request lifecycle (SUCCESS_START vs OK)
- [ ] `ProjectsViewModelTest.kt` — slot list mapping + active marker
- [ ] `BackupLibraryTest.kt` — directory enumeration + timestamp sort
- [ ] `ShareIntentTest.kt` — ShareCompat/FileProvider intent construction
- [ ] FileProvider manifest entry + `res/xml/file_paths.xml`

**Manual-only (hardware required):** real slot names, project download integrity, restore round-trip, share sheet on a real device.

---

## Security Domain

`security_enforcement` not set in config → treated as enabled. This is an offline, single-user device tool with no auth, network, or untrusted input beyond the device itself.

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | offline app, no accounts |
| V3 Session Management | no | none |
| V4 Access Control | no | single local user |
| V5 Input Validation | yes | Validate restore archive filename (`P\d{2}\.tar`) and SysEx response lengths/CRC before acting; reject malformed device responses |
| V6 Cryptography | no | no secrets; never hand-roll crypto (none needed) |

| Threat Pattern | STRIDE | Standard Mitigation |
|----------------|--------|---------------------|
| Malformed/oversized SysEx response corrupts buffer | Tampering / DoS | Length-check every response field; CRC-verify chunks; cap accumulation buffer |
| Shared `content://` URI over-grants access | Information disclosure | `FileProvider` with `exported=false`, scoped `file_paths.xml`, `FLAG_GRANT_READ_URI_PERMISSION` only |
| Restoring an arbitrary/corrupt `.tar` to the device | Tampering | Validate filename pattern + size; AlertDialog confirmation (mirror Phase 2 restore confirm) before PUT |

---

## Project Constraints (from CLAUDE.md)

- Kotlin: `val` over `var`; `when` over if/else chains; no `GlobalScope`; `viewModelScope`/`lifecycleScope`; rethrow `CancellationException`.
- Naming: `MIDI` not `Midi`, `EP133` not `Ep133`; `StateFlow`/`SharedFlow` for reactive state, never expose `MutableStateFlow`; underscore-prefixed private backing fields.
- ViewModels co-located with their Screen composable (`ProjectsViewModel` in `ProjectsScreen.kt`, matching the convention; `ChordsViewModel` is the only documented exception).
- `withContext(Dispatchers.IO)` for file writes/reads; `Log.e(TAG, msg, throwable)` always includes the throwable. Log tags: `EP133MIDI` (MIDI), `EP133APP` (repository).
- Android min API 29; no cross-platform framework; `data/index.js` is read-only (reference only).
- GSD workflow: route changes through a GSD command. Commit format `feat(04-project-management-XX): …`.
- ProGuard `isMinifyEnabled = true` for release — any reflectively-accessed new public API needs `@Keep`.

---

## Sources

### Primary (HIGH confidence)
- `data/index.js` (compiled EP-133 K.O. II web app) — project filesystem layout (`/projects/P00`–`P08`), `downloadProjectArchive`/`uploadProjectArchive`, `iterGet`/`iterNodes`, `SysExGetFileInitRequest`/`SysExGetFileDataRequest`/`SysexGetFileInitResponse`/`SysExGetFileDataResponse`/`SysExFileListResponse`/`SysExFileListRequest`, `getNodeIdByPath`, file subcommand + capability constants, GROUPS/PADS, active-project metadata pointer.
- `AndroidApp/.../SysExProtocol.kt`, `BackupManager.kt`, `MIDIRepository.kt`, `DeviceScreen.kt` — current implementation; confirmed the simplified single-chunk model and path-string FILE_LIST that Phase 4 must replace/correct.
- `.planning/phases/02-android-device-management/02-RESEARCH.md` — protocol foundation (manufacturer ID, frame format, 7-bit codec, status codes), built upon here.
- `AndroidApp/app/build.gradle.kts` — confirmed `androidx.core:core-ktx:1.12.0`, applicationId `com.ep133.sampletool`, no existing FileProvider.

### Secondary (MEDIUM confidence)
- CLAUDE.md (project + repo) — stack, conventions, build commands.
- Android FileProvider/ShareCompat patterns — developer.android.com sharing guide (CITED).

## Metadata

**Confidence breakdown:**
- Blocking unknown (feasibility, layout, archive unit): HIGH — direct string matches in the reference implementation.
- Multi-chunk GET/PUT protocol shape: HIGH on structure, MEDIUM on exact response byte offsets after the file-subcommand header (needs one hardware verification).
- Path-vs-nodeId FILE_LIST: MEDIUM — reference uses node IDs; Phase 2 shipped path strings; reconcile on hardware.
- Android share/storage/library mechanics: HIGH — standard platform APIs already on the classpath.

**Research date:** 2026-06-19
**Valid until:** 2026-08-19 (stable Android APIs; TE firmware protocol unlikely to change)
