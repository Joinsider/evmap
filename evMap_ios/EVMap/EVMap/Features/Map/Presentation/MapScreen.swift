import MapKit
import SwiftUI

struct MapScreen: View {
    @StateObject private var viewModel: MapViewModel
    @StateObject private var search = AddressSearchViewModel()
    let repository: any ChargingStationRepository
    @ObservedObject var authSession: AuthSession
    /// Launch view: Germany at overview scale, which loads the highpower backbone straight away and
    /// gives `onMapCameraChange` a defined starting region instead of whatever `.automatic` picks.
    private static let initialRegion = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 51.1657, longitude: 10.4515),
        latitudinalMeters: 700_000, longitudinalMeters: 700_000
    )

    @State private var position: MapCameraPosition = .region(MapScreen.initialRegion)
    @State private var visibleSpan: MKCoordinateSpan?
    @State private var selectedAnnotation: StationAnnotation?
    @State private var selectedStation: Station?
    @State private var showFilters = false
    /// Focus, not presentation. Dismissing the search *presentation* after a hit would take the
    /// text with it — UIKit clears the field when the search controller goes away — leaving the map
    /// on a place the search bar no longer names. Dropping focus only closes the keyboard.
    @FocusState private var isSearchFieldFocused: Bool

    init(repository: any ChargingStationRepository, authSession: AuthSession) {
        self.repository = repository
        self.authSession = authSession
        _viewModel = StateObject(wrappedValue: MapViewModel(repository: repository))
    }

    var body: some View {
        NavigationStack {
            Map(position: $position, selection: $selectedAnnotation) {
                UserAnnotation()
                // Drawn before the station pins so a searched address never hides one.
                if let place = search.result {
                    Marker(place.displayName, systemImage: "mappin", coordinate: place.coordinate)
                        .tint(.indigo)
                }
                ForEach(viewModel.annotations) { annotation in
                    Annotation(annotation.station?.displayName ?? "", coordinate: annotation.coordinate) {
                        StationAnnotationView(annotation: annotation)
                    }.tag(annotation)
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
            .onChange(of: selectedAnnotation) { _, annotation in
                guard let annotation else { return }
                // A pin either opens a station or resolves the group it stands for; either way the
                // selection is consumed, so MapKit does not keep it highlighted behind the sheet.
                if let station = annotation.station { selectedStation = station } else { zoom(into: annotation) }
                selectedAnnotation = nil
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button { viewModel.requestLocation() } label: { Label("map.locate", systemImage: "location.fill") } }
                ToolbarItem(placement: .topBarTrailing) { Button { showFilters = true } label: { Label("filter.title", systemImage: "line.3.horizontal.decrease.circle") } }
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
            .sheet(item: $selectedStation) { StationDetailScreen(station: $0, repository: repository, authSession: authSession) }
            .sheet(isPresented: $showFilters) { StationFilterScreen(filter: $viewModel.filter) { viewModel.reload() } }
            // One alert for both sources: SwiftUI presents a single alert per view, and a failed
            // station load and a failed address lookup are the same kind of interruption.
            .alert("error.title", isPresented: Binding(
                get: { viewModel.errorMessage != nil || search.errorMessage != nil },
                set: { if !$0 { viewModel.errorMessage = nil; search.errorMessage = nil } }
            )) {
                Button("action.ok", role: .cancel) { }
            } message: { Text(viewModel.errorMessage ?? search.errorMessage ?? "") }
            // Seeds the first query from the known starting region rather than waiting for MapKit to
            // report a camera; the coverage check keeps its subsequent report from refetching.
            .task { viewModel.cameraChanged(to: Self.initialRegion); viewModel.requestLocation() }
        }
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
        .shadow(radius: 2)
        .accessibilityLabel(accessibilityLabel)
    }

    /// Colour alone must not carry the charging speed, so the pin says it out loud.
    private var accessibilityLabel: String {
        let power = annotation.maxPowerKw.map(formattedPower(kW:))
        guard let station = annotation.station else {
            // The count is formatted into a string first: interpolating the `Int` would look up
            // `map.cluster %lld`, which no strings file declares.
            return String(localized: "map.cluster \(annotation.count.formatted())") + (power.map { ", \($0)" } ?? "")
        }
        return station.displayName + (power.map { ", \($0)" } ?? "")
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
