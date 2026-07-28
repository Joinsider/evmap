import AuthenticationServices
import SwiftUI

struct AppleSignInPrompt: View {
    @ObservedObject var authSession: AuthSession
    @State private var errorMessage: String?

    var body: some View {
        VStack(spacing: 8) {
            Text("comments.loginRequired").font(.footnote)
            SignInWithAppleButton(.signIn) { _ in } onCompletion: { result in
                Task {
                    do { try await authSession.completeAppleSignIn(result) }
                    catch { errorMessage = error.localizedDescription }
                }
            }
            .frame(height: 44)
        }
        .padding()
        .background(.thinMaterial)
        .alert("error.title", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("action.ok", role: .cancel) { }
        } message: { Text(errorMessage ?? "") }
    }
}
