import Foundation
import Testing

@testable import EVMap

/// The account area (ADR 0020): deletion, export, blocks, and reporting a comment.
@Suite("Account area", .serialized)
@MainActor
struct AccountTests {
    private struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private func signedIn(_ repository: StubStationRepository) -> AuthSession {
        UserDefaults.standard.set("token", forKey: "EVMapAccessToken")
        return AuthSession(repository: repository)
    }

    private func model(_ repository: StubStationRepository, _ session: AuthSession, directory: URL? = nil) -> AccountViewModel {
        AccountViewModel(repository: repository, authSession: session, exportDirectory: directory ?? FileManager.default.temporaryDirectory)
    }

    @Test("deleting the account signs out, but only once the backend has confirmed it")
    func deletion() async throws {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }

        repository.accountDeletion = .failure(Failure(message: "offline"))
        await #expect(throws: Failure.self) { try await session.deleteAccount() }
        #expect(session.accessToken == "token")
        #expect(!repository.accountDeleted)

        repository.accountDeletion = .success(())
        try await session.deleteAccount()
        #expect(session.accessToken == nil)
        #expect(repository.accountDeleted)
    }

    @Test("the view model deletes the account and reports whether it is gone")
    func modelDeletes() async {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        let model = model(repository, session)
        defer { session.signOut() }

        repository.accountDeletion = .failure(Failure(message: "offline"))
        #expect(await model.deleteAccount() == false)
        #expect(model.errorMessage == "offline")
        #expect(session.accessToken == "token")

        repository.accountDeletion = .success(())
        #expect(await model.deleteAccount())
        #expect(session.accessToken == nil)
    }

    @Test("loads contributions, blocks and the privacy link")
    func loads() async throws {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }
        let block = BlockedAuthor(id: UUID(), createdAt: Date())
        repository.blockList = .success([block])
        repository.contributionList = .success(Contributions(
            comments: [CommentContribution(id: UUID(), stationName: "EnBW", body: "hi", createdAt: Date())],
            reports: [ReportContribution(id: UUID(), reason: "spam", status: "open", stationName: "Ionity", createdAt: Date())]))
        repository.legalInfo = .success(LegalInfo(privacyPolicyUrl: URL(string: "https://evmap.example/privacy")))
        let model = model(repository, session)

        await model.load()

        #expect(model.blocks == [block])
        #expect(model.contributions?.comments.count == 1)
        #expect(model.contributions?.reports.first?.isOpen == true)
        #expect(model.contributions?.reports.first?.reasonName == ReportReason.spam.displayName)
        #expect(model.privacyPolicyURL?.absoluteString == "https://evmap.example/privacy")
    }

    @Test("lifting a block removes it from the list")
    func unblocks() async {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }
        let block = BlockedAuthor(id: UUID(), createdAt: Date())
        repository.blockList = .success([block])
        let model = model(repository, session)
        await model.load()

        await model.unblock(block)

        #expect(model.blocks.isEmpty)
        #expect(repository.liftedBlocks == [block.id])
    }

    @Test("a token the backend no longer accepts signs the session out")
    func staleTokenSignsOut() async {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }
        repository.blockList = .failure(APIError.unauthenticated)
        let model = model(repository, session)

        await model.load()

        #expect(session.accessToken == nil)
    }

    @Test("the export is written to a file that goes away with the screen")
    func export() async throws {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }
        repository.exportResult = .success(Data(#"{"account":{}}"#.utf8))
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let model = model(repository, session, directory: directory)

        await model.prepareExport()

        let url = try #require(model.exportURL)
        #expect(try Data(contentsOf: url) == Data(#"{"account":{}}"#.utf8))
        #expect(url.lastPathComponent == "evmap-export.json")

        model.discardExport()
        #expect(model.exportURL == nil)
        #expect(!FileManager.default.fileExists(atPath: url.path()))
    }

    @Test("a failed export is reported and leaves no file")
    func failedExport() async {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }
        repository.exportResult = .failure(Failure(message: "offline"))
        let model = model(repository, session)

        await model.prepareExport()

        #expect(model.exportURL == nil)
        #expect(model.errorMessage == "offline")
    }

    @Test("Sign in with Apple hands the authorization code to the backend")
    func appleAuthorizationCode() async throws {
        let repository = StubStationRepository()
        let session = signedIn(repository)
        defer { session.signOut() }
        _ = try await repository.signInWithApple(identityToken: "id", authorizationCode: "code")
        #expect(repository.appleAuthorizationCodes == ["code"])
    }

    @Test("reporting a comment hides it at once; a failed report keeps it and says why")
    func reports() async {
        let repository = StubStationRepository()
        let station = Fixtures.station()
        let other = Fixtures.comment(body: "spam", ownedByCurrentUser: false)
        let kept = Fixtures.comment(body: "fine", ownedByCurrentUser: false)
        repository.commentList = .success([other, kept])
        repository.stationDetail = .success(Fixtures.detail(for: station))
        let model = StationDetailViewModel(stationID: station.id, repository: repository)
        await model.load(accessToken: "t")

        repository.reportResult = .failure(Failure(message: "nope"))
        await model.report(other, reason: .spam, accessToken: "t")
        #expect(model.comments.count == 2)
        #expect(model.errorMessage == "nope")

        model.errorMessage = nil
        repository.reportResult = .success(())
        await model.report(other, reason: .spam, accessToken: "t")
        #expect(model.comments.map(\.body) == ["fine"])
        #expect(repository.reports.first?.reason == .spam)
    }

    @Test("blocking an author reloads the comments the backend now leaves out")
    func blocks() async {
        let repository = StubStationRepository()
        let station = Fixtures.station()
        let theirs = Fixtures.comment(body: "theirs", ownedByCurrentUser: false)
        let mine = Fixtures.comment(body: "mine")
        repository.commentList = .success([theirs, mine])
        repository.stationDetail = .success(Fixtures.detail(for: station))
        let model = StationDetailViewModel(stationID: station.id, repository: repository)
        await model.load(accessToken: "t")

        repository.commentList = .success([mine])
        await model.blockAuthor(of: theirs, accessToken: "t")

        #expect(model.comments.map(\.body) == ["mine"])
        #expect(repository.blockedComments == [theirs.id])
    }

    @Test("every report reason has a label and a backend token")
    func reasons() {
        #expect(ReportReason.allCases.map(\.rawValue) == ["spam", "offensive", "wrong", "other"])
        #expect(ReportReason.allCases.allSatisfy { !$0.displayName.isEmpty && $0.displayName != "report.reason.\($0.rawValue)" })
    }
}
