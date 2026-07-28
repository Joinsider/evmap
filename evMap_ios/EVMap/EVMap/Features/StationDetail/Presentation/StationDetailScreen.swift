import SwiftUI

struct StationDetailScreen: View {
    let station: Station
    @ObservedObject var authSession: AuthSession
    @StateObject private var viewModel: StationDetailViewModel
    @State private var showCommentEditor = false
    @State private var editingComment: StationComment?

    init(station: Station, repository: any ChargingStationRepository, authSession: AuthSession) {
        self.station = station
        self.authSession = authSession
        _viewModel = StateObject(wrappedValue: StationDetailViewModel(stationID: station.id, repository: repository))
    }

    var body: some View {
        NavigationStack {
            List {
                StationInformationSection(station: station)
                if let detail = viewModel.detail { StationInfrastructureSections(detail: detail) }
                CommentListSection(comments: viewModel.comments, edit: { editingComment = $0 }, delete: { comment in Task { await delete(comment) } })
            }
            .navigationTitle(station.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if authSession.accessToken != nil {
                    ToolbarItem(placement: .topBarTrailing) { Button { showCommentEditor = true } label: { Label("comments.add", systemImage: "square.and.pencil") } }
                }
            }
            .overlay { if viewModel.isLoading { ProgressView() } }
            .safeAreaInset(edge: .bottom) { if authSession.accessToken == nil { AppleSignInPrompt(authSession: authSession) } }
            .sheet(isPresented: $showCommentEditor) { CommentEditorScreen { await create($0) } }
            .sheet(item: $editingComment) { comment in CommentEditorScreen(comment: comment) { await update(comment, payload: $0) } }
            .alert("error.title", isPresented: Binding(get: { viewModel.errorMessage != nil }, set: { if !$0 { viewModel.errorMessage = nil } })) {
                Button("action.ok", role: .cancel) { }
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

    private func delete(_ comment: StationComment) async {
        guard let token = authSession.accessToken else { return }
        await viewModel.deleteComment(comment, accessToken: token)
    }
}
