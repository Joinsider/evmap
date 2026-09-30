import Foundation
import Testing

@testable import EVMap

/// Favorites (ADR 0021): the list as a value, the device store, and the view model that keeps the
/// device and the account in step.
@Suite("Favorites", .serialized)
@MainActor
struct FavoritesTests {
    private final class MemoryStore: FavoritesStoring {
        var stored: [Station]
        init(_ stored: [Station] = []) { self.stored = stored }
        func load() -> [Station] { stored }
        func save(_ stations: [Station]) { stored = stations }
    }

    private struct Offline: LocalizedError {
        var errorDescription: String? { "offline" }
    }

    private let ada = Fixtures.station(name: "Ada")
    private let grace = Fixtures.station(name: "Grace")
    private let linus = Fixtures.station(name: "Linus")

    private func signedOut(_ repository: StubStationRepository) -> AuthSession {
        UserDefaults.standard.removeObject(forKey: "EVMapAccessToken")
        return AuthSession(repository: repository)
    }

    private func signedIn(_ repository: StubStationRepository) -> AuthSession {
        UserDefaults.standard.set("token", forKey: "EVMapAccessToken")
        return AuthSession(repository: repository)
    }

    private func model(_ repository: StubStationRepository, _ session: AuthSession, store: MemoryStore? = nil) -> FavoritesViewModel {
        FavoritesViewModel(repository: repository, authSession: session, store: store ?? MemoryStore())
    }

    // MARK: The list

    @Test("the list keeps the newest first and each station once")
    func listOrder() {
        var list = FavoriteList([ada, ada, grace])
        #expect(list.stations.map(\.displayName) == ["Ada", "Grace"])

        list.add(linus)
        list.add(ada)
        #expect(list.stations.map(\.displayName) == ["Linus", "Ada", "Grace"])
        #expect(list.contains(grace.id))

        list.remove(ada.id)
        #expect(list.ids == [linus.id, grace.id])
        #expect(!list.isEmpty)
    }

    // MARK: The device store

    @Test("the device store round-trips stations and survives unreadable data")
    func deviceStore() throws {
        let suite = try #require(UserDefaults(suiteName: "favorites-tests-\(UUID().uuidString)"))
        let store = UserDefaultsFavoritesStore(defaults: suite)
        #expect(store.load().isEmpty)

        store.save([ada, grace])
        #expect(store.load() == [ada, grace])

        suite.set(Data("not json".utf8), forKey: "favorites.v1")
        #expect(store.load().isEmpty)
        #expect(suite.data(forKey: "favorites.v1") == nil)
    }

    // MARK: Signed out

    @Test("signed out, a favorite is kept on the device and never sent anywhere")
    func signedOutStaysLocal() async {
        let repository = StubStationRepository()
        let store = MemoryStore()
        let model = model(repository, signedOut(repository), store: store)

        await model.toggle(ada)
        await model.toggle(grace)
        await model.toggle(ada)

        #expect(model.stations.map(\.displayName) == ["Grace"])
        #expect(store.stored == [grace])
        #expect(repository.addedFavorites.isEmpty && repository.removedFavorites.isEmpty)
    }

    @Test("a favorite from an earlier launch is there again")
    func restoresFromTheStore() {
        let repository = StubStationRepository()
        let model = model(repository, signedOut(repository), store: MemoryStore([ada]))

        #expect(model.isFavorite(ada))
        #expect(!model.isFavorite(grace))
        #expect(model.ids == [ada.id])
    }

    @Test("launching signed out does not clear what is on the device")
    func launchKeepsDeviceFavorites() async {
        let repository = StubStationRepository()
        let store = MemoryStore([ada])
        let model = model(repository, signedOut(repository), store: store)

        await model.sessionChanged(to: nil)

        #expect(model.stations == [ada])
        #expect(store.stored == [ada])
    }

    // MARK: Signed in

    @Test("signed in, a change is sent to the account")
    func signedInWritesThrough() async {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }
        let model = model(repository, session)

        await model.toggle(ada)
        #expect(repository.addedFavorites == [ada.id])

        await model.toggle(ada)
        #expect(repository.removedFavorites == [ada.id])
        #expect(model.stations.isEmpty)
    }

    @Test("a change the backend refuses is undone and reported")
    func refusedChangeIsUndone() async {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }
        let store = MemoryStore([ada])
        let model = model(repository, session, store: store)
        repository.favoriteWrite = .failure(Offline())

        await model.toggle(grace)
        #expect(model.stations == [ada])
        #expect(store.stored == [ada])
        #expect(model.errorMessage == "offline")

        model.errorMessage = nil
        await model.toggle(ada)
        #expect(model.stations == [ada])
        #expect(model.errorMessage == "offline")
    }

    @Test("a token the backend no longer accepts signs the session out")
    func staleTokenSignsOut() async {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }
        let model = model(repository, session)
        repository.favoriteWrite = .failure(APIError.unauthenticated)

        await model.toggle(ada)

        #expect(session.accessToken == nil)
        #expect(model.stations.isEmpty)
    }

    // MARK: Signing in and out

    @Test("signing in sends the device's favorites and takes the account's union back")
    func signInMerges() async {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }
        let store = MemoryStore([ada, grace])
        let model = model(repository, session, store: store)
        repository.accountFavorites = [linus, ada, grace]

        await model.sessionChanged(to: "token")

        #expect(repository.mergedFavoriteIDs == [[ada.id, grace.id]])
        #expect(model.stations == [linus, ada, grace])
        #expect(store.stored == [linus, ada, grace])
    }

    @Test("a failed merge keeps the device's list and says so")
    func failedMergeKeepsDeviceList() async {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }
        let model = model(repository, session, store: MemoryStore([ada]))
        repository.mergeFailure = Offline()

        await model.sessionChanged(to: "token")

        #expect(model.stations == [ada])
        #expect(model.errorMessage == "offline")
    }

    @Test("signing out empties the device's favorites, and the account keeps its own")
    func signOutClears() async {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }
        let store = MemoryStore()
        let model = model(repository, session, store: store)
        repository.accountFavorites = [ada]

        await model.sessionChanged(to: "token")
        #expect(model.stations == [ada])

        await model.sessionChanged(to: nil)
        #expect(model.stations.isEmpty)
        #expect(store.stored.isEmpty)
        #expect(repository.removedFavorites.isEmpty)

        // Favorites added signed out again are the device's own, and the next sign-in merges them.
        await model.toggle(grace)
        #expect(model.stations == [grace])
        await model.sessionChanged(to: nil)
        #expect(model.stations == [grace])
    }
}
