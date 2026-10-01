import CoreLocation
import Foundation
import Testing

@testable import EVMap

/// The manual planner (ADR 0017): how stops are added and changed, when it plans, what it asks the
/// backend, how stations are ranked, and what survives a restart.
@Suite("Route planner", .serialized)
@MainActor
struct RoutePlannerViewModelTests {
    /// A filter the test can change between queries.
    private final class FilterBox {
        var filter = StationFilter()
    }

    private struct Harness {
        let planner: RoutePlannerViewModel
        let routes: FakeRouteProvider
        let repository: StubStationRepository
        let places: FakePlaces
        let store: MemoryRoutingStore
    }

    private static let stuttgart = CLLocationCoordinate2D(latitude: 48.7758, longitude: 9.1829)

    private func harness(location: CLLocationCoordinate2D? = RoutePlannerViewModelTests.stuttgart,
                         store: MemoryRoutingStore = MemoryRoutingStore(),
                         filter: StationFilter = StationFilter(),
                         found: [RouteStation] = []) -> Harness {
        let routes = FakeRouteProvider()
        let repository = StubStationRepository()
        repository.alongRoute = .success(found)
        let places = FakePlaces()
        let planner = RoutePlannerViewModel(repository: repository, routes: routes, places: places, store: store,
                                            filter: { filter }, currentLocation: { location })
        return Harness(planner: planner, routes: routes, repository: repository, places: places, store: store)
    }

    /// The planner works in tasks; wait for what a test is about instead of sleeping a fixed time.
    private func settle(_ what: String = #function, _ condition: () -> Bool) async {
        for _ in 0..<1_000 {
            if condition() { return }
            try? await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("Timed out waiting: \(what)")
    }

    /// Names by row, with "where I am" as `here`: its label is localized, which is not what these tests are about.
    private func names(_ planner: RoutePlannerViewModel) -> [String?] {
        planner.slots.map { $0.waypoint.map { $0.kind == .currentLocation ? "here" : $0.name } }
    }

    private let munich = RoutingFixtures.place("München", latitude: 48.1374, longitude: 11.5755)

    // MARK: Starting a plan

    @Test("route to a place starts at the device position and plans at once")
    func routeTo() async {
        let h = harness()
        h.planner.apply(.routeTo, to: munich)

        #expect(h.planner.slots.count == 2)
        #expect(h.planner.slots[0].waypoint?.kind == .currentLocation)
        #expect(h.planner.slots[1].waypoint?.name == "München")
        #expect(h.planner.isPlannerPresented && h.planner.isPlannable)
        await settle { h.planner.phase == .planned }
        #expect(h.routes.routeRequests.count == 1 && h.routes.routeRequests[0].stops.count == 2)
        #expect(h.planner.route != nil && h.planner.fitRevision == 1)
    }

    @Test("without a device position the start stays a gap and nothing is planned")
    func routeToWithoutLocation() async {
        let h = harness(location: nil)
        h.planner.apply(.routeTo, to: munich)

        #expect(h.planner.slots.count == 2 && h.planner.slots[0].waypoint == nil)
        #expect(!h.planner.isPlannable && h.planner.phase == .idle)
        #expect(h.routes.routeRequests.isEmpty)

        h.planner.fill(slot: h.planner.slots[0].id, with: RoutingFixtures.place("Stuttgart"))
        await settle { h.planner.phase == .planned }
        #expect(h.routes.routeRequests.count == 1)
    }

    @Test("route from a place leaves the destination open")
    func routeFrom() {
        let h = harness()
        h.planner.apply(.routeFrom, to: munich)
        #expect(h.planner.slots.map { $0.waypoint?.name } == ["München", nil])
        #expect(!h.planner.isPlannable)
    }

    @Test("on a plan, from and to replace the ends instead of adding stops")
    func replacesEnds() async {
        let h = harness()
        h.planner.apply(.routeTo, to: munich)
        h.planner.apply(.addStop, to: RoutingFixtures.place("Ulm", longitude: 9.99))
        h.planner.apply(.routeTo, to: RoutingFixtures.place("Wien", longitude: 16.37))
        h.planner.apply(.routeFrom, to: RoutingFixtures.place("Basel", longitude: 7.59))

        #expect(h.planner.slots.map { $0.waypoint?.name } == ["Basel", "Ulm", "Wien"])
    }

    @Test("a stop goes between the ends, and a charging station stays half an hour by default")
    func addStop() async {
        let h = harness()
        h.planner.apply(.routeTo, to: munich)
        let station = Fixtures.station(name: "Fastned Ulm")
        h.planner.addChargingStop(station)
        h.planner.apply(.addStop, to: RoutingFixtures.place("Café"))

        #expect(names(h.planner) == ["here", "Fastned Ulm", "Café", "München"])
        #expect(h.planner.slots[1].waypoint?.stationID == station.id)
        #expect(h.planner.slots[1].waypoint?.dwellMinutes == RoutePlannerViewModel.chargingDwellMinutes)
        #expect(h.planner.slots[2].waypoint?.dwellMinutes == 0)
        await settle { h.planner.phase == .planned }
        // Three legs: the stops are joined from separate MapKit requests, one route comes back.
        #expect(h.routes.routeRequests.last?.stops.count == 4)
    }

    @Test("an added stop fills a gap before it makes a new row")
    func addStopFillsGap() {
        let h = harness(location: nil)
        h.planner.apply(.routeTo, to: munich)
        h.planner.apply(.addStop, to: RoutingFixtures.place("Stuttgart"))
        #expect(h.planner.slots.map { $0.waypoint?.name } == ["Stuttgart", "München"])
    }

    @Test("no more stops than a share link and Google Maps can carry")
    func stopLimit() {
        let h = harness()
        h.planner.apply(.routeTo, to: munich)
        for index in 0..<20 { h.planner.apply(.addStop, to: RoutingFixtures.place("S\(index)", longitude: 8 + Double(index) * 0.1)) }
        #expect(h.planner.slots.count == RouteShareLink.maximumStops)
        #expect(h.planner.errorMessage != nil)
    }

    @Test("a stop added to an empty plan makes it the destination, with the device position as start")
    func addStopToNothing() {
        let h = harness()
        h.planner.apply(.addStop, to: munich)
        #expect(names(h.planner) == ["here", "München"])
    }

    @Test("an empty row can be added before the destination, up to the limit")
    func emptyRows() {
        let h = harness()
        h.planner.addEmptyStop()
        #expect(!h.planner.hasPlan)

        h.planner.apply(.routeTo, to: munich)
        h.planner.addEmptyStop()
        #expect(names(h.planner) == ["here", nil, "München"] && !h.planner.isPlannable)
        for _ in 0..<20 { h.planner.addEmptyStop() }
        #expect(h.planner.slots.count == RouteShareLink.maximumStops && !h.planner.canAddStop)
    }

    // MARK: Changing the plan

    @Test("rows can be reordered, reversed and removed, down to two and then to gaps")
    func editing() async {
        let h = harness()
        h.planner.apply(.routeTo, to: munich)
        h.planner.apply(.addStop, to: RoutingFixtures.place("Ulm", longitude: 9.99))
        #expect(h.planner.slots.count == 3)

        h.planner.move(fromOffsets: IndexSet(integer: 1), toOffset: 0)
        #expect(names(h.planner) == ["Ulm", "here", "München"])
        h.planner.reverse()
        #expect(names(h.planner) == ["München", "here", "Ulm"])

        h.planner.remove(slot: h.planner.slots[1].id)
        #expect(h.planner.slots.count == 2)
        h.planner.remove(slot: h.planner.slots[0].id)
        #expect(h.planner.slots.count == 2 && h.planner.slots[0].waypoint == nil)
        h.planner.remove(slot: h.planner.slots[1].id)
        #expect(!h.planner.hasPlan && h.store.plan == nil)
    }

    @Test("a stay changes arrival times without asking MapKit again")
    func dwell() async {
        let h = harness()
        h.planner.apply(.routeTo, to: munich)
        h.planner.apply(.addStop, to: RoutingFixtures.place("Ulm", longitude: 9.99))
        await settle { h.planner.phase == .planned }
        h.routes.result = .success([RoutingFixtures.route(legs: 2)])
        h.planner.reverse()
        await settle { h.planner.phase == .planned && h.routes.routeRequests.count == 2 }
        #expect(h.planner.route?.legs.count == 2)

        let requests = h.routes.routeRequests.count
        h.planner.setDwell(90, forSlot: h.planner.slots[1].id)
        h.planner.setDwell(10_000, forSlot: h.planner.slots[0].id)
        #expect(h.routes.routeRequests.count == requests)
        #expect(h.planner.slots[0].waypoint?.dwellMinutes == RouteWaypoint.maximumDwellMinutes)
        // Leg 1 is 90 minutes of driving; the stay at slot 0 comes first and counts for the second leg.
        #expect(h.planner.arrivalOffset(atSlot: 1) == 5_400 + TimeInterval(RouteWaypoint.maximumDwellMinutes * 60))
        #expect(h.planner.arrivalOffset(atSlot: 0) == 0)
        #expect(h.planner.totalDwellMinutes == 90 + RouteWaypoint.maximumDwellMinutes)
    }

    @Test("changing the route options plans again with them")
    func options() async {
        let h = harness()
        h.planner.apply(.routeTo, to: munich)
        await settle { h.planner.phase == .planned }

        h.planner.setOptions(RouteOptions(avoidTolls: true, avoidMotorways: false))
        await settle { h.routes.routeRequests.count == 2 }
        #expect(h.routes.routeRequests.last?.options.avoidTolls == true)
        // The same options again are not a change.
        h.planner.setOptions(RouteOptions(avoidTolls: true, avoidMotorways: false))
        #expect(h.routes.routeRequests.count == 2)
    }

    @Test("a route MapKit cannot find is a message, and clears what was there")
    func noRoute() async {
        let h = harness()
        h.routes.result = .failure(RouteError.noRoute)
        h.planner.apply(.routeTo, to: munich)
        await settle { if case .failed = h.planner.phase { true } else { false } }
        #expect(h.planner.route == nil && h.planner.candidates.isEmpty)
        #expect(h.planner.phase == .failed(RouteError.noRoute.localizedDescription))
    }

    @Test("alternatives can be chosen, and the stations along the route are fetched again for it")
    func alternatives() async {
        let h = harness()
        h.routes.result = .success([RoutingFixtures.route(hours: 3), RoutingFixtures.route(hours: 4)])
        h.planner.apply(.routeTo, to: munich)
        await settle { h.planner.phase == .planned && h.repository.alongRouteQueries.count == 1 }

        h.planner.selectAlternative(1)
        await settle { h.repository.alongRouteQueries.count == 2 }
        #expect(h.planner.selectedIndex == 1 && h.planner.route?.travelTime == 4 * 3_600)
        h.planner.selectAlternative(7)
        #expect(h.planner.selectedIndex == 1)
    }

    @Test("clearing the plan removes it from the device too")
    func clearing() async {
        let h = harness()
        h.planner.apply(.routeTo, to: munich)
        await settle { h.planner.phase == .planned && h.store.plan?.route != nil }
        h.planner.clear()
        #expect(!h.planner.hasPlan && h.planner.route == nil && h.store.plan == nil && !h.planner.isPlannerPresented)
    }

    // MARK: Stations along the route

    @Test("the corridor query carries the simplified route, the corridor and the map's filter")
    func corridorQuery() async throws {
        var filter = StationFilter()
        filter.minimumPower = 100
        let h = harness(filter: filter)
        h.planner.apply(.routeTo, to: munich)
        await settle { h.repository.alongRouteQueries.count == 1 }

        let query = try #require(h.repository.alongRouteQueries.first)
        #expect(query.route.count >= 2 && query.route.count <= RoutePlannerViewModel.routePointLimit)
        #expect(query.corridorKm == RoutePlannerViewModel.corridorKm && query.limit == RoutePlannerViewModel.candidateLimit)
        #expect(query.filter.minimumPower == 100)
    }

    @Test("closing the settings asks again only when the filter really changed")
    func filterChange() async throws {
        let box = FilterBox()
        let repository = StubStationRepository()
        let planner = RoutePlannerViewModel(repository: repository, routes: FakeRouteProvider(), places: FakePlaces(),
                                            store: MemoryRoutingStore(), filter: { box.filter },
                                            currentLocation: { Self.stuttgart })
        planner.apply(.routeTo, to: munich)
        await settle { repository.alongRouteQueries.count == 1 }

        planner.filterChanged()
        try await Task.sleep(for: .milliseconds(100))
        #expect(repository.alongRouteQueries.count == 1)

        box.filter.minimumPower = 150
        planner.filterChanged()
        await settle { repository.alongRouteQueries.count == 2 }
        #expect(repository.alongRouteQueries.last?.filter.minimumPower == 150)
    }

    @Test("the nearest stations get an exact detour, the rest an estimate, and the list is ranked by it")
    func detours() async {
        let stations = (0..<14).map { RoutingFixtures.routeStation("S\($0)", along: Double($0) * 10 + 5, off: Double($0) + 1) }
        let h = harness(found: stations)
        // 300 s out and 300 s back on a stretch the route covers in about 2 minutes: ~8 minutes.
        h.routes.travelTimes = .success(300)
        h.planner.apply(.routeTo, to: munich)
        await settle { h.planner.candidates.filter(\.isDetourExact).count == RoutePlannerViewModel.exactDetourCount }

        #expect(h.planner.candidates.count == 14)
        let exact = h.planner.candidates.filter(\.isDetourExact).map(\.routeStation.distanceToRouteKm)
        #expect(exact.max() == Double(RoutePlannerViewModel.exactDetourCount))
        #expect(h.routes.travelTimeRequests == RoutePlannerViewModel.exactDetourCount * 2)

        h.planner.stationSort = .alongRoute
        #expect(h.planner.sortedCandidates.map(\.routeStation.distanceAlongRouteKm) == h.planner.sortedCandidates.map(\.routeStation.distanceAlongRouteKm).sorted())
        h.planner.stationSort = .detour
        let minutes = h.planner.sortedCandidates.map(\.rankingMinutes)
        #expect(minutes == minutes.sorted())
    }

    @Test("a throttled MapKit stops the detours and says so; the stations stay listed")
    func throttled() async {
        let stations = (0..<8).map { RoutingFixtures.routeStation("S\($0)", along: Double($0) * 10 + 5, off: Double($0) + 1) }
        let h = harness(found: stations)
        h.routes.throttleAfter = 2
        h.planner.apply(.routeTo, to: munich)
        await settle { h.planner.detoursThrottled }

        #expect(h.planner.candidates.count == 8)
        #expect(h.planner.candidates.filter(\.isDetourExact).count < 8)
    }

    @Test("a failing station query leaves the route standing")
    func stationFailure() async {
        let h = harness()
        h.repository.alongRoute = .failure(StubStationRepository.Failure(message: "offline"))
        h.planner.apply(.routeTo, to: munich)
        await settle { h.planner.stationsFailure != nil }
        #expect(h.planner.phase == .planned && h.planner.route != nil && h.planner.candidates.isEmpty)
    }

    // MARK: Interrupted work

    @Test("a plan that is replaced while MapKit is answering leaves no failure behind")
    func planningSuperseded() async {
        let h = harness()
        h.routes.result = .failure(CancellationError())
        h.planner.apply(.routeTo, to: munich)
        await settle { h.routes.routeRequests.count == 1 }
        try? await Task.sleep(for: .milliseconds(50))
        #expect(h.planner.phase == .planning)
    }

    @Test("a station query that is cancelled is not a failure")
    func stationsSuperseded() async {
        let h = harness()
        h.repository.alongRoute = .failure(CancellationError())
        h.planner.apply(.routeTo, to: munich)
        await settle { h.repository.alongRouteQueries.count == 1 }
        try? await Task.sleep(for: .milliseconds(50))
        #expect(h.planner.stationsFailure == nil && h.planner.phase == .planned)
    }

    @Test("a detour MapKit cannot answer leaves that station with its estimate and is not reported as throttling")
    func detourFailure() async {
        let h = harness(found: [RoutingFixtures.routeStation("S", along: 50, off: 2)])
        h.routes.travelTimes = .failure(StubStationRepository.Failure(message: "no route"))
        h.planner.apply(.routeTo, to: munich)
        await settle { h.routes.travelTimeRequests >= 2 }
        try? await Task.sleep(for: .milliseconds(100))
        #expect(!h.planner.detoursThrottled && h.planner.candidates.count == 1 && !h.planner.candidates[0].isDetourExact)
    }

    // MARK: Breaks

    @Test("break suggestions are searched at intervals of driving time, only on request, and listed in driving order")
    func breaks() async {
        let h = harness()
        h.routes.result = .success([RoutingFixtures.route(hours: 5)])
        h.places.found = [BreakSuggestion(id: "cafe|A", name: "A", category: .cafe, latitude: 48, longitude: 9, distanceAlongRouteKm: 0)]
        h.planner.apply(.routeTo, to: munich)
        await settle { h.planner.phase == .planned }
        #expect(h.places.centers.isEmpty)

        h.planner.loadBreaks()
        await settle { !h.planner.breaks.isEmpty }
        // Five hours at one search per two: two searches, one place each, the same place deduplicated.
        #expect(h.places.centers.count == 2)
        #expect(h.planner.breaks.count == 1)
    }

    @Test("a failing break search costs its stretch only, and a station's surroundings are searched on request")
    func breakFailures() async {
        let h = harness()
        h.routes.result = .success([RoutingFixtures.route(hours: 5)])
        h.places.failure = StubStationRepository.Failure(message: "offline")
        h.planner.apply(.routeTo, to: munich)
        await settle { h.planner.phase == .planned }
        h.planner.loadBreaks()
        await settle { !h.planner.isLoadingBreaks && h.places.centers.count == 2 }
        #expect(h.planner.breaks.isEmpty)

        h.places.failure = nil
        h.places.found = [BreakSuggestion(id: "cafe|A", name: "A", category: .cafe, latitude: 48, longitude: 9, distanceAlongRouteKm: 0)]
        #expect(await h.planner.amenities(near: Fixtures.station()).count == 1)
        h.places.failure = StubStationRepository.Failure(message: "offline")
        #expect(await h.planner.amenities(near: Fixtures.station()).isEmpty)
    }

    @Test("breaks on a route without driving time are not searched")
    func breaksWithoutRoute() {
        let h = harness()
        h.planner.loadBreaks()
        #expect(h.places.centers.isEmpty && h.planner.breaks.isEmpty)
    }

    // MARK: Saved places and routes, links

    @Test("places are saved under the name the person gave, renamed, and deleted")
    func savedPlaces() {
        let h = harness()
        h.planner.savePlace(RoutingFixtures.place("Hauptstraße 1, Stuttgart"), named: "  Zuhause ")
        h.planner.savePlace(RoutingFixtures.place("x"), named: "   ")
        #expect(h.planner.savedPlaces.map(\.name) == ["Zuhause"] && h.store.places.count == 1)

        h.planner.renamePlace(h.planner.savedPlaces[0], to: "Daheim")
        #expect(h.store.places.first?.name == "Daheim")
        #expect(h.planner.savedPlaces[0].waypoint.name == "Daheim")
        h.planner.deletePlace(h.planner.savedPlaces[0])
        #expect(h.planner.savedPlaces.isEmpty && h.store.places.isEmpty)
    }

    @Test("a complete plan can be saved and opened again, and a gap cannot be saved")
    func savedRoutes() async {
        let h = harness()
        h.planner.apply(.routeFrom, to: RoutingFixtures.place("Stuttgart"))
        h.planner.saveRoute(named: "Urlaub")
        #expect(h.planner.savedRoutes.isEmpty)

        h.planner.apply(.routeTo, to: munich)
        h.planner.setOptions(RouteOptions(avoidTolls: true, avoidMotorways: true))
        h.planner.saveRoute(named: "Urlaub")
        #expect(h.planner.savedRoutes.map(\.name) == ["Urlaub"] && h.store.routes.count == 1)

        h.planner.clear()
        h.planner.open(h.planner.savedRoutes[0])
        #expect(h.planner.slots.map { $0.waypoint?.name } == ["Stuttgart", "München"])
        #expect(h.planner.options.avoidTolls && h.planner.isPlannerPresented)
        await settle { h.planner.phase == .planned }
        h.planner.deleteRoute(h.planner.savedRoutes[0])
        #expect(h.planner.savedRoutes.isEmpty)
    }

    @Test("a link of ours opens as the plan, and any other link is left alone")
    func openingLinks() async throws {
        let h = harness()
        #expect(!h.planner.openShareLink(URL(string: "https://example.com/route?w=48.1,9.1,0,,A&w=49.1,10.1,0,,B")!))
        #expect(!h.planner.openShareLink(URL(string: "https://evmap.joinside.de/other")!))
        #expect(!h.planner.hasPlan)

        let link = try #require(RouteShareLink.url(for: [RoutingFixtures.place("A"), munich], options: RouteOptions(avoidTolls: true, avoidMotorways: false)))
        #expect(h.planner.openShareLink(link))
        #expect(names(h.planner) == ["A", "München"] && h.planner.options.avoidTolls && h.planner.isPlannerPresented)
        await settle { h.planner.phase == .planned }
    }

    @Test("a shared route opens as the plan, and its 'where I am' becomes this device's position")
    func sharedRoute() async throws {
        let h = harness()
        let here = RouteWaypoint(kind: .currentLocation, name: "", latitude: 0, longitude: 0)
        h.planner.open(RouteShareLink.Route(waypoints: [here, munich], options: RouteOptions()))

        let start = try #require(h.planner.slots[0].waypoint)
        #expect(start.kind == .currentLocation && start.latitude == Self.stuttgart.latitude)
        await settle { h.planner.phase == .planned }
        #expect(h.planner.shareURL != nil)
        #expect(h.planner.googleMapsURL != nil)
    }

    // MARK: Staying on the device

    @Test("the plan is kept after every change and comes back as it was, route and stations included")
    func persistence() async {
        let store = MemoryRoutingStore()
        let h = harness(store: store, found: [RoutingFixtures.routeStation("S", along: 50)])
        h.planner.apply(.routeTo, to: munich)
        await settle { h.planner.candidates.first?.isDetourExact == true }
        await settle { store.plan?.candidates.first?.isDetourExact == true }

        let restored = harness(location: nil, store: store).planner
        #expect(names(restored) == ["here", "München"])
        #expect(restored.route == h.planner.route)
        #expect(restored.candidates == h.planner.candidates)
        #expect(restored.phase == .planned)
        #expect(restored.mapRevision > 0)
    }
}
