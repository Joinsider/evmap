import Foundation

/// Connector standards commonly found at European charging stations.
/// The raw value is what gets sent to the backend as `connectorType`.
enum ConnectorType: String, CaseIterable, Identifiable, Codable {
    case type2 = "Type 2"
    case ccs = "CCS"
    case chademo = "CHAdeMO"
    case type1 = "Type 1"
    case tesla = "Tesla"
    case schuko = "Schuko"
    case cee = "CEE"

    var id: String { rawValue }

    /// Brand and standard names, identical across locales.
    var displayName: String {
        switch self {
        case .type2: "Type 2 (Mennekes)"
        case .ccs: "CCS (Combo 2)"
        case .chademo: "CHAdeMO"
        case .type1: "Type 1"
        case .tesla: "Tesla Supercharger"
        case .schuko: "Schuko (230 V)"
        case .cee: "CEE (Blue/Red)"
        }
    }
}
