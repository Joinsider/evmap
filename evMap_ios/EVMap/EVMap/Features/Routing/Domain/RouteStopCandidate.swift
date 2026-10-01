import Foundation

/// A station in the corridor of a route, as `POST /api/v1/stations/along-route` answers it.
struct RouteStation: Codable, Identifiable, Hashable {
    let station: Station
    /// Kilometres from the start to the point of the route nearest the station.
    let distanceAlongRouteKm: Double
    /// Straight-line distance from the route: the pre-filter the ranking uses until the real detour is known.
    let distanceToRouteKm: Double

    var id: UUID { station.id }
}

/// A station as the planner lists it: where it is on the route and what it costs to get there.
struct RouteStopCandidate: Codable, Identifiable, Hashable {
    let routeStation: RouteStation
    /// Extra driving minutes to charge here, from MapKit routes; `nil` until computed for this one.
    var detourMinutes: Double?

    var id: UUID { routeStation.id }
    var station: Station { routeStation.station }

    /// The figure the list is ranked by. A station without a computed detour is estimated from how far
    /// it lies off the route — out and back at about 30 km/h on side roads — and shown as an estimate.
    var rankingMinutes: Double { detourMinutes ?? Self.estimatedMinutes(offRouteKm: routeStation.distanceToRouteKm) }
    var isDetourExact: Bool { detourMinutes != nil }

    static func estimatedMinutes(offRouteKm: Double) -> Double { offRouteKm * 2 / 30 * 60 }
}

/// The order the candidate list is shown in.
enum StationSort: String, CaseIterable, Identifiable {
    /// Least extra driving first (ADR 0017).
    case detour
    /// As they come up while driving.
    case alongRoute

    var id: String { rawValue }
}
