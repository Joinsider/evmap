import MapKit
import SwiftUI

struct MapScreen: View {
    @StateObject private var viewModel: MapViewModel
    let repository: any ChargingStationRepository
    @ObservedObject var authSession: AuthSession
    @State private var position: MapCameraPosition = .automatic
    @State private var selectedStation: Station?
    @State private var showFilters = false

    init(repository: any ChargingStationRepository, authSession: AuthSession) {
        self.repository = repository
        self.authSession = authSession
        _viewModel = StateObject(wrappedValue: MapViewModel(repository: repository))
    }

    var body: some View {
        NavigationStack {
            Map(position: $position, selection: $selectedStation) {
                UserAnnotation()
                ForEach(viewModel.stations) { station in
                    Annotation(station.displayName, coordinate: station.coordinate) {
                        Image(systemName: "bolt.car.circle.fill")
                            .font(.title2)
                            .foregroundStyle(.green)
                            .accessibilityLabel(station.displayName)
                    }.tag(station)
                }
            }
            .mapControls { MapCompass(); MapScaleView() }
            .navigationTitle("app.title")
            .onChange(of: viewModel.locationFixCount) {
                position = .region(MKCoordinateRegion(center: viewModel.location, latitudinalMeters: 20_000, longitudinalMeters: 20_000))
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button { viewModel.requestLocation() } label: { Label("map.locate", systemImage: "location.fill") } }
                ToolbarItem(placement: .topBarTrailing) { Button { showFilters = true } label: { Label("filter.title", systemImage: "line.3.horizontal.decrease.circle") } }
            }
            .safeAreaInset(edge: .bottom) { StationCountView(count: viewModel.stations.count, isLoading: viewModel.isLoading) }
            .sheet(item: $selectedStation) { StationDetailScreen(station: $0, repository: repository, authSession: authSession) }
            .sheet(isPresented: $showFilters) { StationFilterScreen(filter: $viewModel.filter) { Task { await viewModel.loadStations() } } }
            .alert("error.title", isPresented: Binding(get: { viewModel.errorMessage != nil }, set: { if !$0 { viewModel.errorMessage = nil } })) {
                Button("action.ok", role: .cancel) { }
            } message: { Text(viewModel.errorMessage ?? "") }
            .task { viewModel.requestLocation(); await viewModel.loadStations() }
        }
    }
}
