import Combine
import CoreLocation
import Foundation
import SwiftUI

/// The manual route planner (ADR 0017, stage 1): stops, options, the route MapKit computes through them,
/// and the charging stations along it ranked by detour.
///
/// It owns the plan and keeps it on the device after every change, so a restart — or a tunnel — finds it
/// again. It does not draw anything: the map reads `mapRevision` and the published values and draws.
@MainActor
final class RoutePlannerViewModel: ObservableObject {
    enum Phase: Equatable {
        case idle
        case planning
        case planned
        case failed(String)
    }

    /// Points kept of the route for the corridor query (ADR 0017: "a few hundred").
    static let routePointLimit = 400
    static let corridorKm = 5.0
    static let candidateLimit = 200
    /// How many candidates get an exact detour from MapKit; the rest are ranked by an estimate.
    static let exactDetourCount = 10
    /// Detours are asked for this many stations at a time, well inside MapKit's request limit.
    private static let detourBatchSize = 3
    /// A charging stop is a stop for about this long, until the person says otherwise.
    static let chargingDwellMinutes = 30
    /// Driving time between break searches, and how many places are searched at most.
    static let breakIntervalSeconds: TimeInterval = 2 * 3_600
    static let breakSearchLimit = 4
    static let breakRadiusMeters = 3_000.0
    static let savedRouteLimit = 50
    static let savedPlaceLimit = 50

    @Published private(set) var slots: [RouteSlot] = []
    @Published private(set) var options = RouteOptions()
    @Published private(set) var alternatives: [PlannedRoute] = []
    @Published private(set) var selectedIndex = 0
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var candidates: [RouteStopCandidate] = []
    @Published private(set) var isLoadingStations = false
    @Published private(set) var stationsFailure: String?
    @Published private(set) var detoursThrottled = false
    @Published private(set) var breaks: [BreakSuggestion] = []
    @Published private(set) var isLoadingBreaks = false
    @Published private(set) var savedPlaces: [SavedPlace]
    @Published private(set) var savedRoutes: [SavedRoute]
    @Published var stationSort: StationSort = .detour
    /// Bumped whenever what the map draws changes — the route, its stops, the stations. The map redraws on it.
    @Published private(set) var mapRevision = 0
    /// Bumped when the camera should frame the route: a new route, not every redraw.
    @Published private(set) var fitRevision = 0
    @Published var isPlannerPresented = false
    @Published var errorMessage: String?

    private let repository: any ChargingStationRepository
    private let routes: any RouteProviding
    private let places: any NearbyPlacesProviding
    private let store: any RoutingStoring
    private let filter: () -> StationFilter
    private let currentLocation: () -> CLLocationCoordinate2D?
    private var planTask: Task<Void, Never>?
    private var stationsTask: Task<Void, Never>?
    private var breaksTask: Task<Void, Never>?

    init(repository: any ChargingStationRepository, routes: any RouteProviding, places: any NearbyPlacesProviding,
         store: any RoutingStoring, filter: @escaping () -> StationFilter,
         currentLocation: @escaping () -> CLLocationCoordinate2D? = { nil }) {
        self.repository = repository
        self.routes = routes
        self.places = places
        self.store = store
        self.filter = filter
        self.currentLocation = currentLocation
        savedPlaces = store.loadPlaces()
        savedRoutes = store.loadRoutes()
        if let plan = store.loadPlan() {
            // The offline copy: readable as it was, replanned only when something is changed.
            slots = plan.slots
            options = plan.options
            alternatives = plan.route.map { [$0] } ?? []
            candidates = plan.candidates
            phase = alternatives.isEmpty ? .idle : .planned
            mapRevision += 1
        }
    }

    // MARK: Reading the plan

    var hasPlan: Bool { !slots.isEmpty }
    var stops: [RouteWaypoint] { slots.compactMap(\.waypoint) }
    var isPlannable: Bool { slots.count >= 2 && slots.allSatisfy { $0.waypoint != nil } }
    var route: PlannedRoute? { alternatives.indices.contains(selectedIndex) ? alternatives[selectedIndex] : nil }
    var canAddStop: Bool { slots.count < RouteShareLink.maximumStops }

    var sortedCandidates: [RouteStopCandidate] {
        switch stationSort {
        case .detour: candidates.sorted { ($0.rankingMinutes, $0.routeStation.distanceAlongRouteKm) < ($1.rankingMinutes, $1.routeStation.distanceAlongRouteKm) }
        case .alongRoute: candidates.sorted { $0.routeStation.distanceAlongRouteKm < $1.routeStation.distanceAlongRouteKm }
        }
    }

    /// Time spent standing still along the way.
    var totalDwellMinutes: Int { slots.reduce(0) { $0 + ($1.waypoint?.dwellMinutes ?? 0) } }
    /// Driving plus stops, which is when the person gets there.
    var totalDuration: TimeInterval? { route.map { $0.travelTime + TimeInterval(totalDwellMinutes * 60) } }

    /// Seconds from departure to arriving at slot `index`: the legs before it plus every stay before it.
    /// `nil` while there is no route that matches the stops.
    func arrivalOffset(atSlot index: Int) -> TimeInterval? {
        guard let route, route.legs.count == slots.count - 1, slots.indices.contains(index) else { return nil }
        let driving = route.legs.prefix(index).reduce(0) { $0 + $1.travelTime }
        let waiting = slots.prefix(index).reduce(0) { $0 + ($1.waypoint?.dwellMinutes ?? 0) } * 60
        return driving + TimeInterval(waiting)
    }

    var shareURL: URL? { RouteShareLink.url(for: stops, options: options) }
    var googleMapsURL: URL? { isPlannable ? RouteHandoff.googleMapsURL(for: stops, options: options) : nil }

    // MARK: Changing the plan

    /// Starts or extends the plan from a place the person chose (ADR 0017: the info card is the way in).
    func apply(_ intent: RouteIntent, to waypoint: RouteWaypoint) {
        let slot = RouteSlot(waypoint: waypoint)
        switch intent {
        case .routeFrom:
            if slots.count >= 2 { slots[0].waypoint = waypoint } else { slots = [slot, RouteSlot()] }
        case .routeTo:
            if slots.count >= 2 { slots[slots.count - 1].waypoint = waypoint } else { slots = [startSlot(), slot] }
        case .addStop:
            if slots.isEmpty {
                slots = [startSlot(), slot]
            } else if let gap = slots.firstIndex(where: { $0.waypoint == nil }) {
                slots[gap].waypoint = waypoint
            } else if canAddStop {
                var stop = waypoint
                // A charging stop is a stay, not a drive-through.
                if stop.kind == .station && stop.dwellMinutes == 0 { stop.dwellMinutes = Self.chargingDwellMinutes }
                slots.insert(RouteSlot(waypoint: stop), at: slots.count - 1)
            } else {
                errorMessage = String(localized: "route.error.tooManyStops")
                return
            }
        }
        isPlannerPresented = true
        changed()
    }

    /// Adds a station as a charging stop between the stops there are.
    func addChargingStop(_ station: Station) {
        apply(.addStop, to: RouteWaypoint(station: station, dwellMinutes: Self.chargingDwellMinutes))
    }

    func fill(slot id: UUID, with waypoint: RouteWaypoint) {
        guard let index = slots.firstIndex(where: { $0.id == id }) else { return }
        slots[index].waypoint = waypoint
        changed()
    }

    func addEmptyStop() {
        guard canAddStop else { return }
        slots.insert(RouteSlot(), at: max(slots.count - 1, 0))
        changed()
    }

    func remove(slot id: UUID) {
        guard let index = slots.firstIndex(where: { $0.id == id }) else { return }
        // Start and destination always stay as rows; removing one of two leaves a gap to fill again.
        if slots.count > 2 { slots.remove(at: index) } else { slots[index].waypoint = nil }
        if slots.allSatisfy({ $0.waypoint == nil }) { return clear() }
        changed()
    }

    func move(fromOffsets: IndexSet, toOffset: Int) {
        slots.move(fromOffsets: fromOffsets, toOffset: toOffset)
        changed()
    }

    func reverse() {
        slots.reverse()
        changed()
    }

    func setDwell(_ minutes: Int, forSlot id: UUID) {
        guard let index = slots.firstIndex(where: { $0.id == id }), slots[index].waypoint != nil else { return }
        slots[index].waypoint?.dwellMinutes = min(max(minutes, 0), RouteWaypoint.maximumDwellMinutes)
        // The stay changes arrival times and nothing about the road: no replan, no new stations.
        persist()
        objectWillChange.send()
    }

    func setOptions(_ options: RouteOptions) {
        guard options != self.options else { return }
        self.options = options
        changed()
    }

    func selectAlternative(_ index: Int) {
        guard alternatives.indices.contains(index), index != selectedIndex else { return }
        selectedIndex = index
        candidates = []
        breaks = []
        mapRevision += 1
        persist()
        loadStations()
    }

    func clear() {
        planTask?.cancel()
        stationsTask?.cancel()
        breaksTask?.cancel()
        slots = []
        alternatives = []
        candidates = []
        breaks = []
        selectedIndex = 0
        phase = .idle
        stationsFailure = nil
        detoursThrottled = false
        isPlannerPresented = false
        store.savePlan(nil)
        mapRevision += 1
    }

    /// The map's filter changed (the settings sheet was closed): the stations along the route follow it.
    func filterChanged() {
        guard route != nil else { return }
        loadStations()
    }

    // MARK: Saved places and routes

    func savePlace(_ waypoint: RouteWaypoint, named name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, savedPlaces.count < Self.savedPlaceLimit else { return }
        savedPlaces.insert(SavedPlace(name: trimmed, subtitle: waypoint.subtitle, latitude: waypoint.latitude, longitude: waypoint.longitude), at: 0)
        store.savePlaces(savedPlaces)
        AppLogger.routing.notice("Saved place added, \(savedPlaces.count) kept")
    }

    func deletePlace(_ place: SavedPlace) {
        savedPlaces.removeAll { $0.id == place.id }
        store.savePlaces(savedPlaces)
    }

    func renamePlace(_ place: SavedPlace, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let index = savedPlaces.firstIndex(where: { $0.id == place.id }) else { return }
        savedPlaces[index].name = trimmed
        store.savePlaces(savedPlaces)
    }

    /// Keeps the open plan under a name. Only possible with every stop chosen.
    func saveRoute(named name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, isPlannable, savedRoutes.count < Self.savedRouteLimit else { return }
        savedRoutes.insert(SavedRoute(name: trimmed, waypoints: stops, options: options), at: 0)
        store.saveRoutes(savedRoutes)
        AppLogger.routing.notice("Route saved, \(savedRoutes.count) kept")
    }

    func deleteRoute(_ route: SavedRoute) {
        savedRoutes.removeAll { $0.id == route.id }
        store.saveRoutes(savedRoutes)
    }

    /// Opens a saved route as the current plan; MapKit computes it afresh.
    func open(_ saved: SavedRoute) {
        load(waypoints: saved.waypoints, options: saved.options)
    }

    /// Opens the route of a share link. The link is untrusted input; `RouteShareLink` has bounded it.
    func open(_ shared: RouteShareLink.Route) {
        load(waypoints: shared.waypoints, options: shared.options)
    }

    private func load(waypoints: [RouteWaypoint], options: RouteOptions) {
        slots = waypoints.map { waypoint in
            guard waypoint.kind == .currentLocation else { return RouteSlot(waypoint: waypoint) }
            // A "where I am" from another device or an earlier day is today's position, or a gap to fill.
            return RouteSlot(waypoint: currentLocationWaypoint())
        }
        self.options = options
        isPlannerPresented = true
        changed()
    }

    // MARK: Planning

    private func changed() {
        persist()
        mapRevision += 1
        replan()
    }

    private func replan() {
        planTask?.cancel()
        stationsTask?.cancel()
        breaksTask?.cancel()
        alternatives = []
        selectedIndex = 0
        candidates = []
        breaks = []
        stationsFailure = nil
        detoursThrottled = false
        refreshCurrentLocation()
        guard isPlannable else {
            phase = .idle
            mapRevision += 1
            persist()
            return
        }
        phase = .planning
        planTask = Task { [weak self] in await self?.performPlan() }
    }

    private func performPlan() async {
        // A task cancelled before it first ran (two edits in a row) must not spend a MapKit request.
        guard !Task.isCancelled else { return }
        let coordinates = stops.map(\.coordinate)
        do {
            let found = try await AppLogger.routing.measure("Routing through \(coordinates.count) stops") {
                try await routes.routes(through: coordinates, options: options)
            }
            guard !Task.isCancelled else { return }
            alternatives = found
            selectedIndex = 0
            phase = .planned
            fitRevision += 1
            mapRevision += 1
            persist()
            AppLogger.routing.notice("Route planned: \(found.count) alternative(s)")
            loadStations()
        } catch is CancellationError {
            AppLogger.routing.debug("Route planning superseded")
        } catch {
            guard !Task.isCancelled else { return }
            phase = .failed(error.localizedDescription)
            mapRevision += 1
            AppLogger.routing.warning("Route planning failed — \(AppLogger.describe(error))")
        }
    }

    private func loadStations() {
        guard let route else { return }
        stationsTask?.cancel()
        stationsFailure = nil
        detoursThrottled = false
        stationsTask = Task { [weak self] in await self?.performStationLoad(route) }
    }

    private func performStationLoad(_ route: PlannedRoute) async {
        isLoadingStations = true
        defer { isLoadingStations = false }
        let simplified = PolylineSimplifier.simplify(route.coordinates, maxPoints: Self.routePointLimit)
        do {
            // The count only: what the polyline says is where somebody is going (ADR 0002).
            AppLogger.routing.debug("Stations along a route of \(simplified.count) points")
            let found = try await repository.stationsAlongRoute(route: simplified, corridorKm: Self.corridorKm,
                                                                limit: Self.candidateLimit, filter: filter())
            guard !Task.isCancelled else { return }
            candidates = found.map { RouteStopCandidate(routeStation: $0, detourMinutes: nil) }
            mapRevision += 1
            persist()
            AppLogger.routing.notice("\(found.count) stations along the route")
            await refineDetours(on: route)
        } catch is CancellationError {
            AppLogger.routing.debug("Station query along the route superseded")
        } catch {
            guard !Task.isCancelled else { return }
            candidates = []
            stationsFailure = error.localizedDescription
            mapRevision += 1
            AppLogger.routing.warning("Stations along the route failed — \(AppLogger.describe(error))")
        }
    }

    /// Computes the exact detour for the stations nearest the road, a few at a time. A throttled MapKit
    /// is not an error: the others keep their estimate, and the list says so.
    private func refineDetours(on route: PlannedRoute) async {
        let nearest = candidates.sorted { $0.routeStation.distanceToRouteKm < $1.routeStation.distanceToRouteKm }
            .prefix(Self.exactDetourCount).map(\.routeStation)
        for batch in stride(from: 0, to: nearest.count, by: Self.detourBatchSize) {
            let group = Array(nearest[batch..<min(batch + Self.detourBatchSize, nearest.count)])
            let results = await withTaskGroup(of: (UUID, Result<Double, Error>)?.self) { tasks in
                for candidate in group {
                    tasks.addTask { [self] in
                        guard let frame = DetourEstimator.frame(for: candidate, on: route) else { return nil }
                        return (candidate.id, await detour(of: candidate, frame: frame))
                    }
                }
                var collected = [(UUID, Result<Double, Error>)]()
                for await result in tasks { if let result { collected.append(result) } }
                return collected
            }
            guard !Task.isCancelled else { return }
            for (id, result) in results {
                switch result {
                case .success(let minutes): setDetour(minutes, for: id)
                case .failure(let error):
                    if (error as? RouteError) == .throttled { detoursThrottled = true } else {
                        AppLogger.routing.debug("No detour for one station — \(AppLogger.describe(error))")
                    }
                }
            }
            if detoursThrottled { break }
        }
        mapRevision += 1
        persist()
    }

    private func detour(of candidate: RouteStation, frame: DetourEstimator.Frame) async -> Result<Double, Error> {
        do {
            let station = candidate.station.coordinate
            async let to = routes.travelTime(from: frame.leave.coordinate, to: station, options: options)
            async let from = routes.travelTime(from: station, to: frame.rejoin.coordinate, options: options)
            return .success(DetourEstimator.detourMinutes(toStation: try await to, fromStation: try await from, routeTime: frame.routeTime))
        } catch {
            return .failure(error)
        }
    }

    private func setDetour(_ minutes: Double, for id: UUID) {
        guard let index = candidates.firstIndex(where: { $0.id == id }) else { return }
        candidates[index].detourMinutes = minutes
    }

    // MARK: Breaks

    /// Looks for food, a toilet or a bed near the route at intervals of driving time (ADR 0017). On request
    /// only: it costs a handful of MapKit searches, and the answer is not needed to plan.
    func loadBreaks() {
        guard let route else { return }
        breaksTask?.cancel()
        breaksTask = Task { [weak self] in await self?.performBreakLoad(route) }
    }

    private func performBreakLoad(_ route: PlannedRoute) async {
        isLoadingBreaks = true
        defer { isLoadingBreaks = false }
        let total = route.travelTime
        guard total > 0, route.polylineMeters > 0 else { return }
        // Positions at multiples of the interval, but not at the very end, where the destination is.
        let count = min(Int((total / Self.breakIntervalSeconds).rounded(.down)), Self.breakSearchLimit)
        let positions = (1...max(count, 1)).map { index -> Double in
            count == 0 ? route.polylineMeters / 2 : route.polylineMeters * Double(index) / Double(count + 1)
        }
        var found = [BreakSuggestion]()
        for meters in positions {
            guard !Task.isCancelled, let center = route.coordinate(atMeters: meters) else { return }
            do {
                let nearby = try await places.places(near: center.coordinate, radiusMeters: Self.breakRadiusMeters,
                                                     categories: BreakSuggestion.Category.allCases)
                found += nearby.map { var suggestion = $0; suggestion.distanceAlongRouteKm = meters / 1_000; return suggestion }
            } catch is CancellationError {
                return
            } catch {
                // One failing search costs its stretch of the route, not the others.
                AppLogger.routing.debug("Break search failed — \(AppLogger.describe(error))")
            }
        }
        guard !Task.isCancelled else { return }
        var seen = Set<String>()
        breaks = found.filter { seen.insert($0.id).inserted }.sorted { $0.distanceAlongRouteKm < $1.distanceAlongRouteKm }
        AppLogger.routing.notice("\(breaks.count) break suggestions")
    }

    /// What is around a station, for the person weighing which charger to take. Loaded when asked.
    func amenities(near station: Station) async -> [BreakSuggestion] {
        let found = try? await places.places(near: station.coordinate, radiusMeters: 400, categories: BreakSuggestion.Category.allCases)
        return found ?? []
    }

    // MARK: Device position

    private func startSlot() -> RouteSlot { RouteSlot(waypoint: currentLocationWaypoint()) }

    private func currentLocationWaypoint() -> RouteWaypoint? {
        currentLocation().map {
            RouteWaypoint(kind: .currentLocation, name: String(localized: "route.currentLocation"),
                          latitude: $0.latitude, longitude: $0.longitude)
        }
    }

    /// "Where I am" is a snapshot, so planning again takes a new one.
    private func refreshCurrentLocation() {
        guard let here = currentLocation() else { return }
        for index in slots.indices where slots[index].waypoint?.kind == .currentLocation {
            slots[index].waypoint?.latitude = here.latitude
            slots[index].waypoint?.longitude = here.longitude
        }
    }

    // MARK: Persistence

    private func persist() {
        guard hasPlan else { return store.savePlan(nil) }
        store.savePlan(StoredRoutePlan(slots: slots, options: options, route: route, candidates: candidates, savedAt: Date()))
    }
}
