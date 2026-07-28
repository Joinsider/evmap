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

    private let repository: any ChargingStationRepository
    private let locationManager = CLLocationManager()

    init(repository: any ChargingStationRepository) {
        self.repository = repository
        super.init()
        locationManager.delegate = self
    }

    func requestLocation() {
        locationManager.requestWhenInUseAuthorization()
        locationManager.startUpdatingLocation()
    }

    func loadStations() async {
        isLoading = true
        defer { isLoading = false }
        do {
            stations = try await repository.nearby(latitude: location.latitude, longitude: location.longitude, filter: filter)
                .filter { !filter.availabilityOnly || !($0.availabilityStatus?.isEmpty ?? true) }
        } catch { errorMessage = error.localizedDescription }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let coordinate = locations.last?.coordinate else { return }
        location = coordinate
        Task { await loadStations() }
        manager.stopUpdatingLocation()
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { await loadStations() }
    }
}
