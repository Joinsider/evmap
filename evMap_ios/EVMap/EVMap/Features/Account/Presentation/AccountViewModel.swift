import Combine
import Foundation

/// The account area's state (ADR 0020): what the person contributed, whom they blocked, the data
/// export and the deletion. Every call needs the signed-in token; a token the backend no longer accepts
/// (the account was deleted elsewhere) signs the session out instead of showing an error forever.
@MainActor
final class AccountViewModel: ObservableObject {
    @Published private(set) var contributions: Contributions?
    @Published private(set) var blocks: [BlockedAuthor] = []
    @Published private(set) var privacyPolicyURL: URL?
    /// The prepared export file, ready to be shared. Cleared whenever the account is left.
    @Published private(set) var exportURL: URL?
    @Published private(set) var isBusy = false
    @Published var errorMessage: String?

    private let repository: any ChargingStationRepository
    private let authSession: AuthSession
    private let exportDirectory: URL

    init(repository: any ChargingStationRepository, authSession: AuthSession,
         exportDirectory: URL = FileManager.default.temporaryDirectory) {
        self.repository = repository
        self.authSession = authSession
        self.exportDirectory = exportDirectory
    }

    func load() async {
        // The privacy link does not depend on signing in, and it must show even when everything else fails.
        privacyPolicyURL = try? await repository.legal().privacyPolicyUrl
        guard let token = authSession.accessToken else { return }
        do {
            async let fetchedContributions = repository.contributions(accessToken: token)
            async let fetchedBlocks = repository.blockedAuthors(accessToken: token)
            contributions = try await fetchedContributions
            blocks = try await fetchedBlocks
        } catch {
            handle(error, while: "loading the account")
        }
    }

    func unblock(_ block: BlockedAuthor) async {
        guard let token = authSession.accessToken else { return }
        do {
            try await repository.unblock(id: block.id, accessToken: token)
            blocks.removeAll { $0.id == block.id }
            AppLogger.auth.notice("Lifted block \(block.id)")
        } catch {
            handle(error, while: "lifting a block")
        }
    }

    /// Fetches the export and writes it where the share sheet can pick it up. The file holds personal
    /// data, so it is written with the strictest file protection and lives in the temporary directory.
    func prepareExport() async {
        guard let token = authSession.accessToken else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            let data = try await repository.exportData(accessToken: token)
            let url = exportDirectory.appending(path: "evmap-export.json")
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            exportURL = url
            AppLogger.auth.notice("Prepared the data export (\(AppLogger.size(data.count)))")
        } catch {
            handle(error, while: "exporting the account data")
        }
    }

    /// Deletes the account. Returns whether it is gone; the export file, if any, goes with it.
    func deleteAccount() async -> Bool {
        isBusy = true
        defer { isBusy = false }
        do {
            try await authSession.deleteAccount()
            discardExport()
            return true
        } catch {
            handle(error, while: "deleting the account")
            return false
        }
    }

    func discardExport() {
        if let exportURL { try? FileManager.default.removeItem(at: exportURL) }
        exportURL = nil
    }

    private func handle(_ error: Error, while action: String) {
        AppLogger.auth.error("Account area failed while \(action)", error: error)
        if case APIError.unauthenticated = error {
            authSession.signOut()
        }
        errorMessage = error.localizedDescription
    }
}
