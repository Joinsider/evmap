import Foundation
import Testing

@testable import EVMap

/// Intercepts every request of a session and answers it from a handler the test sets.
///
/// The handler is process-wide because `URLProtocol` is instantiated by the loading system, not by
/// the test; the suite below is serialized so no two tests share it at once.
final class StubURLProtocol: URLProtocol {
    struct Reply {
        var status = 200
        var body = Data("{}".utf8)
    }

    nonisolated(unsafe) static var handler: ((URLRequest) -> Reply)?
    nonisolated(unsafe) static var requests: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var recorded = request
        // URLSession moves a body into a stream before it reaches a protocol; read it back.
        if recorded.httpBody == nil, let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            var buffer = [UInt8](repeating: 0, count: 4_096)
            while stream.hasBytesAvailable {
                let read = stream.read(&buffer, maxLength: buffer.count)
                if read <= 0 { break }
                data.append(buffer, count: read)
            }
            stream.close()
            recorded.httpBody = data
        }
        Self.requests.append(recorded)

        let reply = Self.handler?(recorded) ?? Reply()
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: reply.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() { }

    static func session() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    static func reset(_ handler: @escaping (URLRequest) -> Reply) {
        self.handler = handler
        requests = []
    }
}

@Suite("REST repository", .serialized)
@MainActor
struct NetworkingTests {
    private let baseURL = URL(string: "https://api.example.test")!

    private func repository() -> RESTChargingStationRepository {
        RESTChargingStationRepository(client: APIClient(baseURL: baseURL, session: StubURLProtocol.session()))
    }

    private func json(_ text: String) -> StubURLProtocol.Reply { .init(status: 200, body: Data(text.utf8)) }

    private var lastRequest: URLRequest { StubURLProtocol.requests.last! }

    private func queryItems(_ request: URLRequest) -> [URLQueryItem] {
        URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
    }

    @Test("a map query carries the viewport and every filter, sorted so equal filters give equal URLs")
    func nearbyQuery() async throws {
        StubURLProtocol.reset { _ in self.json("[]") }
        var filter = StationFilter()
        filter.connectorTypes = [.type2, .ccs]
        filter.minimumPower = 50
        filter.excludedProviders = ["Tesla", "Aral pulse"]

        _ = try await repository().nearby(latitude: 48.77, longitude: 9.18, radiusKm: 4.2, limit: 500, filter: filter)

        #expect(lastRequest.url?.path() == "/api/v1/stations")
        let items = queryItems(lastRequest)
        #expect(items.first { $0.name == "radiusKm" }?.value == "5")
        #expect(items.filter { $0.name == "connectorType" }.map(\.value) == ["CCS", "Type 2"])
        #expect(items.first { $0.name == "minPowerKw" }?.value == "50.0")
        #expect(items.filter { $0.name == "excludeOperator" }.map(\.value) == ["Aral pulse", "Tesla"])
    }

    @Test("an allowlist is sent as includeOperator, and an empty one skips the request entirely")
    func allowlist() async throws {
        StubURLProtocol.reset { _ in self.json("[]") }
        var filter = StationFilter()
        filter.includedProviders = ["IONITY"]
        _ = try await repository().nearby(latitude: 0, longitude: 0, radiusKm: 1, limit: 10, filter: filter)
        #expect(queryItems(lastRequest).filter { $0.name == "includeOperator" }.map(\.value) == ["IONITY"])

        StubURLProtocol.reset { _ in self.json("[]") }
        filter.includedProviders = []
        let stations = try await repository().nearby(latitude: 0, longitude: 0, radiusKm: 1, limit: 10, filter: filter)
        #expect(stations.isEmpty)
        #expect(StubURLProtocol.requests.isEmpty)
    }

    @Test("detail, live status and comments address the station by its id")
    func stationEndpoints() async throws {
        let id = UUID()
        StubURLProtocol.reset { request in
            switch request.url!.lastPathComponent {
            case "availability":
                return self.json(#"{"stationId":"\#(id.uuidString)","status":"UNKNOWN","unknown":2}"#)
            case "comments": return self.json("[]")
            default:
                return self.json(#"{"station":{"id":"\#(id.uuidString)","displayName":"X","latitude":1,"longitude":2},"connectors":[],"sources":["BNetzA"]}"#)
            }
        }
        let repository = repository()

        let detail = try await repository.detail(id: id)
        #expect(detail.sources == ["BNetzA"])
        #expect(lastRequest.url?.path() == "/api/v1/stations/\(id.uuidString)")

        let live = try await repository.liveAvailability(stationID: id)
        #expect(live.unknown == 2)
        #expect(lastRequest.url?.path() == "/api/v1/stations/\(id.uuidString)/availability")

        let comments = try await repository.comments(stationID: id, accessToken: "abc")
        #expect(comments.isEmpty)
        #expect(lastRequest.value(forHTTPHeaderField: "Authorization") == "Bearer abc")
    }

    @Test("web sign-in lists the providers and posts the code with its verifier")
    func webSignIn() async throws {
        StubURLProtocol.reset { request in
            request.url!.lastPathComponent == "providers"
                ? self.json(#"[{"provider":"google","authorizationEndpoint":"https://accounts.google.com/o/oauth2/v2/auth","parameters":{"redirect_uri":"https://evmap.joinside.de/auth/callback/google"},"pkce":true}]"#)
                : self.json(#"{"accessToken":"issued"}"#)
        }
        let repository = repository()

        let providers = try await repository.signInProviders()
        #expect(providers.map(\.provider) == ["google"])
        #expect(providers.first?.redirectURI?.path() == "/auth/callback/google")

        let token = try await repository.signIn(provider: "google", code: "the-code", codeVerifier: "the-verifier")
        #expect(token == "issued")
        #expect(lastRequest.url?.path() == "/api/v1/auth/google/code")
        #expect(lastRequest.httpMethod == "POST")
        let body = try JSONSerialization.jsonObject(with: lastRequest.httpBody ?? Data()) as? [String: String]
        #expect(body == ["code": "the-code", "codeVerifier": "the-verifier"])
    }

    @Test("viewport live status and the operator directory pass their parameters")
    func viewportAndOperators() async throws {
        StubURLProtocol.reset { _ in self.json("[]") }
        let repository = repository()

        _ = try await repository.liveAvailability(latMin: 48.7, lonMin: 9.1, latMax: 48.8, lonMax: 9.2)
        #expect(lastRequest.url?.path() == "/api/v1/stations/availability")
        #expect(queryItems(lastRequest).map(\.name) == ["latMin", "lonMin", "latMax", "lonMax"])

        _ = try await repository.providers(matching: "  ion ", limit: 20)
        #expect(queryItems(lastRequest).first { $0.name == "query" }?.value == "ion")
        _ = try await repository.providers(matching: "   ", limit: 20)
        #expect(queryItems(lastRequest).contains { $0.name == "query" } == false)
    }

    @Test("comment writes send JSON with the token, and a delete needs no response body")
    func commentWrites() async throws {
        let commentID = UUID()
        let stored = #"{"id":"\#(commentID.uuidString)","body":"ok","createdAt":"2026-09-28T10:00:00Z","updatedAt":"2026-09-28T10:00:00Z","ownedByCurrentUser":true}"#
        StubURLProtocol.reset { request in
            request.httpMethod == "DELETE" ? .init(status: 204, body: Data()) : self.json(stored)
        }
        let repository = repository()
        let payload = CommentPayload(body: "ok", paidPriceCents: nil, experience: nil)

        _ = try await repository.createComment(stationID: UUID(), payload: payload, accessToken: "t")
        #expect(lastRequest.httpMethod == "POST")
        #expect(lastRequest.value(forHTTPHeaderField: "Content-Type") == "application/json")
        #expect(String(data: lastRequest.httpBody ?? Data(), encoding: .utf8)?.contains(#""body":"ok""#) == true)

        _ = try await repository.updateComment(id: commentID, payload: payload, accessToken: "t")
        #expect(lastRequest.httpMethod == "PATCH")
        #expect(lastRequest.url?.path() == "/api/v1/comments/\(commentID.uuidString)")

        try await repository.deleteComment(id: commentID, accessToken: "t")
        #expect(lastRequest.httpMethod == "DELETE")
    }

    @Test("Sign in with Apple exchanges the identity token for the API's own access token")
    func signIn() async throws {
        StubURLProtocol.reset { _ in self.json(#"{"accessToken":"api-token"}"#) }

        let token = try await repository().signInWithApple(identityToken: "apple-token", authorizationCode: "auth-code")

        #expect(token == "api-token")
        #expect(lastRequest.url?.path() == "/api/v1/auth/apple")
        let body = try JSONSerialization.jsonObject(with: lastRequest.httpBody ?? Data()) as? [String: String]
        #expect(body == ["identityToken": "apple-token", "authorizationCode": "auth-code"])
    }

    @Test("the account area talks to /me and the comment endpoints, with the token")
    func accountEndpoints() async throws {
        let comment = UUID(), block = UUID()
        StubURLProtocol.reset { request in
            switch request.url!.path() {
            case "/api/v1/me/blocks": self.json(#"[{"id":"\#(block.uuidString)","createdAt":"2026-09-28T10:00:00Z"}]"#)
            case "/api/v1/me/contributions": self.json(#"{"comments":[],"reports":[{"id":"\#(block.uuidString)","reason":"spam","status":"open","createdAt":"2026-09-28T10:00:00Z"}],"stationReports":[{"id":"\#(block.uuidString)","reason":"wrong_power","note":"11 kW","status":"resolved","createdAt":"2026-09-28T10:00:00Z"}]}"#)
            case "/api/v1/me/export": self.json(#"{"account":{}}"#)
            case "/api/v1/legal": self.json(#"{"privacyPolicyUrl":"https://evmap.example/privacy"}"#)
            default: .init(status: 204, body: Data())
            }
        }
        let repository = repository()

        try await repository.reportComment(id: comment, reason: .offensive, accessToken: "t")
        #expect(lastRequest.url?.path() == "/api/v1/comments/\(comment.uuidString)/report")
        #expect(lastRequest.httpMethod == "POST")
        #expect(String(data: lastRequest.httpBody ?? Data(), encoding: .utf8) == #"{"reason":"offensive"}"#)

        try await repository.blockAuthor(ofComment: comment, accessToken: "t")
        #expect(lastRequest.url?.path() == "/api/v1/comments/\(comment.uuidString)/block-author")

        let blocks = try await repository.blockedAuthors(accessToken: "t")
        #expect(blocks.map(\.id) == [block])
        #expect(lastRequest.value(forHTTPHeaderField: "Authorization") == "Bearer t")

        try await repository.unblock(id: block, accessToken: "t")
        #expect(lastRequest.httpMethod == "DELETE")
        #expect(lastRequest.url?.path() == "/api/v1/me/blocks/\(block.uuidString)")

        let contributions = try await repository.contributions(accessToken: "t")
        #expect(contributions.reports.first?.isOpen == true)
        #expect(contributions.stationReports.first?.isResolved == true)
        #expect(contributions.stationReports.first?.reasonName == StationReportReason.wrongPower.displayName)

        let export = try await repository.exportData(accessToken: "t")
        #expect(String(data: export, encoding: .utf8) == #"{"account":{}}"#)

        #expect(try await repository.legal().privacyPolicyUrl?.absoluteString == "https://evmap.example/privacy")
        #expect(lastRequest.value(forHTTPHeaderField: "Authorization") == nil)

        try await repository.deleteAccount(accessToken: "t")
        #expect(lastRequest.httpMethod == "DELETE")
        #expect(lastRequest.url?.path() == "/api/v1/me")
    }

    @Test("favorites are written one by one, merged in bulk, and a station report goes to the station")
    func favoriteAndReportEndpoints() async throws {
        let station = UUID(), other = UUID()
        StubURLProtocol.reset { request in
            request.url!.path() == "/api/v1/me/favorites/merge"
                ? self.json(#"[{"id":"\#(station.uuidString)","displayName":"Ada","latitude":48.7,"longitude":9.1}]"#)
                : .init(status: 204, body: Data())
        }
        let repository = repository()

        try await repository.addFavorite(stationID: station, accessToken: "t")
        #expect(lastRequest.httpMethod == "PUT")
        #expect(lastRequest.url?.path() == "/api/v1/me/favorites/\(station.uuidString)")
        #expect(lastRequest.value(forHTTPHeaderField: "Authorization") == "Bearer t")

        try await repository.removeFavorite(stationID: station, accessToken: "t")
        #expect(lastRequest.httpMethod == "DELETE")
        #expect(lastRequest.url?.path() == "/api/v1/me/favorites/\(station.uuidString)")

        let merged = try await repository.mergeFavorites(stationIDs: [station, other], accessToken: "t")
        #expect(merged.map(\.displayName) == ["Ada"])
        #expect(lastRequest.httpMethod == "POST")
        let body = try JSONSerialization.jsonObject(with: lastRequest.httpBody ?? Data()) as? [String: [String]]
        #expect(body == ["stationIds": [station.uuidString, other.uuidString]])

        try await repository.reportStation(id: station, reason: .wrongConnector, note: "Type 2 only", accessToken: "t")
        #expect(lastRequest.httpMethod == "POST")
        #expect(lastRequest.url?.path() == "/api/v1/stations/\(station.uuidString)/reports")
        let report = try JSONSerialization.jsonObject(with: lastRequest.httpBody ?? Data()) as? [String: String]
        #expect(report == ["reason": "wrong_connector", "note": "Type 2 only"])
    }

    @Test("a rejected token, a server error and a malformed body surface as distinct errors")
    func failures() async throws {
        let repository = repository()

        StubURLProtocol.reset { _ in .init(status: 401, body: Data()) }
        await #expect(throws: APIError.self) { try await repository.detail(id: UUID()) }

        StubURLProtocol.reset { _ in .init(status: 400, body: Data(#"{"error":"radiusKm must be between 1 and 1000"}"#.utf8)) }
        do {
            _ = try await repository.detail(id: UUID())
            Issue.record("expected a server error")
        } catch let error as APIError {
            #expect(error.errorDescription == "radiusKm must be between 1 and 1000")
        }

        StubURLProtocol.reset { _ in .init(status: 500, body: Data("not json".utf8)) }
        await #expect(throws: APIError.self) { try await repository.detail(id: UUID()) }

        StubURLProtocol.reset { _ in self.json(#"{"unexpected":true}"#) }
        await #expect(throws: DecodingError.self) { try await repository.detail(id: UUID()) }
    }
}

/// `POST /api/v1/stations/along-route` (ADR 0017): the route goes in the body, never in the URL.
extension NetworkingTests {
    private var route: [RouteCoordinate] { [RouteCoordinate(latitude: 48.1234, longitude: 9.5678), RouteCoordinate(latitude: 49.5, longitude: 10.25)] }

    @Test("the body carries the route, the corridor and the filters, and the URL carries nothing")
    func requestShape() async throws {
        StubURLProtocol.reset { _ in .init(status: 200, body: Data("[]".utf8)) }
        var filter = StationFilter()
        filter.connectorTypes = [.ccs]
        filter.minimumPower = 100
        filter.excludedProviders = ["Tesla"]

        _ = try await repository().stationsAlongRoute(route: route, corridorKm: 5, limit: 200, filter: filter)

        let request = try #require(StubURLProtocol.requests.last)
        #expect(request.httpMethod == "POST" && request.url?.path() == "/api/v1/stations/along-route")
        #expect(request.url?.query() == nil)
        let body = try #require(request.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] })
        let points = try #require(body["route"] as? [[String: Double]])
        #expect(points == [["latitude": 48.1234, "longitude": 9.5678], ["latitude": 49.5, "longitude": 10.25]])
        #expect(body["corridorKm"] as? Double == 5 && body["limit"] as? Int == 200)
        #expect(body["connectorType"] as? [String] == ["CCS"] && body["excludeOperator"] as? [String] == ["Tesla"])
        #expect(body["minPowerKw"] as? Double == 100)
        #expect(body["includeOperator"] == nil)
    }

    @Test("it decodes the stations with their position along and distance from the route")
    func response() async throws {
        let id = UUID()
        StubURLProtocol.reset { _ in .init(status: 200, body: Data("""
            [{"station":{"id":"\(id.uuidString)","displayName":"EnBW Mitte","latitude":48.2,"longitude":9.6,"maxPowerKw":150.0},
              "distanceAlongRouteKm":42.5,"distanceToRouteKm":1.25}]
            """.utf8)) }

        let found = try await repository().stationsAlongRoute(route: route, corridorKm: 5, limit: 200, filter: StationFilter())

        #expect(found.count == 1 && found[0].station.id == id)
        #expect(found[0].distanceAlongRouteKm == 42.5 && found[0].distanceToRouteKm == 1.25)
    }

    @Test("with every provider switched off nothing is sent, as an empty allowlist would mean everything")
    func nothingToAsk() async throws {
        StubURLProtocol.reset { _ in .init(status: 200, body: Data("[]".utf8)) }
        var filter = StationFilter()
        filter.includedProviders = []

        let found = try await repository().stationsAlongRoute(route: route, corridorKm: 5, limit: 200, filter: filter)

        #expect(found.isEmpty && StubURLProtocol.requests.isEmpty)
    }
}
