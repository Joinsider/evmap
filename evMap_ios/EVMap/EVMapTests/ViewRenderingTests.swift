import SwiftUI
import Testing
import UIKit

@testable import EVMap

/// Every screen rendered in a real window, in the states that change what it shows.
///
/// These are smoke tests with a purpose beyond coverage: SwiftUI evaluates a view's `body` only when
/// it lays the view out, so a force-unwrap, a missing localisation key used in `String(format:)`, or a
/// branch that traps on an empty list only fails when somebody opens that screen. Rendering each
/// state here makes that happen in CI instead. What a view *looks* like is not asserted — that is a
/// job for a human looking at the simulator — but that each state builds and lays out is.
@Suite("View rendering", .serialized)
@MainActor
struct ViewRenderingTests {

    /// Settings that live only as long as the test.
    private final class MemoryStore: AppSettingsStoring {
        var stored: AppSettings
        init(_ stored: AppSettings = .factoryDefaults) { self.stored = stored }
        func load() -> AppSettings { stored }
        func save(_ settings: AppSettings) { stored = settings }
        func reset() { stored = .factoryDefaults }
    }

    /// Lays `view` out in a tall window, so that lazily built rows below the fold are built too, and
    /// lets the run loop turn so `task` and `onAppear` work gets to run.
    ///
    /// `height` is phone-sized for anything containing a map: MapKit renders into a Metal texture the
    /// size of its view, and a 3 000 pt one exceeds the simulator's limit and aborts the test host.
    private func render<Content: View>(_ view: Content, height: CGFloat = 3_000,
                                       settle: Duration = .milliseconds(200)) async throws {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 430, height: height))
        let host = UIHostingController(rootView: view)
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        try await Task.sleep(for: settle)
        host.view.layoutIfNeeded()
        #expect(host.view.bounds.height > 0)
        window.isHidden = true
    }

    private func favoritesModel(_ repository: StubStationRepository, session: AuthSession? = nil,
                                stations: [Station] = []) -> FavoritesViewModel {
        FavoritesViewModel(repository: repository, authSession: session ?? AuthSession(repository: repository),
                           store: MemoryFavorites(stations))
    }

    /// Favorites that live only as long as the test.
    private final class MemoryFavorites: FavoritesStoring {
        var stored: [Station]
        init(_ stored: [Station] = []) { self.stored = stored }
        func load() -> [Station] { stored }
        func save(_ stations: [Station]) { stored = stations }
    }

    private func settingsModel(_ configure: (inout AppSettings) -> Void = { _ in }) -> SettingsViewModel {
        var settings = AppSettings.factoryDefaults
        configure(&settings)
        return SettingsViewModel(store: MemoryStore(settings))
    }

    @Test("the live section renders with one source, with mixed sources, and without a timestamp")
    func liveSection() async throws {
        let id = UUID()
        try await render(List { StationLiveAvailabilitySection(availability: Fixtures.live(stationID: id)) })
        try await render(List {
            StationLiveAvailabilitySection(availability: Fixtures.live(
                stationID: id, sources: [Fixtures.mobiData, Fixtures.transport]))
        })
        let bare = StationLiveAvailability(stationID: id, status: .outOfOrder,
                                           counts: .init(available: 0, occupied: 0, outOfOrder: 1, unknown: 0),
                                           observedAt: nil, chargePoints: [], sources: [Fixtures.transport])
        try await render(List { StationLiveAvailabilitySection(availability: bare) })
    }

    @Test("the station screen renders signed out and signed in, with detail, comments and live status")
    func stationScreen() async throws {
        let station = Fixtures.station()
        let repository = StubStationRepository()
        repository.stationDetail = .success(Fixtures.detail(for: station))
        repository.commentList = .success([Fixtures.comment(), Fixtures.comment(ownedByCurrentUser: false)])
        repository.liveStation = .success(Fixtures.live(stationID: station.id))

        let signedOut = AuthSession(repository: repository)
        try await render(StationDetailScreen(station: station, repository: repository, authSession: signedOut,
                                             favorites: favoritesModel(repository)),
                         settle: .milliseconds(500))

        // AuthSession restores its token from UserDefaults; seeding that is the only way in.
        UserDefaults.standard.set("token", forKey: "EVMapAccessToken")
        defer { UserDefaults.standard.removeObject(forKey: "EVMapAccessToken") }
        let signedIn = AuthSession(repository: repository)
        #expect(signedIn.accessToken == "token")
        try await render(StationDetailScreen(station: station, repository: repository, authSession: signedIn,
                                             favorites: favoritesModel(repository, session: signedIn, stations: [station])),
                         settle: .milliseconds(500))

        repository.stationDetail = .failure(StubStationRepository.Failure(message: "offline"))
        try await render(StationDetailScreen(station: station, repository: repository, authSession: signedOut,
                                             favorites: favoritesModel(repository)),
                         settle: .milliseconds(500))
    }

    @Test("the sign-in prompt renders on its own")
    func signInPrompt() async throws {
        try await render(SignInPrompt(authSession: AuthSession(repository: StubStationRepository())))

        let repository = StubStationRepository()
        repository.signInProviderList = .success(["google", "github", "other"].map {
            SignInProvider(provider: $0, authorizationEndpoint: URL(string: "https://idp.example/auth")!,
                           parameters: ["redirect_uri": "https://evmap.joinside.de/auth/callback/\($0)"], pkce: true)
        })
        try await render(SignInPrompt(authSession: AuthSession(repository: repository)), settle: .milliseconds(300))

        repository.signInProviderList = .failure(StubStationRepository.Failure(message: "offline"))
        try await render(SignInPrompt(authSession: AuthSession(repository: repository)), settle: .milliseconds(300))
    }

    @Test("settings render with their defaults and with filters and provider choices set")
    func settingsScreen() async throws {
        let repository = StubStationRepository()
        try await render(NavigationStack { SettingsScreen(model: settingsModel(), repository: repository, authSession: AuthSession(repository: repository)) })
        try await render(NavigationStack {
            SettingsScreen(model: settingsModel { settings in
                settings.connectorTypes = [.ccs, .type2]
                settings.minimumPower = 150
                settings.setPreference(.hidden, for: "Tesla")
            }, repository: repository, authSession: AuthSession(repository: repository))
        })
    }

    @Test("the account screen renders signed out, and signed in with contributions, blocks and a privacy link")
    func accountScreen() async throws {
        let repository = StubStationRepository()
        try await render(NavigationStack { AccountScreen(repository: repository, authSession: AuthSession(repository: repository)) })

        UserDefaults.standard.set("token", forKey: "EVMapAccessToken")
        defer { UserDefaults.standard.removeObject(forKey: "EVMapAccessToken") }
        repository.blockList = .success([BlockedAuthor(id: UUID(), createdAt: Date())])
        repository.contributionList = .success(Contributions(
            comments: [CommentContribution(id: UUID(), stationName: "EnBW", body: "Lädt schnell", createdAt: Date())],
            reports: [ReportContribution(id: UUID(), reason: "wrong", status: "dismissed", stationName: nil, createdAt: Date())],
            stationReports: [StationReportContribution(id: UUID(), reason: "wrong_power", note: "11 kW", status: "resolved",
                                                       stationName: "EnBW", createdAt: Date()),
                             StationReportContribution(id: UUID(), reason: "unheard-of", note: nil, status: "open",
                                                       stationName: nil, createdAt: Date())]))
        repository.legalInfo = .success(LegalInfo(privacyPolicyUrl: URL(string: "https://evmap.example/privacy")))
        try await render(NavigationStack { AccountScreen(repository: repository, authSession: AuthSession(repository: repository)) },
                         settle: .milliseconds(400))
    }

    @Test("the favorites list renders empty and filled, signed out and signed in, and the report form renders")
    func favoritesAndReportScreens() async throws {
        let repository = StubStationRepository()
        let signedOut = AuthSession(repository: repository)
        try await render(FavoritesScreen(model: favoritesModel(repository), authSession: signedOut) { _ in })
        try await render(FavoritesScreen(model: favoritesModel(repository, stations: [Fixtures.station(), Fixtures.station(name: "Ionity")]),
                                         authSession: signedOut) { _ in })

        UserDefaults.standard.set("token", forKey: "EVMapAccessToken")
        defer { UserDefaults.standard.removeObject(forKey: "EVMapAccessToken") }
        let signedIn = AuthSession(repository: repository)
        try await render(FavoritesScreen(model: favoritesModel(repository, session: signedIn, stations: [Fixtures.station()]),
                                         authSession: signedIn) { _ in })

        try await render(StationReportScreen { _, _ in true })
    }

    @Test("the provider picker renders its configured networks and search results")
    func providerPreferences() async throws {
        let repository = StubStationRepository()
        repository.providerList = .success([ChargingProvider(name: "EnBW mobility+", stationCount: 4_120),
                                            ChargingProvider(name: "IONITY", stationCount: 412)])
        let model = settingsModel { settings in
            settings.setPreference(.hidden, for: "Tesla")
            settings.setPreference(.shown, for: "IONITY")
        }
        try await render(NavigationStack { ProviderPreferencesScreen(model: model, repository: repository) },
                         settle: .milliseconds(600))

        repository.providerList = .failure(StubStationRepository.Failure(message: "offline"))
        try await render(NavigationStack { ProviderPreferencesScreen(model: settingsModel(), repository: repository) },
                         settle: .milliseconds(600))
    }

    @Test("the map renders with stations and live badges")
    func mapScreen() async throws {
        let repository = StubStationRepository()
        let stations = [Fixtures.station(), Fixtures.station(name: "IONITY", latitude: 48.78, maxPowerKw: 350),
                        Fixtures.station(name: "Slow", latitude: 48.77, maxPowerKw: nil)]
        repository.stations = .success(stations)
        repository.liveViewport = .success([Fixtures.live(stationID: stations[0].id)])

        try await render(MapScreen(repository: repository, authSession: AuthSession(repository: repository),
                                   settings: settingsModel(), favorites: favoritesModel(repository, stations: [stations[0]])),
                         height: 932, settle: .milliseconds(800))
    }
}
