import CoreLocation
import SwiftUI
import Testing
import UIKit

@testable import EVMap

/// The routing screens rendered in a real window, in the states that change what they show (ADR 0017).
/// As in `ViewRenderingTests`: that each state builds and lays out, not what it looks like.
@Suite("Routing views", .serialized)
@MainActor
struct RoutingViewTests {
    private final class MemoryFavorites: FavoritesStoring {
        var stored: [Station] = []
        func load() -> [Station] { stored }
        func save(_ stations: [Station]) { stored = stations }
    }

    private func render<Content: View>(_ view: Content, height: CGFloat = 3_000, settle: Duration = .milliseconds(200)) async throws {
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

    private struct Harness {
        let planner: RoutePlannerViewModel
        let routes: FakeRouteProvider
        let favorites: FavoritesViewModel
    }

    private func harness(found: [RouteStation] = [], store: MemoryRoutingStore = MemoryRoutingStore()) -> Harness {
        let repository = StubStationRepository()
        repository.alongRoute = .success(found)
        let routes = FakeRouteProvider()
        let planner = RoutePlannerViewModel(repository: repository, routes: routes, places: FakePlaces(), store: store,
                                            filter: { StationFilter() },
                                            currentLocation: { CLLocationCoordinate2D(latitude: 48.7758, longitude: 9.1829) })
        let favorites = FavoritesViewModel(repository: repository, authSession: AuthSession(repository: repository), store: MemoryFavorites())
        return Harness(planner: planner, routes: routes, favorites: favorites)
    }

    private func screen(_ h: Harness) -> some View {
        RoutePlannerScreen(planner: h.planner, favorites: h.favorites, currentLocation: nil, showStation: { _ in })
    }

    private let munich = RoutingFixtures.place("München", latitude: 48.1374, longitude: 11.5755)

    @Test("the planner renders empty, with a gap to fill, and while planning")
    func plannerEarlyStates() async throws {
        let h = harness()
        try await render(screen(h))
        h.planner.apply(.routeFrom, to: munich)
        try await render(screen(h))
    }

    @Test("the planner renders a planned route with alternatives, stations, breaks and a failed station query")
    func plannerPlanned() async throws {
        let stations = (0..<6).map { RoutingFixtures.routeStation("Station \($0)", along: Double($0) * 20 + 5, off: Double($0) + 0.5) }
        let h = harness(found: stations)
        h.routes.result = .success([RoutingFixtures.route(hours: 3), RoutingFixtures.route(hours: 4)])
        h.planner.apply(.routeTo, to: munich)
        h.planner.addChargingStop(Fixtures.station(name: "Fastned Ulm"))
        h.planner.loadBreaks()
        try await render(screen(h), settle: .milliseconds(800))
        #expect(h.planner.phase == .planned)
    }

    @Test("the planner renders a route that failed and a station query that failed")
    func plannerFailures() async throws {
        let h = harness()
        h.routes.result = .failure(RouteError.noRoute)
        h.planner.apply(.routeTo, to: munich)
        try await render(screen(h), settle: .milliseconds(500))

        let again = harness()
        let repository = StubStationRepository()
        repository.alongRoute = .failure(StubStationRepository.Failure(message: "offline"))
        let planner = RoutePlannerViewModel(repository: repository, routes: again.routes, places: FakePlaces(), store: MemoryRoutingStore(),
                                            filter: { StationFilter() }, currentLocation: { CLLocationCoordinate2D(latitude: 48, longitude: 9) })
        planner.apply(.routeTo, to: munich)
        try await render(RoutePlannerScreen(planner: planner, favorites: again.favorites, currentLocation: nil, showStation: { _ in }),
                         settle: .milliseconds(500))
    }

    @Test("the info card renders for a place with and without a plan")
    func infoCard() async throws {
        let place = PlaceSelection(title: "Stuttgart", subtitle: "Baden-Württemberg, Deutschland",
                                   coordinate: CLLocationCoordinate2D(latitude: 48.7758, longitude: 9.1829))
        try await render(PlaceInfoCard(place: place, hasPlan: false, choose: { _ in }, save: { _ in }), height: 400)
        try await render(PlaceInfoCard(place: place, hasPlan: true, choose: { _ in }, save: { _ in }), height: 400)
    }

    @Test("the summary bar renders for a gap, for a route being planned and for a planned one")
    func summaryBar() async throws {
        let h = harness()
        h.planner.apply(.routeFrom, to: munich)
        try await render(RouteSummaryBar(planner: h.planner) { }, height: 200)
        h.planner.apply(.routeTo, to: RoutingFixtures.place("Wien", longitude: 16.37))
        try await render(RouteSummaryBar(planner: h.planner) { }, height: 200, settle: .milliseconds(400))
    }

    @Test("the picker and the saved screen render with places, favorites and routes")
    func pickerAndSaved() async throws {
        let h = harness()
        h.planner.savePlace(RoutingFixtures.place("Hauptstraße 1, Stuttgart"), named: "Zuhause")
        h.planner.apply(.routeTo, to: munich)
        h.planner.saveRoute(named: "Urlaub")
        await h.favorites.add(Fixtures.station(name: "Fastned Ulm"))
        try await render(WaypointPickerScreen(planner: h.planner, favorites: h.favorites, pick: { _ in },
                                              currentLocation: CLLocationCoordinate2D(latitude: 48, longitude: 9)))
        try await render(SavedRoutingScreen(planner: h.planner, openRoute: { _ in }))
        try await render(SavedRoutingScreen(planner: harness().planner, openRoute: { _ in }))
    }

    @Test("a plan restored from the device renders without asking MapKit")
    func restoredPlan() async throws {
        let store = MemoryRoutingStore()
        store.plan = StoredRoutePlan(slots: [RouteSlot(waypoint: RoutingFixtures.place("A")), RouteSlot(waypoint: munich)],
                                     options: RouteOptions(), route: RoutingFixtures.route(),
                                     candidates: [RouteStopCandidate(routeStation: RoutingFixtures.routeStation("S", along: 50), detourMinutes: 3)],
                                     savedAt: Date())
        let h = harness(store: store)
        #expect(h.planner.phase == .planned && h.routes.routeRequests.isEmpty)
        try await render(screen(h))
    }
}
