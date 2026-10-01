import CoreLocation
import MapKit
import SwiftUI

/// Chooses the place for one row of the planner: search for it, or pick one of the places the person
/// already has — where they are, the places they named, their favorites, what they searched recently.
struct WaypointPickerScreen: View {
    @ObservedObject var planner: RoutePlannerViewModel
    @ObservedObject var favorites: FavoritesViewModel
    let pick: (RouteWaypoint) -> Void
    /// Where the device is, if it is known; offered as "my location".
    let currentLocation: CLLocationCoordinate2D?
    @StateObject private var search = AddressSearchViewModel()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if search.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    if let currentLocation {
                        Button { choose(RouteWaypoint(kind: .currentLocation, name: String(localized: "route.currentLocation"),
                                                      latitude: currentLocation.latitude, longitude: currentLocation.longitude)) } label: {
                            Label("route.currentLocation", systemImage: "location.fill")
                        }
                    }
                    if !planner.savedPlaces.isEmpty {
                        Section("route.savedPlaces") {
                            ForEach(planner.savedPlaces) { place in
                                Button { choose(place.waypoint) } label: { PlaceRow(icon: "bookmark.fill", title: place.name, subtitle: place.subtitle) }
                            }
                        }
                    }
                    if !favorites.stations.isEmpty {
                        Section("favorites.title") {
                            ForEach(favorites.stations) { station in
                                Button { choose(RouteWaypoint(station: station)) } label: {
                                    PlaceRow(icon: "star.fill", title: station.displayName, subtitle: station.address)
                                }
                            }
                        }
                    }
                }
                AddressSearchSuggestions(viewModel: search)
            }
            .buttonStyle(.plain)
            .navigationTitle("route.pick.title")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $search.query, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("search.prompt"))
            .searchSuggestions { EmptyView() }
            .onChange(of: search.query) { _, query in search.queryChanged(to: query) }
            .onSubmit(of: .search) { search.submit() }
            .onChange(of: search.result) { _, place in
                if let place { choose(RouteWaypoint(place: place)) }
            }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("action.cancel") { dismiss() } } }
            .alert("error.title", isPresented: Binding(get: { search.errorMessage != nil }, set: { if !$0 { search.errorMessage = nil } })) {
                Button("action.ok", role: .cancel) {
                    // Dismissing is the whole action; the binding's setter clears the message.
                }
            } message: { Text(search.errorMessage ?? "") }
        }
    }

    private func choose(_ waypoint: RouteWaypoint) {
        pick(waypoint)
        dismiss()
    }
}

struct PlaceRow: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                if !subtitle.isEmpty { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
            }
        } icon: {
            Image(systemName: icon).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}
