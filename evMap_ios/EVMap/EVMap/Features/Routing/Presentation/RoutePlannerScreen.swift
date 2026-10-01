import CoreLocation
import MapKit
import SwiftUI

/// The manual route planner (ADR 0017): the stops with their stays, the route options, the route and its
/// alternatives, the stations along it ranked by detour, breaks, and the way out to a navigation app.
struct RoutePlannerScreen: View {
    @ObservedObject var planner: RoutePlannerViewModel
    @ObservedObject var favorites: FavoritesViewModel
    let currentLocation: CLLocationCoordinate2D?
    /// Shows a station on the map and opens it; the planner closes first.
    let showStation: (Station) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pickingSlot: UUID?
    @State private var isConfirmingClear = false
    @State private var isNamingRoute = false
    @State private var routeName = ""
    @State private var showSaved = false

    var body: some View {
        NavigationStack {
            List {
                RouteStopsSection(planner: planner, pick: { pickingSlot = $0 })
                RouteOptionsSection(planner: planner)
                RouteSummarySection(planner: planner)
                if planner.route != nil {
                    RouteStationsSection(planner: planner, showStation: showStation)
                    RouteBreaksSection(planner: planner)
                    RouteHandoffSection(planner: planner)
                }
                RouteKeepSection(planner: planner, nameRoute: { routeName = ""; isNamingRoute = true }, showSaved: { showSaved = true },
                                 clear: { isConfirmingClear = true })
            }
            .navigationTitle("route.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { EditButton() }
                ToolbarItem(placement: .topBarTrailing) { Button("action.done") { dismiss() } }
            }
            .confirmationDialog("route.clear.confirm", isPresented: $isConfirmingClear, titleVisibility: .visible) {
                Button("route.clear", role: .destructive) { planner.clear() }
                Button("action.cancel", role: .cancel) {
                    // Cancelling is the whole action: the plan stays.
                }
            }
            .alert("route.save.title", isPresented: $isNamingRoute) {
                TextField("route.save.name", text: $routeName)
                Button("action.save") { planner.saveRoute(named: routeName) }
                Button("action.cancel", role: .cancel) {
                    // Cancelling is the whole action: nothing is saved.
                }
            } message: { Text("route.save.message") }
            .alert("error.title", isPresented: Binding(get: { planner.errorMessage != nil }, set: { if !$0 { planner.errorMessage = nil } })) {
                Button("action.ok", role: .cancel) {
                    // Dismissing is the whole action; the binding's setter clears the message.
                }
            } message: { Text(planner.errorMessage ?? "") }
            .sheet(item: Binding(get: { pickingSlot.map(SlotID.init) }, set: { pickingSlot = $0?.id })) { slot in
                WaypointPickerScreen(planner: planner, favorites: favorites, pick: { planner.fill(slot: slot.id, with: $0) },
                                     currentLocation: currentLocation)
            }
            .sheet(isPresented: $showSaved) { SavedRoutingScreen(planner: planner, openRoute: { planner.open($0) }) }
        }
        // The map stays in view and usable beside the plan while it is half height.
        .presentationDetents([.medium, .large])
        .presentationBackgroundInteraction(.enabled(upThrough: .medium))
    }

    private struct SlotID: Identifiable { let id: UUID }
}

// MARK: Stops

private struct RouteStopsSection: View {
    @ObservedObject var planner: RoutePlannerViewModel
    let pick: (UUID) -> Void

    var body: some View {
        Section {
            ForEach(Array(planner.slots.enumerated()), id: \.element.id) { index, slot in
                RouteStopRow(role: role(index), slot: slot, arrival: planner.arrivalOffset(atSlot: index),
                             pick: { pick(slot.id) }, setDwell: { planner.setDwell($0, forSlot: slot.id) })
                    .swipeActions { Button("action.delete", role: .destructive) { planner.remove(slot: slot.id) } }
            }
            .onMove { planner.move(fromOffsets: $0, toOffset: $1) }
            HStack {
                Button { planner.addEmptyStop() } label: { Label("route.addWaypoint", systemImage: "plus.circle") }
                    .disabled(!planner.canAddStop)
                Spacer()
                Button { planner.reverse() } label: { Label("route.reverse", systemImage: "arrow.up.arrow.down") }
                    .labelStyle(.iconOnly)
                    .disabled(planner.slots.count < 2)
                    .accessibilityLabel("route.reverse")
            }
            .buttonStyle(.borderless)
        } header: {
            Text("route.stops")
        }
    }

    private func role(_ index: Int) -> RouteStopRow.Role {
        if index == 0 { return .start }
        return index == planner.slots.count - 1 ? .destination : .waypoint
    }
}

private struct RouteStopRow: View {
    enum Role {
        case start, waypoint, destination

        var title: LocalizedStringKey {
            switch self {
            case .start: "route.role.start"
            case .waypoint: "route.role.waypoint"
            case .destination: "route.role.destination"
            }
        }

        var symbol: String {
            switch self {
            case .start: "circle.fill"
            case .waypoint: "circle"
            case .destination: "mappin.circle.fill"
            }
        }

        var color: Color {
            switch self {
            case .start: .green
            case .waypoint: .blue
            case .destination: .red
            }
        }
    }

    /// Stays offered for a stop: a coffee, a charge, a meal, a night.
    private static let dwellChoices = [0, 15, 30, 45, 60, 90, 120, 180, 240, 480, 720, 1_440]

    let role: Role
    let slot: RouteSlot
    let arrival: TimeInterval?
    let pick: () -> Void
    let setDwell: (Int) -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: role.symbol).foregroundStyle(role.color)
            Button(action: pick) { label }
                .buttonStyle(.plain)
            Spacer(minLength: 4)
            // Staying at the start is waiting to leave, which is not something to plan.
            if let waypoint = slot.waypoint, role != .start { dwellMenu(waypoint) }
        }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private var label: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let waypoint = slot.waypoint {
                Text(waypoint.kind == .currentLocation ? String(localized: "route.currentLocation") : waypoint.name)
                HStack(spacing: 6) {
                    Text(role.title)
                    if let arrival, role != .start { Text("· " + String(format: String(localized: "route.arrival"), RouteFormat.arrival(after: arrival))) }
                }
                .font(.caption).foregroundStyle(.secondary)
            } else {
                Text("route.slot.choose").foregroundStyle(.tint)
                Text(role.title).font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    private func dwellMenu(_ waypoint: RouteWaypoint) -> some View {
        Menu {
            Picker("route.dwell", selection: Binding(get: { waypoint.dwellMinutes }, set: setDwell)) {
                ForEach(Self.dwellChoices, id: \.self) { minutes in
                    Text(minutes == 0 ? String(localized: "route.dwell.none") : RouteFormat.minutes(minutes)).tag(minutes)
                }
            }
        } label: {
            Label(waypoint.dwellMinutes == 0 ? String(localized: "route.dwell") : RouteFormat.minutes(waypoint.dwellMinutes), systemImage: "clock")
                .font(.caption)
                .labelStyle(.titleAndIcon)
        }
        .buttonStyle(.bordered)
        .controlSize(.small)
    }
}

// MARK: Options and summary

private struct RouteOptionsSection: View {
    @ObservedObject var planner: RoutePlannerViewModel

    var body: some View {
        Section("route.options") {
            Toggle("route.avoidTolls", isOn: Binding(get: { planner.options.avoidTolls },
                                                     set: { var options = planner.options; options.avoidTolls = $0; planner.setOptions(options) }))
            Toggle("route.avoidMotorways", isOn: Binding(get: { planner.options.avoidMotorways },
                                                         set: { var options = planner.options; options.avoidMotorways = $0; planner.setOptions(options) }))
        }
    }
}

private struct RouteSummarySection: View {
    @ObservedObject var planner: RoutePlannerViewModel

    var body: some View {
        switch planner.phase {
        case .idle:
            if planner.hasPlan {
                Section { Text("route.summary.incomplete").foregroundStyle(.secondary) }
            }
        case .planning:
            Section { Label { Text("route.planning") } icon: { ProgressView() } }
        case .failed(let message):
            Section { Label(message, systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
        case .planned:
            if let route = planner.route {
                Section("route.summary") {
                    if planner.alternatives.count > 1 {
                        ForEach(Array(planner.alternatives.enumerated()), id: \.element.id) { index, alternative in
                            Button { planner.selectAlternative(index) } label: { alternativeRow(alternative, index: index) }
                                .buttonStyle(.plain)
                        }
                    }
                    LabeledContent("route.drive", value: RouteFormat.duration(route.travelTime))
                    if planner.totalDwellMinutes > 0 {
                        LabeledContent("route.stays", value: RouteFormat.minutes(planner.totalDwellMinutes))
                    }
                    if let total = planner.totalDuration {
                        LabeledContent("route.arrival.total", value: RouteFormat.arrival(after: total))
                    }
                    LabeledContent("route.distance", value: RouteFormat.distance(meters: route.distance))
                    if route.hasTolls { Label("route.hasTolls", systemImage: "eurosign.circle").font(.caption).foregroundStyle(.secondary) }
                }
            }
        }
    }

    private func alternativeRow(_ route: PlannedRoute, index: Int) -> some View {
        HStack {
            Image(systemName: index == planner.selectedIndex ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(index == planner.selectedIndex ? Color.accentColor : .secondary)
            VStack(alignment: .leading, spacing: 1) {
                Text(route.name.isEmpty ? String(format: String(localized: "route.alternative"), index + 1) : route.name)
                Text("\(RouteFormat.duration(route.travelTime)) · \(RouteFormat.distance(meters: route.distance))")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: Stations

private struct RouteStationsSection: View {
    @ObservedObject var planner: RoutePlannerViewModel
    let showStation: (Station) -> Void
    @State private var amenities: [UUID: [BreakSuggestion]] = [:]

    var body: some View {
        Section {
            if planner.isLoadingStations && planner.candidates.isEmpty {
                Label { Text("stations.loading") } icon: { ProgressView() }
            }
            if let failure = planner.stationsFailure {
                Label(failure, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
            }
            if !planner.isLoadingStations && planner.candidates.isEmpty && planner.stationsFailure == nil {
                Text("route.stations.empty").foregroundStyle(.secondary)
            }
            ForEach(planner.sortedCandidates) { candidate in
                VStack(alignment: .leading, spacing: 4) {
                    RouteStationRow(candidate: candidate, show: { showStation(candidate.station) },
                                    addStop: { planner.addChargingStop(candidate.station) },
                                    showAmenities: { Task { amenities[candidate.id] = await planner.amenities(near: candidate.station) } })
                    if let nearby = amenities[candidate.id] {
                        Text(nearby.isEmpty ? String(localized: "route.amenities.none") : nearby.map { "\($0.category.title): \($0.name)" }.prefix(4).joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        } header: {
            HStack {
                Text("route.stations")
                Spacer()
                Picker("route.sort", selection: $planner.stationSort) {
                    Text("route.sort.detour").tag(StationSort.detour)
                    Text("route.sort.along").tag(StationSort.alongRoute)
                }
                .pickerStyle(.menu)
                .textCase(nil)
            }
        } footer: {
            if planner.detoursThrottled { Text("route.detours.throttled") }
        }
    }
}

private struct RouteStationRow: View {
    let candidate: RouteStopCandidate
    let show: () -> Void
    let addStop: () -> Void
    let showAmenities: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Button(action: show) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(candidate.station.displayName)
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Text(RouteFormat.detour(candidate))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(candidate.isDetourExact ? .primary : .secondary)
            Menu {
                Button(action: addStop) { Label("route.addChargingStop", systemImage: "plus.circle") }
                Button(action: showAmenities) { Label("route.amenities", systemImage: "fork.knife") }
                Button(action: show) { Label("route.showOnMap", systemImage: "map") }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .accessibilityLabel("route.stationActions")
        }
    }

    private var subtitle: String {
        let power = candidate.station.maxPowerKw.map(formattedPower(kW:))
        let position = String(format: String(localized: "route.atKm"), RouteFormat.kilometers(candidate.routeStation.distanceAlongRouteKm))
        return [candidate.station.operatorName, power, position].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

// MARK: Breaks

private struct RouteBreaksSection: View {
    @ObservedObject var planner: RoutePlannerViewModel

    var body: some View {
        Section {
            if planner.breaks.isEmpty {
                Button { planner.loadBreaks() } label: {
                    if planner.isLoadingBreaks {
                        Label { Text("route.breaks.loading") } icon: { ProgressView() }
                    } else {
                        Label("route.breaks.load", systemImage: "cup.and.saucer")
                    }
                }
                .disabled(planner.isLoadingBreaks)
            }
            ForEach(planner.breaks) { suggestion in
                HStack {
                    Label {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(suggestion.name)
                            Text("\(suggestion.category.title) · \(String(format: String(localized: "route.atKm"), RouteFormat.kilometers(suggestion.distanceAlongRouteKm)))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: suggestion.category.symbol).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button { planner.apply(.addStop, to: suggestion.waypoint) } label: { Image(systemName: "plus.circle") }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("route.addStop")
                }
            }
        } header: {
            Text("route.breaks")
        } footer: {
            Text("route.breaks.footer")
        }
    }
}

// MARK: Handoff, keeping

private struct RouteHandoffSection: View {
    @ObservedObject var planner: RoutePlannerViewModel
    @Environment(\.openURL) private var openURL

    var body: some View {
        Section {
            if let url = planner.googleMapsURL {
                Button { openURL(url) } label: { Label("route.handoff.google", systemImage: "arrow.up.forward.app") }
            } else if planner.isPlannable {
                Text("route.handoff.google.tooMany").font(.caption).foregroundStyle(.secondary)
            }
            if planner.isPlannable, planner.stops.count >= 2 {
                if planner.stops.count == 2, let items = RouteHandoff.appleMapsItems(for: planner.stops, leg: 0) {
                    Button { RouteHandoff.openInAppleMaps(items) } label: { Label("route.handoff.apple", systemImage: "map") }
                } else {
                    ForEach(0..<(planner.stops.count - 1), id: \.self) { leg in
                        if let items = RouteHandoff.appleMapsItems(for: planner.stops, leg: leg) {
                            Button { RouteHandoff.openInAppleMaps(items) } label: {
                                Label(String(format: String(localized: "route.handoff.leg"), leg + 1, name(planner.stops[leg]), name(planner.stops[leg + 1])),
                                      systemImage: "map")
                            }
                        }
                    }
                }
            }
        } header: {
            Text("route.handoff")
        } footer: {
            Text("route.handoff.footer")
        }
    }

    private func name(_ waypoint: RouteWaypoint) -> String {
        waypoint.kind == .currentLocation ? String(localized: "route.currentLocation") : waypoint.name
    }
}

private struct RouteKeepSection: View {
    @ObservedObject var planner: RoutePlannerViewModel
    let nameRoute: () -> Void
    let showSaved: () -> Void
    let clear: () -> Void

    var body: some View {
        Section {
            if let url = planner.shareURL {
                ShareLink(item: url) { Label("route.share", systemImage: "square.and.arrow.up") }
            }
            Button(action: nameRoute) { Label("route.save", systemImage: "bookmark") }
                .disabled(!planner.isPlannable)
            Button(action: showSaved) { Label("route.saved.title", systemImage: "list.bullet") }
            Button(role: .destructive, action: clear) { Label("route.clear", systemImage: "trash") }
        } footer: {
            Text("route.keep.footer")
        }
    }
}
