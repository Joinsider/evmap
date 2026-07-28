import Combine
import CoreLocation
import Foundation

@MainActor
final class MapViewModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var stations: [Station] = []
    @Published var filter = StationFilter()
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?
    @Published private(set) var location = CLLocationCoordinate2D(latitude: 51.1657, longitude: 10.4515)
    /// Bumped on every accepted location fix so the map can recenter without
    /// needing `CLLocationCoordinate2D` to be `Equatable`.
    @Published private(set) var locationFixCount = 0

    private let repository: any ChargingStationRepository
    private let locationManager = CLLocationManager()

    init(repository: any ChargingStationRepository) {
        self.repository = repository
        super.init()
        locationManager.delegate = self
    }

    func requestLocation() {
        AppLogger.location.info("Requesting location, authorization is \(Self.describe(locationManager.authorizationStatus))")
        locationManager.requestWhenInUseAuthorization()
        locationManager.startUpdatingLocation()
    }

    func loadStations() async {
        isLoading = true
        defer { isLoading = false }
        do {
            // The coordinate goes on the debug channel only; the persisted line
            // carries the filter, which is not personal data.
            AppLogger.stations.debug("Querying around \(AppLogger.coordinate(latitude: location.latitude, longitude: location.longitude))")
            let fetched = try await AppLogger.stations.measure("Nearby stations \(filter.logDescription)") {
                try await repository.nearby(latitude: location.latitude, longitude: location.longitude, filter: filter)
            }
            // Filters on the reported state, not on merely having one. Until ingestion populated
            // this field every station was statusless, so "has a status" happened to be a useful
            // proxy; now that BNetzA reports one for all 113k German stations it selects everything.
            stations = fetched.filter { !filter.availabilityOnly || ($0.availability?.isUsable ?? false) }
            AppLogger.stations.notice("Showing \(stations.count) of \(fetched.count) stations")
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let coordinate = locations.last?.coordinate else { return }
        location = coordinate
        locationFixCount += 1
        // Persisting a stream of fixes would amount to a movement profile in the
        // device log, so only the fact of a fix is durable — the position is not.
        AppLogger.location.notice("Fix #\(locationFixCount) received (accuracy \(String(format: "%.0f m", locations.last?.horizontalAccuracy ?? -1)))")
        AppLogger.location.debug("Fix #\(locationFixCount) at \(AppLogger.coordinate(latitude: coordinate.latitude, longitude: coordinate.longitude))")
        Task { await loadStations() }
        manager.stopUpdatingLocation()
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Not fatal: we keep the last known (or default) coordinate and load anyway.
        AppLogger.location.warning("Location fix failed, keeping last known position — \(AppLogger.describe(error))")
        Task { await loadStations() }
    }

    private static func describe(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .notDetermined: "notDetermined"
        case .restricted: "restricted"
        case .denied: "denied"
        case .authorizedAlways: "authorizedAlways"
        case .authorizedWhenInUse: "authorizedWhenInUse"
        @unknown default: "unknown(\(status.rawValue))"
        }
    }
}
