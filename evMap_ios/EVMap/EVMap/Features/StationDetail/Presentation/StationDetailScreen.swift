import SwiftUI

struct StationDetailScreen: View {
    let station: Station
    @ObservedObject var authSession: AuthSession
    @ObservedObject var favorites: FavoritesViewModel
    /// Whether a route is being planned, which adds "add as a stop" to the route actions (ADR 0017).
    let hasRoute: Bool
    /// What to do with this station as a place on a route; the map screen carries it out.
    let routeAction: (RouteIntent) -> Void
    @StateObject private var viewModel: StationDetailViewModel
    @State private var showCommentEditor = false
    @State private var showReport = false
    @State private var editingComment: StationComment?
    /// The comment somebody else wrote that the reader is about to report or block the author of.
    @State private var moderatingComment: StationComment?

    /// The default for screens opened without a map behind them (tests, previews): the route actions do nothing.
    static func ignoreRouteAction(_: RouteIntent) {
        // Deliberately empty: there is no planner to hand the intent to.
    }

    init(station: Station, repository: any ChargingStationRepository, authSession: AuthSession, favorites: FavoritesViewModel,
         hasRoute: Bool = false, routeAction: @escaping (RouteIntent) -> Void = StationDetailScreen.ignoreRouteAction) {
        self.station = station
        self.authSession = authSession
        self.favorites = favorites
        self.hasRoute = hasRoute
        self.routeAction = routeAction
        _viewModel = StateObject(wrappedValue: StationDetailViewModel(stationID: station.id, repository: repository))
    }

    var body: some View {
        NavigationStack {
            List {
                StationInformationSection(station: station, fromPrice: viewModel.prices.flatMap { PriceFormatter.from($0) })
                StationRouteSection(hasRoute: hasRoute, routeAction: routeAction)
                // Above the infrastructure: "can I charge here now" outranks "what is installed here".
                if let live = viewModel.liveAvailability { StationLiveAvailabilitySection(availability: live) }
                if let prices = viewModel.prices { StationPriceSection(prices: prices, stationOperator: station.operatorName) }
                if let detail = viewModel.detail { StationInfrastructureSections(detail: detail) }
                CommentListSection(comments: viewModel.comments, canModerate: authSession.accessToken != nil, edit: { editingComment = $0 },
                                   delete: { comment in Task { await delete(comment) } }, moderate: { moderatingComment = $0 })
                if authSession.accessToken != nil {
                    Section {
                        Button { showReport = true } label: { Label("station.report", systemImage: "exclamationmark.bubble") }
                    }
                }
            }
            // On the list rather than the stack: one alert per view, and the stack's shows errors.
            .alert("station.report.thanks.title", isPresented: $viewModel.reportAccepted) {
                Button("action.ok", role: .cancel) {
                    // Dismissing is the whole action.
                }
            } message: { Text("station.report.thanks.message") }
            .navigationTitle(station.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { Task { await favorites.toggle(station) } } label: {
                        Label(favorites.isFavorite(station) ? "favorites.remove" : "favorites.add",
                              systemImage: favorites.isFavorite(station) ? "star.fill" : "star")
                    }
                }
                if authSession.accessToken != nil {
                    ToolbarItem(placement: .topBarTrailing) { Button { showCommentEditor = true } label: { Label("comments.add", systemImage: "square.and.pencil") } }
                }
            }
            .overlay { if viewModel.isLoading { ProgressView() } }
            .safeAreaInset(edge: .bottom) { if authSession.accessToken == nil { SignInPrompt(authSession: authSession) } }
            .sheet(isPresented: $showCommentEditor) { CommentEditorScreen { await create($0) } }
            .sheet(isPresented: $showReport) { StationReportScreen { reason, note in await report(reason: reason, note: note) } }
            .sheet(item: $editingComment) { comment in CommentEditorScreen(comment: comment) { await update(comment, payload: $0) } }
            .confirmationDialog("comments.moderation.title", isPresented: Binding(get: { moderatingComment != nil }, set: { if !$0 { moderatingComment = nil } }),
                                titleVisibility: .visible, presenting: moderatingComment) { comment in
                ForEach(ReportReason.allCases) { reason in
                    Button(reason.displayName) { Task { await report(comment, reason: reason) } }
                }
                Button("comments.blockAuthor", role: .destructive) { Task { await blockAuthor(of: comment) } }
                Button("action.cancel", role: .cancel) {
                    // Cancelling is the whole action: nothing is reported or blocked.
                }
            } message: { _ in
                Text("comments.moderation.message")
            }
            // Both view models report here: a failed favorite toggle happens while this sheet is up,
            // where the map's own alert could not be shown.
            .alert("error.title", isPresented: Binding(get: { viewModel.errorMessage != nil || favorites.errorMessage != nil },
                                                        set: { if !$0 { viewModel.errorMessage = nil; favorites.errorMessage = nil } })) {
                Button("action.ok", role: .cancel) {
                    // Dismissing is the whole action; the binding's setter clears the message.
                }
            } message: { Text(viewModel.errorMessage ?? favorites.errorMessage ?? "") }
            .task(id: authSession.accessToken) { await viewModel.load(accessToken: authSession.accessToken) }
        }
    }

    private func create(_ payload: CommentPayload) async {
        guard let token = authSession.accessToken else { return }
        await viewModel.createComment(payload, accessToken: token)
        showCommentEditor = viewModel.errorMessage != nil
    }

    private func update(_ comment: StationComment, payload: CommentPayload) async {
        guard let token = authSession.accessToken else { return }
        await viewModel.updateComment(comment, payload: payload, accessToken: token)
        if viewModel.errorMessage == nil { editingComment = nil }
    }

    private func report(_ comment: StationComment, reason: ReportReason) async {
        guard let token = authSession.accessToken else { return }
        await viewModel.report(comment, reason: reason, accessToken: token)
    }

    private func blockAuthor(of comment: StationComment) async {
        guard let token = authSession.accessToken else { return }
        await viewModel.blockAuthor(of: comment, accessToken: token)
    }

    private func report(reason: StationReportReason, note: String?) async -> Bool {
        guard let token = authSession.accessToken else { return false }
        return await viewModel.reportStation(reason: reason, note: note, accessToken: token)
    }

    private func delete(_ comment: StationComment) async {
        guard let token = authSession.accessToken else { return }
        await viewModel.deleteComment(comment, accessToken: token)
    }
}

/// "Route here", "route from here" and "add as stop" for a charging station (ADR 0017) — the same actions
/// the info card offers for any other place.
private struct StationRouteSection: View {
    let hasRoute: Bool
    let routeAction: (RouteIntent) -> Void

    var body: some View {
        Section {
            HStack(spacing: 10) {
                Button { routeAction(.routeTo) } label: { Label("route.to", systemImage: "arrow.turn.down.right").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
                Button { routeAction(.routeFrom) } label: { Label("route.from", systemImage: "arrow.up.right").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered)
            }
            .controlSize(.large)
            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            if hasRoute {
                Button { routeAction(.addStop) } label: { Label("route.addChargingStop", systemImage: "plus.circle") }
            }
        }
    }
}
