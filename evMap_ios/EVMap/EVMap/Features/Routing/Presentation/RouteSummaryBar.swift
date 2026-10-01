import SwiftUI

/// What stays over the map while a route exists and the planner is closed: where it goes, how long it
/// takes, and a way back into the planner. The plan itself lives in the planner sheet.
struct RouteSummaryBar: View {
    @ObservedObject var planner: RoutePlannerViewModel
    let open: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Button(action: open) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.bold()).lineLimit(1)
                    detail.font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text("route.summary.open"))
            Button { planner.clear() } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                .accessibilityLabel("route.clear")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.thinMaterial, in: .rect(cornerRadius: 16))
        .shadow(radius: 2)
        .padding(.horizontal, 12)
    }

    private var title: String {
        let stops = planner.stops
        guard let first = stops.first, let last = stops.last, stops.count > 1 || planner.slots.count > 1 else {
            return String(localized: "route.summary.new")
        }
        return "\(label(first)) → \(planner.slots.last?.waypoint == nil ? String(localized: "route.slot.choose") : label(last))"
    }

    @ViewBuilder
    private var detail: some View {
        switch planner.phase {
        case .idle:
            Text("route.summary.incomplete")
        case .planning:
            Label { Text("route.planning") } icon: { ProgressView().controlSize(.mini) }
        case .failed(let message):
            Text(message)
        case .planned:
            if let route = planner.route, let total = planner.totalDuration {
                Text("\(RouteFormat.duration(total)) · \(RouteFormat.distance(meters: route.distance))")
            }
        }
    }

    private func label(_ waypoint: RouteWaypoint) -> String {
        waypoint.kind == .currentLocation ? String(localized: "route.currentLocation") : waypoint.name
    }
}
