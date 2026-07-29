import Foundation

struct RESTChargingStationRepository: ChargingStationRepository {
    private let client: APIClient

    init(client: APIClient = APIClient()) { self.client = client }

    func nearby(latitude: Double, longitude: Double, radiusKm: Double, limit: Int, filter: StationFilter) async throws -> [Station] {
        var components = URLComponents(url: client.url(path: "/api/v1/stations"), resolvingAgainstBaseURL: false)!
        var items = [
            URLQueryItem(name: "latitude", value: String(latitude)),
            URLQueryItem(name: "longitude", value: String(longitude)),
            // The endpoint takes whole kilometres; rounding up keeps the viewport's corners inside.
            URLQueryItem(name: "radiusKm", value: String(Int(radiusKm.rounded(.up)))),
            URLQueryItem(name: "limit", value: String(limit))
        ]
        for connector in filter.connectorTypes.map(\.rawValue).sorted() {
            items.append(.init(name: "connectorType", value: connector))
        }
        if let power = filter.minimumPower { items.append(.init(name: "minPowerKw", value: String(power))) }
        if !filter.operatorName.isEmpty { items.append(.init(name: "operator", value: filter.operatorName)) }
        components.queryItems = items
        return try await client.send(components.url!)
    }

    func detail(id: UUID) async throws -> StationDetail {
        try await client.send(client.url(path: "/api/v1/stations/\(id)"))
    }
    
    func comments(stationID: UUID, accessToken: String?) async throws -> [StationComment] {
        try await client.send(client.url(path: "/api/v1/stations/\(stationID)/comments"), accessToken: accessToken)
    }
    
    func createComment(stationID: UUID, payload: CommentPayload, accessToken: String) async throws -> StationComment {
        try await client.send(client.url(path: "/api/v1/stations/\(stationID)/comments"), method: "POST", body: payload, accessToken: accessToken)
    }
    func updateComment(id: UUID, payload: CommentPayload, accessToken: String) async throws -> StationComment { try await client.send(client.url(path: "/api/v1/comments/\(id)"), method: "PATCH", body: payload, accessToken: accessToken) }
    func deleteComment(id: UUID, accessToken: String) async throws { let _: EmptyResponse = try await client.send(client.url(path: "/api/v1/comments/\(id)"), method: "DELETE", accessToken: accessToken) }

    func signInWithApple(identityToken: String) async throws -> String {
        let response: AccessTokenResponse = try await client.send(client.url(path: "/api/v1/auth/apple"), method: "POST", body: AppleLoginRequest(identityToken: identityToken))
        return response.accessToken
    }
}

private struct AppleLoginRequest: Encodable { let identityToken: String }
private struct AccessTokenResponse: Decodable { let accessToken: String }
