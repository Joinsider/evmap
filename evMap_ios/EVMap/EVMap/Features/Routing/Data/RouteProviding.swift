import CoreLocation
import Foundation
import MapKit

/// Computes routes, behind a protocol for the same reason `AddressSearchProviding` is one (ADR 0017):
/// the planner must not know who answers. MapKit does today; a self-hosted Valhalla could take over for
/// turn-by-turn without touching `Presentation`.
@MainActor
protocol RouteProviding: AnyObject {
    /// Routes through `stops` in order. With exactly two stops several alternatives may come back, best
    /// first; with more, each leg is routed on its own and joined, which yields the one route.
    func routes(through stops: [CLLocationCoordinate2D], options: RouteOptions) async throws -> [PlannedRoute]

    /// Driving time only, for detours. Cheaper than a route: no geometry comes back.
    func travelTime(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D, options: RouteOptions) async throws -> TimeInterval
}

enum RouteError: LocalizedError, Equatable {
    /// Fewer than two stops: there is nothing to route yet.
    case needsTwoStops
    /// MapKit has no drivable connection between two of the stops.
    case noRoute
    /// MapKit limits how often a device may ask. It is a pause, not a failure of the trip.
    case throttled

    var errorDescription: String? {
        switch self {
        case .needsTwoStops: String(localized: "route.error.needsTwoStops")
        case .noRoute: String(localized: "route.error.noRoute")
        case .throttled: String(localized: "route.error.throttled")
        }
    }
}

/// One route as the directions service answered it, free of MapKit types so the joining of legs can be tested
/// without a network.
struct DirectionsRoute: Equatable {
    let name: String
    let coordinates: [RouteCoordinate]
    let distance: Double
    let travelTime: TimeInterval
    let hasTolls: Bool
}

/// The one request MapKit's directions answer: a start, an end and the options. The thinnest layer over
/// the system framework, behind a protocol so that everything above it runs in unit tests.
@MainActor
protocol DirectionsServing: AnyObject {
    func directions(from start: CLLocationCoordinate2D, to end: CLLocationCoordinate2D, options: RouteOptions,
                    alternatives: Bool) async throws -> [DirectionsRoute]
    func travelTime(from start: CLLocationCoordinate2D, to end: CLLocationCoordinate2D, options: RouteOptions) async throws -> TimeInterval
}

/// Computes routes through a `DirectionsServing` (MapKit's `MKDirections` in the app, on device and free of
/// charge). Accepted limits (ADR 0017): a request has only a start and an end, so waypoints become legs the
/// app joins; no elevation profile, no ferry avoidance.
@MainActor
final class MapKitRouteProvider: RouteProviding {
    private let directions: any DirectionsServing

    /// `nil` means MapKit; the default cannot be an argument, because those are evaluated outside the actor.
    init(directions: (any DirectionsServing)? = nil) {
        self.directions = directions ?? MapKitDirections()
    }

    func routes(through stops: [CLLocationCoordinate2D], options: RouteOptions) async throws -> [PlannedRoute] {
        guard stops.count >= 2 else { throw RouteError.needsTwoStops }
        if stops.count == 2 {
            let found = try await directions.directions(from: stops[0], to: stops[1], options: options, alternatives: true)
            guard !found.isEmpty else { throw RouteError.noRoute }
            return found.map { Self.planned([$0]) }
        }
        var legs = [DirectionsRoute]()
        for (start, end) in zip(stops, stops.dropFirst()) {
            try Task.checkCancellation()
            guard let leg = try await directions.directions(from: start, to: end, options: options, alternatives: false).first else {
                throw RouteError.noRoute
            }
            legs.append(leg)
        }
        return [Self.planned(legs)]
    }

    func travelTime(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D, options: RouteOptions) async throws -> TimeInterval {
        try await directions.travelTime(from: from, to: to, options: options)
    }

    /// Joins legs into one route. Consecutive legs share their junction vertex, which would otherwise be
    /// drawn and counted twice.
    static func planned(_ legs: [DirectionsRoute]) -> PlannedRoute {
        var coordinates = [RouteCoordinate]()
        for leg in legs {
            var vertices = leg.coordinates
            if let last = coordinates.last, vertices.first == last { vertices.removeFirst() }
            coordinates += vertices
        }
        return PlannedRoute(name: legs.count == 1 ? legs[0].name : "",
                            coordinates: coordinates,
                            legs: legs.map { RouteLeg(distance: $0.distance, travelTime: $0.travelTime) },
                            hasTolls: legs.contains { $0.hasTolls })
    }
}

extension RouteError {
    /// What MapKit's errors mean for a plan: throttled is a pause, an unknown connection is no route.
    /// Anything else is passed on as it is.
    static func translating(_ error: Error) -> Error {
        guard let mapKitError = error as? MKError else { return error }
        switch mapKitError.code {
        case .loadingThrottled: return RouteError.throttled
        case .directionsNotFound: return RouteError.noRoute
        default: return error
        }
    }
}

/// `MKDirections` itself. Not unit-tested: it needs MapKit's servers.
@MainActor
final class MapKitDirections: DirectionsServing {
    func directions(from start: CLLocationCoordinate2D, to end: CLLocationCoordinate2D, options: RouteOptions,
                    alternatives: Bool) async throws -> [DirectionsRoute] {
        do {
            let response = try await MKDirections(request: Self.request(from: start, to: end, options: options, alternatives: alternatives)).calculate()
            return response.routes.map { route in
                let polyline = route.polyline
                let points = polyline.points()
                return DirectionsRoute(name: route.name, coordinates: (0..<polyline.pointCount).map { RouteCoordinate(points[$0].coordinate) },
                                       distance: route.distance, travelTime: route.expectedTravelTime, hasTolls: route.hasTolls)
            }
        } catch {
            throw RouteError.translating(error)
        }
    }

    func travelTime(from start: CLLocationCoordinate2D, to end: CLLocationCoordinate2D, options: RouteOptions) async throws -> TimeInterval {
        do {
            return try await MKDirections(request: Self.request(from: start, to: end, options: options, alternatives: false)).calculateETA().expectedTravelTime
        } catch {
            throw RouteError.translating(error)
        }
    }

    static func request(from start: CLLocationCoordinate2D, to end: CLLocationCoordinate2D, options: RouteOptions,
                        alternatives: Bool) -> MKDirections.Request {
        let request = MKDirections.Request()
        request.source = MKMapItem(location: CLLocation(latitude: start.latitude, longitude: start.longitude), address: nil)
        request.destination = MKMapItem(location: CLLocation(latitude: end.latitude, longitude: end.longitude), address: nil)
        request.transportType = .automobile
        request.requestsAlternateRoutes = alternatives
        request.tollPreference = options.avoidTolls ? .avoid : .any
        request.highwayPreference = options.avoidMotorways ? .avoid : .any
        return request
    }
}
