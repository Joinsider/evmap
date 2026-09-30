import Foundation
import Testing

@testable import EVMap

@Suite("Station detail")
@MainActor
struct StationDetailViewModelTests {
    private let station = Fixtures.station()

    private func model(_ repository: StubStationRepository) -> StationDetailViewModel {
        StationDetailViewModel(stationID: station.id, repository: repository)
    }

    @Test("loads the detail, the comments and then the live status")
    func loadsEverything() async {
        let repository = StubStationRepository()
        repository.stationDetail = .success(Fixtures.detail(for: station))
        repository.commentList = .success([Fixtures.comment()])
        repository.liveStation = .success(Fixtures.live(stationID: station.id))
        let model = model(repository)

        await model.load(accessToken: nil)

        #expect(model.detail?.connectors.count == 2)
        #expect(model.comments.count == 1)
        #expect(model.liveAvailability?.available == 1)
        #expect(!model.isLoading)
        #expect(model.errorMessage == nil)
    }

    @Test("a failed station load is reported, but the live status is still tried")
    func reportsDetailFailure() async {
        let repository = StubStationRepository()
        repository.liveStation = .success(Fixtures.live(stationID: station.id))
        let model = model(repository)

        await model.load(accessToken: nil)

        #expect(model.errorMessage == "no detail")
        #expect(model.liveAvailability != nil)
    }

    @Test("a live source that is down or knows nothing leaves no live section, and no error")
    func hidesUnknownLiveStatus() async {
        let repository = StubStationRepository()
        repository.stationDetail = .success(Fixtures.detail(for: station))
        let model = model(repository)

        await model.load(accessToken: nil)
        #expect(model.liveAvailability == nil)
        #expect(model.errorMessage == nil)

        repository.liveStation = .success(StationLiveAvailability(
            stationID: station.id, status: .unknown, counts: .init(available: 0, occupied: 0, outOfOrder: 0, unknown: 3),
            observedAt: nil, chargePoints: []))
        await model.loadLiveAvailability()
        #expect(model.liveAvailability == nil)
    }

    @Test("a station report is sent with its reason and note, and thanks the person once it is accepted")
    func reportsAStation() async {
        let repository = StubStationRepository()
        let model = model(repository)

        let accepted = await model.reportStation(reason: .wrongPower, note: "It is 11 kW", accessToken: "t")

        #expect(accepted)
        #expect(model.reportAccepted)
        #expect(repository.stationReports.count == 1)
        #expect(repository.stationReports.first?.id == station.id)
        #expect(repository.stationReports.first?.reason == .wrongPower)
        #expect(repository.stationReports.first?.note == "It is 11 kW")
    }

    @Test("a refused station report is an error and is not thanked")
    func refusedStationReport() async {
        let repository = StubStationRepository()
        repository.stationReportResult = .failure(StubStationRepository.Failure(message: "offline"))
        let model = model(repository)

        let accepted = await model.reportStation(reason: .gone, note: nil, accessToken: "t")

        #expect(!accepted)
        #expect(!model.reportAccepted)
        #expect(model.errorMessage == "offline")
    }

    @Test("a new comment goes to the top, an edit replaces it in place, a delete removes it")
    func writesComments() async {
        let repository = StubStationRepository()
        let original = Fixtures.comment(body: "first")
        repository.commentList = .success([original])
        repository.stationDetail = .success(Fixtures.detail(for: station))
        let model = model(repository)
        await model.load(accessToken: "t")

        let created = Fixtures.comment(body: "second")
        repository.commentWrite = .success(created)
        await model.createComment(CommentPayload(body: "second", paidPriceCents: nil, experience: nil), accessToken: "t")
        #expect(model.comments.map(\.body) == ["second", "first"])

        let edited = Fixtures.comment(id: original.id, body: "first, edited")
        repository.commentWrite = .success(edited)
        await model.updateComment(original, payload: CommentPayload(body: "first, edited", paidPriceCents: nil, experience: nil),
                                  accessToken: "t")
        #expect(model.comments.map(\.body) == ["second", "first, edited"])

        await model.deleteComment(created, accessToken: "t")
        #expect(model.comments.map(\.body) == ["first, edited"])
        #expect(repository.deletedComments == [created.id])
    }

    @Test("a failed comment write or delete keeps the list and reports the error")
    func reportsCommentFailures() async {
        let repository = StubStationRepository()
        let existing = Fixtures.comment()
        repository.commentList = .success([existing])
        // The comments are assigned after the detail, so the detail has to load for them to arrive.
        repository.stationDetail = .success(Fixtures.detail(for: station))
        let model = model(repository)
        await model.load(accessToken: "t")
        model.errorMessage = nil

        await model.createComment(CommentPayload(body: "x", paidPriceCents: nil, experience: nil), accessToken: "t")
        #expect(model.errorMessage == "no comment")

        repository.deleteResult = .failure(StubStationRepository.Failure(message: "gone"))
        await model.deleteComment(existing, accessToken: "t")
        #expect(model.errorMessage == "gone")
        #expect(model.comments.count == 1)
    }
}
