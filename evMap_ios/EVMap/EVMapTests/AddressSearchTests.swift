import CoreLocation
import Foundation
import MapKit
import Testing

@testable import EVMap

// MARK: - Doubles

@MainActor
private final class StubAddressSearchProvider: AddressSearchProviding {
    var suggestionsToReturn: [AddressSuggestion] = []
    var placeToReturn: SearchedPlace?
    var resolveError: Error?
    private(set) var lastQuery: String?
    private(set) var cancelCount = 0

    func suggestions(matching query: String, near region: MKCoordinateRegion) async -> [AddressSuggestion] {
        lastQuery = query
        return suggestionsToReturn
    }

    func resolve(_ suggestion: AddressSuggestion, near region: MKCoordinateRegion) async throws -> SearchedPlace {
        if let resolveError { throw resolveError }
        return placeToReturn ?? place(suggestion.title, subtitle: suggestion.subtitle)
    }

    func cancel() { cancelCount += 1 }
}

private final class InMemoryRecentSearchStore: RecentSearchStoring {
    private(set) var places: [SearchedPlace]

    init(_ places: [SearchedPlace] = []) { self.places = places }

    func load() -> [SearchedPlace] { places }

    @discardableResult
    func remember(_ place: SearchedPlace) -> [SearchedPlace] {
        places = [place] + places.filter { $0.id != place.id }
        return places
    }

    @discardableResult
    func forget(_ place: SearchedPlace) -> [SearchedPlace] {
        places = places.filter { $0.id != place.id }
        return places
    }

    func clear() { places = [] }
}

private func place(_ title: String, subtitle: String = "Berlin, Deutschland", latitude: Double = 52.52, longitude: Double = 13.405) -> SearchedPlace {
    SearchedPlace(title: title, subtitle: subtitle, coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
}

/// Polls `condition` because the view model does its work in detached tasks; a fixed sleep would be
/// either flaky or slow.
private func waitUntil(_ description: String, _ condition: @MainActor () -> Bool) async throws {
    for _ in 0..<200 {
        if await MainActor.run(body: condition) { return }
        try await Task.sleep(for: .milliseconds(10))
    }
    Issue.record("Timed out waiting for \(description)")
}

// MARK: - Tests

@Suite("Recent search history")
struct RecentSearchStoreTests {
    /// Each test gets its own defaults domain so the suite neither reads nor leaves real user data.
    private func makeStore() -> (UserDefaultsRecentSearchStore, UserDefaults, String) {
        let name = "de.joinside.EVMap.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        return (UserDefaultsRecentSearchStore(defaults: defaults), defaults, name)
    }

    @Test("A fresh install has no history")
    func startsEmpty() {
        let (store, defaults, name) = makeStore()
        defer { defaults.removePersistentDomain(forName: name) }
        #expect(store.load().isEmpty)
    }

    @Test("The most recent search comes first")
    func mostRecentFirst() {
        let (store, defaults, name) = makeStore()
        defer { defaults.removePersistentDomain(forName: name) }
        store.remember(place("Alexanderplatz"))
        store.remember(place("Hauptbahnhof"))
        #expect(store.load().map(\.title) == ["Hauptbahnhof", "Alexanderplatz"])
    }

    @Test("Searching the same place again moves it up instead of duplicating it")
    func repeatedSearchIsDeduplicated() {
        let (store, defaults, name) = makeStore()
        defer { defaults.removePersistentDomain(forName: name) }
        store.remember(place("Alexanderplatz"))
        store.remember(place("Hauptbahnhof"))
        store.remember(place("Alexanderplatz", latitude: 52.521, longitude: 13.413))
        let loaded = store.load()
        #expect(loaded.map(\.title) == ["Alexanderplatz", "Hauptbahnhof"])
        // The refreshed coordinate wins — MapKit may have located the place better since.
        #expect(loaded.first?.latitude == 52.521)
    }

    @Test("History stops growing at the cap, dropping the oldest entry")
    func historyIsCapped() {
        let (store, defaults, name) = makeStore()
        defer { defaults.removePersistentDomain(forName: name) }
        for index in 0...UserDefaultsRecentSearchStore.maximumCount {
            store.remember(place("Place \(index)"))
        }
        let loaded = store.load()
        #expect(loaded.count == UserDefaultsRecentSearchStore.maximumCount)
        #expect(!loaded.contains { $0.title == "Place 0" })
    }

    @Test("A single entry can be forgotten without touching the rest")
    func forgetsOneEntry() {
        let (store, defaults, name) = makeStore()
        defer { defaults.removePersistentDomain(forName: name) }
        store.remember(place("Alexanderplatz"))
        store.remember(place("Hauptbahnhof"))
        #expect(store.forget(place("Hauptbahnhof")).map(\.title) == ["Alexanderplatz"])
    }

    @Test("Clearing the history leaves nothing behind")
    func clearsEverything() {
        let (store, defaults, name) = makeStore()
        defer { defaults.removePersistentDomain(forName: name) }
        store.remember(place("Alexanderplatz"))
        store.clear()
        #expect(store.load().isEmpty)
    }

    @Test("Unreadable stored data is discarded rather than crashing the search field")
    func survivesCorruptedData() {
        let (store, defaults, name) = makeStore()
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set(Data("not json".utf8), forKey: "search.recentPlaces")
        #expect(store.load().isEmpty)
        // And the field works again afterwards.
        #expect(store.remember(place("Alexanderplatz")).count == 1)
    }
}

@Suite("Address search")
@MainActor
struct AddressSearchViewModelTests {
    private func makeViewModel(
        recents: [SearchedPlace] = []
    ) -> (AddressSearchViewModel, StubAddressSearchProvider, InMemoryRecentSearchStore) {
        let provider = StubAddressSearchProvider()
        let store = InMemoryRecentSearchStore(recents)
        return (AddressSearchViewModel(provider: provider, store: store), provider, store)
    }

    @Test("The history is offered as soon as the model is built")
    func recentsAreLoadedUpFront() {
        let (viewModel, _, _) = makeViewModel(recents: [place("Alexanderplatz")])
        #expect(viewModel.recents.map(\.title) == ["Alexanderplatz"])
    }

    @Test("Typing produces suggestions after the pause")
    func typingProducesSuggestions() async throws {
        let (viewModel, provider, _) = makeViewModel()
        provider.suggestionsToReturn = [AddressSuggestion(title: "Alexanderplatz", subtitle: "Berlin")]
        viewModel.queryChanged(to: " Alex ")
        try await waitUntil("suggestions") { !viewModel.suggestions.isEmpty }
        #expect(viewModel.suggestions.map(\.title) == ["Alexanderplatz"])
        // Trimmed before it leaves the app: the surrounding spaces are not part of the address.
        #expect(provider.lastQuery == "Alex")
    }

    @Test("Emptying the field drops the suggestions, so the history shows instead")
    func emptyQueryDropsSuggestions() async throws {
        let (viewModel, provider, _) = makeViewModel()
        provider.suggestionsToReturn = [AddressSuggestion(title: "Alexanderplatz", subtitle: "Berlin")]
        viewModel.queryChanged(to: "Alex")
        try await waitUntil("suggestions") { !viewModel.suggestions.isEmpty }
        viewModel.queryChanged(to: "")
        #expect(viewModel.suggestions.isEmpty)
    }

    @Test("Picking a suggestion places the pin and remembers the search")
    func selectionResolvesAndRemembers() async throws {
        let (viewModel, provider, store) = makeViewModel()
        provider.placeToReturn = place("Alexanderplatz")
        viewModel.select(AddressSuggestion(title: "Alexanderplatz", subtitle: "Berlin, Deutschland"))
        try await waitUntil("a resolved place") { viewModel.result != nil }
        #expect(viewModel.result?.coordinate.latitude == 52.52)
        #expect(viewModel.query == "Alexanderplatz")
        #expect(store.places.map(\.title) == ["Alexanderplatz"])
    }

    @Test("Echoing the selection into the field does not reopen the suggestion list")
    func selectionDoesNotRetrigger() async throws {
        let (viewModel, provider, _) = makeViewModel()
        provider.placeToReturn = place("Alexanderplatz")
        viewModel.select(AddressSuggestion(title: "Alexanderplatz", subtitle: "Berlin, Deutschland"))
        try await waitUntil("a resolved place") { viewModel.result != nil }

        provider.suggestionsToReturn = [AddressSuggestion(title: "Alexanderplatz", subtitle: "Berlin")]
        // What SwiftUI reports back after the model wrote the field itself.
        viewModel.queryChanged(to: viewModel.query)
        try await Task.sleep(for: .milliseconds(400))
        #expect(viewModel.suggestions.isEmpty)
    }

    @Test("A keystroke after a selection searches again")
    func typingAfterSelectionSearchesAgain() async throws {
        let (viewModel, provider, _) = makeViewModel()
        provider.placeToReturn = place("Alexanderplatz")
        viewModel.select(AddressSuggestion(title: "Alexanderplatz", subtitle: "Berlin, Deutschland"))
        try await waitUntil("a resolved place") { viewModel.result != nil }

        provider.suggestionsToReturn = [AddressSuggestion(title: "Hauptbahnhof", subtitle: "Berlin")]
        viewModel.queryChanged(to: "Haupt")
        try await waitUntil("new suggestions") { !viewModel.suggestions.isEmpty }
        #expect(viewModel.suggestions.map(\.title) == ["Hauptbahnhof"])
    }

    @Test("A remembered place is shown without asking MapKit again")
    func recentIsReplayedWithoutLookup() {
        let (viewModel, provider, _) = makeViewModel()
        viewModel.show(place("Alexanderplatz"))
        #expect(viewModel.result?.title == "Alexanderplatz")
        #expect(provider.lastQuery == nil)
    }

    @Test("A failed lookup surfaces a message and leaves the map where it was")
    func failedLookupReportsAnError() async throws {
        let (viewModel, provider, store) = makeViewModel()
        provider.resolveError = AddressSearchError.notFound
        viewModel.select(AddressSuggestion(title: "Nirgendwo", subtitle: ""))
        try await waitUntil("an error message") { viewModel.errorMessage != nil }
        #expect(viewModel.result == nil)
        #expect(store.places.isEmpty)
    }

    @Test("Emptying the field with its own clear button takes the pin with it")
    func clearingTheFieldRemovesThePin() async throws {
        let (viewModel, provider, _) = makeViewModel()
        provider.placeToReturn = place("Alexanderplatz")
        viewModel.select(AddressSuggestion(title: "Alexanderplatz", subtitle: "Berlin, Deutschland"))
        try await waitUntil("a resolved place") { viewModel.result != nil }

        // What the search field's X reports back.
        viewModel.query = ""
        viewModel.queryChanged(to: "")
        #expect(viewModel.result == nil)
        #expect(viewModel.suggestions.isEmpty)
        // Clearing the field is not the same as forgetting where the user has been.
        #expect(viewModel.recents.map(\.title) == ["Alexanderplatz"])
    }

    @Test("Deleting back to nothing while typing also drops the pin")
    func erasingTypedTextRemovesThePin() async throws {
        let (viewModel, provider, _) = makeViewModel()
        provider.placeToReturn = place("Alexanderplatz")
        viewModel.select(AddressSuggestion(title: "Alexanderplatz", subtitle: "Berlin, Deutschland"))
        try await waitUntil("a resolved place") { viewModel.result != nil }

        viewModel.queryChanged(to: "Alexanderplat")
        #expect(viewModel.result != nil)
        viewModel.queryChanged(to: "   ")
        #expect(viewModel.result == nil)
    }

    @Test("Submitting takes the best suggestion over the raw fragment")
    func submitPrefersTheTopSuggestion() async throws {
        let (viewModel, provider, _) = makeViewModel()
        provider.suggestionsToReturn = [AddressSuggestion(title: "Alexanderplatz", subtitle: "Berlin, Deutschland")]
        viewModel.query = "Alex"
        viewModel.queryChanged(to: viewModel.query)
        try await waitUntil("suggestions") { !viewModel.suggestions.isEmpty }

        viewModel.submit()
        try await waitUntil("a resolved place") { viewModel.result != nil }
        #expect(viewModel.result?.title == "Alexanderplatz")
    }

    @Test("Submitting an empty field does nothing")
    func submitIgnoresEmptyQuery() async throws {
        let (viewModel, _, _) = makeViewModel()
        viewModel.query = "   "
        viewModel.submit()
        try await Task.sleep(for: .milliseconds(100))
        #expect(viewModel.result == nil)
    }
}

@Suite("Searched places")
struct SearchedPlaceTests {
    @Test("A place is identified by what it says, so the same address is one entry")
    func identityIsTitleAndSubtitle() {
        #expect(place("Alexanderplatz").id == place("Alexanderplatz", latitude: 0, longitude: 0).id)
        #expect(place("Alexanderplatz").id != place("Hauptbahnhof").id)
    }

    @Test("A place survives a round trip through the history file")
    func roundTripsThroughJSON() throws {
        let original = place("Alexanderplatz")
        let decoded = try JSONDecoder().decode(SearchedPlace.self, from: JSONEncoder().encode(original))
        #expect(decoded == original)
    }

    @Test("A suggestion with no subtitle still searches for something")
    func bareSuggestionHasSearchText() {
        #expect(AddressSuggestion(title: "Berlin", subtitle: "").searchText == "Berlin")
        #expect(AddressSuggestion(title: "Alexanderplatz", subtitle: "Berlin").searchText == "Alexanderplatz, Berlin")
    }
}
