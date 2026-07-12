import Foundation

/// Mirrors AndroidApp/app/src/main/java/com/ep133/sampletool/domain/pack/SamplePack.kt.
/// Android loads packs from a SAF tree URI; iOS loads from a folder URL picked via the
/// document picker. Callers own the security-scoped access lifetime around load(from:).

/// One audio one-shot in a pack.
struct KitSample: Equatable {
    let name: String     // display name (filename without extension)
    let url: URL
    let category: String
    let meta: String     // small subtitle, e.g. "142 KB"
}

/// A category tab in a pack (a top-level subfolder such as KICKS, SNARES, …).
struct KitCategory: Equatable {
    let id: String
    let samples: [KitSample]
}

/// A loaded sample pack: the folder name + its categories.
struct KitPack: Equatable {
    let name: String
    let categories: [KitCategory]

    var isEmpty: Bool { categories.allSatisfy { $0.samples.isEmpty } }
}

/// Loads a sample pack from a folder URL. Each top-level subfolder becomes a category (its
/// numeric "N. " prefix stripped, uppercased); audio files anywhere under it (recursing one
/// extra level, e.g. HATS/OPEN HATS) become its samples.
enum SamplePackLoader {

    private static let audioExtensions: Set<String> = ["wav", "aif", "aiff", "flac", "mp3", "ogg", "m4a"]

    static func load(from rootURL: URL) async -> KitPack {
        await Task.detached(priority: .userInitiated) { loadSync(from: rootURL) }.value
    }

    static func loadSync(from rootURL: URL) -> KitPack {
        let fm = FileManager.default
        var isDirectory: ObjCBool = false
        guard fm.fileExists(atPath: rootURL.path, isDirectory: &isDirectory), isDirectory.boolValue else {
            return KitPack(name: "(unreadable)", categories: [])
        }
        let packName = rootURL.lastPathComponent

        let categories = children(of: rootURL, in: fm)
            .filter { $0.hasDirectoryPath }
            .sorted { $0.lastPathComponent.lowercased() < $1.lastPathComponent.lowercased() }
            .map { dir -> KitCategory in
                let id = categoryId(dir.lastPathComponent)
                let samples = collectAudio(in: dir, category: id, fm: fm)
                    .sorted { $0.name.lowercased() < $1.name.lowercased() }
                return KitCategory(id: id, samples: samples)
            }
            .filter { !$0.samples.isEmpty }

        return KitPack(name: packName, categories: categories)
    }

    /// Strip a leading "N. " / "N " ordering prefix and uppercase, e.g. "1. KICKS" -> "KICKS".
    private static func categoryId(_ folderName: String) -> String {
        let trimmed = folderName.trimmingCharacters(in: .whitespacesAndNewlines)
        let stripped = trimmed.replacingOccurrences(
            of: #"^\d+\s*[.)-]?\s*"#,
            with: "",
            options: .regularExpression
        ).uppercased()
        return stripped.isEmpty ? folderName.uppercased() : stripped
    }

    /// All audio files directly in `dir` plus one level of nested subfolders.
    private static func collectAudio(in dir: URL, category: String, fm: FileManager) -> [KitSample] {
        var out: [KitSample] = []
        for child in children(of: dir, in: fm) {
            if child.hasDirectoryPath {
                for grand in children(of: child, in: fm) where !grand.hasDirectoryPath && isAudio(grand) {
                    out.append(toSample(grand, category: category, fm: fm))
                }
            } else if isAudio(child) {
                out.append(toSample(child, category: category, fm: fm))
            }
        }
        return out
    }

    private static func children(of url: URL, in fm: FileManager) -> [URL] {
        (try? fm.contentsOfDirectory(
            at: url,
            includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        )) ?? []
    }

    private static func isAudio(_ url: URL) -> Bool {
        audioExtensions.contains(url.pathExtension.lowercased())
    }

    private static func toSample(_ url: URL, category: String, fm: FileManager) -> KitSample {
        let display = url.deletingPathExtension().lastPathComponent
        let bytes = ((try? fm.attributesOfItem(atPath: url.path))?[.size] as? Int64) ?? 0
        let kb = max(bytes / 1024, 0)
        return KitSample(name: display, url: url, category: category, meta: "\(kb) KB")
    }
}
