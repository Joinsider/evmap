import CoreLocation
import Foundation

/// Extra driving minutes to charge at a station (ADR 0017), from MapKit travel times.
///
/// The detour is the time to leave the route a little before the station, reach it, and rejoin a little
/// after it, minus the time the route itself takes over that stretch. Leaving and rejoining at the
/// same point would count a motorway service area as an out-and-back; the route positions on either
/// side let a junction before and one after be used, as a driver would.
enum DetourEstimator {
    /// How far before and after the station's position the route is left and rejoined.
    static let marginMeters = 2_000.0

    /// The two route points the detour is measured between and what the route spends between them.
    struct Frame: Equatable {
        let leave: RouteCoordinate
        let rejoin: RouteCoordinate
        let routeTime: TimeInterval
    }

    static func frame(for candidate: RouteStation, on route: PlannedRoute) -> Frame? {
        let along = candidate.distanceAlongRouteKm * 1_000
        let start = max(along - marginMeters, 0)
        let end = min(along + marginMeters, route.polylineMeters)
        guard let leave = route.coordinate(atMeters: start), let rejoin = route.coordinate(atMeters: end) else { return nil }
        return Frame(leave: leave, rejoin: rejoin, routeTime: route.travelTime(fromMeters: start, toMeters: end))
    }

    /// Never negative: two independent ETAs can differ by noise, and "−1 minute" is not a detour.
    static func detourMinutes(toStation: TimeInterval, fromStation: TimeInterval, routeTime: TimeInterval) -> Double {
        max((toStation + fromStation - routeTime) / 60, 0)
    }
}
