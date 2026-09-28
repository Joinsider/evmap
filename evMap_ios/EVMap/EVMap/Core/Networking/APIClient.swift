import Foundation

struct APIClient {
    private let baseURL: URL
    private let session: URLSession

    init(baseURL: URL = APIEnvironment.baseURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    /// The versioned API root every endpoint hangs off.
    private static let apiRoot = ["api", "v1"]

    /// An endpoint under the API root, built from path segments rather than a "/api/v1/…" string:
    /// each segment is percent-encoded on its own, so an id can never inject a path separator.
    func endpoint(_ segments: String...) -> URL {
        (Self.apiRoot + segments).reduce(baseURL) { $0.appending(component: $1) }
    }

    func send<T: Decodable>(_ url: URL, method: String = "GET", body: (any Encodable)? = nil, accessToken: String? = nil) async throws -> T {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let accessToken { request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder.evmap.encode(AnyEncodable(body))
        }

        let start = ContinuousClock.now
        // Two labels on purpose: `label` is safe for persisted levels, the debug
        // line may carry the full query (which includes the user's coordinates).
        let label = "\(method) \(url.path())\(Self.redactedQuery(url))"
        AppLogger.network.debug("→ \(method) \(url.path())\(url.query().map { "?\($0)" } ?? "")\(accessToken == nil ? "" : " [auth \(AppLogger.redact(token: accessToken))]")")

        let data: Data
        let response: HTTPURLResponse
        do {
            let (body, urlResponse) = try await session.data(for: request)
            guard let http = urlResponse as? HTTPURLResponse else {
                AppLogger.network.error("← \(label) returned a non-HTTP response after \(AppLogger.duration(since: start))")
                throw APIError.invalidResponse
            }
            data = body
            response = http
        } catch {
            // The transport itself failed (offline, DNS, TLS) — distinct from a
            // request that reached the server and came back non-2xx.
            AppLogger.network.error("← \(label) transport failure after \(AppLogger.duration(since: start))", error: error)
            throw error
        }

        let summary = "← \(label) \(response.statusCode) in \(AppLogger.duration(since: start)), \(AppLogger.size(data.count))"
        if response.statusCode == 401 {
            AppLogger.network.warning("\(summary) — token rejected, signing out")
            throw APIError.unauthenticated
        }
        guard 200..<300 ~= response.statusCode else {
            let message = (try? JSONDecoder.evmap.decode(ServerError.self, from: data).error) ?? String(localized: "error.requestFailed")
            AppLogger.network.error("\(summary) — \(message)")
            Self.logBodyPreview(data, label: label)
            throw APIError.server(message)
        }
        AppLogger.network.info(summary)

        if T.self == EmptyResponse.self { return EmptyResponse() as! T }
        do {
            return try JSONDecoder.evmap.decode(T.self, from: data)
        } catch {
            // The coding path alone rarely explains what the server actually sent,
            // so the payload follows on the debug channel.
            AppLogger.network.error("← \(label) decoding \(T.self) failed", error: error)
            Self.logBodyPreview(data, label: label)
            throw error
        }
    }

    /// Logs the first 512 bytes of a failing response body — debug builds only.
    ///
    /// A response body can contain other users' comment text, so it is compiled
    /// out of release builds entirely rather than merely lowered to `.debug`.
    /// See `docs/privacy/data-processing.md`.
    private static func logBodyPreview(_ data: Data, label: String) {
        #if DEBUG
        guard let text = String(data: data.prefix(512), encoding: .utf8), !text.isEmpty else { return }
        let truncated = data.count > 512 ? "… (\(AppLogger.size(data.count)) total)" : ""
        AppLogger.network.debug("   \(label) body: \(text)\(truncated)")
        #endif
    }

    /// Query string with the values of location parameters removed.
    ///
    /// Coordinates are the most sensitive value the app handles; they must not
    /// appear on levels that the unified log persists to disk.
    private static func redactedQuery(_ url: URL) -> String {
        guard let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems, !items.isEmpty else { return "" }
        let personal: Set<String> = ["latitude", "longitude"]
        let rendered = items.map { "\($0.name)=\(personal.contains($0.name) ? "…" : $0.value ?? "")" }
        return "?\(rendered.joined(separator: "&"))"
    }
}

private struct ServerError: Decodable { let error: String }
struct EmptyResponse: Decodable { }
struct AnyEncodable: Encodable {
    let value: any Encodable
    init(_ value: any Encodable) { self.value = value }
    func encode(to encoder: Encoder) throws { try value.encode(to: encoder) }
}

extension JSONDecoder {
    static let evmap: JSONDecoder = { let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601; return decoder }()
}

extension JSONEncoder {
    static let evmap: JSONEncoder = { let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601; return encoder }()
}
