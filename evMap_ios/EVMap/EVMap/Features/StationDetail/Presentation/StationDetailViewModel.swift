import Combine
import Foundation

@MainActor
final class StationDetailViewModel: ObservableObject {
    @Published private(set) var detail: StationDetail?
    @Published private(set) var comments: [StationComment] = []
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
