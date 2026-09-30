import SwiftUI

struct StationDetailScreen: View {
    let station: Station
    @ObservedObject var authSession: AuthSession
    @StateObject private var viewModel: StationDetailViewModel
    @State private var showCommentEditor = false
    @State private var editingComment: StationComment?
    /// The comment somebody else wrote that the reader is about to report or block the author of.
    @State private var moderatingComment: StationComment?

    init(station: Station, repository: any ChargingStationRepository, authSession: AuthSession) {
        self.station = station
        self.authSession = authSession
        _viewModel = StateObject(wrappedValue: StationDetailViewModel(stationID: station.id, repository: repository))
    }

    var body: some View {
        NavigationStack {
            List {
                StationInformationSection(station: station)
                // Above the infrastructure: "can I charge here now" outranks "what is installed here".
                if let live = viewModel.liveAvailability { StationLiveAvailabilitySection(availability: live) }
                if let detail = viewModel.detail { StationInfrastructureSections(detail: detail) }
                CommentListSection(comments: viewModel.comments, canModerate: authSession.accessToken != nil, edit: { editingComment = $0 },
                                   delete: { comment in Task { await delete(comment) } }, moderate: { moderatingComment = $0 })
            }
            .navigationTitle(station.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if authSession.accessToken != nil {
                    ToolbarItem(placement: .topBarTrailing) { Button { showCommentEditor = true } label: { Label("comments.add", systemImage: "square.and.pencil") } }
                }
            }
            .overlay { if viewModel.isLoading { ProgressView() } }
            .safeAreaInset(edge: .bottom) { if authSession.accessToken == nil { SignInPrompt(authSession: authSession) } }
            .sheet(isPresented: $showCommentEditor) { CommentEditorScreen { await create($0) } }
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
            .alert("error.title", isPresented: Binding(get: { viewModel.errorMessage != nil }, set: { if !$0 { viewModel.errorMessage = nil } })) {
                Button("action.ok", role: .cancel) {
                    // Dismissing is the whole action; the binding's setter clears the message.
                }
            } message: { Text(viewModel.errorMessage ?? "") }
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

    private func delete(_ comment: StationComment) async {
        guard let token = authSession.accessToken else { return }
        await viewModel.deleteComment(comment, accessToken: token)
    }
}
