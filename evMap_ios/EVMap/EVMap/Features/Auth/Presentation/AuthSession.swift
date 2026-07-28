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
    }

    func completeAppleSignIn(_ result: Result<ASAuthorization, Error>) async throws {
        guard case let .success(authorization) = result,
              let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let data = credential.identityToken,
              let identityToken = String(data: data, encoding: .utf8) else {
            throw APIError.server(String(localized: "error.appleSignIn"))
        }
        accessToken = try await repository.signInWithApple(identityToken: identityToken)
    }

    func signOut() { accessToken = nil }
}
