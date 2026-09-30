import Foundation
import os

extension Bundle {
    /// `1.2 (34)`, for the launch banner.
    var appVersion: String {
        let short = infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let build = infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (\(build))"
    }
}

/// Logical area a log line belongs to.
///
/// Each case maps to an `os.Logger` category, so the Xcode console and
/// Console.app can filter on a single subsystem/category pair
/// (`category:network`) instead of grepping free text.
enum LogCategory: String, CaseIterable, Sendable {
    case app
    case network
    case map
    case location
    case stations
    case auth
    case comments
    case settings
    case favorites

    /// Prefix shown in the console so a line's area is readable at a glance.
    fileprivate var label: String {
        switch self {
        case .app: "app"
        case .network: "net"
        case .map: "map"
        case .location: "loc"
        case .stations: "stations"
        case .auth: "auth"
        case .comments: "comments"
        case .settings: "settings"
        case .favorites: "fav"
        }
    }
}

/// Severity of a log line, mirroring the levels `os.Logger` exposes.
///
/// The level is also this app's data-retention control, because the unified log
/// treats levels differently on disk:
///
/// | Level              | Where it ends up                                      |
/// | ------------------ | ----------------------------------------------------- |
/// | `.debug`           | nowhere, unless someone is actively streaming (Xcode)  |
/// | `.info`            | memory buffer; reaches disk only via a `sysdiagnose`   |
/// | `.notice`+ | persisted to disk for days, always in a `sysdiagnose`  |
///
/// Since a `sysdiagnose` is something a user hands to Apple or to support, that
/// table decides what may be logged where:
///
/// - **Anything personal** — coordinates, token fingerprints, request bodies —
///   goes on `.debug` and nowhere else.
/// - **Operational facts** — endpoint, status, duration, size, error class,
///   counts — may use `.info` and above.
///
/// See `docs/privacy/data-processing.md`.
enum LogLevel: Sendable {
    case debug
    case info
    case notice
    case warning
    case error
    case fault

    fileprivate var symbol: String {
        switch self {
        case .debug: "🔎"
        case .info: "ℹ️"
        case .notice: "✅"
        case .warning: "⚠️"
        case .error: "❌"
        case .fault: "🔥"
        }
    }

    fileprivate var osLogType: OSLogType {
        switch self {
        case .debug: .debug
        case .info: .info
        case .notice: .default
        case .warning: .default
        case .error: .error
        case .fault: .fault
        }
    }
}

/// The app's single logging entry point.
///
/// Everything goes through `os.Logger` rather than `print`, which gets us
/// levels, per-category filtering, and log persistence that survives a
/// detached run. The message itself is composed here — severity symbol,
/// category, call site — so a console line reads the same everywhere:
///
/// ```
/// ❌ [net] APIClient.send():48 › GET /api/v1/stations failed after 812 ms — offline
/// ```
///
/// Call sites use the per-category statics (`AppLogger.network.info(…)`)
/// rather than constructing a logger.
struct AppLogger: Sendable {
    static let app = AppLogger(category: .app)
    static let network = AppLogger(category: .network)
    static let map = AppLogger(category: .map)
    static let location = AppLogger(category: .location)
    static let stations = AppLogger(category: .stations)
    static let auth = AppLogger(category: .auth)
    static let comments = AppLogger(category: .comments)
    static let settings = AppLogger(category: .settings)
    static let favorites = AppLogger(category: .favorites)

    private static let subsystem = Bundle.main.bundleIdentifier ?? "de.joinside.EVMap"

    private let category: LogCategory
    private let logger: Logger

    init(category: LogCategory) {
        self.category = category
        self.logger = Logger(subsystem: Self.subsystem, category: category.rawValue)
    }

    // MARK: - Levels

    func debug(_ message: @autoclosure () -> String, file: String = #fileID, function: String = #function, line: UInt = #line) {
        log(.debug, message(), file: file, function: function, line: line)
    }

    func info(_ message: @autoclosure () -> String, file: String = #fileID, function: String = #function, line: UInt = #line) {
        log(.info, message(), file: file, function: function, line: line)
    }

    func notice(_ message: @autoclosure () -> String, file: String = #fileID, function: String = #function, line: UInt = #line) {
        log(.notice, message(), file: file, function: function, line: line)
    }

    func warning(_ message: @autoclosure () -> String, file: String = #fileID, function: String = #function, line: UInt = #line) {
        log(.warning, message(), file: file, function: function, line: line)
    }

    /// Logs an error with a human-readable explanation of `error`.
    ///
    /// Prefer this over interpolating the error yourself: `describe(_:)` turns
    /// `DecodingError` and `URLError` into something that names the actual
    /// problem, which their default descriptions do not.
    func error(_ message: @autoclosure () -> String, error: Error? = nil, file: String = #fileID, function: String = #function, line: UInt = #line) {
        let suffix = error.map { " — \(Self.describe($0))" } ?? ""
        log(.error, message() + suffix, file: file, function: function, line: line)
    }

    func fault(_ message: @autoclosure () -> String, file: String = #fileID, function: String = #function, line: UInt = #line) {
        log(.fault, message(), file: file, function: function, line: line)
    }

    // MARK: - Timing

    /// Runs `work`, logging its duration and — on a thrown error — what failed.
    ///
    /// Used to give every repository call a start/finish pair without repeating
    /// the same do/catch in each view model.
    @discardableResult
    func measure<T>(
        _ name: String,
        file: String = #fileID,
        function: String = #function,
        line: UInt = #line,
        _ work: () async throws -> T
    ) async rethrows -> T {
        let start = ContinuousClock.now
        debug("\(name) started", file: file, function: function, line: line)
        do {
            let result = try await work()
            info("\(name) finished in \(Self.duration(since: start))", file: file, function: function, line: line)
            return result
        } catch {
            self.error("\(name) failed after \(Self.duration(since: start))", error: error, file: file, function: function, line: line)
            throw error
        }
    }

    // MARK: - Formatting helpers

    /// Elapsed time since `start`, formatted for a log line (`142 ms`, `1.24 s`).
    static func duration(since start: ContinuousClock.Instant) -> String {
        let components = (ContinuousClock.now - start).components
        let seconds = Double(components.seconds) + Double(components.attoseconds) / 1e18
        return seconds >= 1 ? String(format: "%.2f s", seconds) : String(format: "%.0f ms", seconds * 1000)
    }

    /// Byte count formatted for a log line (`842 B`, `8.4 kB`).
    static func size(_ byteCount: Int) -> String {
        byteCount < 1024 ? "\(byteCount) B" : String(format: "%.1f kB", Double(byteCount) / 1024)
    }

    /// Shortens a secret to a recognizable but non-reusable fingerprint.
    ///
    /// Access tokens must never reach the log in full — this keeps enough to
    /// correlate "same token as before?" while leaving nothing usable behind.
    static func redact(token: String?) -> String {
        guard let token, !token.isEmpty else { return "none" }
        return "\(token.prefix(4))…\(token.suffix(4)) (\(token.count) chars)"
    }

    /// Coordinates rounded to ~1 km.
    ///
    /// Rounding alone does not anonymize a position — a sequence of rounded
    /// fixes still reconstructs a movement profile — so callers must additionally
    /// keep this on `.debug`, which the unified log does not persist.
    static func coordinate(latitude: Double, longitude: Double) -> String {
        String(format: "%.2f, %.2f", latitude, longitude)
    }

    /// Turns an error into a line that names the actual failure.
    ///
    /// `DecodingError.localizedDescription` is famously useless ("The data
    /// couldn't be read"), and `URLError` hides its code — both are exactly the
    /// errors worth reading in a console, so they get unpacked here.
    static func describe(_ error: Error) -> String {
        switch error {
        case let error as DecodingError:
            switch error {
            case let .keyNotFound(key, context):
                "decoding: missing key '\(key.stringValue)' at \(path(context))"
            case let .typeMismatch(type, context):
                "decoding: expected \(type) at \(path(context)) — \(context.debugDescription)"
            case let .valueNotFound(type, context):
                "decoding: null value for \(type) at \(path(context))"
            case let .dataCorrupted(context):
                "decoding: corrupted data at \(path(context)) — \(context.debugDescription)"
            @unknown default:
                "decoding: \(error)"
            }
        case let error as URLError:
            "network: \(error.localizedDescription) (URLError \(error.errorCode))"
        case let error as APIError:
            "api: \(error.errorDescription ?? "\(error)")"
        case let error as LocalizedError:
            error.errorDescription ?? "\(error)"
        default:
            "\(type(of: error)): \(error.localizedDescription)"
        }
    }

    private static func path(_ context: DecodingError.Context) -> String {
        let path = context.codingPath.map(\.stringValue).joined(separator: ".")
        return path.isEmpty ? "<root>" : path
    }

    // MARK: - Emission

    private func log(_ level: LogLevel, _ message: String, file: String, function: String, line: UInt) {
        let location = "\(Self.fileName(file)).\(Self.functionName(function)):\(line)"
        // Composed here and logged as `.public`: every value that reaches this
        // point was already redacted or rounded by the call site, and an
        // all-`<private>` console is worse than no console.
        logger.log(
            level: level.osLogType,
            "\(level.symbol, privacy: .public) [\(self.category.label, privacy: .public)] \(location, privacy: .public) › \(message, privacy: .public)"
        )
    }

    /// `#fileID` is `Module/Path/File.swift`; only the file name is useful here.
    private static func fileName(_ file: String) -> String {
        (file as NSString).lastPathComponent.replacingOccurrences(of: ".swift", with: "")
    }

    /// Drops the argument list from `#function` (`load(accessToken:)` → `load`).
    private static func functionName(_ function: String) -> String {
        String(function.prefix { $0 != "(" })
    }
}
