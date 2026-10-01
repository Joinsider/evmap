import CoreLocation
import Foundation
import MapKit
import Testing

@testable import EVMap

/// The routing values and codecs of ADR 0017, without a view model: simplification, positions along a
/// route, the share link, the handoff URLs, the detour arithmetic and the device store.
@Suite("Route planning values")
@MainActor
struct RoutePlanningTests {
    // MARK: Simplification

    @Test("a straight line shrinks to its two ends however many vertices it has")
    func straightLine() {
        let line = (0...1_000).map { RouteCoordinate(latitude: 48, longitude: 8 + Double($0) * 0.004) }
        let simplified = PolylineSimplifier.simplify(line, maxPoints: 400)
        #expect(simplified == [line.first!, line.last!])
    }

    @Test("a corner survives and the ends always do")
    func corner() {
        let line = [RouteCoordinate(latitude: 48, longitude: 8), RouteCoordinate(latitude: 48, longitude: 9),
                    RouteCoordinate(latitude: 48, longitude: 10), RouteCoordinate(latitude: 49, longitude: 10)]
        let simplified = PolylineSimplifier.simplify(line, toleranceMeters: 100)
        #expect(simplified == [line[0], line[2], line[3]])
    }

    @Test("a wiggly line is cut to the limit by widening the tolerance")
    func limit() {
        let line = (0..<5_000).map { RouteCoordinate(latitude: 48 + sin(Double($0) / 20) * 0.01, longitude: 8 + Double($0) * 0.001) }
        let simplified = PolylineSimplifier.simplify(line, maxPoints: 300)
        #expect(simplified.count <= 300)
        #expect(simplified.first == line.first && simplified.last == line.last)
        // Short input passes through untouched.
        #expect(PolylineSimplifier.simplify(Array(line.prefix(10)), maxPoints: 300).count == 10)
    }

    // MARK: Positions along a route

    @Test("positions and times along a route follow its length")
    func alongRoute() throws {
        let route = RoutingFixtures.route()
        #expect(abs(route.polylineMeters - 297_000) < 3_000)
        let middle = try #require(route.coordinate(atMeters: route.polylineMeters / 2))
        #expect(abs(middle.longitude - 10) < 0.05)
        #expect(route.coordinate(atMeters: -5) == route.coordinates.first)
        #expect(route.coordinate(atMeters: 1e9) == route.coordinates.last)
        #expect(abs(route.travelTime(fromMeters: 0, toMeters: route.polylineMeters / 2) - 5_400) < 1)
        #expect(route.distance == 297_000 && route.travelTime == 10_800)
    }

    @Test("a route survives being stored and keeps its positions")
    func routeCodable() throws {
        let route = RoutingFixtures.route(legs: 2)
        let decoded = try JSONDecoder().decode(PlannedRoute.self, from: JSONEncoder().encode(route))
        #expect(decoded == route)
        #expect(decoded.polylineMeters == route.polylineMeters)
        // The compact pair form, not keyed objects.
        let text = String(decoding: try JSONEncoder().encode(Array(route.coordinates.prefix(1))), as: UTF8.self)
        #expect(text == "[[48,8]]")
    }

    // MARK: Share link

    @Test("a route round-trips through its share link, names with awkward characters included")
    func shareRoundTrip() throws {
        let station = Fixtures.station(name: "Fastned, Süd & Ost = 100%")
        let stops = [RoutingFixtures.place("Zuhause, Stuttgart", latitude: 48.7758, longitude: 9.1829),
                     RouteWaypoint(station: station, dwellMinutes: 30),
                     RoutingFixtures.place("München", latitude: 48.1374, longitude: 11.5755)]
        let url = try #require(RouteShareLink.url(for: stops, options: RouteOptions(avoidTolls: true, avoidMotorways: false)))

        #expect(url.host == "evmap.joinside.de" && url.path == "/route")
        let parsed = try #require(RouteShareLink.parse(url))
        #expect(parsed.waypoints.map(\.name) == ["Zuhause, Stuttgart", "Fastned, Süd & Ost = 100%", "München"])
        #expect(parsed.waypoints[1].stationID == station.id && parsed.waypoints[1].kind == .station)
        #expect(parsed.waypoints[1].dwellMinutes == 30)
        #expect(abs(parsed.waypoints[0].latitude - 48.7758) < 1e-5)
        #expect(parsed.options == RouteOptions(avoidTolls: true, avoidMotorways: false))
    }

    @Test("the device position is never written into a link, and comes back as a position to resolve")
    func shareKeepsLocationPrivate() throws {
        let here = RouteWaypoint(kind: .currentLocation, name: "Mein Standort", latitude: 48.5, longitude: 9.5)
        let url = try #require(RouteShareLink.url(for: [here, RoutingFixtures.place("Ziel", latitude: 49, longitude: 10)], options: RouteOptions()))
        #expect(!url.absoluteString.contains("48.5"))
        let parsed = try #require(RouteShareLink.parse(url))
        #expect(parsed.waypoints.first?.kind == .currentLocation)
    }

    @Test("a link that is not ours, or damaged anywhere, is no route")
    func shareRejects() throws {
        func parse(_ text: String) -> RouteShareLink.Route? { RouteShareLink.parse(URL(string: text)!) }
        let good = "https://evmap.joinside.de/route?w=48.1,9.1,0,,A&w=49.1,10.1,0,,B"
        #expect(parse(good) != nil)
        #expect(parse("https://example.com/route?w=48.1,9.1,0,,A&w=49.1,10.1,0,,B") == nil)
        #expect(parse("https://evmap.joinside.de/other?w=48.1,9.1,0,,A&w=49.1,10.1,0,,B") == nil)
        #expect(parse("http://evmap.joinside.de/route?w=48.1,9.1,0,,A&w=49.1,10.1,0,,B") == nil)
        // One stop only, one stop broken, a coordinate out of range, a stay of a week, a bad station id.
        #expect(parse("https://evmap.joinside.de/route?w=48.1,9.1,0,,A") == nil)
        #expect(parse("https://evmap.joinside.de/route?w=48.1,9.1,0,,A&w=nonsense") == nil)
        #expect(parse("https://evmap.joinside.de/route?w=98.1,9.1,0,,A&w=49.1,10.1,0,,B") == nil)
        #expect(parse("https://evmap.joinside.de/route?w=48.1,9.1,10080,,A&w=49.1,10.1,0,,B") == nil)
        #expect(parse("https://evmap.joinside.de/route?w=48.1,9.1,0,not-a-uuid,A&w=49.1,10.1,0,,B") == nil)
        let tooMany = (0...RouteShareLink.maximumStops).map { "w=48.1,9.1,0,,S\($0)" }.joined(separator: "&")
        #expect(parse("https://evmap.joinside.de/route?\(tooMany)") == nil)
    }

    @Test("a long name is cut, and a route with fewer than two stops has no link")
    func shareBounds() throws {
        let long = String(repeating: "x", count: 500)
        let url = try #require(RouteShareLink.url(for: [RoutingFixtures.place(long), RoutingFixtures.place("B", longitude: 9)], options: RouteOptions()))
        let parsed = try #require(RouteShareLink.parse(url))
        #expect(parsed.waypoints[0].name.count == 80)
        #expect(RouteShareLink.url(for: [RoutingFixtures.place("A")], options: RouteOptions()) == nil)
    }

    // MARK: Handoff

    @Test("Google Maps gets the whole route: origin, waypoints, destination and what to avoid")
    func googleMapsURL() throws {
        let stops = [RoutingFixtures.place("A", latitude: 48.1, longitude: 8.1), RoutingFixtures.place("B", latitude: 48.2, longitude: 9.2),
                     RoutingFixtures.place("C", latitude: 48.3, longitude: 10.3), RoutingFixtures.place("D", latitude: 48.4, longitude: 11.4)]
        let url = try #require(RouteHandoff.googleMapsURL(for: stops, options: RouteOptions(avoidTolls: true, avoidMotorways: true)))
        let items = Dictionary(uniqueKeysWithValues: (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") })

        #expect(url.host == "www.google.com" && url.path() == "/maps/dir/", "\(url)")
        #expect(items["origin"] == "48.100000,8.100000" && items["destination"] == "48.400000,11.400000", "\(url)")
        #expect(items["waypoints"] == "48.200000,9.200000|48.300000,10.300000", "\(url)")
        #expect(items["travelmode"] == "driving" && items["avoid"] == "tolls|highways", "\(url)")
    }

    @Test("a start at the device position is left out, and more waypoints than Google takes mean leg by leg")
    func googleMapsEdges() throws {
        let here = RouteWaypoint(kind: .currentLocation, name: "Hier", latitude: 48, longitude: 8)
        let url = try #require(RouteHandoff.googleMapsURL(for: [here, RoutingFixtures.place("B", longitude: 9)], options: RouteOptions()))
        #expect(!(url.query ?? "").contains("origin"))

        let many = (0..<(RouteHandoff.googleMapsWaypointLimit + 3)).map { RoutingFixtures.place("S\($0)", longitude: 8 + Double($0) * 0.1) }
        #expect(RouteHandoff.googleMapsURL(for: many, options: RouteOptions()) == nil)
        #expect(RouteHandoff.googleMapsURL(for: [many[0]], options: RouteOptions()) == nil)
    }

    @Test("Apple Maps gets one leg at a time, and only legs that exist")
    func appleMapsLegs() {
        let stops = [RoutingFixtures.place("A"), RoutingFixtures.place("B", longitude: 9), RoutingFixtures.place("C", longitude: 10)]
        #expect(RouteHandoff.appleMapsItems(for: stops, leg: 0)?.count == 2)
        #expect(RouteHandoff.appleMapsItems(for: stops, leg: 1)?.last?.name == "C")
        #expect(RouteHandoff.appleMapsItems(for: stops, leg: 2) == nil)
    }

    // MARK: Detours

    @Test("a detour is the time out and back minus what the route takes there, and never negative")
    func detourArithmetic() {
        #expect(DetourEstimator.detourMinutes(toStation: 240, fromStation: 300, routeTime: 180) == 6)
        #expect(DetourEstimator.detourMinutes(toStation: 60, fromStation: 60, routeTime: 600) == 0)
    }

    @Test("the detour is measured between route points either side of the station, clamped to the route")
    func detourFrame() throws {
        let route = RoutingFixtures.route()
        let middle = try #require(DetourEstimator.frame(for: RoutingFixtures.routeStation("M", along: 148), on: route))
        #expect(abs(middle.leave.longitude - 9.97) < 0.05 && abs(middle.rejoin.longitude - 10.03) < 0.05)
        // 4 km of a 297 km route that takes three hours.
        #expect(abs(middle.routeTime - 4_000 / 297_000 * 10_800) < 60)

        let atStart = try #require(DetourEstimator.frame(for: RoutingFixtures.routeStation("S", along: 0.5), on: route))
        #expect(atStart.leave == route.coordinates.first)
    }

    @Test("a station is ranked by its exact detour, or by an estimate from how far off the road it lies")
    func ranking() {
        var candidate = RouteStopCandidate(routeStation: RoutingFixtures.routeStation("S", along: 10, off: 3))
        #expect(!candidate.isDetourExact)
        #expect(abs(candidate.rankingMinutes - 12) < 0.001)
        candidate.detourMinutes = 5
        #expect(candidate.isDetourExact && candidate.rankingMinutes == 5)
    }

    // MARK: The device store

    @Test("the file store round-trips the plan, routes and places, clears the plan, and survives garbage")
    func fileStore() throws {
        let directory = FileManager.default.temporaryDirectory.appending(component: "routing-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = FileRoutingStore(directory: directory)
        #expect(store.loadPlan() == nil && store.loadRoutes().isEmpty && store.loadPlaces().isEmpty)

        let plan = StoredRoutePlan(slots: [RouteSlot(waypoint: RoutingFixtures.place("A")), RouteSlot()], options: RouteOptions(avoidTolls: true),
                                   route: RoutingFixtures.route(), candidates: [RouteStopCandidate(routeStation: RoutingFixtures.routeStation("S", along: 5), detourMinutes: 4)],
                                   savedAt: Date(timeIntervalSince1970: 1_790_000_000))
        store.savePlan(plan)
        store.saveRoutes([SavedRoute(name: "Urlaub", waypoints: [RoutingFixtures.place("A")], options: RouteOptions())])
        store.savePlaces([SavedPlace(name: "Büro", latitude: 48, longitude: 9)])
        #expect(FileRoutingStore(directory: directory).loadPlan() == plan)
        #expect(store.loadRoutes().map(\.name) == ["Urlaub"] && store.loadPlaces().map(\.name) == ["Büro"])

        store.savePlan(nil)
        #expect(store.loadPlan() == nil)

        try Data("not json".utf8).write(to: directory.appending(component: "saved-places.json"))
        #expect(store.loadPlaces().isEmpty)
    }
}
