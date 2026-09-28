import Foundation

/// Everything the app remembers between launches about which stations the user wants to see.
///
/// One value type rather than a key per setting: the settings screen edits it, the map derives its
/// query from it, `reset` replaces it wholesale, and persisting it is a single encode. A setting
/// added later cannot then be forgotten by a partial reset or left behind by a partial save.
struct AppSettings: Codable, Equatable {
    var connectorTypes: Set<ConnectorType> = []
    var minimumPower: Double?
    var availabilityOnly = false
    /// Keyed by operator name exactly as the backend reports it — there is no operator id to key
    /// on (see ADR 0014). Only entries that differ from `ProviderPreference.fallback` are kept, so
    /// "no opinion" costs nothing and `isDefault` stays honest after a network is switched back on.
    var providerPreferences: [String: ProviderPreference] = [:]
    /// What happens to every network the user has *not* decided about individually — the "show
    /// all"/"hide all" switch on the provider screen. Flipping it to `hidden` turns the provider
    /// preferences from a blocklist into an allowlist: the map then shows only what is listed.
    ///
    /// It is a `ProviderPreference` rather than a `Bool` so that the two reserved cases can become
    /// a global default too without a stored field changing type. See ADR 0014.
    var unlistedProviders: ProviderPreference = .fallback

    /// The state a fresh install is in, and what the reset button restores.
    static let factoryDefaults = AppSettings()

    var isDefault: Bool { self == Self.factoryDefaults }

    /// Every stored property has a default, so a fresh value needs no arguments.
    init() {
        // Intentionally empty: see the property defaults above.
    }

    func preference(for provider: String) -> ProviderPreference {
        providerPreferences[provider] ?? unlistedProviders
    }

    /// Stores an opinion, or forgets one: a preference that already matches `unlistedProviders`
    /// says nothing beyond what the global switch says, so it is removed rather than written.
    ///
    /// That is what lets the provider screen drop a row once it is back at the default — and it
    /// keeps `isDefault` honest, since a network switched back on must be indistinguishable from
    /// one that was never touched. The comparison is against the *current* global default, not
    /// against `shown`: in allowlist mode, "shown" is the exception worth storing and "hidden" is
    /// the thing that goes without saying.
    mutating func setPreference(_ preference: ProviderPreference, for provider: String) {
        if preference == unlistedProviders {
            providerPreferences.removeValue(forKey: provider)
        } else {
            providerPreferences[provider] = preference
        }
    }

    /// Networks the user switched off, in the form the station query needs. Empty while
    /// `unlistedProviders` hides — there a network is left out by *not being on* `visibleProviders`,
    /// and sending both lists would say the same thing twice.
    var hiddenProviders: Set<String> {
        guard !unlistedProviders.hidesStations else { return [] }
        return Set(providerPreferences.filter { $0.value.hidesStations }.keys)
    }

    /// The only networks the map may show, or `nil` when it may show all of them.
    ///
    /// Non-nil exactly when the global switch is off, and then possibly *empty*: "hide everything
    /// and I have not picked an exception yet" is a legitimate state that must resolve to an empty
    /// map, not to an unfiltered one. `StationFilter.matchesNothing` is what keeps that from
    /// turning into a query with no restriction at all.
    var visibleProviders: Set<String>? {
        guard unlistedProviders.hidesStations else { return nil }
        return Set(providerPreferences.filter { !$0.value.hidesStations }.keys)
    }

    /// Networks the user has any opinion about — what the settings row counts, so a preference that
    /// does not hide anything is still visibly *set* once more cases become selectable.
    var configuredProviders: [String] { providerPreferences.keys.sorted() }

    /// The station query these settings describe. The map never reads the preferences directly;
    /// this is the one place the settings vocabulary is translated into filter terms.
    var stationFilter: StationFilter {
        StationFilter(
            connectorTypes: connectorTypes,
            minimumPower: minimumPower,
            availabilityOnly: availabilityOnly,
            excludedProviders: hiddenProviders,
            includedProviders: visibleProviders
        )
    }

    // MARK: - Codable

    private enum CodingKeys: String, CodingKey {
        case connectorTypes, minimumPower, availabilityOnly, providerPreferences, unlistedProviders
    }

    /// Decoded field by field rather than through the synthesized initializer, so that stored
    /// settings written by a different version of the app still load:
    ///
    /// - a **missing** key falls back to its default, which is what happens when this build reads
    ///   settings written before that field existed;
    /// - an **unknown connector type** is dropped instead of failing the whole decode, which is
    ///   what happens when a value is removed from `ConnectorType` while somebody had it selected.
    ///
    /// The alternative — throwing — sends the store down its corrupt-data path and silently resets
    /// every other setting the user made.
    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let storedConnectors = try container.decodeIfPresent([String].self, forKey: .connectorTypes) ?? []
        connectorTypes = Set(storedConnectors.compactMap(ConnectorType.init(rawValue:)))
        if storedConnectors.count != connectorTypes.count {
            AppLogger.settings.warning("Dropped \(storedConnectors.count - self.connectorTypes.count) stored connector type(s) this build does not know")
        }
        minimumPower = try container.decodeIfPresent(Double.self, forKey: .minimumPower)
        availabilityOnly = try container.decodeIfPresent(Bool.self, forKey: .availabilityOnly) ?? false
        providerPreferences = try container.decodeIfPresent([String: ProviderPreference].self, forKey: .providerPreferences) ?? [:]
        // Missing for every payload written before the global switch existed, which is the same
        // thing those installs meant: show everything not switched off individually.
        unlistedProviders = try container.decodeIfPresent(ProviderPreference.self, forKey: .unlistedProviders) ?? .fallback
    }

    func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        // Sorted so the stored payload is stable for a given set — an unordered `Set` would
        // otherwise re-encode differently every launch for no reason.
        try container.encode(connectorTypes.map(\.rawValue).sorted(), forKey: .connectorTypes)
        try container.encodeIfPresent(minimumPower, forKey: .minimumPower)
        try container.encode(availabilityOnly, forKey: .availabilityOnly)
        try container.encode(providerPreferences, forKey: .providerPreferences)
        try container.encode(unlistedProviders, forKey: .unlistedProviders)
    }
}
