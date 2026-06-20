# Phase 04 Project Management — Human UAT (physical EP-133 required)

These checks need a physical EP-133 (or EP-1320) connected over USB to an Android device
with USB host. No emulator can do USB MIDI. Each item lists the assumption the code ships
against, exact verification steps, and the fallback if the assumption is wrong.

**Status legend:** ☐ not verified · ✅ verified · ⚠️ failed (apply fallback)

---

## UAT-1 — FILE_LIST addressing: nodeId walk vs path string (RESEARCH Open Q1 / A2)

**Assumption shipped:** `/projects` enumerates by resolved numeric node ID. `resolveNodeId("/projects")`
walks from root (nodeId 0), matches the child named `projects`, then FILE_LISTs that node ID
(`SysExProtocol.buildFileListByNodeFrame`). Marked `// HARDWARE-VERIFY (Open Q1)` in
`MIDIRepository.listNodeBody` / `resolveNodeId` and `SysExProtocol.buildFileListByNodeFrame`.

**Steps:**
1. Connect a real EP-133 with at least one non-empty project slot.
2. Open the app, trigger project enumeration (Wave 3 Projects screen, or call `listProjects()`).
3. Confirm 9 slots appear (P00..P08) with names and sizes, and the currently-loaded slot is marked active.

**Fallback if it fails (empty list despite projects present):** the firmware likely accepts the
Phase 2 path string. One-line switch in `MIDIRepository.listNodeBody`: replace
`SysExProtocol.buildFileListByNodeFrame(currentDeviceId, nodeId, ...)` with
`SysExProtocol.buildFileListFrame(currentDeviceId, path, ...)` and thread the path through.

**Status:** ☐ not verified

---

## UAT-2 — FILE_GET/LIST response body byte offsets (RESEARCH Assumption A3)

**Assumption shipped:** after the dispatcher strips the `[TE_SYSEX_FILE, subcommand, status]`
prefix and 7-bit-unpacks the body, INIT/DATA/LIST bodies start exactly at the documented
offsets — `parseGetInitResponse` (fileId u16, flags, fileSize u32 BE, null-term name),
`parseGetDataResponse` (page u16, data), `parseFileListEntries` (nodeId u16, flags, size u32 BE,
null-term name). Marked `// HARDWARE-VERIFY (A3)` in `SysExProtocol`.

**Steps:**
1. Back up one project slot to phone storage (Wave 3 backup action).
2. Confirm the written `EP133-P{NN}-{ts}.tar` is the full archive (multi-KB), not a few hundred bytes.
3. Confirm slot names/sizes in the browser are sane (not garbled — a sign of an off-by-N body offset).

**Fallback if it fails (garbled names/sizes, truncated archive):** adjust the leading-byte skip in
`parseGetInitResponse` / `parseGetDataResponse` / `parseFileListEntries` to match the real
post-header offset, then re-run `ProjectProtocolTest` with corrected expected bytes.

**Status:** ☐ not verified

---

## UAT-3 — Single-project restore round-trip (RESEARCH Open Q2 / A6)

**Assumption shipped:** restore (FILE_PUT) mirrors GET's INIT/DATA paging. `restoreProject`
validates the `P(\d{2})\.tar` filename, constrains the source to the backups dir, resolves the
target slot node, and uploads via `putProjectArchive`. The destructive PUT is wired but the
user-facing button is GATED on this hardware pass. Marked `// HARDWARE-GATE (Open Q2)` in
`ProjectBackupManager.restoreProject`.

**Steps:**
1. Back up project slot PNN (UAT-2).
2. Modify that slot on the device (or pick a different slot), then restore the `PNN.tar` backup.
3. Confirm the device accepts the upload and the slot matches the backed-up project (audible/visible round-trip).

**Decision pending this pass:** enable the Wave 3 restore button (behind the restore-confirm
AlertDialog) only after a clean backup→restore round-trip. Until verified, ship backup +
library + share; keep restore behind the gate.

**Fallback if it fails:** PUT framing may differ from GET (`iterPut` re-init step, capability args,
or a different ack sequence). Re-examine `data/index.js uploadProjectArchive` and adjust
`putProjectArchive` / `buildFilePut*Frame`; leave the restore button disabled until it passes.

**Status:** ☐ not verified
