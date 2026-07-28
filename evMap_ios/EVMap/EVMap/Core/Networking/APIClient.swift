import Foundation

struct APIClient {
    private let baseURL: URL
    private let session: URLSession

    init(baseURL: URL = URL(string: UserDefaults.standard.string(forKey: "API_BASE_URL") ?? "http://127.0.0.1:8080")!, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func url(path: String) -> URL { baseURL.appending(path: path) }

    func send<T: Decodable>(_ url: URL, method: String = "GET", body: (any Encodable)? = nil, accessToken: String? = nil) async throws -> T {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let accessToken { request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder.evmap.encode(AnyEncodable(body))
        }

        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        if response.statusCode == 401 { throw APIError.unauthenticated }
        guard 200..<300 ~= response.statusCode else {
            let message = (try? JSONDecoder.evmap.decode(ServerError.self, from: data).error) ?? String(localized: "error.requestFailed")
            throw APIError.server(message)
        }
        if T.self == EmptyResponse.self { return EmptyResponse() as! T }
        return try JSONDecoder.evmap.decode(T.self, from: data)
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
