import CoreLocation
import Foundation

/// A point on a route. Its own type rather than `CLLocationCoordinate2D` because a route holds tens of
/// thousands of them and has to be stored and compared: the coordinate struct is neither `Hashable`
/// nor `Codable`, and a keyed encoding of 20 000 points would be three times the size of this one.
struct RouteCoordinate: Hashable, Codable {
    let latitude: Double
    let longitude: Double

    init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }

    init(_ coordinate: CLLocationCoordinate2D) {
        self.init(latitude: coordinate.latitude, longitude: coordinate.longitude)
    }

    var coordinate: CLLocationCoordinate2D { .init(latitude: latitude, longitude: longitude) }

    /// Stored as `[latitude, longitude]`. The request to the backend is built from its own type
    /// (`AlongRouteRequest`), so this compact form never reaches the wire.
    init(from decoder: Decoder) throws {
        var values = try decoder.unkeyedContainer()
        latitude = try values.decode(Double.self)
        longitude = try values.decode(Double.self)
    }

    func encode(to encoder: Encoder) throws {
        var values = encoder.unkeyedContainer()
        try values.encode(latitude)
        try values.encode(longitude)
    }

    /// Great-circle distance in metres. Haversine instead of `CLLocation.distance(from:)`: this runs
    /// over every vertex of a route, and allocating two `CLLocation`s per step is the slow part of it.
    func meters(to other: RouteCoordinate) -> Double {
        let radius = 6_371_000.0
        let lat1 = latitude * .pi / 180, lat2 = other.latitude * .pi / 180
        let dLat = lat2 - lat1
        let dLon = (other.longitude - longitude) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * radius * asin(min(1, sqrt(a)))
    }
}
