import Foundation

protocol ChargingStationRepository {
    func nearby(latitude: Double, longitude: Double, radiusKm: Double, limit: Int, filter: StationFilter) async throws -> [Station]
    func detail(id: UUID) async throws -> StationDetail
    /// Live occupancy of one station's charge points, with per-charge-point detail.
    ///
    /// Separate from ``detail(id:)`` on purpose: a station's description is cacheable for hours and
    /// this is not, and a live source that is down has to degrade to "unknown" without taking the
    /// station screen with it.
    func liveAvailability(stationID: UUID) async throws -> StationLiveAvailability
    /// Live occupancy for a viewport, for the map. Answers only the stations that have one, so the
    /// response scales with live coverage rather than with how far the user zoomed out.
    func liveAvailability(latMin: Double, lonMin: Double, latMax: Double, lonMax: Double) async throws -> [StationLiveAvailability]
    /// Charging networks whose name contains `query`, most stations first. An empty query returns
    /// the largest networks, which is what a freshly opened picker should show.
    func providers(matching query: String, limit: Int) async throws -> [ChargingProvider]
    func comments(stationID: UUID, accessToken: String?) async throws -> [StationComment]
    func createComment(stationID: UUID, payload: CommentPayload, accessToken: String) async throws -> StationComment
    func updateComment(id: UUID, payload: CommentPayload, accessToken: String) async throws -> StationComment
    func deleteComment(id: UUID, accessToken: String) async throws
    func signInWithApple(identityToken: String) async throws -> String
    /// The web sign-in providers this backend offers (ADR 0018).
    func signInProviders() async throws -> [SignInProvider]
    /// Redeems an authorization code from a web sign-in; answers with the backend's access token.
    func signIn(provider: String, code: String, codeVerifier: String?) async throws -> String
}
