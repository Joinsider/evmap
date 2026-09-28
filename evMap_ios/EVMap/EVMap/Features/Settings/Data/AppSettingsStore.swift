import Foundation

/// Keeps the user's settings across app launches.
///
/// A protocol for the same reason `ChargingStationRepository` is one: the view model must be
/// testable without touching the device's real defaults, and where settings live is not something
/// the UI should know. A future iCloud-synced implementation slots in here.
protocol AppSettingsStoring {
    func load() -> AppSettings
    func save(_ settings: AppSettings)
    /// Forgets everything, putting the app back into the state a fresh install is in.
    func reset()
}

/// `UserDefaults`-backed, device-local.
///
/// Nothing stored here is personal data under ADR 0002's rules — a connector type and a list of
/// charging networks say nothing about where somebody has been — but it is also not worth a
/// backend round trip, and keeping it local means the map has its filter before the first request
/// rather than after one. JSON under a single key rather than a key per field, so the whole value
/// is written and cleared atomically.
final class UserDefaultsAppSettingsStore: AppSettingsStoring {
    /// Versioned so that a future format change can be introduced beside the old key instead of
    /// having to be readable by both versions at once.
    private let key = "settings.v1"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> AppSettings {
        guard let data = defaults.data(forKey: key) else { return .factoryDefaults }
        do {
            let settings = try JSONDecoder().decode(AppSettings.self, from: data)
            AppLogger.settings.info("Loaded settings — \(settings.stationFilter.logDescription)")
            return settings
        } catch {
            // `AppSettings.init(from:)` already tolerates missing fields and unknown values, so
            // reaching this means the payload is genuinely unreadable. Losing the settings is
            // survivable; refusing to start the app over them is not.
            AppLogger.settings.warning("Discarding unreadable settings — \(AppLogger.describe(error))")
            defaults.removeObject(forKey: key)
            return .factoryDefaults
        }
    }

    func save(_ settings: AppSettings) {
        do {
            defaults.set(try JSONEncoder().encode(settings), forKey: key)
        } catch {
            AppLogger.settings.error("Could not persist settings", error: error)
        }
    }

    func reset() {
        defaults.removeObject(forKey: key)
        AppLogger.settings.notice("Settings reset to defaults")
    }
}
