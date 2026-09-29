import AuthenticationServices
import Foundation
import Testing

@testable import EVMap

/// The app side of the web sign-in (ADR 0018): what goes out to the provider and what an answer has
/// to carry before the app redeems it.
@Suite("Web sign-in", .serialized)
@MainActor
struct WebSignInTests {
    private let google = SignInProvider(
        provider: "google",
        authorizationEndpoint: URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!,
        parameters: ["client_id": "client", "redirect_uri": "https://evmap.joinside.de/auth/callback/google", "response_type": "code"],
        pkce: true)

    private func session(_ repository: StubStationRepository) -> AuthSession {
        UserDefaults.standard.removeObject(forKey: "EVMapAccessToken")
        return AuthSession(repository: repository)
    }

    private func items(_ url: URL) -> [String: String] {
        Dictionary(uniqueKeysWithValues: (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? [])
            .map { ($0.name, $0.value ?? "") })
    }

    @Test("derives the S256 challenge of RFC 7636, appendix B")
    func challenge() {
        #expect(PKCE.challenge(for: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        #expect(PKCE.randomToken().count == 43)
        #expect(PKCE.randomToken() != PKCE.randomToken())
    }

    @Test("opens the provider with state and challenge, listens on the redirect URI, and redeems the code")
    func signsIn() async throws {
        let repository = StubStationRepository()
        repository.signIn = .success("issued")
        let auth = session(repository)
        defer { auth.signOut() }

        try await auth.signIn(with: google) { url, callback in
            let query = items(url)
            #expect(query["client_id"] == "client")
            #expect(query["code_challenge_method"] == "S256")
            #expect(query["code_verifier"] == nil)
            #expect(callback.matchesURL(URL(string: "https://evmap.joinside.de/auth/callback/google?code=x")!))
            return URL(string: "https://evmap.joinside.de/auth/callback/google?code=the-code&state=\(query["state"]!)")!
        }

        #expect(auth.accessToken == "issued")
        let exchange = try #require(repository.codeSignIns.first)
        #expect(exchange.provider == "google")
        #expect(exchange.code == "the-code")
        #expect(exchange.codeVerifier.map { PKCE.challenge(for: $0).isEmpty } == false)
    }

    @Test("an answer with another state is refused without redeeming it")
    func rejectsForeignState() async {
        let repository = StubStationRepository()
        let auth = session(repository)

        await #expect(throws: APIError.self) {
            try await auth.signIn(with: google) { _, _ in
                URL(string: "https://evmap.joinside.de/auth/callback/google?code=the-code&state=forged")!
            }
        }
        #expect(repository.codeSignIns.isEmpty)
        #expect(auth.accessToken == nil)
    }

    @Test("a provider without redirect URI, or an answer without code, is refused")
    func rejectsIncompleteAnswers() async {
        let repository = StubStationRepository()
        let auth = session(repository)
        let broken = SignInProvider(provider: "github", authorizationEndpoint: google.authorizationEndpoint, parameters: [:], pkce: false)

        await #expect(throws: APIError.self) {
            try await auth.signIn(with: broken) { _, _ in Issue.record("must not open a session"); return URL(string: "https://x")! }
        }
        await #expect(throws: APIError.self) {
            try await auth.signIn(with: google) { url, _ in
                URL(string: "https://evmap.joinside.de/auth/callback/google?error=access_denied&state=\(items(url)["state"]!)")!
            }
        }
        #expect(repository.codeSignIns.isEmpty)
    }

    @Test("only the web providers are offered next to the native Apple button")
    func filtersApple() async throws {
        let repository = StubStationRepository()
        let apple = SignInProvider(provider: "apple", authorizationEndpoint: URL(string: "https://appleid.apple.com/auth/authorize")!,
                                   parameters: [:], pkce: false)
        repository.signInProviderList = .success([apple, google])

        #expect(try await session(repository).webSignInProviders().map(\.provider) == ["google"])
    }
}
