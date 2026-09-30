import Combine
import Foundation

/// The favorites (ADR 0021): on the device while signed out, synchronized with the account once signed in.
///
/// Signing in merges both lists (nothing is lost on either side); signing out empties the device copy,
/// so a signed-out device carries nothing of the account. The account keeps its favorites and the next
/// sign-in brings them back. Everything a person changes is applied at once and undone if the backend
/// refuses it, so the star never lies about what the account holds.
@MainActor
final class FavoritesViewModel: ObservableObject {
    @Published private(set) var list: FavoriteList
    @Published var errorMessage: String?

    private let repository: any ChargingStationRepository
    private let authSession: AuthSession
    private let store: any FavoritesStoring
    /// Whether a sign-in was seen in this run. Clearing on "signed out" must not happen at launch,
    /// where signed out is simply the state of a device that never had an account.
    private var wasSignedIn = false

    init(repository: any ChargingStationRepository, authSession: AuthSession,
         store: (any FavoritesStoring)? = nil) {
        let store = store ?? UserDefaultsFavoritesStore()
        self.repository = repository
        self.authSession = authSession
        self.store = store
        list = FavoriteList(store.load())
    }

    var stations: [Station] { list.stations }
    var ids: Set<UUID> { list.ids }

    func isFavorite(_ station: Station) -> Bool { list.contains(station.id) }

    func toggle(_ station: Station) async {
        if isFavorite(station) { await remove(station) } else { await add(station) }
    }

    func add(_ station: Station) async {
        guard !list.contains(station.id) else { return }
        let before = list
        list.add(station)
        persist()
        guard let token = authSession.accessToken else { return }
        do {
            try await repository.addFavorite(stationID: station.id, accessToken: token)
        } catch {
            list = before
            persist()
            handle(error, while: "adding a favorite")
        }
    }

    func remove(_ station: Station) async {
        guard list.contains(station.id) else { return }
        let before = list
        list.remove(station.id)
        persist()
        guard let token = authSession.accessToken else { return }
        do {
            try await repository.removeFavorite(stationID: station.id, accessToken: token)
        } catch {
            list = before
            persist()
            handle(error, while: "removing a favorite")
        }
    }

    /// Follows the sign-in state: merges on sign-in (also at launch with a restored session), clears on sign-out.
    func sessionChanged(to token: String?) async {
        if let token {
            wasSignedIn = true
            do {
                let merged = try await repository.mergeFavorites(stationIDs: list.stations.map(\.id), accessToken: token)
                list = FavoriteList(merged)
                persist()
                AppLogger.favorites.info("Merged favorites with the account: \(list.stations.count) now")
            } catch {
                // The device keeps its list; the next launch or sign-in merges again.
                handle(error, while: "merging favorites")
            }
        } else if wasSignedIn {
            wasSignedIn = false
            list = FavoriteList()
            persist()
            AppLogger.favorites.info("Cleared the device's favorites after sign-out")
        }
    }

    private func persist() {
        store.save(list.stations)
    }

    private func handle(_ error: Error, while action: String) {
        AppLogger.favorites.error("Favorites failed while \(action)", error: error)
        if case APIError.unauthenticated = error {
            authSession.signOut()
        }
        errorMessage = error.localizedDescription
    }
}
