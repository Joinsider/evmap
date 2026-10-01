import Foundation

/// A place the person named, to pick again as a waypoint (ADR 0017). Device-only: like the recent
/// searches it says where somebody lives or works, so it is never sent to the backend or logged.
struct SavedPlace: Identifiable, Hashable, Codable {
    let id: UUID
    var name: String
    var subtitle: String
    var latitude: Double
    var longitude: Double

    init(id: UUID = UUID(), name: String, subtitle: String = "", latitude: Double, longitude: Double) {
        self.id = id
        self.name = name
        self.subtitle = subtitle
        self.latitude = latitude
        self.longitude = longitude
    }

    var waypoint: RouteWaypoint { RouteWaypoint(name: name, subtitle: subtitle, latitude: latitude, longitude: longitude) }
}

/// A route the person named and kept, to plan again later: its stops and options, not its geometry,
/// because the road may have changed by then and MapKit computes it again anyway.
struct SavedRoute: Identifiable, Hashable, Codable {
    let id: UUID
    var name: String
    var waypoints: [RouteWaypoint]
    var options: RouteOptions
    let savedAt: Date

    init(id: UUID = UUID(), name: String, waypoints: [RouteWaypoint], options: RouteOptions, savedAt: Date = Date()) {
        self.id = id
        self.name = name
        self.waypoints = waypoints
        self.options = options
        self.savedAt = savedAt
    }
}

/// The plan that is open — stops, options, the computed route and the stations along it — kept so it can
/// be read without a network (ADR 0017: "planning again needs one").
struct StoredRoutePlan: Codable, Equatable {
    var slots: [RouteSlot]
    var options: RouteOptions
    var route: PlannedRoute?
    var candidates: [RouteStopCandidate]
    var savedAt: Date
}
