import MapKit
import SwiftUI

struct MapScreen: View {
    @StateObject private var viewModel: MapViewModel
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

    init(repository: any ChargingStationRepository, authSession: AuthSession) {
        self.repository = repository
        self.authSession = authSession
        _viewModel = StateObject(wrappedValue: MapViewModel(repository: repository))
    }

    var body: some View {
        NavigationStack {
            Map(position: $position, selection: $selectedAnnotation) {
                UserAnnotation()
                ForEach(viewModel.annotations) { annotation in
                    Annotation(annotation.station?.displayName ?? "", coordinate: annotation.coordinate) {
                        StationAnnotationView(annotation: annotation)
                    }.tag(annotation)
                }
            }
            .mapControls { MapCompass(); MapScaleView() }
            .navigationTitle("app.title")
            // `.onEnd` rather than `.continuous`: the camera settles once per gesture, which is the
            // natural debounce for a network query. The view model decides whether to actually fetch.
            .onMapCameraChange(frequency: .onEnd) { context in
                visibleSpan = context.region.span
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
            }
            .overlay(alignment: .top) { MapStatusBadge(isLoading: viewModel.isLoading, isTruncated: viewModel.isTruncated) }
            .overlay(alignment: .bottomTrailing) { ChargingPowerLegend().padding(12) }
            .sheet(item: $selectedStation) { StationDetailScreen(station: $0, repository: repository, authSession: authSession) }
            .sheet(isPresented: $showFilters) { StationFilterScreen(filter: $viewModel.filter) { viewModel.reload() } }
            .alert("error.title", isPresented: Binding(get: { viewModel.errorMessage != nil }, set: { if !$0 { viewModel.errorMessage = nil } })) {
                Button("action.ok", role: .cancel) { }
            } message: { Text(viewModel.errorMessage ?? "") }
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
private struct MapStatusBadge: View {
    let isLoading: Bool
    let isTruncated: Bool

    var body: some View {
        Group {
            if isLoading {
                Label { Text("stations.loading") } icon: { ProgressView().controlSize(.mini) }
            } else if isTruncated {
                Label("stations.truncated", systemImage: "arrow.down.left.and.arrow.up.right")
            }
        }
        .font(.caption)
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(.thinMaterial, in: .capsule)
        .shadow(radius: 2)
        .padding(.top, 8)
        .animation(.default, value: isLoading)
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
