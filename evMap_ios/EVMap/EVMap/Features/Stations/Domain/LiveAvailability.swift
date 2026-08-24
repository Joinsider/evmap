import Foundation

/// Whether a charge point is free *right now*.
///
/// Distinct from ``AvailabilityStatus``, which is what the register last reported about the station
/// and is refreshed once a day. This one comes from a national access point under AFIR Article 20,
/// which obliges operators to publish a change within a minute. The two are shown together and
/// answer different questions: "is this station in service at all" versus "can I plug in now".
///
/// The raw values mirror `LiveAvailability` in the backend's `availability` package.
///
/// Unlike ``AvailabilityStatus``, an unrecognised token is **not** surfaced to the user. There the
/// raw string is at least a description of a state; here it would sit where a driver reads "free"
/// or "occupied", and a word they cannot interpret in that position is worse than an honest
/// "unknown". A backend that adds a value therefore degrades to `.unknown` until the app learns it.
enum LiveAvailability: Hashable {
    case available
    case occupied
    case outOfOrder
    /// No live answer — nothing covers this charge point, or the source itself does not know.
    ///
    /// A first-class result, not an error. The honest failure mode of live availability is "we don't
    /// know"; the dishonest one is "free".
    case unknown

    init(rawValue: String) {
        switch rawValue {
        case "AVAILABLE": self = .available
        case "OCCUPIED": self = .occupied
        case "OUT_OF_ORDER": self = .outOfOrder
        default: self = .unknown
        }
    }

    var displayName: String {
        switch self {
        case .available: String(localized: "station.live.available")
        case .occupied: String(localized: "station.live.occupied")
        case .outOfOrder: String(localized: "station.live.outOfOrder")
        case .unknown: String(localized: "station.live.unknown")
        }
    }

    var systemImage: String {
        switch self {
        case .available: "bolt.circle.fill"
        case .occupied: "clock.fill"
        case .outOfOrder: "exclamationmark.triangle.fill"
        case .unknown: "questionmark.circle"
        }
    }
}

/// Live state of one charge point, as the backend resolved it.
struct ChargePointLiveStatus: Decodable, Hashable, Identifiable {
    let id: UUID
    /// The EVSE-ID as the operator publishes it — shown so it can be read off against the label on
    /// the physical post, and deliberately not the normalized form the backend joins on.
    let evseId: String?
    let status: LiveAvailability
    let observedAt: Date?

    private enum CodingKeys: String, CodingKey { case id, evseId, status, observedAt }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        evseId = try container.decodeIfPresent(String.self, forKey: .evseId)
        status = LiveAvailability(rawValue: try container.decode(String.self, forKey: .status))
        observedAt = try container.decodeIfPresent(Date.self, forKey: .observedAt)
    }

    init(id: UUID, evseId: String?, status: LiveAvailability, observedAt: Date?) {
        self.id = id
        self.evseId = evseId
        self.status = status
        self.observedAt = observedAt
    }
}

/// Live state of a station, summarized over its charge points.
struct StationLiveAvailability: Decodable, Hashable, Identifiable {
    let stationID: UUID
    let status: LiveAvailability
    let available: Int
    let occupied: Int
    let outOfOrder: Int
    /// Charge points with no live answer. Sent rather than hidden, so "2 von 4 frei" is never read
    /// off a station whose other two are simply unknown.
    let unknown: Int
    /// When the newest underlying reading was taken. `nil` when nothing is known — the UI shows the
    /// age rather than the bare value, because a status without one cannot be told from a stale one.
    let observedAt: Date?
    /// Per-charge-point detail. Empty in the map response, which needs only the summary.
    let chargePoints: [ChargePointLiveStatus]

    var id: UUID { stationID }

    private enum CodingKeys: String, CodingKey {
        case stationId, status, available, occupied, outOfOrder, unknown, observedAt, chargePoints
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        stationID = try container.decode(UUID.self, forKey: .stationId)
        status = LiveAvailability(rawValue: try container.decode(String.self, forKey: .status))
        available = try container.decodeIfPresent(Int.self, forKey: .available) ?? 0
        occupied = try container.decodeIfPresent(Int.self, forKey: .occupied) ?? 0
        outOfOrder = try container.decodeIfPresent(Int.self, forKey: .outOfOrder) ?? 0
        unknown = try container.decodeIfPresent(Int.self, forKey: .unknown) ?? 0
        observedAt = try container.decodeIfPresent(Date.self, forKey: .observedAt)
        chargePoints = try container.decodeIfPresent([ChargePointLiveStatus].self, forKey: .chargePoints) ?? []
    }

    init(stationID: UUID, status: LiveAvailability, available: Int, occupied: Int,
         outOfOrder: Int, unknown: Int, observedAt: Date?, chargePoints: [ChargePointLiveStatus]) {
        self.stationID = stationID
        self.status = status
        self.available = available
        self.occupied = occupied
        self.outOfOrder = outOfOrder
        self.unknown = unknown
        self.observedAt = observedAt
        self.chargePoints = chargePoints
    }

    /// Charge points the backend could actually resolve. Zero means nothing here is live data.
    var resolvedCount: Int { available + occupied + outOfOrder }

    /// Whether there is anything worth showing. A summary of nothing is not rendered at all rather
    /// than as an empty "unknown" row that suggests the feature is broken.
    var isKnown: Bool { status != .unknown && resolvedCount > 0 }

    /// "2 von 4 frei", counting only the charge points that have an answer.
    var occupancyDescription: String {
        String(format: String(localized: "station.live.occupancy"), available, resolvedCount)
    }
}
