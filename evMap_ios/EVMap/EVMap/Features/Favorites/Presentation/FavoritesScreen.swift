import SwiftUI

/// The favorite stations as a list (ADR 0021). A tap hands the station back to the map, which centers
/// on it and opens it.
struct FavoritesScreen: View {
    @ObservedObject var model: FavoritesViewModel
    @ObservedObject var authSession: AuthSession
    let select: (Station) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if model.stations.isEmpty {
                    Text("favorites.empty").foregroundStyle(.secondary)
                }
                ForEach(model.stations) { station in
                    Button { select(station) } label: { FavoriteRow(station: station) }
                        .buttonStyle(.plain)
                        .swipeActions {
                            Button("favorites.remove", role: .destructive) { Task { await model.remove(station) } }
                        }
                }
                Section {
                    // Nothing in the section itself: the text is the footer, stating where favorites live.
                } footer: {
                    Text(authSession.accessToken == nil ? "favorites.deviceOnly" : "favorites.synced")
                }
            }
            .navigationTitle("favorites.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("action.done") { dismiss() } }
            }
            .alert("error.title", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
                Button("action.ok", role: .cancel) {
                    // Dismissing is the whole action; the binding's setter clears the message.
                }
            } message: { Text(model.errorMessage ?? "") }
        }
    }
}

private struct FavoriteRow: View {
    let station: Station

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(station.displayName)
            Text([station.operatorName, station.address].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                .font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}
