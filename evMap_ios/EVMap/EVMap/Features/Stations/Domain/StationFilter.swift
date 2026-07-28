import Foundation

struct StationFilter: Equatable {
    var connectorTypes: Set<ConnectorType> = []
    var minimumPower: Double?
    var operatorName = ""
    var availabilityOnly = false

    /// Compact one-line rendering for log output, listing only active criteria.
    var logDescription: String {
        var parts: [String] = []
        if !connectorTypes.isEmpty { parts.append("connectors=\(connectorTypes.map(\.rawValue).sorted().joined(separator: "|"))") }
        if let minimumPower { parts.append("minPower=\(Int(minimumPower))kW") }
        if !operatorName.isEmpty { parts.append("operator=\(operatorName)") }
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
