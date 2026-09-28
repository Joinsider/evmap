import Foundation
import MapKit

/// The map's visible rectangle, translated into the circular query the backend speaks.
///
/// `GET /api/v1/stations` takes a centre and a radius, so the viewport is covered by its
/// circumcircle — every station on screen is inside the circle, plus some that are not. Overfetching
/// the corners is cheaper than a second request after every pan.
struct MapViewport: Equatable {
    let center: CLLocationCoordinate2D
    /// Latitudinal extent of the visible rectangle, in degrees — the zoom level in the form the
    /// clusterer needs.
    let latitudeSpan: Double
    let longitudeSpan: Double

    /// Backend ceiling (`StationService.MAX_RADIUS_KM`). A viewport wider than this still queries,
    /// it just stops growing — past a continent the circle no longer describes anything actionable.
    static let maxRadiusKm = 1_000.0
    /// Row ceiling per request. Above this the map is drawing more pins than a screen can separate,
    /// and clustering is carrying the display anyway.
    static let maxStations = 600
    /// Viewports wider than this only ask for highpower chargers: at country scale the complete set
    /// is neither drawable nor useful, whereas the HPC backbone is exactly what a driver scans for.
    static let overviewLatitudeSpan = 1.2
    /// Floor applied in that case, in kW.
    static let overviewMinimumPowerKw = 100.0

    init(region: MKCoordinateRegion) {
        center = region.center
        latitudeSpan = region.span.latitudeDelta
        longitudeSpan = region.span.longitudeDelta
    }

    /// Radius covering the viewport's corners, clamped to what the backend accepts.
    var radiusKm: Double {
        let latitudeKm = latitudeSpan * 111.0
        let longitudeKm = longitudeSpan * 111.0 * max(cos(center.latitude * .pi / 180), 0.01)
        let diagonalKm = (latitudeKm * latitudeKm + longitudeKm * longitudeKm).squareRoot()
        return min(max(diagonalKm / 2, 1), Self.maxRadiusKm)
    }

    /// Whether this viewport is wide enough to trade completeness for the highpower network.
    var isOverview: Bool { latitudeSpan > Self.overviewLatitudeSpan }

    /// The visible rectangle, for the endpoints that take a bounding box rather than a circle.
    ///
    /// Live availability is asked this way because the national access points are: MobiData BW
    /// filters locations by box. Unlike `radiusKm` this does not overfetch the corners — a live
    /// status just outside the screen is of no use, and the box is the cheaper question.
    var bounds: (latMin: Double, lonMin: Double, latMax: Double, lonMax: Double) {
        (latMin: center.latitude - latitudeSpan / 2,
         lonMin: center.longitude - longitudeSpan / 2,
         latMax: center.latitude + latitudeSpan / 2,
         lonMax: center.longitude + longitudeSpan / 2)
    }

    /// The filter as sent for this viewport: at overview scale the user's power floor is raised to
    /// the highpower threshold, never lowered below what they asked for.
    func effectiveFilter(_ filter: StationFilter) -> StationFilter {
        guard isOverview else { return filter }
        var raised = filter
        raised.minimumPower = max(filter.minimumPower ?? 0, Self.overviewMinimumPowerKw)
        return raised
    }

    /// Whether stations fetched for `other` still cover this viewport well enough to skip a request.
    ///
    /// Guards against the request storm that `onMapCameraChange` would otherwise produce: MapKit
    /// settles the camera after every gesture, including ones that barely moved it. A viewport
    /// counts as covered when it did not drift far relative to what was fetched and did not change
    /// scale much — a scale change matters even without movement, because it changes both the
    /// cluster grid and, across the overview threshold, the query itself.
    func isCovered(by other: MapViewport) -> Bool {
        guard isOverview == other.isOverview else { return false }
        let scale = latitudeSpan / other.latitudeSpan
        guard 0.8...1.25 ~= scale else { return false }
        let driftKm = CLLocation(latitude: center.latitude, longitude: center.longitude)
            .distance(from: CLLocation(latitude: other.center.latitude, longitude: other.center.longitude)) / 1_000
        return driftKm < other.radiusKm * 0.25
    }

    static func == (lhs: MapViewport, rhs: MapViewport) -> Bool {
        lhs.center.latitude == rhs.center.latitude && lhs.center.longitude == rhs.center.longitude
            && lhs.latitudeSpan == rhs.latitudeSpan && lhs.longitudeSpan == rhs.longitudeSpan
    }
}
