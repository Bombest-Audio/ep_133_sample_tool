import Foundation

// Swift port of AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/ProjectBackupManager.kt.
//
// Translation map from the Kotlin original:
// - `Flow<ProjectBackupProgress>` → `AsyncStream` (unbounded buffer; producer Task cancelled
//   on stream termination)
// - `Context.getExternalFilesDir("backups")` (with internal-storage fallback) → a
//   caller-supplied base directory (the app's Documents directory by default) + `backups/`
// - `java.io.File` → `URL` + `FileManager`
// - `Dispatchers.IO` file reads/writes → direct FileManager/Data calls (small archives; the
//   paged device transfer dominates)

/// Subdirectory under the base directory holding project `.tar` backups
/// (Kotlin `BACKUPS_DIR` under app-specific external storage).
private let backupsDirName = "backups"

/// A restore filename must be a project archive. Matches both the plain legacy form
/// (`[prefix]P{NN}.tar`) and the form `suggestedProjectFilename` actually writes
/// (`[{customName}-]EP133-P{NN}-{yyyy-MM-dd-HHmm}.tar`) — the old `^\w*P(\d{2})\.tar$`
/// rejected every backup the app itself created because `\w*` can't span the hyphens/date,
/// so restore could never find its own files. Anchored full match; the character classes
/// exclude `/`, `\`, and `.` (outside `.tar`), so a traversal name can never match.
private let projectTarPattern = #"^(?:[A-Za-z0-9_]+-)?(?:EP133-)?P(\d{2})(?:-\d{4}-\d{2}-\d{2}-\d{4})?\.tar$"#

/// Progress events emitted by `ProjectBackupManager.backupProject` / `restoreProject`.
///
/// Mirrors `BackupProgress`; a project is a single opaque `.tar` device archive, so
/// `total`/`current` track bytes rather than a file count.
enum ProjectBackupProgress: Equatable {
    /// Transfer in progress: `current` bytes of `total` moved.
    case progress(current: Int, total: Int)
    /// Backup/restore complete. `file` is the written archive (backup) or source (restore).
    case done(file: URL)
    /// Failed: `message` describes the error.
    case error(message: String)
}

/// One on-disk project backup in the library (PROJ-03). `timestamp` is the file's
/// last-modified time in milliseconds since the epoch (Kotlin `File.lastModified()`).
struct BackupItem: Equatable {
    let file: URL
    let name: String
    let timestamp: Int64
}

/// Single-project archive backup / restore + local backup-library enumeration over the
/// Wave 1 paged transfer protocol. Mirrors AndroidApp's `ProjectBackupManager.kt`.
///
/// A project backup is an **opaque device blob**: download the `.tar` via the paged GET,
/// write it to app storage, done — no ZIP re-archiving and never inspect inside the tar
/// (contrast `BackupManager`'s `/sounds` `.pak` assembly). Restore is the inverse PUT.
///
/// Reference: data/index.js `downloadProjectArchive` / `uploadProjectArchive`.
@MainActor
final class ProjectBackupManager {

    private let midi: MIDIRepository

    init(_ midi: MIDIRepository) {
        self.midi = midi
    }

    /// The default backup base directory: the app's Documents directory (the iOS analog of
    /// Android's app-specific external storage).
    nonisolated static func defaultBaseDirectory() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// Backup file name: `[{customName}-]EP133-P{NN}-{timestamp}.tar`. An optional app-side
    /// `customName` (see ProjectNameStore) is sanitized to `[A-Za-z0-9_ ]`, trimmed to 40 chars,
    /// spaces → underscores, and prefixed so the exported file carries the user's project name.
    func suggestedProjectFilename(
        slot: MIDIRepository.ProjectSlot,
        customName: String? = nil
    ) -> String {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd-HHmm"
        let slotIndex = slotIndexOf(slot.name)
        // Same pipeline order as Kotlin: strip → trim → take(40) → spaces to underscores.
        let prefix = customName
            .map { $0.replacingOccurrences(of: "[^A-Za-z0-9_ ]", with: "", options: .regularExpression) }
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .map { String($0.prefix(40)) }
            .map { $0.replacingOccurrences(of: " ", with: "_") }
            .flatMap { $0.isEmpty ? nil : "\($0)-" }
            ?? ""
        return String(format: "%@EP133-P%02d-%@.tar", prefix, slotIndex, fmt.string(from: Date()))
    }

    /// Back up a single project slot to the backup library as an opaque `.tar`.
    ///
    /// Downloads the archive via the paged GET (no ZIP re-archiving), then writes it under
    /// `<baseDirectory>/backups` (Kotlin wrote under `getExternalFilesDir("backups")`, falling
    /// back to internal storage — the caller-supplied base replaces both). Emits
    /// Progress → Done(file), or Error on failure.
    func backupProject(
        slot: MIDIRepository.ProjectSlot,
        baseDirectory: URL = ProjectBackupManager.defaultBaseDirectory(),
        customName: String? = nil
    ) -> AsyncStream<ProjectBackupProgress> {
        AsyncStream { continuation in
            let task = Task { @MainActor [midi] in
                defer { continuation.finish() }
                if midi.deviceState.outputPortId == nil {
                    continuation.yield(.error(message: "No EP-133 connected"))
                    return
                }
                continuation.yield(.progress(current: 0, total: Int(slot.sizeBytes)))

                let archive: [UInt8]
                do {
                    archive = try await midi.getProjectArchive(nodeId: slot.nodeId)
                } catch is CancellationError {
                    return
                } catch {
                    EP133Log.error(.backup, "Project backup download failed for \(slot.name): \(error)")
                    continuation.yield(.error(message: "Download failed: \(error)"))
                    return
                }

                let file: URL
                do {
                    let dir = try self.backupsDir(baseDirectory)
                    let out = dir.appendingPathComponent(
                        self.suggestedProjectFilename(slot: slot, customName: customName))
                    try Data(archive).write(to: out)   // opaque blob — written as-is
                    file = out
                } catch {
                    EP133Log.error(.backup, "Project backup write failed for \(slot.name): \(error)")
                    continuation.yield(.error(message: "Write failed: \(error)"))
                    return
                }

                continuation.yield(.progress(current: archive.count, total: archive.count))
                continuation.yield(.done(file: file))
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Enumerate the backup library: `.tar` files in the backups dir, newest first (PROJ-03).
    /// Pure enough to test against a temp dir.
    func listBackups(baseDirectory: URL = ProjectBackupManager.defaultBaseDirectory()) -> [BackupItem] {
        guard let dir = try? backupsDir(baseDirectory) else { return [] }
        return Self.enumerateBackups(dir: dir)
    }

    /// Restore a single project archive to the device (destructive PUT).
    ///
    /// Validates the filename against `P(\d{2})\.tar` before any device write (threat T-04-06):
    /// the regex also rejects path separators, so a traversal name ("../") can never match.
    /// Reads the bytes, then uploads via the paged PUT.
    ///
    /// HARDWARE-GATE (Open Q2): restore is wired and validated, but the user-facing button is
    /// gated on a hardware backup→restore round-trip pass; default = ship behind the Wave 3
    /// restore-confirm alert. The destructive PUT requires that confirmation before invocation.
    func restoreProject(
        file: URL,
        baseDirectory: URL = ProjectBackupManager.defaultBaseDirectory()
    ) -> AsyncStream<ProjectBackupProgress> {
        AsyncStream { continuation in
            let task = Task { @MainActor [midi] in
                defer { continuation.finish() }
                let name = file.lastPathComponent
                guard let slotIndex = Self.tarSlotIndex(of: name) else {
                    continuation.yield(.error(
                        message: "Invalid backup filename: \(name) (expected P{NN}.tar)"))
                    return
                }
                // Constrain the source to the app-owned backups dir — defends against a restore
                // whose path escapes the library (T-04-08); the file must live under backupsDir.
                do {
                    let dir = try self.backupsDir(baseDirectory)
                    let parent = file.deletingLastPathComponent()
                    if parent.resolvingSymlinksInPath().standardizedFileURL.path
                        != dir.resolvingSymlinksInPath().standardizedFileURL.path {
                        continuation.yield(.error(message: "Backup must be in the app library"))
                        return
                    }
                } catch {
                    continuation.yield(.error(message: "Backup must be in the app library"))
                    return
                }
                if midi.deviceState.outputPortId == nil {
                    continuation.yield(.error(message: "No EP-133 connected"))
                    return
                }

                // Device project slots are named 01..09, so valid indices are 1...9. The old
                // 0...8 bound was off by one: it rejected a legitimate P09 restore and admitted
                // a nonexistent P00.
                guard (1...9).contains(slotIndex) else {
                    continuation.yield(.error(
                        message: "Backup filename slot out of range: \(name)"))
                    return
                }

                let bytes: [UInt8]
                do {
                    bytes = [UInt8](try Data(contentsOf: file))
                } catch {
                    EP133Log.error(.backup, "Project restore read failed for \(name): \(error)")
                    continuation.yield(.error(message: "Read failed: \(error)"))
                    return
                }
                continuation.yield(.progress(current: 0, total: bytes.count))

                // Resolve the target slot node by re-enumerating /projects and matching the
                // slot index.
                let slot: MIDIRepository.ProjectSlot?
                do {
                    slot = try await midi.listProjects()
                        .first { self.slotIndexOf($0.name) == slotIndex }
                } catch is CancellationError {
                    return
                } catch {
                    continuation.yield(.error(message: "Upload failed: \(error)"))
                    return
                }
                guard let slot else {
                    continuation.yield(.error(message: String(
                        format: "Target slot P%02d not found on device", slotIndex)))
                    return
                }

                let ok: Bool
                do {
                    ok = try await midi.putProjectArchive(slotNodeId: slot.nodeId, tarBytes: bytes)
                } catch is CancellationError {
                    return
                } catch {
                    EP133Log.error(.backup, "Project restore upload failed for \(name): \(error)")
                    continuation.yield(.error(message: "Upload failed: \(error)"))
                    return
                }

                if ok {
                    continuation.yield(.progress(current: bytes.count, total: bytes.count))
                    continuation.yield(.done(file: file))
                } else {
                    continuation.yield(.error(message: "Device did not acknowledge the restore"))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// The backups dir under `base`, created if missing (Kotlin `backupsDir(context)`; the
    /// external-vs-internal storage fallback collapses into the caller-supplied base).
    private nonisolated func backupsDir(_ base: URL) throws -> URL {
        let dir = base.appendingPathComponent(backupsDirName, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Extract the numeric slot index from a project name. The device names slots "01".."09"
    /// (no "P" prefix — confirmed from the /projects/NN device paths), so match digits anywhere;
    /// "P03" still yields 3. Fall back to 0 so a backup never ends up named "P-1".
    private nonisolated func slotIndexOf(_ name: String) -> Int {
        guard let range = name.range(of: #"\d{1,2}"#, options: .regularExpression) else {
            return 0
        }
        return Int(name[range]) ?? 0
    }

    /// Full-match a restore filename against `PROJECT_TAR_REGEX` and return the captured
    /// slot index, or nil if the name is not a valid project archive name.
    private nonisolated static func tarSlotIndex(of name: String) -> Int? {
        let regex = try! NSRegularExpression(pattern: projectTarPattern)
        let whole = NSRange(name.startIndex..., in: name)
        guard let match = regex.firstMatch(in: name, range: whole),
              match.range == whole,
              let group = Range(match.range(at: 1), in: name) else {
            return nil
        }
        return Int(name[group])
    }

    /// Pure backup-library enumeration: `.tar` files in `dir`, newest first. Hardware-free
    /// and Context-free so it is directly unit-testable against a temp directory (PROJ-03).
    nonisolated static func enumerateBackups(dir: URL) -> [BackupItem] {
        let fm = FileManager.default
        guard let contents = try? fm.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: [.contentModificationDateKey]) else {
            return []
        }
        return contents
            .filter { $0.pathExtension == "tar" }
            .map { url -> BackupItem in
                let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?
                    .contentModificationDate ?? Date(timeIntervalSince1970: 0)
                return BackupItem(
                    file: url,
                    name: url.lastPathComponent,
                    timestamp: Int64((modified.timeIntervalSince1970 * 1000).rounded()))
            }
            .sorted { $0.timestamp > $1.timestamp }
    }
}
