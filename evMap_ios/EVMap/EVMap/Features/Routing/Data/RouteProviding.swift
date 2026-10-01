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

/// `MKDirections`, on device and free of charge. Accepted limits (ADR 0017): a request has only a start
/// and an end, so waypoints become legs the app joins; no elevation profile, no ferry avoidance.
@MainActor
final class MapKitRouteProvider: RouteProviding {
    func routes(through stops: [CLLocationCoordinate2D], options: RouteOptions) async throws -> [PlannedRoute] {
        guard stops.count >= 2 else { throw RouteError.needsTwoStops }
        if stops.count == 2 {
            let response = try await calculate(from: stops[0], to: stops[1], options: options, alternatives: true)
            let routes = response.routes.map { Self.planned([$0]) }
            guard !routes.isEmpty else { throw RouteError.noRoute }
            return routes
        }
        var legs = [MKRoute]()
        for (start, end) in zip(stops, stops.dropFirst()) {
            try Task.checkCancellation()
            guard let leg = try await calculate(from: start, to: end, options: options, alternatives: false).routes.first else {
                throw RouteError.noRoute
            }
            legs.append(leg)
        }
        return [Self.planned(legs)]
    }

    func travelTime(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D, options: RouteOptions) async throws -> TimeInterval {
        let directions = MKDirections(request: Self.request(from: from, to: to, options: options, alternatives: false))
        do {
            return try await directions.calculateETA().expectedTravelTime
        } catch {
            throw Self.translate(error)
        }
    }

    private func calculate(from start: CLLocationCoordinate2D, to end: CLLocationCoordinate2D, options: RouteOptions,
                           alternatives: Bool) async throws -> MKDirections.Response {
        do {
            return try await MKDirections(request: Self.request(from: start, to: end, options: options, alternatives: alternatives)).calculate()
        } catch {
            throw Self.translate(error)
        }
    }

    private static func request(from start: CLLocationCoordinate2D, to end: CLLocationCoordinate2D, options: RouteOptions,
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

    private static func translate(_ error: Error) -> Error {
        guard let mapKitError = error as? MKError else { return error }
        switch mapKitError.code {
        case .loadingThrottled: return RouteError.throttled
        case .directionsNotFound: return RouteError.noRoute
        default: return error
        }
    }

    /// Joins legs into one route. Consecutive legs share their junction vertex, which would otherwise be
    /// drawn and counted twice.
    private static func planned(_ legs: [MKRoute]) -> PlannedRoute {
        var coordinates = [RouteCoordinate]()
        for leg in legs {
            let polyline = leg.polyline
            let points = polyline.points()
            var leg = (0..<polyline.pointCount).map { RouteCoordinate(points[$0].coordinate) }
            if let last = coordinates.last, leg.first == last { leg.removeFirst() }
            coordinates += leg
        }
        return PlannedRoute(name: legs.count == 1 ? legs[0].name : "",
                            coordinates: coordinates,
                            legs: legs.map { RouteLeg(distance: $0.distance, travelTime: $0.expectedTravelTime) },
                            hasTolls: legs.contains { $0.hasTolls })
    }
}
