import Foundation

enum APIError: LocalizedError {
    case invalidResponse
    case server(String)
    case unauthenticated

    var errorDescription: String? {
        switch self {
        case .invalidResponse: String(localized: "error.invalidResponse")
        case .server(let message): message
        case .unauthenticated: String(localized: "error.unauthenticated")
        }
    }
}
