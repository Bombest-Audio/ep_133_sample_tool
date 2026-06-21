# Phase 5 — Active Group Selection Protocol (app↔device sync)

**Researched:** 2026-06-21
**Domain:** EP-133 K.O. II TE SysEx file-metadata protocol, reverse-engineered from `data/index.js`
**Goal:** Replace the wrong MIDI Program Change group-select with the real TE SysEx file-metadata mechanism, both directions.
**Overall confidence:** HIGH on wire formats and A/B/C/D mapping; MEDIUM on the exact response-body offset the Kotlin dispatcher must strip; the few remaining unknowns need ONE hardware capture each (listed at the end).

> All byte offsets below are **before** 7-bit packing and are the FILE-subsystem payload only. The Kotlin `buildFrame()` prepends the `TE_SYSEX_FILE(5)` subsystem byte and 7-bit-packs the whole payload; the JS `asBytes()` methods produce the bytes *starting at the op byte* (e.g. `METADATA=7`) because the JS transport (`sendAndReceiveTeSysexBySerial(serial, TE_SYSEX_FILE, op.asBytes(), ...)`) supplies the subsystem byte separately. So `asBytes()[0]` in JS == `buildFrame` payload byte **after** the `5`. This is exactly how the existing Kotlin `buildFileSystemFrame` already works.

---

## TL;DR / Headline

The device does **not** use Program Change for group select. Group select = **set the `/projects/<active>/groups` directory node's metadata `active` field to the target group's nodeId**, via a single-frame `FILE_METADATA / METADATA_SET` SysEx carrying tiny JSON `{"active":<nodeId>}`. Reading the current group = read that same directory's metadata `active` pointer, then resolve the pointed node's name. Group names on the device are literally `"A","B","C","D"` (`GROUPS=["A","B","C","D"]`), in that order — a clean 1:1 with `PadChannel.entries[ordinal]`.

Device→app: the device **pushes** `METADATA_UPDATED` events carrying `{active:<nodeId>}` for the groups directory **if** the host first subscribes (`FILE_INIT` with `SUBSCRIBE` flag). Without a subscription the device pushes nothing, so the fallback is polling. Recommendation below covers both; start with polling (less new dispatch surface), keep subscribe as a follow-up.

---

## Verified constants (de-minified)

From `data/index.js` byte ~827617 (`const TE_SYSEX_FILE=5, ...`):

```
TE_SYSEX_FILE                       = 5     // subsystem (prepended by transport)
TE_SYSEX_FILE_INIT                  = 1     // subscribe/handshake; returns chunkSize
TE_SYSEX_FILE_INIT_SUBSCRIBE        = 1     // flag for INIT
TE_SYSEX_FILE_PUT                   = 2
TE_SYSEX_FILE_GET                   = 3
TE_SYSEX_FILE_LIST                  = 4     // <-- NOTE: list op is 4
TE_SYSEX_FILE_PLAYBACK              = 5
TE_SYSEX_FILE_DELETE                = 6
TE_SYSEX_FILE_METADATA              = 7
  TE_SYSEX_FILE_METADATA_SET        = 1
  TE_SYSEX_FILE_METADATA_GET        = 2
  TE_SYSEX_FILE_METADATA_SET_PAGED  = 4
    ..._SET_PAGED_TYPE_INIT         = 0
    ..._SET_PAGED_TYPE_DATA         = 1
TE_SYSEX_FILE_INFO                  = 11    // getNode lives here, not metadata
TE_SYSEX_FILE_MOVED                 = 12
TE_SYSEX_FILE_FILE_TYPE_FILE        = 1     // flags bit
TE_SYSEX_FILE_FILE_TYPE_DIR         = 2     // flags bit (FSNode.type: flags&1 ? File : Dir)
TE_SYSEX_FILE_CAPABILITY_READ       = 4
TE_SYSEX_FILE_CAPABILITY_WRITE      = 8     // isWritable = flags & 8
TE_SYSEX_FILE_CAPABILITY_DELETE     = 16
TE_SYSEX_FILE_CAPABILITY_MOVE       = 32
TE_SYSEX_FILE_CAPABILITY_PLAYBACK   = 64
```

Event opcodes (`TE_SYSEX_FILE_EVENT`, byte ~828546):
```
METADATA_UPDATED = 3
FILE_ADDED       = 8
FILE_UPDATED     = 9
FILE_DELETED     = 10
FILE_MOVED       = 13
```

Path/structure constants (byte ~1134250):
```
PROJECTS = ["01","02","03","04","05","06","07","08","09"]
GROUPS   = ["A","B","C","D"]
PADS     = ["01","02","03","04","05","06","07","08","09","10","11","12"]
```

> **Correction to the brief's "already-confirmed facts":** the brief listed `TE_SYSEX_FILE = 5` as an *opcode*; it's the **subsystem** byte. `FILE_GET=3`/`FILE_PUT=2` are correct. `FILE_LIST=4` and `FILE_INFO=11` were not in the brief — the Kotlin already has both right. **Confidence: HIGH** (source: literal constant block).

---

## Wire formats — the critical deliverable

### 1. `SysExFileGetMetadataRequest` — read metadata (HIGH)

De-minified (byte ~833139):
```js
class SysExFileGetMetadataRequest{
  constructor(fileId, page, key=null){ this.fileId=fileId; this.page=page; this.key=key; }
  asBytes(){
    const o=new Uint8Array(6 + (this.key?.length ? this.key.length+1 : 0)), v=new DataView(o.buffer);
    v.setUint8(0, TE_SYSEX_FILE_METADATA);     // 7
    v.setUint8(1, TE_SYSEX_FILE_METADATA_GET); // 2
    v.setUint16(2, this.fileId);               // u16 BE
    v.setUint16(4, this.page);                 // u16 BE
    if(this.key!=null) writeStringToView(v,6,this.key,true); // ASCII + NUL
    return o;
  }
}
```
Payload (after the `5` subsystem byte): `[7, 2, nodeId_hi, nodeId_lo, page_hi, page_lo, (key ASCII…, 0x00)?]`. `key` is optional; when null, the whole metadata blob is returned page by page.

### 2. `SysExFileGetMetadataResponse` + paging loop (HIGH)

Response class (byte ~833139):
```js
class SysExFileGetMetadataResponse{
  constructor(a){ this.page = a[0]<<8 | a[1]; this.metadata = parseNullTerminatedString(a,2); }
}
```
Response body = `[page u16 BE][JSON-fragment ASCII … NUL?]`. The driver loop (FileHandler.getMetadata, byte ~843253):
```js
for (const key of keys||[null]) {
  let acc="", page=0;
  for(;;){
    const req = new SysExFileGetMetadataRequest(nodeId, page, key);
    const resp = await send(req);
    if (resp.data.byteLength <= 2) break;            // terminator: empty/tiny body
    const r = new SysExFileGetMetadataResponse(resp.data);
    if (r.page !== page) throw "unexpected page";
    page += 1;
    acc += r.metadata;
    if (resp.data.slice(-1)[0] === 0) break;         // last byte NUL => final page
  }
  Object.assign(result, JSON.parse(acc));
}
```
**Two terminator conditions:** (a) body ≤ 2 bytes, or (b) last byte of the body is `0x00`. For `{"active":<id>}` it's a single page that ends in NUL. **Confidence: HIGH.**

### 3. `SysExFileSetMetadataRequest` — write metadata (HIGH)

De-minified (byte ~832383):
```js
class SysExFileSetMetadataRequest{
  constructor(fileId, metadata){ this.fileId=fileId; this.metadata=metadata; } // metadata = JSON string
  asBytes(){
    const o=new Uint8Array(4 + this.metadata.length + 1), v=new DataView(o.buffer);
    v.setUint8(0, TE_SYSEX_FILE_METADATA);     // 7
    v.setUint8(1, TE_SYSEX_FILE_METADATA_SET); // 1
    v.setUint16(2, this.fileId);               // u16 BE
    writeStringToView(v, 4, this.metadata, true); // JSON ASCII + NUL
    return o;
  }
}
```
Payload: `[7, 1, nodeId_hi, nodeId_lo, <JSON ASCII bytes>, 0x00]`. For group select the JSON is `{"active":<groupNodeId>}` — tiny, always single-frame.

**Single vs paged decision** (FileHandler.setMetadata, byte ~842427): `const $=8; if (JSON.stringify(obj).length <= chunkSize-8) singleFrame; else paged`. With chunkSize ≥ ~1024 (device-reported) and `{"active":NNNN}` ≤ ~16 bytes, **always single-frame**. The paged variants (`SET_PAGED_INIT` op layout `[7,4,0,fileId u16, size u32]`, `SET_PAGED_DATA` `[7,4,1,page u16, data…]`) are documented for completeness but **not needed** for active-group. **Confidence: HIGH.**

**Ack:** `setMetadata` `await`s `sendSysExFileRequest` but does **not** parse a typed response — it relies on the request/response round-trip completing (the transport resolves on the matching `requestId` echo). For Kotlin: treat the matching METADATA response frame (any status) as the ack; do not require a JSON body. See "Kotlin gaps" — you need a small METADATA_SET ack deferred. **Confidence: MEDIUM** (the exact ack frame shape for SET needs one hardware capture; see HW-VERIFY-3).

### 4. `getNode(nodeId)` → `{name, id, parentId, isWritable, …}` (HIGH)

`getNode` uses **`FILE_INFO` (op 11)**, not metadata (FileHandler, byte ~840788):
```js
async getNode(serial, nodeId){
  const req = new SysExFileInfoRequest(nodeId);
  const resp = await send(req);
  const r = new SysexFileInfoResponse(resp.data);
  return new FSNode(r.nodeId, r.parentId, r.fileName, r.flags, r.fileSize);
}
```
Request (byte ~833656): `[11, nodeId_hi, nodeId_lo]`.
Response (`SysexFileInfoResponse`, byte ~833859):
```
nodeId   = a[0..1]  u16 BE
parentId = a[2..3]  u16 BE
flags    = a[4]
fileSize = a[5..8]  u32 BE
fileName = null-terminated ASCII from a[9]
```
`isWritable = (flags & CAPABILITY_WRITE(8)) != 0`; `type = (flags & FILE_TYPE_FILE(1)) ? File : Dir`. (FSNode getters, byte ~836620.)
**This differs from the existing Kotlin FILE_LIST entry layout** (LIST entries have no parentId field — see #5). Don't reuse the LIST parser for INFO. **Confidence: HIGH.**

### 5. `getNodeIdByPath(path)` and FILE_LIST (HIGH; matches existing Kotlin)

`getNodeIdByPath` walks from root nodeId 0, FILE_LISTing each segment and matching the child by name — exactly what Kotlin's `resolveNodeId` already does. FILE_LIST request (byte ~831483):
```js
class SysExFileListRequest{
  constructor(page, nodeId){ this.page=page; this.nodeId=nodeId; }
  asBytes(){ // [4, page u16 BE, nodeId u16 BE]
    ... setUint8(0,TE_SYSEX_FILE_LIST); setUint16(1,page); setUint16(3,nodeId); }
}
```
FILE_LIST response paging (FileHandler.iterNodes, byte ~840291):
```
body[0..1] = page u16 BE  (must equal requested page)
body[2..]  = concatenated entries, parsed by SysExFileListResponse.iter
loop until resp.data.byteLength <= 2  (empty page = end)
```
Per-entry (`SysExFileListResponse`, byte ~831709):
```
nodeId   = a[0..1] u16 BE
flags    = a[2]
fileSize = a[3..6] u32 BE
fileName = null-terminated ASCII from a[7]
length   = 7 + name.length ; iter advances o += length + 1  (skip the NUL)
```
**The existing Kotlin `parseFileListEntries` matches this exactly** (offsets 0,2,3..6,7; skip NUL). `resolveNodeId` matches `getNodeIdByPath`. **No changes needed to node resolution.** **Confidence: HIGH.**

> One nuance the Kotlin already half-handles: JS `iterNodes` sends a **page** field and concatenates pages; the device returns `[page u16][entries…]` per page and terminates on a ≤2-byte body. The Kotlin node-list path accumulates across `SUCCESS_START` statuses and terminates on `STATUS_OK`. These are two different framings of the same idea — the Kotlin one is what Phase 4 verified works on hardware, so keep it. (HW-VERIFY-1 already resolved in Phase 4.)

---

## High-level group/project logic (de-minified, DeviceService byte ~1141000)

```js
async getActiveProject(){
  const projectsNode = await getNodeIdByPath("/projects");
  const active = (await fileHandler.getMetadata(serial, projectsNode)).active; // a nodeId
  const node = await fileHandler.getNode(serial, active);
  if(!node.isWritable) return null;          // sanity gate
  return { node, path:`/projects/${node.name}` };
}

async getActiveGroup(){
  const proj = await getActiveProject(); if(proj==null) return null;
  const groupsNode = await getNodeIdByPath(`${proj.path}/groups`);
  const active = (await fileHandler.getMetadata(serial, groupsNode)).active; // group nodeId
  const node = await fileHandler.getNode(serial, active);
  const meta = await fileHandler.getMetadata(serial, node.id);
  return { node, path:`${proj.path}/groups/${node.name}`, meta };
}

async setActiveGroup(groupPath){
  const proj = await getActiveProject(); if(proj==null) throw "no active project";
  const target = await getNodeIdByPath(groupPath);                       // group nodeId
  const groupsNode = await getNodeIdByPath(`/projects/${proj.node.name}/groups`);
  await fileHandler.setMetadata(serial, groupsNode, { active: target }); // <-- the write
}
```
Confirms the brief precisely: **selecting a group = SET the groups-directory node's `{active}` to the target group's nodeId.** `getActiveProject` gates on `isWritable` (a loaded/real project). **Confidence: HIGH.**

---

## Group name → A/B/C/D mapping (DECISIVE — HIGH)

- Device groups live at `/projects/<NN>/groups/<name>` where `<name> ∈ {"A","B","C","D"}` — literal letters, from `GROUPS=["A","B","C","D"]` (byte ~1134339). `getProjectPadMeta` iterates `GROUPS.flatMap(g => PADS.map(p => getMetadata(.../groups/${g}/${p}))))` — i.e. paths are built straight from those letter strings.
- `PadChannel` enum order is `A,B,C,D` (ordinals 0..3), identical to `GROUPS`.

**Mapping rule (use this):**
- App→device: `setActiveGroup(channel)` → group **node name** = `GROUPS[channel.ordinal]` = `channel.name` (Kotlin `PadChannel.A.name == "A"`). Resolve `/projects/<activeProjName>/groups/<channel.name>` → nodeId, then SET groups-dir `{active:nodeId}`.
- Device→app: read groups-dir `active` → `getNode(active).name` (a letter) → `PadChannel.valueOf(name)`.

**Prefer name-based mapping over FILE_LIST index.** The letters are explicit in the firmware; relying on child enumeration order is a needless risk. If `getNode().name` ever returns something unexpected (HW-VERIFY-2), fall back to FILE_LIST order → `PadChannel.entries[index]`. But the evidence says names are "A".."D". **Confidence: HIGH** (two independent corroborations: the `GROUPS` constant and the path-building in `getProjectPadMeta`/`getActivePads`).

---

## Device → app change notification (MEDIUM-HIGH)

The device **does** push changes — but only to subscribers. `getActiveGroup` is otherwise pull-only; the device sends nothing unsolicited unless a `FILE_INIT` subscription was established.

Subscribe handshake (`SysExFileInitRequest`, byte ~829974):
```
[1, flags, maxResponseLength u32 BE]   // op=FILE_INIT(1), flags=SUBSCRIBE(1)
```
Response (`SysExFileInitResponse`): `chunkSize = a[1..4] u32 BE` (also how the JS app learns chunkSize for the single-vs-paged metadata decision).

Pushed event (top-level `command === TE_SYSEX_FILE`, `data[0]` = event opcode; FileHandler.onFileEvent byte ~845741):
```js
// data[0] selects the event class; data.slice(1) is the body
METADATA_UPDATED(3) -> SysExFileMetadataUpdatedEventMessage:
  nodeId   = body[0..1] u16 BE
  metadata = JSON.parse(parseNullTerminatedString(body,2))  // e.g. {"active":1234}
```
The app handler (byte ~1146300) reacts to `METADATA_UPDATED` whose `metadata.active` is a number, then resolves the new active path — this is exactly the "device changed group on the hardware, reflect it in the UI" path.

**Implication for the recommendation:** there IS a push channel. But wiring it requires (a) sending the subscribe `FILE_INIT` on connect, and (b) a new event-dispatch branch in Kotlin that recognizes unsolicited `TE_SYSEX_FILE` event frames (opcode in payload[0] ∈ {3,8,9,10,13}) with no matching `requestId`. That's more new surface than a poll. **Recommendation: ship polling first; treat subscribe+event as a fast-follow** that removes the poll. **Confidence: MEDIUM-HIGH** (HW-VERIFY-4: confirm the device actually emits METADATA_UPDATED for the *groups directory* node, not only for leaf files, after a front-panel group change).

---

## Existing Kotlin coverage assessment

### Reusable as-is (HIGH)
- **7-bit codec** (`pack7bit`/`unpack7bit`) — correct, matches JS `packToBuffer`/`unpackInPlace`.
- **`buildFrame` envelope** — verified on hardware in Phase 4. Prepends `5` subsystem byte and packs; matches the JS transport contract.
- **`resolveNodeId(path)`** — equals `getNodeIdByPath`. Use it directly to resolve group paths.
- **`parseFileListEntries`** — equals `SysExFileListResponse` per-entry layout. Reuse for node enumeration.
- **`listNodeBody` + `pendingNodeListDeferred`** — node FILE_LIST round-trip with accumulation. Reuse.
- **`queryProjectsActiveNode()`** — already reads `/projects` "active" pointer. **BUT** it does so via the *path-based* `buildFileMetadataFrame` + greet-style `key:value` parse (see gap below), not the nodeId+page+JSON GET. It currently works for `/projects` on hardware (Phase 4), which is a useful data point — see HW-VERIFY-3.

### Gaps / mismatches to fix (the real work)

1. **Metadata frame builder is path-based, not nodeId+page+key.** `buildFileMetadataFrame(path)` sends `[5,7, <path ASCII>]`. The reference GET is `[5,7, METADATA_GET(2), nodeId u16, page u16, key?]`. The reference SET is `[5,7, METADATA_SET(1), nodeId u16, JSON, 0x00]`. **You need two new builders** (`buildMetadataGetFrame(nodeId,page,key)`, `buildMetadataSetFrame(nodeId,json)`); the existing path-based one should be considered legacy/Phase-4-storage-only.
   - *Open question (HW-VERIFY-3):* the existing path-based metadata call **works on real hardware for `/projects` and `/sounds`** (Phase 4 shipped it). That suggests the firmware may accept BOTH a path form AND the op-2/op-1 nodeId form, OR the Phase-4 call is hitting a different code path than the reference tool. One capture resolves which. The safe path: implement the reference nodeId form (it's what the official tool uses for groups) and keep the path form only where Phase 4 already relies on it.

2. **Metadata response parser is greet-format, not JSON.** `dispatchFileResponse` parses METADATA payload with `parseGreetResponse` (semicolon `k:v` string). The reference response is `[page u16][JSON … NUL]`. **You need a JSON-aware METADATA response parse** (`[page u16] + null-terminated JSON`, accumulate pages, `JSON.parse`). Add a `pendingMetadataJsonDeferred: CompletableDeferred<JSONObject>`. (Keep the old greet-style parse only for the legacy `/sounds` storage call if HW-VERIFY-3 shows it's a distinct firmware path.)

3. **No METADATA_SET ack deferred.** SET needs a `CompletableDeferred<Boolean>` resolved when the matching METADATA response frame returns (status byte; treat any non-error as success). The dispatcher's METADATA branch currently only feeds `pendingMetadataDeferred` (a GET). Add a SET branch.

4. **`chunkSize` is hardcoded** (`MAX_PAGE_BYTES=4096`). For `{active:id}` single-frame this is irrelevant — the JSON is ~16 bytes, far under any chunkSize. **No change needed for active-group.** (If you later add the FILE_INIT subscribe handshake, capture `chunkSize` from its response and store per-device, mirroring JS `deviceChunkSizes`.)

5. **No event-dispatch branch** for unsolicited `TE_SYSEX_FILE` event frames (only needed for the subscribe-based push path, not for polling).

---

## Recommendation — minimal, implementation-ready

Ship in two steps. Step 1 (polling) is enough to fix the bug and is low-risk. Step 2 (subscribe) is an optimization.

### Step 1 — SysExProtocol additions

```kotlin
// METADATA GET: [7, 2, nodeId u16 BE, page u16 BE, (key ASCII + 0x00)?]
fun buildMetadataGetFrame(deviceId:Int, nodeId:Int, page:Int=0, key:String?=null, requestId:Int): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(TE_SYSEX_FILE); out.write(TE_SYSEX_FILE_METADATA); out.write(TE_SYSEX_FILE_METADATA_GET)
    out.write(nodeId shr 8); out.write(nodeId and 0xFF)
    out.write(page shr 8); out.write(page and 0xFF)
    if (key != null) { out.write(key.toByteArray(US_ASCII)); out.write(0) }
    return buildFrame(deviceId, CMD_PRODUCT_SPECIFIC, requestId, out.toByteArray())
}

// METADATA SET: [7, 1, nodeId u16 BE, JSON ASCII, 0x00]
fun buildMetadataSetFrame(deviceId:Int, nodeId:Int, json:String, requestId:Int): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(TE_SYSEX_FILE); out.write(TE_SYSEX_FILE_METADATA); out.write(TE_SYSEX_FILE_METADATA_SET)
    out.write(nodeId shr 8); out.write(nodeId and 0xFF)
    out.write(json.toByteArray(US_ASCII)); out.write(0)
    return buildFrame(deviceId, CMD_PRODUCT_SPECIFIC, requestId, out.toByteArray())
}

// FILE_INFO (getNode): [11, nodeId u16 BE]
fun buildFileInfoFrame(deviceId:Int, nodeId:Int, requestId:Int): ByteArray =
    buildFrame(deviceId, CMD_PRODUCT_SPECIFIC, requestId,
        byteArrayOf(TE_SYSEX_FILE.toByte(), TE_SYSEX_FILE_INFO.toByte(),
                    (nodeId shr 8).toByte(), (nodeId and 0xFF).toByte()))

data class NodeInfo(val nodeId:Int, val parentId:Int, val flags:Int, val sizeBytes:Long, val name:String) {
    val isWritable get() = flags and TE_SYSEX_FILE_CAPABILITY_WRITE != 0
    val isDir      get() = flags and TE_SYSEX_FILE_FILE_TYPE_FILE == 0
}
fun parseFileInfo(body:ByteArray): NodeInfo { /* nodeId u16, parentId u16, flags u8, size u32, name@9 NUL */ }

// METADATA GET response body: [page u16 BE][JSON ASCII ... NUL?]; accumulate pages.
fun parseMetadataPage(body:ByteArray): Pair<Int,String>   // (page, fragment)
fun isMetadataTerminator(body:ByteArray): Boolean = body.size <= 2 || body.last().toInt() == 0
```
Need `TE_SYSEX_FILE_CAPABILITY_WRITE = 8` added to the constants block.

### Step 1 — MIDIRepository additions

```kotlin
suspend fun getMetadataJson(nodeId:Int): JSONObject              // GET loop: page 0.., accumulate, JSON.parse
suspend fun setMetadata(nodeId:Int, json:String): Boolean        // single frame; await SET ack deferred
suspend fun getNodeInfo(nodeId:Int): NodeInfo?                   // FILE_INFO round-trip

suspend fun getActiveGroupIndex(): Int? {
    val projectsNode = resolveNodeId("/projects") ?: return null
    val activeProj   = getMetadataJson(projectsNode).optInt("active", -1).takeIf { it>=0 } ?: return null
    val projName     = getNodeInfo(activeProj)?.name ?: return null
    val groupsNode   = resolveNodeId("/projects/$projName/groups") ?: return null
    val activeGroup  = getMetadataJson(groupsNode).optInt("active", -1).takeIf { it>=0 } ?: return null
    val groupName    = getNodeInfo(activeGroup)?.name ?: return null   // "A".."D"
    return PadChannel.entries.indexOfFirst { it.name == groupName }.takeIf { it>=0 }
}

suspend fun setActiveGroup(index:Int): Boolean {
    val channel = PadChannel.entries.getOrNull(index) ?: return false
    val projectsNode = resolveNodeId("/projects") ?: return false
    val activeProj   = getMetadataJson(projectsNode).optInt("active", -1).takeIf { it>=0 } ?: return false
    val projName     = getNodeInfo(activeProj)?.name ?: return false
    val groupNode    = resolveNodeId("/projects/$projName/groups/${channel.name}") ?: return false
    val groupsNode   = resolveNodeId("/projects/$projName/groups") ?: return false
    return setMetadata(groupsNode, """{"active":$groupNode}""")
}
```
New dispatcher branches:
- METADATA GET response → feed `pendingMetadataJsonDeferred` (accumulate `[page u16][JSON…]`, complete on terminator).
- METADATA SET response (matching requestId, status OK) → complete `pendingMetadataSetAckDeferred`.
- FILE_INFO response → complete `pendingNodeInfoDeferred` with `parseFileInfo(unpack7bit(body))`.

Guard all of these with the existing `statsQueryInFlight`-style single-flight mutex so they don't collide with Phase-4 storage queries.

### Step 1 — Wiring

- `PadsScreen.kt:104`: replace `midi.programChange(channel.ordinal)` with
  `viewModelScope.launch { midi.setActiveGroup(channel.ordinal) }`. Keep the optimistic `_selectedChannel.value = channel` for instant UI; reconcile on poll.
- Device→app poll: while the Pads screen is resumed, `LaunchedEffect`/`repeatOnLifecycle(RESUMED)` loop every **1500 ms** → `getActiveGroupIndex()` → if non-null and differs, update `_selectedChannel`. Pause on STARTED→STOPPED to avoid background MIDI churn. Cost: one resolveNodeId chain + two metadata GETs + two FILE_INFOs per poll (~6 round-trips). To cut that, **cache** `/projects` and `/projects/<proj>/groups` nodeIds + the active-project name across polls; only re-resolve on a project-change signal. Cached, a poll is ~2 round-trips (groups-dir GET + active-group INFO).
- Remove the Program Change path for group select (leave `programChange` for the Chords sound-select use at `ChordsViewModel.kt:121/161`, which is unrelated).

### Step 2 (fast-follow) — subscribe + push, drop the poll

- On device connect, send `FILE_INIT` `[1, SUBSCRIBE(1), maxResponseLength u32]`; store `chunkSize` from the response.
- Add an event-dispatch branch: a `TE_SYSEX_FILE` frame whose `payload[0]` ∈ {3,8,9,10,13} and that has **no matching pending requestId** is an unsolicited event. For `METADATA_UPDATED(3)`: `nodeId=body[1..2]`, `metadata=JSON(body@3)`. If `nodeId == groupsDirNode` and `metadata.active` present → map to `PadChannel` and update `_selectedChannel`.
- Once events are trusted on hardware (HW-VERIFY-4), remove the 1.5 s poll.

---

## Hardware-verify checklist (each needs ONE capture; user has EP-133 on a Pixel via adb)

Run with the app on the Pads screen, device connected. Tag: `EP133MIDI`/`EP133APP`.

```bash
adb logcat -c
adb logcat -s EP133MIDI EP133APP | tee /tmp/ep133.log
```

- **HW-VERIFY-2 (group name shape):** in `getActiveGroupIndex`, log `getNodeInfo(activeGroup).name`. Tap A/B/C/D on the **device** front panel, confirm log prints exactly `A`/`B`/`C`/`D`. If it prints `1`/`01`/something else → switch to FILE_LIST-order mapping (`PadChannel.entries[index]`).
  - adb check: `adb logcat -s EP133APP | grep "activeGroup name"`
- **HW-VERIFY-3 (metadata GET/SET wire form + ack):** log the raw outbound METADATA frames and the inbound response bytes (pre-unpack). Confirm (a) the device returns `[page u16][JSON…NUL]` for the nodeId-form GET, and (b) what a SET response frame looks like (status byte? echoed nodeId?). This decides the SET-ack completion condition.
  - adb check: `adb logcat -s EP133MIDI | grep -E "METADATA (GET|SET)"`
- **HW-VERIFY-3b (path-vs-nodeId metadata):** confirm whether the firmware also answers the legacy path-based `buildFileMetadataFrame` for `/projects/<p>/groups` (it does for `/sounds` in Phase 4). If yes, you can skip the nodeId resolution for the groups dir and SET by path — simpler. Capture both forms once.
- **HW-VERIFY-4 (push events):** with a `FILE_INIT` subscribe sent, change the group on the **device**; confirm an unsolicited `METADATA_UPDATED` frame arrives for the groups-directory node with `{active:…}`. If only leaf-file events arrive (not the groups dir), keep polling.
  - adb check: `adb logcat -s EP133APP | grep "METADATA_UPDATED"`
- **HW-VERIFY-1 (resolved, retest):** `/projects` lists by nodeId via the Phase-4 path — already confirmed working; just sanity-check `resolveNodeId("/projects/01/groups/A")` returns a non-null nodeId in the log.

---

## Confidence summary

| Section | Confidence | Evidence location |
|---|---|---|
| Opcode/constant values | HIGH | `data/index.js` ~827617 (literal const block) |
| GET request layout | HIGH | ~833139 `SysExFileGetMetadataRequest.asBytes` |
| GET response + paging terminators | HIGH | ~833139 response + ~843253 driver loop |
| SET request layout | HIGH | ~832383 `SysExFileSetMetadataRequest.asBytes` |
| SET ack frame shape | MEDIUM | ~842427 setMetadata awaits but doesn't parse (HW-VERIFY-3) |
| getNode = FILE_INFO(11) + response layout | HIGH | ~840788 getNode, ~833859 `SysexFileInfoResponse` |
| getNodeIdByPath = FILE_LIST walk | HIGH | ~831483 req, ~831709 entry, matches Kotlin `resolveNodeId` |
| setActiveGroup = SET groups-dir {active} | HIGH | ~1141348 `setActiveGroup` |
| A/B/C/D = group node names | HIGH | ~1134339 `GROUPS=["A","B","C","D"]` + `getProjectPadMeta` path-build |
| Device push via FILE_INIT subscribe | MEDIUM-HIGH | ~829974 INIT, ~845741 onFileEvent, ~1146300 handler (HW-VERIFY-4) |
| Kotlin reuse (codec/frame/resolve/list) | HIGH | SysExProtocol.kt + MIDIRepository.kt read in full |
| Path-vs-nodeId metadata firmware tolerance | LOW | Phase-4 path form works for /sounds; reference uses nodeId form (HW-VERIFY-3b) |
