import CoreLocation
import Foundation
import MapKit
import Testing

@testable import EVMap

/// The pieces around the planner (ADR 0017): roles of the rows, framing the route, joining legs from the
/// directions service, what MapKit's errors mean, places of interest as suggestions, and the sheets that
/// lead into the planner.
@Suite("Routing support", .serialized)
@MainActor
struct RoutingSupportTests {
    private let stuttgart = CLLocationCoordinate2D(latitude: 48.7758, longitude: 9.1829)
    private let munich = CLLocationCoordinate2D(latitude: 48.1374, longitude: 11.5755)
    private let ulm = CLLocationCoordinate2D(latitude: 48.4, longitude: 9.99)

    // MARK: Roles

    @Test("rows are start, waypoints and destination by position, and named A, 1, 2 … B on the map")
    func roles() {
        let roles = (0..<4).map { RouteStopRole(index: $0, count: 4) }
        #expect(roles == [.start, .waypoint, .waypoint, .destination])
        #expect((0..<4).map { roles[$0].monogram(index: $0) } == ["A", "1", "2", "B"])
        #expect(RouteStopRole(index: 0, count: 1) == .start)
        #expect(RouteStopRole(index: 1, count: 2) == .destination)
        #expect([RouteStopRole.start, .waypoint, .destination].map(\.symbol).count == 3)
    }

    // MARK: Framing

    @Test("the camera frames the whole route with its middle in the upper half, and never zooms in past a street")
    func framing() throws {
        let route = RoutingFixtures.route().coordinates
        let region = try #require(RouteFraming.region(fitting: route))
        // 4° of longitude wide: 1.3 times that, centred on the route; the route is 40 % of the height.
        #expect(abs(region.span.longitudeDelta - 5.2) < 0.01 && abs(region.center.longitude - 10) < 0.01)
        #expect(region.center.latitude < 48)

        let short = try #require(RouteFraming.region(fitting: [RouteCoordinate(latitude: 48, longitude: 9)]))
        #expect(short.span.latitudeDelta == 0.04 && short.span.longitudeDelta == 0.04)
        #expect(RouteFraming.region(fitting: []) == nil)
    }

    @Test("the camera frames a neighbourhood, and zooms a cluster open to a quarter of the span, never past a street")
    func camera() {
        let around = MapCamera.region(around: munich)
        #expect(around.center.latitude == munich.latitude && abs(around.span.latitudeDelta - 4_000 / 111_000) < 0.01)

        let zoomed = MapCamera.region(zoomingInto: munich, from: MKCoordinateSpan(latitudeDelta: 2, longitudeDelta: 4))
        #expect(zoomed.span.latitudeDelta == 0.5 && zoomed.span.longitudeDelta == 1)
        #expect(MapCamera.region(zoomingInto: munich, from: nil).span.latitudeDelta == 0.1)
        #expect(MapCamera.region(zoomingInto: munich, from: MKCoordinateSpan(latitudeDelta: 0.001, longitudeDelta: 0.001)).span.latitudeDelta == 0.002)
    }

    // MARK: Joining legs

    private func provider(_ answers: [Result<[DirectionsRoute], Error>]) -> (MapKitRouteProvider, FakeDirections) {
        let directions = FakeDirections()
        directions.answers = answers
        return (MapKitRouteProvider(directions: directions), directions)
    }

    @Test("two stops ask once, with alternatives, and every answer becomes a route")
    func twoStops() async throws {
        let (provider, directions) = provider([.success([FakeDirections.leg(9, 11, name: "A8"), FakeDirections.leg(9, 11, name: "B27", tolls: true)])])
        let options = RouteOptions(avoidTolls: true, avoidMotorways: false)

        let routes = try await provider.routes(through: [stuttgart, munich], options: options)

        #expect(routes.map(\.name) == ["A8", "B27"] && routes.map(\.hasTolls) == [false, true])
        #expect(directions.routes.count == 1 && directions.routes[0].alternatives && directions.routes[0].options == options)
        #expect(routes[0].legs.count == 1 && routes[0].coordinates.count == 3)
    }

    @Test("with waypoints each leg is asked for on its own and joined into one route without a doubled junction")
    func legsAreJoined() async throws {
        let (provider, directions) = provider([.success([FakeDirections.leg(9, 10)]), .success([FakeDirections.leg(10, 11, tolls: true)])])

        let routes = try await provider.routes(through: [stuttgart, ulm, munich], options: RouteOptions())

        #expect(routes.count == 1 && directions.routes.count == 2 && directions.routes.allSatisfy { !$0.alternatives })
        let route = try #require(routes.first)
        // Three vertices per leg, one shared: five.
        #expect(route.coordinates.count == 5 && route.legs.count == 2 && route.name.isEmpty && route.hasTolls)
        #expect(route.coordinates.first?.longitude == 9 && route.coordinates.last?.longitude == 11)
        #expect(route.travelTime == 7_200)
    }

    @Test("no connection is no route, one stop is no route yet, and other errors are passed on")
    func failures() async {
        let (empty, _) = provider([.success([])])
        await #expect(throws: RouteError.noRoute) { try await empty.routes(through: [stuttgart, munich], options: RouteOptions()) }

        let (gap, _) = provider([.success([FakeDirections.leg(9, 10)]), .success([])])
        await #expect(throws: RouteError.noRoute) { try await gap.routes(through: [stuttgart, ulm, munich], options: RouteOptions()) }

        let (single, _) = provider([])
        await #expect(throws: RouteError.needsTwoStops) { try await single.routes(through: [stuttgart], options: RouteOptions()) }

        let (broken, _) = provider([.failure(StubStationRepository.Failure(message: "offline"))])
        await #expect(throws: StubStationRepository.Failure.self) { try await broken.routes(through: [stuttgart, munich], options: RouteOptions()) }
    }

    @Test("a travel time is the service's, and its errors reach the caller")
    func travelTime() async throws {
        let (provider, directions) = provider([])
        directions.eta = .success(480)
        #expect(try await provider.travelTime(from: stuttgart, to: munich, options: RouteOptions()) == 480)
        directions.eta = .failure(RouteError.throttled)
        await #expect(throws: RouteError.throttled) { try await provider.travelTime(from: stuttgart, to: munich, options: RouteOptions()) }
    }

    @Test("MapKit's errors become what a plan can act on, and the messages are said in words")
    func errors() {
        #expect(RouteError.translating(MKError(.loadingThrottled)) as? RouteError == .throttled)
        #expect(RouteError.translating(MKError(.directionsNotFound)) as? RouteError == .noRoute)
        #expect(RouteError.translating(MKError(.serverFailure)) is MKError)
        #expect(RouteError.translating(StubStationRepository.Failure(message: "x")) is StubStationRepository.Failure)
        for error in [RouteError.needsTwoStops, .noRoute, .throttled] {
            #expect(error.errorDescription?.isEmpty == false)
        }
    }

    @Test("the directions request is for driving, with the options as preferences and alternatives only when asked")
    func directionsRequest() {
        let plain = MapKitDirections.request(from: stuttgart, to: munich, options: RouteOptions(), alternatives: false)
        #expect(plain.transportType == .automobile && !plain.requestsAlternateRoutes)
        #expect(plain.tollPreference == .any && plain.highwayPreference == .any)
        #expect(plain.source?.location.coordinate.latitude == stuttgart.latitude)
        #expect(plain.destination?.location.coordinate.longitude == munich.longitude)

        let avoiding = MapKitDirections.request(from: stuttgart, to: munich, options: RouteOptions(avoidTolls: true, avoidMotorways: true), alternatives: true)
        #expect(avoiding.requestsAlternateRoutes && avoiding.tollPreference == .avoid && avoiding.highwayPreference == .avoid)
    }

    // MARK: Places of interest

    @Test("the nearby search asks for the five categories around the point and keeps what it lists")
    func nearbySearch() async throws {
        var asked: MKLocalPointsOfInterestRequest?
        let provider = MapKitNearbyPlacesProvider { request in
            asked = request
            let cafe = MKMapItem(location: CLLocation(latitude: 48.1, longitude: 11.5), address: nil)
            cafe.name = "Café Eins"
            cafe.pointOfInterestCategory = .cafe
            let park = MKMapItem(location: CLLocation(latitude: 48.3, longitude: 11.7), address: nil)
            park.name = "Park"
            park.pointOfInterestCategory = .park
            return [cafe, park]
        }

        let found = try await provider.places(near: munich, radiusMeters: 3_000, categories: BreakSuggestion.Category.allCases)

        #expect(found.map(\.name) == ["Café Eins"] && found.first?.category == .cafe)
        let request = try #require(asked)
        #expect(request.radius == 3_000 && request.coordinate.latitude == munich.latitude)
        #expect(request.pointOfInterestFilter?.includes(.hotel) == true && request.pointOfInterestFilter?.includes(.park) == false)
    }

    @Test("a place of interest becomes a break suggestion in its category; a nameless or unlisted one does not")
    func suggestions() throws {
        let place = CLLocationCoordinate2D(latitude: 48.1, longitude: 11.5)
        for category in BreakSuggestion.Category.allCases {
            let found = MapKitNearbyPlacesProvider.suggestion(name: "Ort", category: MapKitNearbyPlacesProvider.mapKitCategory(category), coordinate: place)
            #expect(found?.category == category && found?.name == "Ort" && found?.latitude == 48.1)
            #expect(!category.title.isEmpty && !category.symbol.isEmpty)
        }
        #expect(MapKitNearbyPlacesProvider.suggestion(name: nil, category: .cafe, coordinate: place) == nil)
        #expect(MapKitNearbyPlacesProvider.suggestion(name: "Park", category: .park, coordinate: place) == nil)
        #expect(MapKitNearbyPlacesProvider.suggestion(name: "Ort", category: nil, coordinate: place) == nil)

        let cafe = try #require(MapKitNearbyPlacesProvider.suggestion(name: "Café", category: .cafe, coordinate: place))
        #expect(cafe.waypoint.name == "Café" && cafe.waypoint.subtitle == BreakSuggestion.Category.cafe.title)
        #expect(cafe.coordinate.longitude == 11.5)
    }

    @Test("a selected place knows its coordinate, and is a waypoint under its own name")
    func placeSelection() {
        let searched = SearchedPlace(title: "Hauptstraße 1", subtitle: "Stuttgart", coordinate: stuttgart)
        let selection = PlaceSelection(place: searched)
        #expect(selection.title == "Hauptstraße 1" && selection.subtitle == "Stuttgart")
        #expect(selection.coordinate.latitude == stuttgart.latitude)
        #expect(selection.waypoint.name == "Hauptstraße 1" && selection.waypoint.longitude == stuttgart.longitude)
        #expect(RouteWaypoint(place: searched).subtitle == "Stuttgart")
        #expect(selection.waypoint.kind == .place)
    }

    @Test("a station screen opened without a map ignores route actions")
    func ignoredRouteAction() {
        StationDetailScreen.ignoreRouteAction(.routeTo)
        StationDetailScreen.ignoreRouteAction(.addStop)
    }

    // MARK: Sheets

    private func flow() -> (MapPlaceFlow, RoutePlannerViewModel) {
        let planner = RoutePlannerViewModel(repository: StubStationRepository(), routes: FakeRouteProvider(), places: FakePlaces(),
                                            store: MemoryRoutingStore(), filter: { StationFilter() }, currentLocation: { CLLocationCoordinate2D(latitude: 48, longitude: 9) })
        return (MapPlaceFlow(planner: planner), planner)
    }

    private func settle(_ condition: () -> Bool) async {
        for _ in 0..<1_000 {
            if condition() { return }
            try? await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("Timed out")
    }

    @Test("a tapped pin opens its station; a cluster is handed back to be zoomed into")
    func tapping() {
        let (flow, _) = flow()
        let single = StationAnnotation(id: "a", latitude: 48, longitude: 9, stations: [Fixtures.station(name: "Eine")])
        let cluster = StationAnnotation(id: "b", latitude: 48, longitude: 9, stations: [Fixtures.station(name: "Eins"), Fixtures.station(name: "Zwei")])

        #expect(flow.tap(single) == nil && flow.station?.displayName == "Eine")
        flow.station = nil
        #expect(flow.tap(cluster) == cluster && flow.station == nil)
    }

    @Test("a search hit opens the card")
    func searchHit() {
        let (flow, _) = flow()
        flow.show(SearchedPlace(title: "Stuttgart", subtitle: "", coordinate: stuttgart))
        #expect(flow.place?.title == "Stuttgart")
    }

    @Test("a tapped feature opens the card at once and learns its address; an unnamed one gets a name")
    func feature() async {
        let (flow, _) = flow()
        flow.show(title: "Marienplatz", coordinate: munich) { "München" }
        #expect(flow.place?.title == "Marienplatz" && flow.place?.subtitle == "")
        await settle { flow.place?.subtitle == "München" }
        #expect(flow.place?.subtitle == "München")

        flow.show(title: nil, coordinate: munich) { nil }
        #expect(flow.place?.title == String(localized: "route.place.unnamed"))
    }

    @Test("an address that arrives after another place took the card is dropped")
    func staleAddress() async {
        let (flow, _) = flow()
        flow.show(title: "Erster", coordinate: munich) {
            try? await Task.sleep(for: .milliseconds(200))
            return "Spät"
        }
        flow.show(title: "Zweiter", coordinate: stuttgart) { nil }
        try? await Task.sleep(for: .milliseconds(400))
        #expect(flow.place?.title == "Zweiter" && flow.place?.subtitle == "")
    }

    @Test("a route action closes the sheets, and starts only once they have gone")
    func choosing() {
        let (flow, planner) = flow()
        flow.place = PlaceSelection(title: "München", coordinate: munich)
        flow.choose(.routeTo, for: RoutingFixtures.place("München", latitude: 48.1374, longitude: 11.5755))
        #expect(flow.place == nil && flow.station == nil && !planner.hasPlan)

        flow.sheetDismissed()
        #expect(planner.slots.count == 2 && planner.isPlannerPresented)
        // Dismissing again, or a sheet that had nothing waiting, does nothing.
        planner.clear()
        flow.sheetDismissed()
        #expect(!planner.hasPlan)
    }

    @Test("a station picked in the planner opens once the planner has gone, and only then")
    func stationFromPlanner() {
        let (flow, planner) = flow()
        #expect(flow.plannerDismissed() == nil)

        let station = Fixtures.station(name: "Fastned Ulm")
        planner.apply(.routeTo, to: RoutingFixtures.place("München"))
        flow.showStationAfterPlanner(station)
        #expect(!planner.isPlannerPresented && flow.station == nil)
        #expect(flow.plannerDismissed() == station && flow.station == station)
        #expect(flow.plannerDismissed() == nil)
    }

    // MARK: The map follows the planner

    @Test("the map shows the route's stations while there is a route, keeps itself while one is planned, and returns without")
    func following() async {
        let repository = StubStationRepository()
        let routes = FakeRouteProvider()
        let found = [RoutingFixtures.routeStation("Auf der Route", along: 50)]
        repository.alongRoute = .success(found)
        let planner = RoutePlannerViewModel(repository: repository, routes: routes, places: FakePlaces(), store: MemoryRoutingStore(),
                                            filter: { StationFilter() }, currentLocation: { CLLocationCoordinate2D(latitude: 48, longitude: 9) })
        let map = MapViewModel(repository: repository)

        map.follow(planner)
        #expect(map.mode == .viewport)

        planner.apply(.routeTo, to: RoutingFixtures.place("München", longitude: 11.5))
        await settle { planner.candidates.count == 1 }
        map.follow(planner)
        #expect(map.mode == .route && map.annotations.compactMap { $0.station?.displayName } == ["Auf der Route"])

        planner.setOptions(RouteOptions(avoidTolls: true, avoidMotorways: false))
        #expect(planner.phase == .planning)
        map.follow(planner)
        #expect(map.mode == .route)

        planner.clear()
        map.follow(planner)
        #expect(map.mode == .viewport)
    }
}
