import Combine
import Foundation

/// Owns the user's settings for the lifetime of the app and writes every change straight through
/// to the store.
///
/// There is no "save" button: a settings screen that can be abandoned without applying is a promise
/// the app would have to keep across a swipe-to-dismiss, a backgrounding and a crash. Persisting on
/// change makes the stored value and the screen the same thing at all times. What is *not*
/// immediate is the refetch — the map re-queries when the screen closes (see `MapScreen`), because
/// dragging a slider would otherwise be one network request per step.
@MainActor
final class SettingsViewModel: ObservableObject {
    @Published var settings: AppSettings {
        didSet { persist(changedFrom: oldValue) }
    }

    private let store: any AppSettingsStoring

    init(store: (any AppSettingsStoring)? = nil) {
        let store = store ?? UserDefaultsAppSettingsStore()
        self.store = store
        settings = store.load()
    }

    func preference(for provider: String) -> ProviderPreference {
        settings.preference(for: provider)
    }

    func setPreference(_ preference: ProviderPreference, for provider: String) {
        settings.setPreference(preference, for: provider)
        AppLogger.settings.info("Provider preference set to \(preference.rawValue) (\(self.settings.providerPreferences.count) configured)")
    }

    /// Puts everything back to the shipped defaults. Deliberately narrow: the search history and
    /// the Apple sign-in are other features' data with their own controls, and a button labelled
    /// "reset settings" must not quietly log somebody out.
    func reset() {
        // Assigned rather than reloaded so the reset is one publish, and so a store that fails to
        // clear its storage cannot leave the screen showing the old values.
        settings = .factoryDefaults
        // After the assignment, never before: `didSet` persists the new value, so clearing first
        // would leave the defaults written straight back into the key that was just removed.
        store.reset()
    }

    private func persist(changedFrom oldValue: AppSettings) {
        // `didSet` fires for every keystroke and every slider step, most of which change nothing.
        guard settings != oldValue else { return }
        store.save(settings)
    }
}
