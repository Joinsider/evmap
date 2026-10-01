import CoreLocation
import Foundation

@testable import EVMap

/// A routing seam whose answers the test sets, recording what it was asked.
@MainActor
final class FakeRouteProvider: RouteProviding {
    var result: Result<[PlannedRoute], Error> = .success([RoutingFixtures.route()])
    /// Seconds for any `travelTime` request, or an error for all of them.
    var travelTimes: Result<TimeInterval, Error> = .success(300)
    private(set) var routeRequests: [(stops: [CLLocationCoordinate2D], options: RouteOptions)] = []
    private(set) var travelTimeRequests = 0
    /// After this many travel-time answers every further one is throttled; `nil` never throttles.
    var throttleAfter: Int?

    func routes(through stops: [CLLocationCoordinate2D], options: RouteOptions) async throws -> [PlannedRoute] {
        routeRequests.append((stops, options))
        return try result.get()
    }

    func travelTime(from _: CLLocationCoordinate2D, to _: CLLocationCoordinate2D, options _: RouteOptions) async throws -> TimeInterval {
        travelTimeRequests += 1
        if let throttleAfter, travelTimeRequests > throttleAfter { throw RouteError.throttled }
        return try travelTimes.get()
    }
}

@MainActor
final class FakePlaces: NearbyPlacesProviding {
    var found: [BreakSuggestion] = []
    private(set) var centers: [CLLocationCoordinate2D] = []

    func places(near center: CLLocationCoordinate2D, radiusMeters _: Double, categories _: [BreakSuggestion.Category]) async throws -> [BreakSuggestion] {
        centers.append(center)
        return found
    }
}

final class MemoryRoutingStore: RoutingStoring {
    var plan: StoredRoutePlan?
    var routes: [SavedRoute] = []
    var places: [SavedPlace] = []
    func loadPlan() -> StoredRoutePlan? { plan }
    func savePlan(_ plan: StoredRoutePlan?) { self.plan = plan }
    func loadRoutes() -> [SavedRoute] { routes }
    func saveRoutes(_ routes: [SavedRoute]) { self.routes = routes }
    func loadPlaces() -> [SavedPlace] { places }
    func savePlaces(_ places: [SavedPlace]) { self.places = places }
}

enum RoutingFixtures {
    /// Due east along 48° N from 8° to 12° E, about 297 km, one vertex every 0.5°, covered in three hours.
    static func route(legs: Int = 1, hours: Double = 3) -> PlannedRoute {
        let coordinates = stride(from: 8.0, through: 12.0, by: 0.5).map { RouteCoordinate(latitude: 48, longitude: $0) }
        let leg = RouteLeg(distance: 297_000 / Double(legs), travelTime: hours * 3_600 / Double(legs))
        return PlannedRoute(name: "A8", coordinates: coordinates, legs: Array(repeating: leg, count: legs))
    }

    static func place(_ name: String, latitude: Double = 48, longitude: Double = 8) -> RouteWaypoint {
        RouteWaypoint(name: name, latitude: latitude, longitude: longitude)
    }

    static func routeStation(_ name: String, along: Double, off: Double = 1, id: UUID = UUID()) -> RouteStation {
        RouteStation(station: Fixtures.station(id: id, name: name, latitude: 48, longitude: 8 + along / 74.5),
                     distanceAlongRouteKm: along, distanceToRouteKm: off)
    }
}
