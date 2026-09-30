import Foundation

/// Keeps the device's favorites across launches (ADR 0021).
///
/// A protocol for the same reason `AppSettingsStoring` is one: the view model must be testable without
/// the device's real defaults.
protocol FavoritesStoring {
    func load() -> [Station]
    func save(_ stations: [Station])
}

/// `UserDefaults`-backed, device-local. The list says which stations somebody cares about, so it is
/// kept off the log and cleared when they sign out (`FavoritesViewModel`).
final class UserDefaultsFavoritesStore: FavoritesStoring {
    /// Versioned so a later format can live beside this one.
    private let key = "favorites.v1"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> [Station] {
        guard let data = defaults.data(forKey: key) else { return [] }
        do {
            return try JSONDecoder().decode([Station].self, from: data)
        } catch {
            // Losing a list of favorites is survivable; refusing to start over it is not.
            AppLogger.favorites.warning("Discarding unreadable favorites — \(AppLogger.describe(error))")
            defaults.removeObject(forKey: key)
            return []
        }
    }

    func save(_ stations: [Station]) {
        do {
            defaults.set(try JSONEncoder().encode(stations), forKey: key)
        } catch {
            AppLogger.favorites.error("Could not persist favorites", error: error)
        }
    }
}
