import MapKit

/// Where the camera goes for the few moves the map makes by itself.
enum MapCamera {
    /// A neighbourhood around a place: what the map shows after a search hit, a picked station or a favorite.
    static func region(around coordinate: CLLocationCoordinate2D, meters: Double = 4_000) -> MKCoordinateRegion {
        MKCoordinateRegion(center: coordinate, latitudinalMeters: meters, longitudinalMeters: meters)
    }

    /// A quarter of the current span, centred on a cluster — enough to break the grid cell apart without
    /// losing the person's place. Never closer than a street (0.002°). Without a known span, 0.4° is assumed.
    static func region(zoomingInto coordinate: CLLocationCoordinate2D, from span: MKCoordinateSpan?) -> MKCoordinateRegion {
        let current = span ?? MKCoordinateSpan(latitudeDelta: 0.4, longitudeDelta: 0.4)
        return MKCoordinateRegion(center: coordinate,
                                  span: MKCoordinateSpan(latitudeDelta: max(current.latitudeDelta / 4, 0.002),
                                                         longitudeDelta: max(current.longitudeDelta / 4, 0.002)))
    }
}
