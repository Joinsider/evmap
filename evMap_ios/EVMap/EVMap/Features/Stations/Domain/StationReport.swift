import Foundation

/// What is wrong with a station (ADR 0021). The raw values are the backend's tokens
/// (`api.stationreport.StationReportReason`); adding one on either side without the other makes the
/// report a 400.
enum StationReportReason: String, CaseIterable, Codable, Identifiable {
    case gone
    case wrongPower = "wrong_power"
    case wrongConnector = "wrong_connector"
    case defective
    case other

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .gone: String(localized: "stationReport.reason.gone")
        case .wrongPower: String(localized: "stationReport.reason.wrongPower")
        case .wrongConnector: String(localized: "stationReport.reason.wrongConnector")
        case .defective: String(localized: "stationReport.reason.defective")
        case .other: String(localized: "stationReport.reason.other")
        }
    }
}

enum StationReportLimits {
    /// The longest note the backend accepts; the editor stops at this so a long text is never lost to a 400.
    static let noteLength = 500
}
