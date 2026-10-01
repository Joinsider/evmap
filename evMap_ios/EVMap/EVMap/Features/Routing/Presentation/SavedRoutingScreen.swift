import SwiftUI

/// The places and routes the person named (ADR 0017), to open, rename and delete. Device-only; the footer says so.
struct SavedRoutingScreen: View {
    @ObservedObject var planner: RoutePlannerViewModel
    /// Opens a saved route and closes this screen.
    let openRoute: (SavedRoute) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var renaming: SavedPlace?
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                Section("route.savedRoutes") {
                    if planner.savedRoutes.isEmpty { Text("route.savedRoutes.empty").foregroundStyle(.secondary) }
                    ForEach(planner.savedRoutes) { route in
                        Button { openRoute(route); dismiss() } label: {
                            PlaceRow(icon: "point.topleft.down.to.point.bottomright.curvepath", title: route.name,
                                     subtitle: route.waypoints.map(\.name).joined(separator: " → "))
                        }
                        .buttonStyle(.plain)
                        .swipeActions { Button("action.delete", role: .destructive) { planner.deleteRoute(route) } }
                    }
                }
                Section {
                    if planner.savedPlaces.isEmpty { Text("route.savedPlaces.empty").foregroundStyle(.secondary) }
                    ForEach(planner.savedPlaces) { place in
                        PlaceRow(icon: "bookmark.fill", title: place.name, subtitle: place.subtitle)
                            .swipeActions {
                                Button("action.delete", role: .destructive) { planner.deletePlace(place) }
                                Button("action.edit") { newName = place.name; renaming = place }.tint(.blue)
                            }
                    }
                } header: {
                    Text("route.savedPlaces")
                } footer: {
                    Text("route.saved.footer")
                }
            }
            .navigationTitle("route.saved.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("action.done") { dismiss() } } }
            .alert("route.savePlace.rename", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("route.savePlace.name", text: $newName)
                Button("action.save") { if let renaming { planner.renamePlace(renaming, to: newName) } }
                Button("action.cancel", role: .cancel) {
                    // Cancelling is the whole action: the name stays as it was.
                }
            }
        }
    }
}
