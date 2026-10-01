import CoreLocation
import Foundation
import MapKit
import Testing

@testable import EVMap

/// The map's two modes (ADR 0017): the viewport's stations, or the stations along a route, which must
/// not overwrite each other.
@Suite("Map route mode", .serialized)
@MainActor
struct MapRouteModeTests {
    private let region = MKCoordinateRegionFixture.stuttgart

    private func settle(_ condition: () -> Bool) async {
        for _ in 0..<1_000 {
            if condition() { return }
            try? await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("Timed out")
    }

    @Test("on a route the map shows its stations, loads nothing on camera changes, and the viewport returns afterwards")
    func routeMode() async {
        let repository = StubStationRepository()
        let viewportStation = Fixtures.station(name: "Viewport")
        let routeStation = Fixtures.station(name: "Route", latitude: 49, longitude: 10)
        repository.stations = .success([viewportStation])
        let map = MapViewModel(repository: repository)

        map.cameraChanged(to: region)
        await settle { map.annotations.count == 1 }
        #expect(map.mode == .viewport && repository.nearbyFilters.count == 1)

        map.showRoute(stations: [routeStation])
        #expect(map.mode == .route && map.annotations.compactMap { $0.station?.displayName } == ["Route"])

        // Panning somewhere new asks the backend for nothing, and a reload is not a viewport query.
        map.cameraChanged(to: MKCoordinateRegionFixture.berlin)
        map.reload()
        try? await Task.sleep(for: .milliseconds(100))
        #expect(repository.nearbyFilters.count == 1 && map.annotations.compactMap { $0.station?.displayName } == ["Route"])

        repository.stations = .success([viewportStation])
        map.clearRoute()
        await settle { map.mode == .viewport && repository.nearbyFilters.count == 2 }
        await settle { map.annotations.compactMap { $0.station?.displayName } == ["Viewport"] }
    }

    @Test("a filter change on a route waits for the planner instead of reloading the viewport")
    func filterOnRoute() {
        let repository = StubStationRepository()
        let map = MapViewModel(repository: repository)
        map.cameraChanged(to: region)
        map.showRoute(stations: [])
        var filter = StationFilter()
        filter.minimumPower = 150
        let queries = repository.nearbyFilters.count

        map.apply(filter)

        #expect(map.filter == filter && repository.nearbyFilters.count == queries)
    }

    @Test("clearing when there is no route changes nothing")
    func clearWithoutRoute() {
        let repository = StubStationRepository()
        let map = MapViewModel(repository: repository)
        map.clearRoute()
        #expect(map.mode == .viewport && repository.nearbyFilters.isEmpty)
    }
}

enum MKCoordinateRegionFixture {
    static let stuttgart = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 48.7758, longitude: 9.1829),
                                              latitudinalMeters: 5_000, longitudinalMeters: 5_000)
    static let berlin = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 52.52, longitude: 13.405),
                                           latitudinalMeters: 5_000, longitudinalMeters: 5_000)
}
