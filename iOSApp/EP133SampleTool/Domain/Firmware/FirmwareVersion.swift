import Foundation

/// Immutable value type representing a firmware version as an ordered list of integer parts.
///
/// Comparison pads the shorter operand with zeros, so 2.5 ([2,5]) is treated as [2,5,0]
/// when compared against 2.0.5 ([2,0,5]), making 2.5 > 2.0.5.
///
/// Mirrors AndroidApp/app/src/main/java/com/ep133/sampletool/domain/firmware/FirmwareVersion.kt.
/// Like the Kotlin data class, `==` is structural on `parts` (so [2,5] != [2,5,0]) while
/// `compare(to:)` zero-pads and reports them equal — the same equals()-vs-compareTo() contract
/// as the Android side.
struct FirmwareVersion: Hashable, Comparable, CustomStringConvertible {
    let parts: [Int]

    /// Kotlin `compareTo`: element-wise comparison with zero-padding of the shorter operand.
    /// Compares (never subtracts), so huge components cannot overflow.
    func compare(to other: FirmwareVersion) -> Int {
        let maxLen = max(parts.count, other.parts.count)
        for i in 0..<maxLen {
            let a = i < parts.count ? parts[i] : 0
            let b = i < other.parts.count ? other.parts[i] : 0
            if a != b { return a < b ? -1 : 1 }
        }
        return 0
    }

    static func < (lhs: FirmwareVersion, rhs: FirmwareVersion) -> Bool {
        lhs.compare(to: rhs) < 0
    }

    var description: String { parts.map(String.init).joined(separator: ".") }

    /// Parses a raw version string like "2.5" or "2.0.5" into a FirmwareVersion.
    /// Returns nil on nil, blank, or any non-numeric part.
    static func parse(_ raw: String?) -> FirmwareVersion? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        // components(separatedBy:) keeps empty tokens exactly like Kotlin's split, so
        // "2.5." → ["2","5",""] and "2..5" → ["2","","5"]; the empty part fails Int()
        // and rejects the whole string.
        var intParts: [Int] = []
        for part in trimmed.components(separatedBy: ".") {
            guard let value = Int(part) else { return nil }
            intParts.append(value)
        }
        return FirmwareVersion(parts: intParts)
    }
}
