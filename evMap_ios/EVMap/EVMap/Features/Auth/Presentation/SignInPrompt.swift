import AuthenticationServices
import SwiftUI

/// Sign in with Apple natively, and with every web provider the backend offers (ADR 0018).
///
/// The web providers are asked for on appear; if the backend is unreachable or offers none, only the
/// Apple button shows — the prompt never blocks on them.
struct SignInPrompt: View {
    @ObservedObject var authSession: AuthSession
    /// What the prompt says above the buttons; by default it is about leaving a comment.
    var message: LocalizedStringKey = "comments.loginRequired"
    @Environment(\.webAuthenticationSession) private var webAuthenticationSession
    @State private var providers: [SignInProvider] = []
    @State private var errorMessage: String?
    @State private var inProgress = false

    var body: some View {
        VStack(spacing: 8) {
            Text(message).font(.footnote)
            SignInWithAppleButton(.signIn) { request in
                // The address is what lets an Apple account be linked to a Google or GitHub one. The user
                // can still hide it; Apple then hands out a relay address, which links nothing.
                request.requestedScopes = [.email]
            } onCompletion: { result in
                Task {
                    do { try await authSession.completeAppleSignIn(result) }
                    catch { errorMessage = error.localizedDescription }
                }
            }
            .frame(height: 44)

            ForEach(providers) { provider in
                Button { signIn(with: provider) } label: {
                    Text(label(for: provider)).frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.bordered)
                .disabled(inProgress)
            }
        }
        .padding()
        .background(.thinMaterial)
        .task { await loadProviders() }
        .alert("error.title", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("action.ok", role: .cancel) {
                // Dismissing is the whole action; the binding's setter clears the message.
            }
        } message: { Text(errorMessage ?? "") }
    }

    private func label(for provider: SignInProvider) -> LocalizedStringKey {
        switch provider.provider {
        case "google": "auth.signInWithGoogle"
        case "github": "auth.signInWithGitHub"
        default: "auth.signIn"
        }
    }

    private func loadProviders() async {
        do {
            providers = try await authSession.webSignInProviders()
        } catch {
            // Apple still works without the list; the failure itself is already logged by the client.
            providers = []
        }
    }

    private func signIn(with provider: SignInProvider) {
        inProgress = true
        Task {
            defer { inProgress = false }
            do {
                try await authSession.signIn(with: provider) { url, callback in
                    try await webAuthenticationSession.authenticate(using: url, callback: callback, additionalHeaderFields: [:])
                }
            } catch ASWebAuthenticationSessionError.canceledLogin {
                AppLogger.auth.info("\(provider.provider) sign-in cancelled")
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}
