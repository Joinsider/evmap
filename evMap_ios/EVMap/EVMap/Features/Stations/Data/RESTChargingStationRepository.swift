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
        var components = URLComponents(url: client.endpoint("stations"), resolvingAgainstBaseURL: false)!
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
        try await client.send(client.endpoint("stations", id.uuidString))
    }

    func liveAvailability(stationID: UUID) async throws -> StationLiveAvailability {
        try await client.send(client.endpoint("stations", stationID.uuidString, "availability"))
    }

    func liveAvailability(latMin: Double, lonMin: Double, latMax: Double, lonMax: Double) async throws -> [StationLiveAvailability] {
        var components = URLComponents(url: client.endpoint("stations", "availability"), resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "latMin", value: String(latMin)),
            URLQueryItem(name: "lonMin", value: String(lonMin)),
            URLQueryItem(name: "latMax", value: String(latMax)),
            URLQueryItem(name: "lonMax", value: String(lonMax))
        ]
        return try await client.send(components.url!)
    }

    func providers(matching query: String, limit: Int) async throws -> [ChargingProvider] {
        var components = URLComponents(url: client.endpoint("operators"), resolvingAgainstBaseURL: false)!
        var items = [URLQueryItem(name: "limit", value: String(limit))]
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        // Omitted rather than sent empty: the endpoint reads a blank query as "no name filter",
        // but not sending it at all says so without depending on that.
        if !trimmed.isEmpty { items.append(.init(name: "query", value: trimmed)) }
        components.queryItems = items
        return try await client.send(components.url!)
    }
    
    func comments(stationID: UUID, accessToken: String?) async throws -> [StationComment] {
        try await client.send(client.endpoint("stations", stationID.uuidString, "comments"), accessToken: accessToken)
    }
    
    func createComment(stationID: UUID, payload: CommentPayload, accessToken: String) async throws -> StationComment {
        try await client.send(client.endpoint("stations", stationID.uuidString, "comments"), method: "POST", body: payload, accessToken: accessToken)
    }
    func updateComment(id: UUID, payload: CommentPayload, accessToken: String) async throws -> StationComment { try await client.send(client.endpoint("comments", id.uuidString), method: "PATCH", body: payload, accessToken: accessToken) }
    func deleteComment(id: UUID, accessToken: String) async throws { let _: EmptyResponse = try await client.send(client.endpoint("comments", id.uuidString), method: "DELETE", accessToken: accessToken) }

    func signInWithApple(identityToken: String, authorizationCode: String?) async throws -> String {
        let response: AccessTokenResponse = try await client.send(
            client.endpoint("auth", "apple"), method: "POST",
            body: AppleLoginRequest(identityToken: identityToken, authorizationCode: authorizationCode))
        return response.accessToken
    }

    func signInProviders() async throws -> [SignInProvider] {
        try await client.send(client.endpoint("auth", "providers"))
    }

    func signIn(provider: String, code: String, codeVerifier: String?) async throws -> String {
        let response: AccessTokenResponse = try await client.send(client.endpoint("auth", provider, "code"), method: "POST",
                                                                  body: CodeLoginRequest(code: code, codeVerifier: codeVerifier))
        return response.accessToken
    }

    func reportComment(id: UUID, reason: ReportReason, accessToken: String) async throws {
        let _: EmptyResponse = try await client.send(client.endpoint("comments", id.uuidString, "report"), method: "POST",
                                                     body: ReportRequest(reason: reason.rawValue), accessToken: accessToken)
    }

    func blockAuthor(ofComment id: UUID, accessToken: String) async throws {
        let _: EmptyResponse = try await client.send(client.endpoint("comments", id.uuidString, "block-author"), method: "POST", accessToken: accessToken)
    }

    func blockedAuthors(accessToken: String) async throws -> [BlockedAuthor] {
        try await client.send(client.endpoint("me", "blocks"), accessToken: accessToken)
    }

    func unblock(id: UUID, accessToken: String) async throws {
        let _: EmptyResponse = try await client.send(client.endpoint("me", "blocks", id.uuidString), method: "DELETE", accessToken: accessToken)
    }

    func contributions(accessToken: String) async throws -> Contributions {
        try await client.send(client.endpoint("me", "contributions"), accessToken: accessToken)
    }

    func exportData(accessToken: String) async throws -> Data {
        let response: RawResponse = try await client.send(client.endpoint("me", "export"), accessToken: accessToken)
        return response.data
    }

    func deleteAccount(accessToken: String) async throws {
        let _: EmptyResponse = try await client.send(client.endpoint("me"), method: "DELETE", accessToken: accessToken)
    }

    func legal() async throws -> LegalInfo {
        try await client.send(client.endpoint("legal"))
    }

    func addFavorite(stationID: UUID, accessToken: String) async throws {
        let _: EmptyResponse = try await client.send(client.endpoint("me", "favorites", stationID.uuidString), method: "PUT", accessToken: accessToken)
    }

    func removeFavorite(stationID: UUID, accessToken: String) async throws {
        let _: EmptyResponse = try await client.send(client.endpoint("me", "favorites", stationID.uuidString), method: "DELETE", accessToken: accessToken)
    }

    func mergeFavorites(stationIDs: [UUID], accessToken: String) async throws -> [Station] {
        try await client.send(client.endpoint("me", "favorites", "merge"), method: "POST",
                              body: MergeFavoritesRequest(stationIds: stationIDs), accessToken: accessToken)
    }

    func reportStation(id: UUID, reason: StationReportReason, note: String?, accessToken: String) async throws {
        let _: EmptyResponse = try await client.send(client.endpoint("stations", id.uuidString, "reports"), method: "POST",
                                                     body: StationReportRequest(reason: reason.rawValue, note: note), accessToken: accessToken)
    }
}

private struct AppleLoginRequest: Encodable { let identityToken: String; let authorizationCode: String? }
private struct ReportRequest: Encodable { let reason: String }
private struct MergeFavoritesRequest: Encodable { let stationIds: [UUID] }
private struct StationReportRequest: Encodable { let reason: String; let note: String? }
private struct CodeLoginRequest: Encodable { let code: String; let codeVerifier: String? }
private struct AccessTokenResponse: Decodable { let accessToken: String }
