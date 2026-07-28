import Foundation

/// Reported service state of a station, as the backend's ingestion writes it.
///
/// This is what the data source says about the station, not live occupancy — real-time availability
/// is a v1 non-goal. The backend stores stable tokens rather than source wording (BNetzA reports
/// German labels, Open Charge Map English ones), so the localized text lives here.
///
/// The raw value must match `AvailabilityStatus` in the backend's `sync` package. An unknown token
/// is kept rather than discarded, so a backend that learns a new state degrades to showing it
/// untranslated instead of hiding the station's state entirely.
enum AvailabilityStatus: Equatable {
    case operational
    case maintenance
    case outOfService
    case unknown(String)

    init(rawValue: String) {
        switch rawValue {
        case "OPERATIONAL": self = .operational
        case "MAINTENANCE": self = .maintenance
        case "OUT_OF_SERVICE": self = .outOfService
        default: self = .unknown(rawValue)
        }
    }

    /// Whether a driver can expect to charge here right now.
    var isUsable: Bool {
        switch self {
        case .operational: true
        case .maintenance, .outOfService: false
        // An unrecognised state is not a promise that the station works.
        case .unknown: false
        }
    }

    var displayName: String {
        switch self {
        case .operational: String(localized: "station.availability.operational")
        case .maintenance: String(localized: "station.availability.maintenance")
        case .outOfService: String(localized: "station.availability.outOfService")
        case .unknown(let raw): raw
        }
    }

    var systemImage: String {
        switch self {
        case .operational: "checkmark.circle"
        case .maintenance: "wrench.and.screwdriver"
        case .outOfService: "xmark.circle"
        case .unknown: "questionmark.circle"
        }
    }
}

extension Station {
    /// Parsed form of the backend's raw status string, `nil` when the source reported none.
    var availability: AvailabilityStatus? {
        guard let availabilityStatus, !availabilityStatus.isEmpty else { return nil }
        return AvailabilityStatus(rawValue: availabilityStatus)
    }
}
