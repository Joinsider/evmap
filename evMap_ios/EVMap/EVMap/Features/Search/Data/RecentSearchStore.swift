import Foundation

/// Remembers the places a user searched for, most recent first.
protocol RecentSearchStoring {
    func load() -> [SearchedPlace]
    /// Records `place` and returns the updated list.
    @discardableResult
    func remember(_ place: SearchedPlace) -> [SearchedPlace]
    /// Forgets a single entry, e.g. when the user swipes it away.
    @discardableResult
    func forget(_ place: SearchedPlace) -> [SearchedPlace]
    func clear()
}

/// `UserDefaults`-backed history, on the device only.
///
/// Searched addresses are as personal as a location fix — they are somebody's home, workplace, or
/// destination — so they stay local: never sent to the backend, never written to the log, and
/// erased with the app. `UserDefaults` rather than the Keychain because losing the list is
/// harmless, and a short list of strings does not warrant a database.
final class UserDefaultsRecentSearchStore: RecentSearchStoring {
    /// Long enough to cover the handful of places somebody actually returns to, short enough that
    /// the list stays scannable under a search field without scrolling.
    static let maximumCount = 8

    private let key = "search.recentPlaces"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> [SearchedPlace] {
        guard let data = defaults.data(forKey: key) else { return [] }
        do {
            return try JSONDecoder().decode([SearchedPlace].self, from: data)
        } catch {
            // A shape change between app versions must not take the search field down with it.
            AppLogger.map.warning("Discarding unreadable recent searches — \(AppLogger.describe(error))")
            defaults.removeObject(forKey: key)
            return []
        }
    }

    @discardableResult
    func remember(_ place: SearchedPlace) -> [SearchedPlace] {
        // Re-searching a known place moves it to the front rather than duplicating it, and refreshes
        // its coordinate in case MapKit now locates it better.
        var places = load().filter { $0.id != place.id }
        places.insert(place, at: 0)
        return save(Array(places.prefix(Self.maximumCount)))
    }

    @discardableResult
    func forget(_ place: SearchedPlace) -> [SearchedPlace] {
        save(load().filter { $0.id != place.id })
    }

    func clear() {
        defaults.removeObject(forKey: key)
        AppLogger.map.info("Recent searches cleared")
    }

    @discardableResult
    private func save(_ places: [SearchedPlace]) -> [SearchedPlace] {
        do {
            defaults.set(try JSONEncoder().encode(places), forKey: key)
        } catch {
            // The count is safe to log; the addresses themselves are not.
            AppLogger.map.error("Could not persist \(places.count) recent searches", error: error)
        }
        return places
    }
}
