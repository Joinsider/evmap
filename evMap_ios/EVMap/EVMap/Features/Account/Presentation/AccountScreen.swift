import SwiftUI

/// Everything about the signed-in person's account (ADR 0020): contributions, blocked authors, the
/// data export, sign-out and the deletion App Store review requires. Pushed from the settings.
struct AccountScreen: View {
    @ObservedObject var authSession: AuthSession
    @StateObject private var model: AccountViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var isConfirmingDeletion = false

    init(repository: any ChargingStationRepository, authSession: AuthSession) {
        self.authSession = authSession
        _model = StateObject(wrappedValue: AccountViewModel(repository: repository, authSession: authSession))
    }

    var body: some View {
        Form {
            if authSession.accessToken == nil {
                Section { SignInPrompt(authSession: authSession, message: "account.signInRequired") }
            } else {
                contributionsSection
                blocksSection
                dataSection
                Section {
                    Button("account.signOut") { authSession.signOut(); dismiss() }
                }
                Section {
                    Button("account.delete", role: .destructive) { isConfirmingDeletion = true }
                        .disabled(model.isBusy)
                } footer: {
                    Text("account.delete.footer")
                }
            }
            if let url = model.privacyPolicyURL {
                Section { Link("account.privacy", destination: url) }
            }
        }
        .navigationTitle("account.title")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: authSession.accessToken) { await model.load() }
        .onDisappear { model.discardExport() }
        .confirmationDialog("account.delete.confirm", isPresented: $isConfirmingDeletion, titleVisibility: .visible) {
            Button("account.delete.action", role: .destructive) {
                Task { if await model.deleteAccount() { dismiss() } }
            }
            Button("action.cancel", role: .cancel) {
                // Cancelling is the whole action: the dialog closes and nothing is deleted.
            }
        } message: {
            Text("account.delete.message")
        }
        .alert("error.title", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("action.ok", role: .cancel) {
                // Dismissing is the whole action; the binding's setter clears the message.
            }
        } message: { Text(model.errorMessage ?? "") }
    }

    private var contributionsSection: some View {
        Section("account.contributions") {
            if let contributions = model.contributions {
                if contributions.comments.isEmpty && contributions.reports.isEmpty {
                    Text("account.contributions.empty").foregroundStyle(.secondary)
                }
                ForEach(contributions.comments) { comment in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(comment.body)
                        Text("\(comment.stationName ?? "") · \(comment.createdAt.formatted(date: .abbreviated, time: .omitted))")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                ForEach(contributions.reports) { report in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("account.report \(report.reasonName)")
                        Text("\(report.isOpen ? String(localized: "account.report.open") : String(localized: "account.report.closed")) · \(report.stationName ?? "")")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            } else {
                ProgressView()
            }
        }
    }

    private var blocksSection: some View {
        Section {
            ForEach(model.blocks) { block in
                HStack {
                    Text("account.blockedOn \(block.createdAt.formatted(date: .abbreviated, time: .omitted))")
                    Spacer()
                    Button("account.unblock") { Task { await model.unblock(block) } }
                        .buttonStyle(.borderless)
                }
            }
            if model.blocks.isEmpty { Text("account.blocks.empty").foregroundStyle(.secondary) }
        } header: {
            Text("account.blocks")
        } footer: {
            Text("account.blocks.footer")
        }
    }

    private var dataSection: some View {
        Section {
            if let url = model.exportURL {
                ShareLink(item: url) { Label("account.export.share", systemImage: "square.and.arrow.up") }
            } else {
                Button("account.export") { Task { await model.prepareExport() } }.disabled(model.isBusy)
            }
        } header: {
            Text("account.data")
        } footer: {
            Text("account.export.footer")
        }
    }
}
