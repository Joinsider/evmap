import MapKit

/// Where the camera goes to show a whole route (ADR 0017).
enum RouteFraming {
    /// The route takes about 40 % of the screen's height and its middle sits a quarter of the way down: the
    /// upper half is what stays free above the planner sheet at half height.
    private static let routeShareOfHeight = 0.4
    private static let centerShift = 0.27
    private static let minimumSpan = 0.04

    static func region(fitting coordinates: [RouteCoordinate]) -> MKCoordinateRegion? {
        guard let first = coordinates.first else { return nil }
        var minLat = first.latitude
        var maxLat = first.latitude
        var minLon = first.longitude
        var maxLon = first.longitude
        for coordinate in coordinates {
            minLat = min(minLat, coordinate.latitude)
            maxLat = max(maxLat, coordinate.latitude)
            minLon = min(minLon, coordinate.longitude)
            maxLon = max(maxLon, coordinate.longitude)
        }
        let latSpan = max((maxLat - minLat) / routeShareOfHeight, minimumSpan)
        let lonSpan = max((maxLon - minLon) * 1.3, minimumSpan)
        let center = CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2 - latSpan * centerShift,
                                            longitude: (minLon + maxLon) / 2)
        return MKCoordinateRegion(center: center, span: MKCoordinateSpan(latitudeDelta: latSpan, longitudeDelta: lonSpan))
    }
}
