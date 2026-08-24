import Foundation

struct RESTChargingStationRepository: ChargingStationRepository {
    private let client: APIClient

    init(client: APIClient = APIClient()) { self.client = client }

    func nearby(latitude: Double, longitude: Double, radiusKm: Double, limit: Int, filter: StationFilter) async throws -> [Station] {
        // Answered without asking, and deliberately here rather than at the call site: an allowlist
        // of nothing has no representation in the query string, so an empty `includeOperator` would
        // reach the server as "no provider restriction" and return exactly what the user hid.
        guard !filter.matchesNothing else {
            AppLogger.stations.info("Every provider is switched off — station query skipped")
            return []
        }
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
        // Sorted so the same filter always produces the same URL — otherwise a `Set`'s order alone
        // makes two identical queries look different in the log and to any cache in between.
        for provider in filter.excludedProviders.sorted() {
            items.append(.init(name: "excludeOperator", value: provider))
        }
        // Never both: the settings produce one list or the other, and the two say the same thing
        // from opposite ends.
        for provider in (filter.includedProviders ?? []).sorted() {
            items.append(.init(name: "includeOperator", value: provider))
        }
        components.queryItems = items
        return try await client.send(components.url!)
    }

    func detail(id: UUID) async throws -> StationDetail {
        try await client.send(client.url(path: "/api/v1/stations/\(id)"))
    }

    func liveAvailability(stationID: UUID) async throws -> StationLiveAvailability {
        try await client.send(client.url(path: "/api/v1/stations/\(stationID)/availability"))
    }

    func liveAvailability(latMin: Double, lonMin: Double, latMax: Double, lonMax: Double) async throws -> [StationLiveAvailability] {
        var components = URLComponents(url: client.url(path: "/api/v1/stations/availability"), resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "latMin", value: String(latMin)),
            URLQueryItem(name: "lonMin", value: String(lonMin)),
            URLQueryItem(name: "latMax", value: String(latMax)),
            URLQueryItem(name: "lonMax", value: String(lonMax))
        ]
        return try await client.send(components.url!)
    }

    func providers(matching query: String, limit: Int) async throws -> [ChargingProvider] {
        var components = URLComponents(url: client.url(path: "/api/v1/operators"), resolvingAgainstBaseURL: false)!
        var items = [URLQueryItem(name: "limit", value: String(limit))]
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        // Omitted rather than sent empty: the endpoint reads a blank query as "no name filter",
        // but not sending it at all says so without depending on that.
        if !trimmed.isEmpty { items.append(.init(name: "query", value: trimmed)) }
        components.queryItems = items
        return try await client.send(components.url!)
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
