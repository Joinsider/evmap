import Foundation

/// What the user wants the app to do with a charging network.
///
/// The vocabulary is declared in full even though the settings screen offers only two of its cases
/// today (`selectableCases`). `preferred` and `avoided` belong to route and range planning, which
/// will write them into the same stored settings — and a value written by a later version of the
/// app must stay readable by an earlier one. Fixing the raw values now costs nothing; discovering
/// later that a shipped build discards its own settings file is expensive. See ADR 0014.
enum ProviderPreference: String, Codable, CaseIterable, Identifiable, Sendable {
    /// Stations of this network appear on the map like any other. The default, and the meaning of
    /// having no stored preference at all.
    case shown
    /// Stations of this network are not requested and not drawn.
    case hidden
    /// Reserved for route planning: pick this network's stations over others when they are
    /// equivalent. Not selectable yet — it has no effect until routing exists.
    case preferred
    /// Reserved for route planning: route around this network where an alternative exists. Not
    /// selectable yet, for the same reason.
    case avoided

    /// What a network the user never expressed an opinion about is treated as.
    static let fallback = ProviderPreference.shown

    /// The subset the settings UI offers. Values outside it still decode, persist and round-trip;
    /// they just cannot be picked, so the picker never shows a choice that does nothing.
    static let selectableCases: [ProviderPreference] = [.shown, .hidden]

    var id: String { rawValue }

    /// Whether stations of this network are kept out of the map query. Only `hidden` says no;
    /// `avoided` is a routing weight, not a visibility switch, and must still draw its pins.
    var hidesStations: Bool { self == .hidden }

    var displayName: String {
        switch self {
        case .shown: String(localized: "provider.preference.shown")
        case .hidden: String(localized: "provider.preference.hidden")
        case .preferred: String(localized: "provider.preference.preferred")
        case .avoided: String(localized: "provider.preference.avoided")
        }
    }

    var systemImage: String {
        switch self {
        case .shown: "eye"
        case .hidden: "eye.slash"
        case .preferred: "star"
        case .avoided: "exclamationmark.triangle"
        }
    }

    /// A raw value this build does not know — one a later version wrote — degrades to the default
    /// rather than throwing. Throwing here would fail the enclosing `AppSettings` decode and take
    /// every other setting down with it, which is a much worse outcome than one forgotten opinion.
    init(from decoder: any Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        guard let preference = ProviderPreference(rawValue: raw) else {
            AppLogger.settings.warning("Unknown provider preference '\(raw)' — treating it as \(Self.fallback.rawValue)")
            self = .fallback
            return
        }
        self = preference
    }
}
