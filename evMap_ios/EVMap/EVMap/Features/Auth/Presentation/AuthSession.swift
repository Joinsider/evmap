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
        AppLogger.auth.debug("Exchanging Apple identity token \(AppLogger.redact(token: identityToken)) for an access token")
        accessToken = try await AppLogger.auth.measure("Apple sign-in") {
            try await repository.signInWithApple(identityToken: identityToken)
        }
        AppLogger.auth.notice("Signed in")
        AppLogger.auth.debug("Access token \(AppLogger.redact(token: accessToken))")
    }

    func signOut() {
        AppLogger.auth.notice("Signed out")
        accessToken = nil
    }
}
