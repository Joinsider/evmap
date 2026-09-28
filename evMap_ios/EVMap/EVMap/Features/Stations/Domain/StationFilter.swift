import Foundation

/// The criteria one station query is made under — what goes on the wire, not what the user edits.
///
/// Built by `AppSettings.stationFilter`, which is where the persisted settings are translated into
/// query terms; nothing constructs a filter by hand except the viewport, which raises the power
/// floor at overview scale.
struct StationFilter: Equatable {
    var connectorTypes: Set<ConnectorType> = []
    var minimumPower: Double?
    var availabilityOnly = false
    /// Operator names to leave out, from the networks the user set to `ProviderPreference.hidden`.
    /// Excluded server-side rather than after the fact, because the row limit is applied
    /// server-side: dropping them here would let hidden stations use up slots and quietly shrink
    /// the map. See ADR 0014.
    var excludedProviders: Set<String> = []
    /// The only operator names the query may return, or `nil` for "no restriction". Set when the
    /// user turned the global provider switch off, which inverts the meaning of the preference
    /// list from a blocklist into an allowlist.
    ///
    /// An **empty** set is not the same as `nil`: it means every network is hidden and nothing can
    /// match. Since an allowlist cannot be expressed as "no query parameters", that case must never
    /// reach the wire — see `matchesNothing`.
    var includedProviders: Set<String>?

    /// Whether this filter can match a station at all. Only an empty allowlist makes it false, and
    /// the repository short-circuits on it: sending an allowlist of nothing would arrive at the
    /// server as an unrestricted query and show the user every station they just hid.
    var matchesNothing: Bool { includedProviders?.isEmpty ?? false }

    /// Compact one-line rendering for log output, listing only active criteria.
    var logDescription: String {
        var parts: [String] = []
        if !connectorTypes.isEmpty { parts.append("connectors=\(connectorTypes.map(\.rawValue).sorted().joined(separator: "|"))") }
        if let minimumPower { parts.append("minPower=\(Int(minimumPower))kW") }
        // Counted, not listed: which networks somebody switched off is their preference, and forty
        // names would drown the line it is meant to explain.
        if !excludedProviders.isEmpty { parts.append("hiddenProviders=\(excludedProviders.count)") }
        if let includedProviders { parts.append("onlyProviders=\(includedProviders.count)") }
        if availabilityOnly { parts.append("availableOnly") }
        return parts.isEmpty ? "(no filter)" : "(\(parts.joined(separator: ", ")))"
    }
}

/// Discrete positions for the charging-speed slider: the common European
/// charging speeds between 3 kW and 600 kW, preceded by "no power filter".
enum ChargingPowerStep {
    static let values: [Double?] = [nil, 3, 11, 22, 50, 100, 150, 300, 400, 600]

    static var sliderRange: ClosedRange<Double> { 0...Double(values.count - 1) }

    /// Index of the highest step that the given power still satisfies, so a
    /// value that is not exactly on a step snaps down instead of being lost.
    static func index(for power: Double?) -> Int {
        guard let power else { return 0 }
        return values.lastIndex { $0.map { $0 <= power } ?? true } ?? 0
    }

    static func power(atIndex index: Int) -> Double? {
        values[min(max(index, 0), values.count - 1)]
    }
}
