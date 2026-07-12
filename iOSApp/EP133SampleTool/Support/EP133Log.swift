import os

/// Logging shim over `os.Logger`, replacing the `print("[EP133*] …")` calls carried over from
/// the Android port's `Log.x(TAG, …)`. Categories mirror the old tags (`EP133` → midi,
/// `EP133APP` → app, `EP133BACKUP` → backup); the level is explicit at each call site.
///
/// Unlike `print()`, these don't write to release stdout unconditionally: they're routed through
/// the unified logging system (filterable by subsystem/category/level in Console, `debug` elided
/// from release by default). Messages are logged `.public` — this is local hardware/protocol
/// diagnostics, not PII.
enum EP133Log {
    enum Category {
        case midi
        case app
        case backup
    }

    private static let subsystem = "com.ep133.sampletool"
    private static let midiLogger = Logger(subsystem: subsystem, category: "midi")
    private static let appLogger = Logger(subsystem: subsystem, category: "app")
    private static let backupLogger = Logger(subsystem: subsystem, category: "backup")

    private static func logger(for category: Category) -> Logger {
        switch category {
        case .midi: return midiLogger
        case .app: return appLogger
        case .backup: return backupLogger
        }
    }

    static func error(_ category: Category, _ message: String) {
        logger(for: category).error("\(message, privacy: .public)")
    }

    static func warning(_ category: Category, _ message: String) {
        logger(for: category).warning("\(message, privacy: .public)")
    }

    static func debug(_ category: Category, _ message: String) {
        logger(for: category).debug("\(message, privacy: .public)")
    }
}
