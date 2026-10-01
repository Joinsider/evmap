import MapKit
import SwiftUI

struct MapScreen: View {
    @StateObject private var viewModel: MapViewModel
    @StateObject private var search = AddressSearchViewModel()
    let repository: any ChargingStationRepository
    @ObservedObject var authSession: AuthSession
    /// Owned by the app, not by this screen: the settings outlive any one view, and the map has to
    /// be able to read them before its first query.
    @ObservedObject var settings: SettingsViewModel
    /// Owned by the app too: the stars on the pins and the favorite list outlive any one screen.
    @ObservedObject var favorites: FavoritesViewModel
    /// Owned by the app too: a share link can open a route before this screen exists, and the plan has
    /// to outlive it (ADR 0017).
    @ObservedObject var planner: RoutePlannerViewModel
    /// Launch view: Germany at overview scale, which loads the highpower backbone straight away and
    /// gives `onMapCameraChange` a defined starting region instead of whatever `.automatic` picks.
    private static let initialRegion = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 51.1657, longitude: 10.4515),
        latitudinalMeters: 700_000, longitudinalMeters: 700_000
    )

    @State private var position: MapCameraPosition = .region(MapScreen.initialRegion)
    @State private var visibleSpan: MKCoordinateSpan?
    @State private var selection: MapSelection<StationAnnotation>?
    @State private var selectedStation: Station?
    /// The place on the info card: a search hit, a tapped town or POI, a station.
    @State private var placeSelection: PlaceSelection?
    /// What to do with a place once its sheet has gone: two sheets cannot swap in one go.
    @State private var pendingRoute: (intent: RouteIntent, waypoint: RouteWaypoint)?
    /// A station picked in the planner, opened once the planner is gone.
    @State private var pendingStation: Station?
    @State private var showSettings = false
    @State private var showFavorites = false
    /// The favorite the person picked, opened once the list sheet is gone: two sheets cannot swap in one go.
    @State private var pendingFavorite: Station?
    /// Focus, not presentation. Dismissing the search *presentation* after a hit would take the
    /// text with it — UIKit clears the field when the search controller goes away — leaving the map
    /// on a place the search bar no longer names. Dropping focus only closes the keyboard.
    @FocusState private var isSearchFieldFocused: Bool

    init(repository: any ChargingStationRepository, authSession: AuthSession, settings: SettingsViewModel, favorites: FavoritesViewModel,
         planner: RoutePlannerViewModel) {
        self.repository = repository
        self.authSession = authSession
        self.settings = settings
        self.favorites = favorites
        self.planner = planner
        _viewModel = StateObject(wrappedValue: MapViewModel(repository: repository, filter: settings.settings.stationFilter))
    }

    var body: some View {
        NavigationStack {
            Map(position: $position, selection: $selection) {
                UserAnnotation()
                // Alternatives first, the chosen route over them, then the stops over the line.
                ForEach(planner.lines) { line in
                    MapPolyline(coordinates: line.coordinates)
                        .stroke(line.isSelected ? Color.blue : Color.gray.opacity(0.6), lineWidth: line.isSelected ? 6 : 4)
                }
                ForEach(Array(planner.slots.enumerated()), id: \.element.id) { index, slot in
                    if let waypoint = slot.waypoint {
                        Marker(waypoint.kind == .currentLocation ? String(localized: "route.currentLocation") : waypoint.name,
                               monogram: Text(stopMonogram(index)), coordinate: waypoint.coordinate)
                            .tint(index == 0 ? .green : (index == planner.slots.count - 1 ? .red : .blue))
                    }
                }
                // Drawn before the station pins so a searched address never hides one.
                if let place = search.result {
                    Marker(place.displayName, systemImage: "mappin", coordinate: place.coordinate)
                        .tint(.indigo)
                }
                ForEach(viewModel.annotations) { annotation in
                    Annotation(annotation.station?.displayName ?? "", coordinate: annotation.coordinate) {
                        StationAnnotationView(annotation: annotation,
                                              isFavorite: annotation.stations.contains { favorites.ids.contains($0.id) })
                    }.tag(MapSelection(annotation))
                }
            }
            .mapControls { MapCompass(); MapScaleView() }
            .navigationTitle("app.title")
            // Placement is left to the toolbar, which puts the field in the bottom bar below.
            .searchable(text: $search.query, prompt: Text("search.prompt"))
            .searchFocused($isSearchFieldFocused)
            .searchSuggestions { AddressSearchSuggestions(viewModel: search) }
            .onChange(of: search.query) { _, query in search.queryChanged(to: query) }
            .onSubmit(of: .search) { search.submit() }
            // Moving the map to the hit is what "search" means here; the camera change then loads the
            // stations around it through the normal viewport path.
            .onChange(of: search.result) { _, place in
                guard let place else { return }
                isSearchFieldFocused = false
                withAnimation {
                    position = .region(MKCoordinateRegion(center: place.coordinate, latitudinalMeters: 4_000, longitudinalMeters: 4_000))
                }
                // A hit is not only somewhere to look at: the card offers the route there (ADR 0017).
                placeSelection = PlaceSelection(place: place)
            }
            // `.onEnd` rather than `.continuous`: the camera settles once per gesture, which is the
            // natural debounce for a network query. The view model decides whether to actually fetch.
            .onMapCameraChange(frequency: .onEnd) { context in
                visibleSpan = context.region.span
                // Autocomplete is biased towards what is on screen, so it follows the camera.
                search.searchRegion = context.region
                viewModel.cameraChanged(to: context.region)
            }
            .onChange(of: viewModel.locationFixCount) {
                position = .region(MKCoordinateRegion(center: viewModel.location, latitudinalMeters: 20_000, longitudinalMeters: 20_000))
            }
            .onChange(of: selection) { _, selected in
                guard let selected else { return }
                // A pin either opens a station or resolves the group it stands for; a map feature — a town,
                // a place of interest — opens the info card. Either way the selection is consumed, so
                // MapKit does not keep it highlighted behind the sheet.
                if let annotation = selected.value {
                    if let station = annotation.station { selectedStation = station } else { zoom(into: annotation) }
                } else if let feature = selected.feature {
                    select(feature)
                }
                selection = nil
            }
            .onChange(of: planner.mapRevision) { syncRoute() }
            .onChange(of: planner.fitRevision) { fitRoute() }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button { viewModel.requestLocation() } label: { Label("map.locate", systemImage: "location.fill") } }
                ToolbarItem(placement: .topBarTrailing) { Button { showFavorites = true } label: { Label("favorites.title", systemImage: "star") } }
                ToolbarItem(placement: .topBarTrailing) { Button { showSettings = true } label: { Label("settings.title", systemImage: "line.3.horizontal.decrease.circle") } }
                // Where iOS 26 puts search: a full-width field in the bottom bar, within thumb
                // reach, rather than a drawer under the title at the far end of the screen.
                DefaultToolbarItem(kind: .search, placement: .bottomBar)
            }
            .overlay(alignment: .top) {
                MapStatusBadge(isLoading: viewModel.isLoading, isResolving: search.isResolving, isTruncated: viewModel.isTruncated)
                    .animation(.default, value: viewModel.isLoading)
                    .animation(.default, value: viewModel.isTruncated)
                    .animation(.default, value: search.isResolving)
            }
            .overlay(alignment: .bottomTrailing) { ChargingPowerLegend().padding(12) }
            .overlay(alignment: .bottom) {
                // Above the search field of the bottom bar; the plan itself is in the planner sheet.
                if planner.hasPlan && !planner.isPlannerPresented {
                    RouteSummaryBar(planner: planner) { planner.isPlannerPresented = true }
                        .padding(.bottom, 76)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.default, value: planner.hasPlan)
            .sheet(item: $selectedStation, onDismiss: startPendingRoute) {
                StationDetailScreen(station: $0, repository: repository, authSession: authSession, favorites: favorites,
                                    hasRoute: planner.hasPlan) { [station = $0] intent in choose(intent, for: RouteWaypoint(station: station)) }
            }
            .sheet(item: $placeSelection, onDismiss: startPendingRoute) { place in
                PlaceInfoCard(place: place, hasPlan: planner.hasPlan, choose: { choose($0, for: place.waypoint) },
                              save: { planner.savePlace(place.waypoint, named: $0) })
            }
            .sheet(isPresented: $planner.isPlannerPresented, onDismiss: openPendingStation) {
                RoutePlannerScreen(planner: planner, favorites: favorites, currentLocation: knownLocation) { station in
                    pendingStation = station
                    planner.isPlannerPresented = false
                }
            }
            .sheet(isPresented: $showFavorites, onDismiss: openPendingFavorite) {
                FavoritesScreen(model: favorites, authSession: authSession) { station in
                    pendingFavorite = station
                    showFavorites = false
                }
            }
            // Applied on dismiss, not on change: the settings persist themselves keystroke by
            // keystroke, but dragging the power slider must not be one network request per step.
            .sheet(isPresented: $showSettings, onDismiss: {
                viewModel.apply(settings.settings.stationFilter)
                planner.filterChanged()
            }) {
                SettingsScreen(model: settings, repository: repository, authSession: authSession)
            }
            // One alert for both sources: SwiftUI presents a single alert per view, and a failed
            // station load and a failed address lookup are the same kind of interruption.
            .alert("error.title", isPresented: Binding(
                get: { viewModel.errorMessage != nil || search.errorMessage != nil },
                set: { if !$0 { viewModel.errorMessage = nil; search.errorMessage = nil } }
            )) {
                Button("action.ok", role: .cancel) {
                    // Dismissing is the whole action; the binding's setter clears both messages.
                }
            } message: { Text(viewModel.errorMessage ?? search.errorMessage ?? "") }
            // Seeds the first query from the known starting region rather than waiting for MapKit to
            // report a camera; the coverage check keeps its subsequent report from refetching.
            .task {
                viewModel.cameraChanged(to: Self.initialRegion)
                viewModel.requestLocation()
                // The planner starts from where the device is; only this screen knows.
                planner.currentLocation = { [viewModel] in viewModel.locationFixCount > 0 ? viewModel.location : nil }
                // A plan restored from the device (or opened by a link) is on the map from the start.
                syncRoute()
                fitRoute()
            }
            // Merges the device's favorites into the account on sign-in (also at launch with a restored
            // session) and empties the device copy on sign-out (ADR 0021).
            .task(id: authSession.accessToken) { await favorites.sessionChanged(to: authSession.accessToken) }
        }
    }

    /// A for the start, B for the destination, numbers for the stops between.
    private func stopMonogram(_ index: Int) -> String {
        if index == 0 { return "A" }
        return index == planner.slots.count - 1 ? "B" : String(index)
    }

    /// Where the device is, once there has been a fix; the initial value of the view model is only a
    /// starting view of Germany, not a position.
    private var knownLocation: CLLocationCoordinate2D? { viewModel.locationFixCount > 0 ? viewModel.location : nil }

    /// A place was chosen on its card or in the station screen: close the sheet, then act (ADR 0017).
    private func choose(_ intent: RouteIntent, for waypoint: RouteWaypoint) {
        pendingRoute = (intent, waypoint)
        placeSelection = nil
        selectedStation = nil
    }

    private func startPendingRoute() {
        guard let pending = pendingRoute else { return }
        pendingRoute = nil
        planner.apply(pending.intent, to: pending.waypoint)
    }

    private func openPendingStation() {
        guard let station = pendingStation else { return }
        pendingStation = nil
        withAnimation {
            position = .region(MKCoordinateRegion(center: station.coordinate, latitudinalMeters: 4_000, longitudinalMeters: 4_000))
        }
        selectedStation = station
    }

    /// A tapped town or place of interest becomes a card at once, and learns its address a moment later.
    private func select(_ feature: MapFeature) {
        let place = PlaceSelection(title: feature.title ?? String(localized: "route.place.unnamed"), coordinate: feature.coordinate)
        placeSelection = place
        Task {
            guard let item = try? await MKMapItemRequest(feature: feature).mapItem, placeSelection?.id == place.id else { return }
            placeSelection?.subtitle = item.address?.shortAddress ?? ""
        }
    }

    /// The map shows the stations along a route while there is one, the viewport's otherwise. A route that
    /// is being planned again keeps the map as it was, instead of flickering through the viewport.
    private func syncRoute() {
        if planner.route != nil {
            viewModel.showRoute(stations: planner.candidates.map(\.station))
        } else if planner.phase != .planning {
            viewModel.clearRoute()
        }
    }

    /// Frames the whole route in the upper half of the screen, which is what stays free above the planner
    /// sheet at half height.
    private func fitRoute() {
        guard let route = planner.route, let region = Self.region(fitting: route.coordinates) else { return }
        withAnimation { position = .region(region) }
    }

    private static func region(fitting coordinates: [RouteCoordinate]) -> MKCoordinateRegion? {
        guard let first = coordinates.first else { return nil }
        var minLat = first.latitude, maxLat = first.latitude, minLon = first.longitude, maxLon = first.longitude
        for coordinate in coordinates {
            minLat = min(minLat, coordinate.latitude); maxLat = max(maxLat, coordinate.latitude)
            minLon = min(minLon, coordinate.longitude); maxLon = max(maxLon, coordinate.longitude)
        }
        // The route takes about 40 % of the height, and its middle sits a quarter of the way down.
        let latSpan = max((maxLat - minLat) / 0.4, 0.04), lonSpan = max((maxLon - minLon) * 1.3, 0.04)
        return MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2 - latSpan * 0.27, longitude: (minLon + maxLon) / 2),
                                  span: MKCoordinateSpan(latitudeDelta: latSpan, longitudeDelta: lonSpan))
    }

    /// Centers the map on the favorite picked in the list and opens it.
    private func openPendingFavorite() {
        guard let station = pendingFavorite else { return }
        pendingFavorite = nil
        withAnimation {
            position = .region(MKCoordinateRegion(center: station.coordinate, latitudinalMeters: 4_000, longitudinalMeters: 4_000))
        }
        selectedStation = station
    }

    /// Opens a cluster by zooming to a quarter of the current span, centred on it — enough to break
    /// the grid cell apart without losing the user's place.
    private func zoom(into annotation: StationAnnotation) {
        let span = visibleSpan ?? MKCoordinateSpan(latitudeDelta: 0.4, longitudeDelta: 0.4)
        withAnimation {
            position = .region(MKCoordinateRegion(
                center: annotation.coordinate,
                span: MKCoordinateSpan(latitudeDelta: max(span.latitudeDelta / 4, 0.002),
                                       longitudeDelta: max(span.longitudeDelta / 4, 0.002))
            ))
        }
    }
}

/// A single station pin, or a cluster badge carrying its member count.
private struct StationAnnotationView: View {
    let annotation: StationAnnotation
    /// Whether this pin is a favorite, or a group that holds one.
    let isFavorite: Bool

    var body: some View {
        Group {
            if let station = annotation.station {
                Image(systemName: "bolt.car.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.white, station.powerColor)
            } else {
                Text(annotation.count, format: .number)
                    .font(.caption.bold())
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 5)
                    .background(annotation.powerTier?.color ?? .gray, in: .capsule)
                    .overlay(Capsule().strokeBorder(.white, lineWidth: 1.5))
            }
        }
        .overlay(alignment: .topTrailing) { liveBadge }
        .overlay(alignment: .topLeading) { favoriteBadge }
        .shadow(radius: 2)
        .accessibilityLabel(accessibilityLabel)
    }

    /// A star on a favorite, so it stands out among its neighbours without changing its power colour.
    @ViewBuilder
    private var favoriteBadge: some View {
        if isFavorite {
            Image(systemName: "star.fill")
                .font(.system(size: 10))
                .foregroundStyle(.yellow)
                .padding(2)
                .background(.white, in: .circle)
                .offset(x: -6, y: -4)
        }
    }

    /// How many charge points behind this pin are free right now.
    ///
    /// Drawn only where a live source actually answered, which is a minority of stations — the pin
    /// keeps its power colour and gains a badge, rather than changing colour, so that the absence of
    /// live data is never mistaken for a status. A count of zero is still shown: "0 frei" is real
    /// information, and hiding it would leave the pin looking uncovered.
    @ViewBuilder
    private var liveBadge: some View {
        if let free = annotation.liveAvailableCount {
            Text(free, format: .number)
                .font(.system(size: 10, weight: .bold))
                .monospacedDigit()
                .foregroundStyle(.white)
                .padding(.horizontal, 4)
                .padding(.vertical, 1)
                .background(free > 0 ? Color.green : Color.orange, in: .capsule)
                .overlay(Capsule().strokeBorder(.white, lineWidth: 1))
                .offset(x: 6, y: -4)
        }
    }

    /// Colour alone must not carry the charging speed or the live status, so the pin says both out loud.
    private var accessibilityLabel: String {
        let power = annotation.maxPowerKw.map(formattedPower(kW:))
        let live = (annotation.liveAvailableCount.map {
            ", " + String(format: String(localized: "map.liveAvailable"), $0)
        } ?? "") + (isFavorite ? ", " + String(localized: "map.favorite") : "")
        guard let station = annotation.station else {
            // The count is formatted into a string first: interpolating the `Int` would look up
            // `map.cluster %lld`, which no strings file declares.
            return String(localized: "map.cluster \(annotation.count.formatted())") + (power.map { ", \($0)" } ?? "") + live
        }
        return station.displayName + (power.map { ", \($0)" } ?? "") + live
    }
}

/// Transient status over the map: loading, or a note that the viewport holds more than was drawn.
///
/// The capsule is built per state rather than around a `Group`, because a `Group` with an empty
/// body still carries the padding and the material — an idle map was left with a blank blob
/// hovering over it. Nothing to say means no view at all.
private struct MapStatusBadge: View {
    let isLoading: Bool
    let isResolving: Bool
    let isTruncated: Bool

    var body: some View {
        if isResolving {
            capsule { Label { Text("search.resolving") } icon: { ProgressView().controlSize(.mini) } }
        } else if isLoading {
            capsule { Label { Text("stations.loading") } icon: { ProgressView().controlSize(.mini) } }
        } else if isTruncated {
            capsule { Label("stations.truncated", systemImage: "arrow.down.left.and.arrow.up.right") }
        }
    }

    private func capsule(@ViewBuilder _ content: () -> some View) -> some View {
        content()
            .font(.caption)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.thinMaterial, in: .capsule)
            .shadow(radius: 2)
            .padding(.top, 8)
            .transition(.opacity)
    }
}

/// Compact key for the pin colours — a colour ramp is only readable if its scale is stated somewhere.
private struct ChargingPowerLegend: View {
    var body: some View {
        HStack(spacing: 6) {
            ForEach(ChargingPowerTier.allCases, id: \.self) { tier in
                HStack(spacing: 3) {
                    Circle().fill(tier.color).frame(width: 7, height: 7)
                    Text(tier.legendLabel)
                }
            }
            Text("unit.kW")
        }
        .font(.caption2)
        .monospacedDigit()
        .foregroundStyle(.secondary)
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .background(.thinMaterial, in: .capsule)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("map.legend.accessibility")
    }
}
