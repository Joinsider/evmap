import Combine
import CoreLocation
import Foundation
import MapKit

@MainActor
final class MapViewModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var annotations: [StationAnnotation] = []
    /// The criteria the map is currently showing. Owned by the settings (`apply(_:)`) rather than
    /// editable here, so there is one source of truth for what the user asked to see.
    @Published private(set) var filter: StationFilter
    @Published private(set) var isLoading = false
    /// True when the last response filled the row limit, i.e. the viewport holds more stations
    /// than were returned. The map says so rather than pretending to be complete.
    @Published private(set) var isTruncated = false
    @Published var errorMessage: String?
    @Published private(set) var location = CLLocationCoordinate2D(latitude: 51.1657, longitude: 10.4515)
    /// Bumped on every accepted location fix so the map can recenter without
    /// needing `CLLocationCoordinate2D` to be `Equatable`.
    @Published private(set) var locationFixCount = 0

    private let repository: any ChargingStationRepository
    private let locationManager = CLLocationManager()

    private var stations: [Station] = []
    /// Viewport the current `stations` were fetched for — the yardstick `isCovered(by:)` uses to
    /// decide whether a camera change needs the network at all.
    private var loadedViewport: MapViewport?
    /// Live occupancy for what is on screen, by station id. Empty is the normal state — only a
    /// minority of stations is covered by any access point, and none is outside the countries that
    /// have a provider.
    private var liveAvailability: [UUID: StationLiveAvailability] = [:]
    private var liveTask: Task<Void, Never>?
    /// Viewport the map is showing now, which may be finer-grained than what was fetched; the
    /// clusterer works from this so grouping follows the zoom immediately.
    private var currentViewport: MapViewport?
    private var loadTask: Task<Void, Never>?

    /// `filter` is passed in rather than defaulted so the very first viewport query already carries
    /// the user's stored settings — otherwise the map would load the unfiltered world once and
    /// visibly correct itself.
    init(repository: any ChargingStationRepository, filter: StationFilter = StationFilter()) {
        self.repository = repository
        self.filter = filter
        super.init()
        locationManager.delegate = self
    }

    /// Adopts the filter the settings describe, refetching only when it actually differs from what
    /// is on screen — opening the settings screen and closing it again is not a reason to re-query.
    func apply(_ filter: StationFilter) {
        guard filter != self.filter else { return }
        self.filter = filter
        AppLogger.stations.notice("Filter changed to \(filter.logDescription) — reloading")
        reload()
    }

    func requestLocation() {
        AppLogger.location.info("Requesting location, authorization is \(Self.describe(locationManager.authorizationStatus))")
        locationManager.requestWhenInUseAuthorization()
        locationManager.startUpdatingLocation()
    }

    /// Entry point for `onMapCameraChange`: reclusters for the new zoom always, refetches only when
    /// the new viewport is no longer covered by what is already loaded.
    func cameraChanged(to region: MKCoordinateRegion) {
        let viewport = MapViewport(region: region)
        currentViewport = viewport
        recluster()

        if let loadedViewport, viewport.isCovered(by: loadedViewport) {
            AppLogger.stations.debug("Camera settled inside the loaded area — no refetch")
            return
        }
        load(viewport)
    }

    /// Refetches the area currently on screen, e.g. after the filter changed.
    func reload() {
        guard let currentViewport else { return }
        load(currentViewport)
    }

    private func load(_ viewport: MapViewport) {
        // A pan that outruns the network would otherwise leave two responses racing for `stations`,
        // and the slower one wins by arriving last.
        loadTask?.cancel()
        loadTask = Task { [weak self] in await self?.performLoad(viewport) }
    }

    private func performLoad(_ viewport: MapViewport) async {
        isLoading = true
        defer { isLoading = false }
        let query = viewport.effectiveFilter(filter)
        do {
            // The coordinate goes on the debug channel only; the persisted line
            // carries the filter, which is not personal data.
            AppLogger.stations.debug("Querying around \(AppLogger.coordinate(latitude: viewport.center.latitude, longitude: viewport.center.longitude))")
            let fetched = try await AppLogger.stations.measure("Viewport stations r=\(Int(viewport.radiusKm))km \(query.logDescription)") {
                try await repository.nearby(
                    latitude: viewport.center.latitude,
                    longitude: viewport.center.longitude,
                    radiusKm: viewport.radiusKm,
                    limit: MapViewport.maxStations,
                    filter: query
                )
            }
            guard !Task.isCancelled else { return }
            // Filters on the reported state, not on merely having one. Until ingestion populated
            // this field every station was statusless, so "has a status" happened to be a useful
            // proxy; now that BNetzA reports one for all 113k German stations it selects everything.
            stations = fetched.filter { !filter.availabilityOnly || ($0.availability?.isUsable ?? false) }
            isTruncated = fetched.count >= MapViewport.maxStations
            loadedViewport = viewport
            recluster()
            AppLogger.stations.notice("Showing \(self.stations.count) of \(fetched.count) stations in \(self.annotations.count) pins\(self.isTruncated ? " (limit reached)" : "")")
            loadLiveAvailability(for: viewport)
        } catch is CancellationError {
            AppLogger.stations.debug("Viewport query superseded by a newer one")
        } catch {
            guard !Task.isCancelled else { return }
            errorMessage = error.localizedDescription
        }
    }

    /// Fetches live occupancy for the area that was just loaded.
    ///
    /// Deliberately started after the stations arrived rather than in parallel with them: the pins
    /// are the map, and live status is a badge on top. Making the map wait for the least reliable
    /// of the two requests would trade the thing that always works for the thing that often has no
    /// answer. A failure is swallowed — no error banner for a badge that simply does not appear.
    ///
    /// Skipped entirely at overview zoom, where the backend answers nothing anyway and the pins
    /// stand for whole regions.
    private func loadLiveAvailability(for viewport: MapViewport) {
        liveTask?.cancel()
        guard !viewport.isOverview else {
            liveAvailability = [:]
            recluster()
            return
        }
        liveTask = Task { [weak self] in await self?.performLiveLoad(viewport) }
    }

    private func performLiveLoad(_ viewport: MapViewport) async {
        do {
            let bounds = viewport.bounds
            let fetched = try await repository.liveAvailability(
                latMin: bounds.latMin, lonMin: bounds.lonMin, latMax: bounds.latMax, lonMax: bounds.lonMax)
            guard !Task.isCancelled else { return }
            liveAvailability = Dictionary(fetched.map { ($0.stationID, $0) }, uniquingKeysWith: { first, _ in first })
            recluster()
            AppLogger.stations.debug("Live availability for \(self.liveAvailability.count) station(s) in view")
        } catch is CancellationError {
            AppLogger.stations.debug("Live availability query superseded by a newer one")
        } catch {
            // Not surfaced: a live source being down costs the badges, not the map.
            guard !Task.isCancelled else { return }
            liveAvailability = [:]
            recluster()
            AppLogger.stations.debug("No live availability for this viewport — \(AppLogger.describe(error))")
        }
    }

    private func recluster() {
        guard let currentViewport else {
            annotations = StationClusterer.cluster(stations, latitudeSpan: 0, liveAvailability: liveAvailability)
            return
        }
        annotations = StationClusterer.cluster(stations, latitudeSpan: currentViewport.latitudeSpan,
                                               liveAvailability: liveAvailability)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let coordinate = locations.last?.coordinate else { return }
        location = coordinate
        locationFixCount += 1
        // Persisting a stream of fixes would amount to a movement profile in the
        // device log, so only the fact of a fix is durable — the position is not.
        AppLogger.location.notice("Fix #\(self.locationFixCount) received (accuracy \(String(format: "%.0f m", locations.last?.horizontalAccuracy ?? -1)))")
        AppLogger.location.debug("Fix #\(self.locationFixCount) at \(AppLogger.coordinate(latitude: coordinate.latitude, longitude: coordinate.longitude))")
        // No fetch here: recentering moves the camera, and `cameraChanged(to:)` loads what it lands on.
        manager.stopUpdatingLocation()
    }

    func locationManager(_: CLLocationManager, didFailWithError error: Error) {
        // Not fatal: the map keeps its current viewport, which already has stations loaded for it.
        AppLogger.location.warning("Location fix failed, keeping last known position — \(AppLogger.describe(error))")
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
