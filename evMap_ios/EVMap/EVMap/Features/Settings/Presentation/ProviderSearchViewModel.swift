import Combine
import Foundation

/// Drives the provider picker's list: the largest networks by default, whatever matches the search
/// field while the user types.
///
/// Goes through `ChargingStationRepository` like every other backend read, so the picker is
/// testable against a stub and survives the planned move to GraphQL untouched.
@MainActor
final class ProviderSearchViewModel: ObservableObject {
    @Published private(set) var providers: [ChargingProvider] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    /// Long enough to make a typed word one request instead of one per character, short enough not
    /// to feel like a delay. Matches the address field's pause for the same reason.
    private static let typingPause = Duration.milliseconds(300)
    /// Rows per request. More than a user scrolls through before narrowing the search, and far
    /// under the endpoint's own ceiling.
    static let limit = 60

    private let repository: any ChargingStationRepository
    private var searchTask: Task<Void, Never>?
    /// The query the current `providers` answer, so the screen can tell "no networks match this"
    /// apart from "nothing has been searched yet".
    private(set) var loadedQuery = ""

    init(repository: any ChargingStationRepository) {
        self.repository = repository
    }

    /// First load, on appear. Not debounced: there is nobody typing yet.
    func load() {
        search(for: "", debounced: false)
    }

    func queryChanged(to query: String) {
        search(for: query, debounced: true)
    }

    private func search(for query: String, debounced: Bool) {
        // A user typing faster than the network answers would otherwise get whichever response
        // happens to arrive last, which is not necessarily the one for what is in the field.
        searchTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        searchTask = Task { [weak self] in
            if debounced { try? await Task.sleep(for: Self.typingPause) }
            guard !Task.isCancelled, let self else { return }
            isLoading = true
            defer { isLoading = false }
            do {
                let found = try await AppLogger.settings.measure("Provider lookup") {
                    try await self.repository.providers(matching: trimmed, limit: Self.limit)
                }
                guard !Task.isCancelled else { return }
                providers = found
                loadedQuery = trimmed
            } catch is CancellationError {
                AppLogger.settings.debug("Provider lookup superseded by a newer one")
            } catch {
                guard !Task.isCancelled else { return }
                errorMessage = error.localizedDescription
            }
        }
    }
}
