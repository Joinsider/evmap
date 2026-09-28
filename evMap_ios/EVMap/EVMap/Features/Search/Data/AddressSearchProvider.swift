import Foundation
import MapKit

/// Turns typed text into places, behind a protocol for the same reason
/// `ChargingStationRepository` is one: the presentation layer must not know which framework
/// answers, and a test must be able to answer without a network or a MapKit daemon.
@MainActor
protocol AddressSearchProviding: AnyObject {
    /// Completions for a partial query, biased towards `region` but not restricted to it —
    /// searching for a city while looking at another one has to work.
    func suggestions(matching query: String, near region: MKCoordinateRegion) async -> [AddressSuggestion]

    /// Locates a suggestion. `region` only biases the lookup; the result may lie outside it.
    func resolve(_ suggestion: AddressSuggestion, near region: MKCoordinateRegion) async throws -> SearchedPlace

    /// Drops any in-flight completion request, e.g. when the search field closes.
    func cancel()
}

enum AddressSearchError: LocalizedError {
    /// The query is well-formed but names nowhere MapKit knows.
    case notFound

    var errorDescription: String? {
        switch self {
        case .notFound: String(localized: "search.error.notFound")
        }
    }
}

/// `MKLocalSearchCompleter` for the suggestions, `MKLocalSearch` for the coordinate.
///
/// The completer is a long-lived, delegate-driven object that answers whenever it feels like it,
/// which is awkward to await. It is wrapped here so callers see a plain `async` function: each
/// call parks a continuation that the delegate resumes, and a newer query resumes the older one
/// empty rather than leaving it waiting for a callback that now belongs to someone else.
@MainActor
final class MapKitAddressSearchProvider: NSObject, AddressSearchProviding {
    private let completer = MKLocalSearchCompleter()
    private var pending: CheckedContinuation<[AddressSuggestion], Never>?
    /// Handles for the suggestions currently on offer. `MKLocalSearch.Request(completion:)` resolves
    /// exactly what MapKit meant by a row, where a text search only guesses at it — but the handle
    /// exists solely for live results, so recents fall back to the text path.
    private var completions: [String: MKLocalSearchCompletion] = [:]

    override init() {
        super.init()
        // Queries ("pizza") are excluded: this field looks for an address to move the map to, and a
        // category search would produce rows that resolve to no single place.
        completer.resultTypes = [.address, .pointOfInterest]
        completer.delegate = self
    }

    func suggestions(matching query: String, near region: MKCoordinateRegion) async -> [AddressSuggestion] {
        finishPending(with: [])
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }

        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                pending = continuation
                completer.region = region
                completer.regionPriority = .default
                completer.queryFragment = trimmed
            }
        } onCancel: {
            Task { @MainActor in self.finishPending(with: []) }
        }
    }

    func resolve(_ suggestion: AddressSuggestion, near region: MKCoordinateRegion) async throws -> SearchedPlace {
        let request: MKLocalSearch.Request
        if let completion = completions[suggestion.id] {
            request = MKLocalSearch.Request(completion: completion)
        } else {
            request = MKLocalSearch.Request()
            request.naturalLanguageQuery = suggestion.searchText
        }
        request.region = region
        request.regionPriority = .default

        let response = try await MKLocalSearch(request: request).start()
        guard let item = response.mapItems.first else { throw AddressSearchError.notFound }
        // The suggestion's own title/subtitle are kept rather than the map item's name: they are what
        // the user tapped, and a remembered search should read back as what they picked.
        return SearchedPlace(
            title: suggestion.title.isEmpty ? (item.name ?? suggestion.searchText) : suggestion.title,
            subtitle: suggestion.subtitle,
            coordinate: item.location.coordinate
        )
    }

    func cancel() {
        completer.cancel()
        finishPending(with: [])
    }

    private func finishPending(with suggestions: [AddressSuggestion]) {
        pending?.resume(returning: suggestions)
        pending = nil
    }
}

extension MapKitAddressSearchProvider: MKLocalSearchCompleterDelegate {
    func completerDidUpdateResults(_ completer: MKLocalSearchCompleter) {
        let results = completer.results
        completions = Dictionary(
            results.map { (AddressSuggestion(title: $0.title, subtitle: $0.subtitle).id, $0) },
            // Two rows can share a title/subtitle pair; either handle resolves to the same place.
            uniquingKeysWith: { first, _ in first }
        )
        AppLogger.map.debug("Autocomplete returned \(results.count) suggestions")
        finishPending(with: results.map { AddressSuggestion(title: $0.title, subtitle: $0.subtitle) })
    }

    func completer(_: MKLocalSearchCompleter, didFailWithError error: Error) {
        // Not surfaced to the user: a failing keystroke is followed by the next one, and an alert
        // per character typed offline would be unusable. The empty list is the message.
        AppLogger.map.warning("Autocomplete failed — \(AppLogger.describe(error))")
        finishPending(with: [])
    }
}
