import Foundation

protocol ChargingStationRepository {
    func nearby(latitude: Double, longitude: Double, radiusKm: Double, limit: Int, filter: StationFilter) async throws -> [Station]
    func detail(id: UUID) async throws -> StationDetail
    func comments(stationID: UUID, accessToken: String?) async throws -> [StationComment]
    func createComment(stationID: UUID, payload: CommentPayload, accessToken: String) async throws -> StationComment
    func updateComment(id: UUID, payload: CommentPayload, accessToken: String) async throws -> StationComment
    func deleteComment(id: UUID, accessToken: String) async throws
    func signInWithApple(identityToken: String) async throws -> String
}
