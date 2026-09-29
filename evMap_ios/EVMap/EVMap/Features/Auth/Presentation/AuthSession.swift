import AuthenticationServices
import Combine
import Foundation

@MainActor
final class AuthSession: ObservableObject {
    @Published private(set) var accessToken: String? {
        didSet { UserDefaults.standard.set(accessToken, forKey: Self.accessTokenKey) }
    }

    private static let accessTokenKey = "EVMapAccessToken"
    private let repository: any ChargingStationRepository

    init(repository: any ChargingStationRepository) {
        self.repository = repository
        accessToken = UserDefaults.standard.string(forKey: Self.accessTokenKey)
        // Even a truncated token is a credential fingerprint — durable levels get
        // the fact, the debug channel gets the value.
        AppLogger.auth.info("Restored session: \(accessToken == nil ? "signed out" : "signed in")")
        AppLogger.auth.debug("Restored token \(AppLogger.redact(token: accessToken))")
    }

    func completeAppleSignIn(_ result: Result<ASAuthorization, Error>) async throws {
        if case let .failure(error) = result {
            AppLogger.auth.error("Apple returned no credential", error: error)
        }
        guard case let .success(authorization) = result,
              let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let data = credential.identityToken,
              let identityToken = String(data: data, encoding: .utf8) else {
            // Distinguishes "Apple failed" from "Apple succeeded but the payload
            // was not shaped as expected" — both surface the same user-facing string.
            AppLogger.auth.error("Apple sign-in did not yield a usable identity token")
            throw APIError.server(String(localized: "error.appleSignIn"))
        }
        // Lets the backend keep a refresh token, which Apple requires it to revoke on account deletion.
        let authorizationCode = credential.authorizationCode.flatMap { String(data: $0, encoding: .utf8) }
        AppLogger.auth.debug("Exchanging Apple identity token \(AppLogger.redact(token: identityToken)) for an access token")
        accessToken = try await AppLogger.auth.measure("Apple sign-in") {
            try await repository.signInWithApple(identityToken: identityToken, authorizationCode: authorizationCode)
        }
        AppLogger.auth.notice("Signed in")
        AppLogger.auth.debug("Access token \(AppLogger.redact(token: accessToken))")
    }

    /// The web providers to offer next to Sign in with Apple.
    func webSignInProviders() async throws -> [SignInProvider] {
        try await repository.signInProviders().filter { SignInProvider.webProviders.contains($0.provider) }
    }

    /// Opens a provider in a web authentication session and exchanges the code it returns (ADR 0018).
    ///
    /// `authenticate` presents the session; the view passes SwiftUI's `WebAuthenticationSession`,
    /// tests a stand-in. The callback is the provider's HTTPS redirect URI, which iOS hands back to
    /// the app through the associated domain instead of loading it. A cancelled sheet throws
    /// `ASWebAuthenticationSessionError.canceledLogin`, which callers treat as "nothing happened".
    func signIn(with provider: SignInProvider,
                authenticate: (URL, ASWebAuthenticationSession.Callback) async throws -> URL) async throws {
        let state = PKCE.randomToken()
        let verifier = provider.pkce ? PKCE.randomToken(byteCount: 48) : nil
        guard let redirect = provider.redirectURI, let host = redirect.host(),
              let url = provider.authorizationURL(state: state, codeChallenge: verifier.map(PKCE.challenge(for:))) else {
            AppLogger.auth.error("Sign-in provider \(provider.provider) is missing its redirect URI")
            throw APIError.server(String(localized: "error.signIn"))
        }

        AppLogger.auth.info("Starting \(provider.provider) sign-in")
        let callback = try await authenticate(url, .https(host: host, path: redirect.path()))
        let query = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func value(_ name: String) -> String? { query.first { $0.name == name }?.value }

        // The state is what ties this answer to the session this app opened.
        guard value("state") == state, let code = value("code") else {
            AppLogger.auth.error("\(provider.provider) sign-in returned \(value("error") ?? "no code or a mismatched state")")
            throw APIError.server(String(localized: "error.signIn"))
        }
        accessToken = try await AppLogger.auth.measure("\(provider.provider) sign-in") {
            try await repository.signIn(provider: provider.provider, code: code, codeVerifier: verifier)
        }
        AppLogger.auth.notice("Signed in")
        AppLogger.auth.debug("Access token \(AppLogger.redact(token: accessToken))")
    }

    /// Deletes the account on the backend, and only then forgets the token: a failed deletion leaves the
    /// person signed in and able to try again (ADR 0020).
    func deleteAccount() async throws {
        guard let accessToken else { throw APIError.unauthenticated }
        try await AppLogger.auth.measure("Account deletion") {
            try await repository.deleteAccount(accessToken: accessToken)
        }
        AppLogger.auth.notice("Account deleted")
        self.accessToken = nil
    }

    func signOut() {
        AppLogger.auth.notice("Signed out")
        accessToken = nil
    }
}
