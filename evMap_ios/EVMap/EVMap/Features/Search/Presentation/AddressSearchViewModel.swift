import Combine
import Foundation
import MapKit

/// Drives the map's address field: autocomplete while typing, recents while empty, and the one
/// resolved place the map is currently pointing at.
@MainActor
final class AddressSearchViewModel: ObservableObject {
    /// Bound to the search field. The view reports its changes through `queryChanged(to:)` rather
    /// than the model observing itself, so the one write this class makes — echoing a selection
    /// back into the field — stays distinguishable from something the user typed.
    @Published var query = ""
    @Published private(set) var suggestions: [AddressSuggestion] = []
    @Published private(set) var recents: [SearchedPlace] = []
    /// The place the map is showing a pin for, or `nil` when no search is active.
    @Published private(set) var result: SearchedPlace?
    @Published private(set) var isResolving = false
    @Published var errorMessage: String?

    /// Bias for both autocomplete and resolution — what the user is looking at is the best guess at
    /// which "Hauptstraße" they mean. Kept in sync by the map's camera.
    var searchRegion = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 51.1657, longitude: 10.4515),
        latitudinalMeters: 700_000, longitudinalMeters: 700_000
    )

    /// Typing is faster than MapKit answers, and the completer is rate-limited; a short pause turns
    /// a word into one request instead of one per character.
    private static let typingPause = Duration.milliseconds(250)

    private let provider: any AddressSearchProviding
    private let store: any RecentSearchStoring
    private var suggestionTask: Task<Void, Never>?
    private var resolveTask: Task<Void, Never>?
    /// The text this model wrote into `query` itself. The `onChange` it provokes must not be read as
    /// a new search, or selecting a suggestion would immediately re-open the list underneath it.
    private var echoedQuery: String?

    /// The production collaborators cannot be default arguments: those are evaluated outside the
    /// actor, and `MapKitAddressSearchProvider` is main-actor bound. `nil` means "the real one".
    init(provider: (any AddressSearchProviding)? = nil, store: (any RecentSearchStoring)? = nil) {
        let store = store ?? UserDefaultsRecentSearchStore()
        self.provider = provider ?? MapKitAddressSearchProvider()
        self.store = store
        recents = store.load()
    }

    func queryChanged(to query: String) {
        guard query != echoedQuery else {
            echoedQuery = nil
            return
        }
        echoedQuery = nil
        suggestionTask?.cancel()

        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            // The search field's own clear button is the only clear this screen needs: emptying the
            // text takes the suggestions and the pin with it, so there is no second control to
            // explain and nothing left over that the field no longer describes.
            resolveTask?.cancel()
            suggestions = []
            result = nil
            provider.cancel()
            return
        }

        suggestionTask = Task { [weak self] in
            try? await Task.sleep(for: Self.typingPause)
            guard !Task.isCancelled, let self else { return }
            let found = await provider.suggestions(matching: trimmed, near: searchRegion)
            guard !Task.isCancelled else { return }
            suggestions = found
        }
    }

    /// Locates a suggestion the user tapped and hands the map somewhere to go.
    func select(_ suggestion: AddressSuggestion) {
        suggestionTask?.cancel()
        resolveTask?.cancel()
        resolveTask = Task { [weak self] in
            guard let self else { return }
            isResolving = true
            defer { isResolving = false }
            do {
                let place = try await AppLogger.map.measure("Resolving address suggestion") {
                    try await self.provider.resolve(suggestion, near: self.searchRegion)
                }
                guard !Task.isCancelled else { return }
                show(place)
            } catch is CancellationError {
                AppLogger.map.debug("Address resolution superseded")
            } catch {
                guard !Task.isCancelled else { return }
                errorMessage = error.localizedDescription
            }
        }
    }

    /// Replays a remembered search. No lookup: the coordinate was stored with it.
    func show(_ place: SearchedPlace) {
        result = place
        suggestions = []
        recents = store.remember(place)
        echoedQuery = place.displayName
        query = place.displayName
        provider.cancel()
        // The place itself is somebody's destination, so only the fact of a search is durable.
        AppLogger.map.notice("Map moved to a searched place")
        AppLogger.map.debug("Searched place at \(AppLogger.coordinate(latitude: place.latitude, longitude: place.longitude))")
    }

    /// Submits whatever is typed, for the case where the user hits return instead of picking a row.
    func submit() {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        // The first completion is a better search than the raw fragment when one has arrived.
        select(suggestions.first ?? AddressSuggestion(title: trimmed, subtitle: ""))
    }

    func forget(_ place: SearchedPlace) {
        recents = store.forget(place)
    }

    func clearRecents() {
        store.clear()
        recents = []
    }
}
