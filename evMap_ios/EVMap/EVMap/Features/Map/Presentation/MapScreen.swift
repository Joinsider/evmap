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
    /// The info card, the station screen and what has to wait between them (ADR 0017).
    @StateObject private var flow: MapPlaceFlow
    @State private var showSettings = false
    @State private var showFavorites = false
    /// The favorite the person picked, opened once the list sheet is gone: two sheets cannot swap in one go.
    @State private var pendingFavorite: Station?
    /// Focus, not presentation. Dismissing the search *presentation* after a hit would take the
    /// text with it — UIKit clears the field when the search controller goes away — leaving the map
    /// on a place the search bar no longer names. Dropping focus only closes the keyboard.
    @FocusState private var isSearchFieldFocused: Bool

    /// `flow` is for tests, which present a card or a station without tapping the map.
    init(repository: any ChargingStationRepository, authSession: AuthSession, settings: SettingsViewModel, favorites: FavoritesViewModel,
         planner: RoutePlannerViewModel, flow: MapPlaceFlow? = nil) {
        self.repository = repository
        self.authSession = authSession
        self.settings = settings
        self.favorites = favorites
        self.planner = planner
        _flow = StateObject(wrappedValue: flow ?? MapPlaceFlow(planner: planner))
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
                        let role = RouteStopRole(index: index, count: planner.slots.count)
                        Marker(waypoint.kind == .currentLocation ? String(localized: "route.currentLocation") : waypoint.name,
                               monogram: Text(role.monogram(index: index)), coordinate: waypoint.coordinate)
                            .tint(role.color)
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
                withAnimation { position = .region(MapCamera.region(around: place.coordinate)) }
                // A hit is not only somewhere to look at: the card offers the route there (ADR 0017).
                flow.show(place)
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
                    if let cluster = flow.tap(annotation) { zoom(into: cluster) }
                } else if let feature = selected.feature {
                    flow.show(title: feature.title, coordinate: feature.coordinate) {
                        try? await MKMapItemRequest(feature: feature).mapItem.address?.shortAddress
                    }
                }
                selection = nil
            }
            .onChange(of: planner.mapRevision) { viewModel.follow(planner) }
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
            .sheet(item: $flow.station, onDismiss: flow.sheetDismissed) { shown in
                StationDetailScreen(station: shown, repository: repository, authSession: authSession, favorites: favorites,
                                    hasRoute: planner.hasPlan) { flow.choose($0, for: RouteWaypoint(station: shown)) }
            }
            .sheet(item: $flow.place, onDismiss: flow.sheetDismissed) { place in
                PlaceInfoCard(place: place, hasPlan: planner.hasPlan, choose: { flow.choose($0, for: place.waypoint) },
                              save: { planner.savePlace(place.waypoint, named: $0) })
            }
            .sheet(isPresented: $planner.isPlannerPresented, onDismiss: showPickedStation) {
                RoutePlannerScreen(planner: planner, favorites: favorites, currentLocation: knownLocation, showStation: flow.showStationAfterPlanner)
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
                viewModel.follow(planner)
                fitRoute()
            }
            // Merges the device's favorites into the account on sign-in (also at launch with a restored
            // session) and empties the device copy on sign-out (ADR 0021).
            .task(id: authSession.accessToken) { await favorites.sessionChanged(to: authSession.accessToken) }
        }
    }

    /// Where the device is, once there has been a fix; the initial value of the view model is only a
    /// starting view of Germany, not a position.
    private var knownLocation: CLLocationCoordinate2D? { viewModel.locationFixCount > 0 ? viewModel.location : nil }

    /// The planner has gone; if a station was picked in it, the map centers on it and opens it.
    private func showPickedStation() {
        if let station = flow.plannerDismissed() { focus(on: station) }
    }

    private func focus(on station: Station) {
        withAnimation { position = .region(MapCamera.region(around: station.coordinate)) }
    }

    /// Frames the whole route in the upper half of the screen, which is what stays free above the planner
    /// sheet at half height.
    private func fitRoute() {
        guard let route = planner.route, let region = RouteFraming.region(fitting: route.coordinates) else { return }
        withAnimation { position = .region(region) }
    }

    /// Centers the map on the favorite picked in the list and opens it.
    private func openPendingFavorite() {
        guard let station = pendingFavorite else { return }
        pendingFavorite = nil
        focus(on: station)
        flow.station = station
    }

    /// Opens a cluster by zooming to a quarter of the current span, centred on it — enough to break
    /// the grid cell apart without losing the user's place.
    private func zoom(into annotation: StationAnnotation) {
        withAnimation { position = .region(MapCamera.region(zoomingInto: annotation.coordinate, from: visibleSpan)) }
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
