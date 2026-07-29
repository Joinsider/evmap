import CoreLocation
import MapKit
import Testing

@testable import EVMap

@Suite("Map viewport")
struct MapViewportTests {
    private func viewport(latitude: Double = 52.52, longitude: Double = 13.405, spanDegrees: Double) -> MapViewport {
        MapViewport(region: MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
            span: MKCoordinateSpan(latitudeDelta: spanDegrees, longitudeDelta: spanDegrees)
        ))
    }

    @Test("Radius covers the viewport's corners")
    func radiusCoversCorners() {
        // 0.2° of latitude is ~22 km; the diagonal half of a 22 km box is ~14 km at Berlin's latitude.
        let radius = viewport(spanDegrees: 0.2).radiusKm
        #expect(radius > 11)
        #expect(radius < 18)
    }

    @Test("Radius never exceeds what the backend accepts")
    func radiusIsClamped() {
        #expect(viewport(spanDegrees: 90).radiusKm == MapViewport.maxRadiusKm)
    }

    @Test("Radius never falls below the backend's minimum of one kilometre")
    func radiusHasFloor() {
        #expect(viewport(spanDegrees: 0.0001).radiusKm >= 1)
    }

    @Test("Wide viewports raise the power floor to the highpower threshold")
    func overviewRaisesPowerFloor() {
        let filter = viewport(spanDegrees: 4).effectiveFilter(StationFilter())
        #expect(filter.minimumPower == MapViewport.overviewMinimumPowerKw)
    }

    @Test("The overview floor never lowers a stricter user filter")
    func overviewKeepsStricterFilter() {
        var strict = StationFilter()
        strict.minimumPower = 300
        #expect(viewport(spanDegrees: 4).effectiveFilter(strict).minimumPower == 300)
    }

    @Test("Close viewports leave the filter untouched")
    func localKeepsFilter() {
        #expect(viewport(spanDegrees: 0.1).effectiveFilter(StationFilter()).minimumPower == nil)
    }

    @Test("A nudge inside the loaded area does not warrant a refetch")
    func smallPanIsCovered() {
        let loaded = viewport(spanDegrees: 0.2)
        let nudged = viewport(latitude: 52.525, longitude: 13.41, spanDegrees: 0.2)
        #expect(nudged.isCovered(by: loaded))
    }

    @Test("Panning past the loaded area warrants a refetch")
    func largePanIsNotCovered() {
        let loaded = viewport(spanDegrees: 0.2)
        let moved = viewport(latitude: 53.5, longitude: 13.405, spanDegrees: 0.2)
        #expect(!moved.isCovered(by: loaded))
    }

    @Test("Zooming changes the query even when the centre stays put")
    func zoomIsNotCovered() {
        let loaded = viewport(spanDegrees: 0.2)
        #expect(!viewport(spanDegrees: 0.05).isCovered(by: loaded))
        #expect(!viewport(spanDegrees: 0.8).isCovered(by: loaded))
    }

    @Test("Crossing the overview threshold always refetches, since the power floor changes")
    func overviewBoundaryIsNotCovered() {
        let below = viewport(spanDegrees: MapViewport.overviewLatitudeSpan * 0.95)
        let above = viewport(spanDegrees: MapViewport.overviewLatitudeSpan * 1.05)
        #expect(!above.isCovered(by: below))
        #expect(!below.isCovered(by: above))
    }
}
