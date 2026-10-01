import CoreLocation
import Foundation

/// One stop of a planned route (ADR 0017): an address, a place, a charging station — or "where I am".
struct RouteWaypoint: Identifiable, Hashable, Codable {
    enum Kind: String, Codable {
        case place
        case station
        /// The device's position at the time the route is planned. Its coordinate is a snapshot, which is
        /// why it is never put into a share link: the person who opens the link has a position of their own.
        case currentLocation
    }

    var id: UUID
    var kind: Kind
    var name: String
    var subtitle: String
    var latitude: Double
    var longitude: Double
    /// The master station's id when this stop is a charging station.
    var stationID: UUID?
    /// How long the person stays here, in minutes. Zero means they drive through.
    var dwellMinutes: Int

    /// Longest stay that can be entered: a night, which is what a hotel waypoint means.
    static let maximumDwellMinutes = 24 * 60

    init(kind: Kind = .place, name: String, subtitle: String = "", latitude: Double,
         longitude: Double, stationID: UUID? = nil, dwellMinutes: Int = 0) {
        self.id = UUID()
        self.kind = kind
        self.name = name
        self.subtitle = subtitle
        self.latitude = latitude
        self.longitude = longitude
        self.stationID = stationID
        self.dwellMinutes = min(max(dwellMinutes, 0), Self.maximumDwellMinutes)
    }

    init(station: Station, dwellMinutes: Int = 0) {
        self.init(kind: .station, name: station.displayName, subtitle: station.address, latitude: station.latitude,
                  longitude: station.longitude, stationID: station.id, dwellMinutes: dwellMinutes)
    }

    init(place: SearchedPlace) {
        self.init(name: place.title, subtitle: place.subtitle, latitude: place.latitude, longitude: place.longitude)
    }

    var coordinate: CLLocationCoordinate2D { .init(latitude: latitude, longitude: longitude) }
    var routeCoordinate: RouteCoordinate { .init(latitude: latitude, longitude: longitude) }
}

/// One row of the planner: a stop, or the empty place where one still has to be chosen.
///
/// Rows rather than bare waypoints because "route from here" has a start and no destination yet, and
/// the planner has to show that gap and let the person fill it — and reorder rows, gaps included.
struct RouteSlot: Identifiable, Hashable, Codable {
    let id: UUID
    var waypoint: RouteWaypoint?

    init(id: UUID = UUID(), waypoint: RouteWaypoint? = nil) {
        self.id = id
        self.waypoint = waypoint
    }
}

/// What the person asked of a place: begin a route there, end one there, or pass through it.
enum RouteIntent: Equatable {
    case routeFrom
    case routeTo
    case addStop
}

/// The ways to drive a route, as far as MapKit can be told (ADR 0017: no ferry avoidance).
struct RouteOptions: Hashable, Codable {
    var avoidTolls = false
    var avoidMotorways = false
}
