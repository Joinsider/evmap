import SwiftUI

/// The card for a place the person searched for or tapped (ADR 0017): what it is, and what to do about
/// it — drive there, start from there, or take it along as a stop. The way into the planner.
struct PlaceInfoCard: View {
    let place: PlaceSelection
    /// Whether a route is already being planned: then the place can also be added as a stop.
    let hasPlan: Bool
    let choose: (RouteIntent) -> Void
    let save: (String) -> Void
    @State private var isNaming = false
    @State private var name = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text(place.title).font(.title3.bold())
                if !place.subtitle.isEmpty {
                    Text(place.subtitle).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 10) {
                Button { choose(.routeTo) } label: { Label("route.to", systemImage: "arrow.turn.down.right").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
                Button { choose(.routeFrom) } label: { Label("route.from", systemImage: "arrow.up.right").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered)
            }
            .controlSize(.large)
            if hasPlan {
                Button { choose(.addStop) } label: { Label("route.addStop", systemImage: "plus.circle").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
            }
            Button { name = place.title; isNaming = true } label: { Label("route.savePlace", systemImage: "bookmark") }
                .font(.subheadline)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .presentationDetents([.height(hasPlan ? 270 : 220), .medium])
        .presentationBackgroundInteraction(.enabled(upThrough: .height(270)))
        .alert("route.savePlace.title", isPresented: $isNaming) {
            TextField("route.savePlace.name", text: $name)
            Button("action.save") { save(name); dismiss() }
            Button("action.cancel", role: .cancel) {
                // Cancelling is the whole action: nothing is saved.
            }
        } message: { Text("route.savePlace.message") }
    }
}
