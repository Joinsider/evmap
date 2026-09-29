import Foundation

/// A web sign-in option the backend has credentials for (`GET /api/v1/auth/providers`, ADR 0018).
///
/// The backend owns client ids and redirect URIs, so a provider can be switched on or off without an
/// app release. The app adds `state` and the PKCE challenge; the client secret never reaches it.
struct SignInProvider: Decodable, Equatable, Identifiable {
    let provider: String
    let authorizationEndpoint: URL
    let parameters: [String: String]
    let pkce: Bool

    var id: String { provider }

    /// Providers the app offers through the web flow. Apple is signed in natively instead.
    static let webProviders: Set<String> = ["google", "github"]

    /// The HTTPS URL the provider redirects to. The app intercepts it through its associated domain.
    var redirectURI: URL? { parameters["redirect_uri"].flatMap(URL.init(string:)) }

    /// The authorization URL for one attempt.
    func authorizationURL(state: String, codeChallenge: String?) -> URL? {
        guard var components = URLComponents(url: authorizationEndpoint, resolvingAgainstBaseURL: false) else { return nil }
        var items = parameters.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        items.append(URLQueryItem(name: "state", value: state))
        if let codeChallenge {
            items.append(URLQueryItem(name: "code_challenge", value: codeChallenge))
            items.append(URLQueryItem(name: "code_challenge_method", value: "S256"))
        }
        components.queryItems = (components.queryItems ?? []) + items
        return components.url
    }
}
