import Foundation

/// Resolves which backend the app talks to.
///
/// Simulator builds point at the dev server on the host machine, everything else
/// at production. Setting `API_BASE_URL` in `UserDefaults` overrides both, which
/// keeps the launch-argument workflow (`-API_BASE_URL http://…`) working.
enum APIEnvironment {
    static let development = URL(string: "http://127.0.0.1:8080")!
    static let production = URL(string: "https://evmap.joinside.de")!

    static var baseURL: URL {
        if let override = UserDefaults.standard.string(forKey: "API_BASE_URL"),
           let url = URL(string: override) {
            return url
        }
        #if targetEnvironment(simulator)
        return development
        #else
        return production
        #endif
    }
}
