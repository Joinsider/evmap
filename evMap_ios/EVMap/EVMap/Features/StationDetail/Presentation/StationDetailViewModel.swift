import Combine
import Foundation

@MainActor
final class StationDetailViewModel: ObservableObject {
    @Published private(set) var detail: StationDetail?
    @Published private(set) var comments: [StationComment] = []
    /// Live occupancy, `nil` until it arrives and after it failed. Never an error the user sees:
    /// a live source being down means the screen shows no live section, not a broken station.
    @Published private(set) var liveAvailability: StationLiveAvailability?
    @Published private(set) var isLoading = true
    @Published var errorMessage: String?

    private let stationID: UUID
    private let repository: any ChargingStationRepository

    init(stationID: UUID, repository: any ChargingStationRepository) {
        self.stationID = stationID
        self.repository = repository
    }

    func load(accessToken: String?) async {
        isLoading = true
        defer { isLoading = false }
        do {
            try await AppLogger.stations.measure("Station \(stationID) detail + comments") {
                async let stationDetail = repository.detail(id: stationID)
                async let stationComments = repository.comments(stationID: stationID, accessToken: accessToken)
                detail = try await stationDetail
                comments = try await stationComments
            }
            AppLogger.stations.debug("Loaded \(detail?.connectors.count ?? 0) connector(s) from source(s) \(detail?.sources.joined(separator: ", ") ?? "-"), \(comments.count) comment(s)")
        } catch {
            errorMessage = error.localizedDescription
        }
        await loadLiveAvailability()
    }

    /// Fetched after the station rather than alongside it, and failing silently.
    ///
    /// Live status is an addition to the screen, not a precondition for it: a national access point
    /// that is unreachable must cost the live section and nothing else. Racing it with the station
    /// query would only make the screen wait for the least reliable of the three requests.
    func loadLiveAvailability() async {
        do {
            let fetched = try await repository.liveAvailability(stationID: stationID)
            liveAvailability = fetched.isKnown ? fetched : nil
            AppLogger.stations.debug("Live availability for \(self.stationID): \(fetched.available)/\(fetched.resolvedCount) free, \(fetched.unknown) unresolved")
        } catch {
            // Deliberately not surfaced. The section is simply absent.
            liveAvailability = nil
            AppLogger.stations.debug("No live availability for \(self.stationID) — \(AppLogger.describe(error))")
        }
    }

    func createComment(_ payload: CommentPayload, accessToken: String) async {
        await perform { try await self.repository.createComment(stationID: self.stationID, payload: payload, accessToken: accessToken) } onSuccess: { self.comments.insert($0, at: 0) }
    }

    func updateComment(_ comment: StationComment, payload: CommentPayload, accessToken: String) async {
        await perform { try await self.repository.updateComment(id: comment.id, payload: payload, accessToken: accessToken) } onSuccess: { changed in
            if let index = self.comments.firstIndex(where: { $0.id == changed.id }) { self.comments[index] = changed }
        }
    }

    func deleteComment(_ comment: StationComment, accessToken: String) async {
        do {
            try await repository.deleteComment(id: comment.id, accessToken: accessToken)
            comments.removeAll { $0.id == comment.id }
            AppLogger.comments.notice("Deleted comment \(comment.id)")
        } catch {
            AppLogger.comments.error("Deleting comment \(comment.id) failed", error: error)
            errorMessage = error.localizedDescription
        }
    }

    private func perform(_ operation: () async throws -> StationComment, onSuccess: (StationComment) -> Void) async {
        do {
            onSuccess(try await operation())
        } catch {
            AppLogger.comments.error("Comment write on station \(stationID) failed", error: error)
            errorMessage = error.localizedDescription
        }
    }
}
