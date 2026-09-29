import Foundation
import Testing

@testable import EVMap

/// Answers provider lookups and records what it was asked; every other repository method traps,
/// because the provider picker must not be reaching for one.
///
/// Main-actor isolated rather than an `actor`: the app target defaults to `MainActor` isolation, which
/// makes `ChargingStationRepository` a main-actor protocol that an actor cannot conform to.
@MainActor
private final class StubProviderRepository: ChargingStationRepository {
    private var providersToReturn: [ChargingProvider]
    private var error: Error?
    private(set) var queries: [String] = []
    /// Parked until the test releases it, so a second lookup can be started while the first is
    /// still in flight.
    private var gate: CheckedContinuation<Void, Never>?
    private var isGated = false

    init(_ providers: [ChargingProvider] = [], error: Error? = nil) {
        providersToReturn = providers
        self.error = error
    }

    func gateNextCall() { isGated = true }

    func releaseGate() {
        gate?.resume()
        gate = nil
        isGated = false
    }

    func providers(matching query: String, limit _: Int) async throws -> [ChargingProvider] {
        queries.append(query)
        if isGated {
            await withCheckedContinuation { gate = $0 }
        }
        if let error { throw error }
        // A query answers with itself when the test seeded no fixed list, which is what lets a
        // stale response be told apart from the current one.
        return providersToReturn.isEmpty ? [ChargingProvider(name: query, stationCount: 1)] : providersToReturn
    }

    func nearby(latitude _: Double, longitude _: Double, radiusKm _: Double, limit _: Int, filter _: StationFilter) async throws -> [Station] {
        Issue.record("The provider picker must not query stations")
        return []
    }

    func detail(id _: UUID) async throws -> StationDetail { fatalError("unused") }
    func liveAvailability(stationID _: UUID) async throws -> StationLiveAvailability { fatalError("unused") }
    func liveAvailability(latMin _: Double, lonMin _: Double, latMax _: Double, lonMax _: Double) async throws -> [StationLiveAvailability] { fatalError("unused") }
    func comments(stationID _: UUID, accessToken _: String?) async throws -> [StationComment] { fatalError("unused") }
    func createComment(stationID _: UUID, payload _: CommentPayload, accessToken _: String) async throws -> StationComment { fatalError("unused") }
    func updateComment(id _: UUID, payload _: CommentPayload, accessToken _: String) async throws -> StationComment { fatalError("unused") }
    func deleteComment(id _: UUID, accessToken _: String) async throws { fatalError("unused") }
    func signInWithApple(identityToken _: String) async throws -> String { fatalError("unused") }
    func signInProviders() async throws -> [SignInProvider] { fatalError("unused") }
    func signIn(provider _: String, code _: String, codeVerifier _: String?) async throws -> String { fatalError("unused") }
}

private let sampleProviders = [
    ChargingProvider(name: "EnBW mobility+", stationCount: 4_120),
    ChargingProvider(name: "IONITY", stationCount: 412)
]

/// Long enough for the 300 ms debounce plus the stubbed round trip to have finished.
private func settle() async throws {
    try await Task.sleep(for: .milliseconds(500))
}

@Suite("Provider search")
@MainActor
struct ProviderSearchTests {

    @Test("the first load asks for the largest networks, without waiting for anybody to type")
    func loadsMostCommonProvidersImmediately() async throws {
        let repository = StubProviderRepository(sampleProviders)
        let model = ProviderSearchViewModel(repository: repository)

        model.load()
        try await settle()

        #expect(model.providers == sampleProviders)
        #expect(repository.queries == [""])
    }

    @Test("a typed word is one request, not one per character")
    func debouncesTyping() async throws {
        let repository = StubProviderRepository(sampleProviders)
        let model = ProviderSearchViewModel(repository: repository)

        for query in ["i", "io", "ion", "ioni"] { model.queryChanged(to: query) }
        try await settle()

        #expect(repository.queries == ["ioni"])
    }

    @Test("surrounding whitespace is not part of the query")
    func trimsQuery() async throws {
        let repository = StubProviderRepository(sampleProviders)
        let model = ProviderSearchViewModel(repository: repository)

        model.queryChanged(to: "  ionity  ")
        try await settle()

        #expect(repository.queries == ["ionity"])
    }

    @Test("a slow answer cannot overwrite the results of the search that replaced it")
    func staleResponseDoesNotWin() async throws {
        // Echoing stub: each query answers with a provider named after itself.
        let repository = StubProviderRepository()
        let model = ProviderSearchViewModel(repository: repository)
        repository.gateNextCall()

        model.queryChanged(to: "stale")
        // Past the debounce, so the first lookup is inside the repository and parked there.
        try await Task.sleep(for: .milliseconds(400))
        model.queryChanged(to: "ionity")
        repository.releaseGate()
        try await settle()

        #expect(repository.queries == ["stale", "ionity"])
        #expect(model.loadedQuery == "ionity")
        #expect(model.providers.map(\.name) == ["ionity"])
        #expect(!model.isLoading)
    }

    @Test("a failed lookup surfaces as a message rather than as an empty list of providers")
    func reportsFailure() async throws {
        let repository = StubProviderRepository(sampleProviders, error: APIError.invalidResponse)
        let model = ProviderSearchViewModel(repository: repository)

        model.load()
        try await settle()

        #expect(model.errorMessage != nil)
        #expect(model.providers.isEmpty)
        #expect(!model.isLoading)
    }
}
