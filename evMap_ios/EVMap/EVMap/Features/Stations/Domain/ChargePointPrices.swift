import Foundation

/// The ad-hoc price of a charge point (ADR 0022): what charging costs without a charging card.
///
/// Every amount is gross and optional. An absent amount means "not published", never "free" — that is
/// ``free``. The backend sends a price only where it is certain of it; a charge point without one simply has
/// no price, which the screen says rather than guesses.
struct AdHocPrice: Decodable, Hashable {
    /// A fee per minute of charging, from a minute of the session on.
    struct TimeFee: Decodable, Hashable {
        /// 0 for the whole session.
        let fromMinute: Int
        /// `nil` when a time-based fee applies but its amount is not certain; the screen then names the fee
        /// without a number.
        let perMinute: Decimal?
    }

    let currency: String
    let energyPerKwh: Decimal?
    let sessionFee: Decimal?
    let timeFees: [TimeFee]
    let free: Bool
    /// The source lists fees that are not shown (idle fees, time windows), so the amounts are not complete.
    let furtherFees: Bool
    let observedAt: Date?
    /// Who stated the price: a live source's credited name (`MobiData BW`) or the register's token (`IRVE`).
    private(set) var source: String?

    private enum CodingKeys: String, CodingKey {
        case currency, energyPerKwh, sessionFee, timeFees, free, furtherFees, observedAt, source
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        currency = try container.decodeIfPresent(String.self, forKey: .currency) ?? "EUR"
        energyPerKwh = try container.decodeIfPresent(Decimal.self, forKey: .energyPerKwh)
        sessionFee = try container.decodeIfPresent(Decimal.self, forKey: .sessionFee)
        timeFees = try container.decodeIfPresent([TimeFee].self, forKey: .timeFees) ?? []
        free = try container.decodeIfPresent(Bool.self, forKey: .free) ?? false
        furtherFees = try container.decodeIfPresent(Bool.self, forKey: .furtherFees) ?? false
        observedAt = try container.decodeIfPresent(Date.self, forKey: .observedAt)
        source = try container.decodeIfPresent(String.self, forKey: .source)
    }

    init(currency: String = "EUR", energyPerKwh: Decimal? = nil, sessionFee: Decimal? = nil, timeFees: [TimeFee] = [],
         free: Bool = false, furtherFees: Bool = false, observedAt: Date? = nil) {
        self.currency = currency
        self.energyPerKwh = energyPerKwh
        self.sessionFee = sessionFee
        self.timeFees = timeFees
        self.free = free
        self.furtherFees = furtherFees
        self.observedAt = observedAt
        self.source = nil
    }

    private init(_ price: AdHocPrice, source: String?) {
        self = price
        self.source = source
    }

    /// The same price, credited to `source`.
    func stated(by source: String) -> AdHocPrice { AdHocPrice(self, source: source) }

    /// The same price, regardless of when and by whom it was stated — what makes two charge points one row.
    var amounts: AdHocPrice {
        AdHocPrice(currency: currency, energyPerKwh: energyPerKwh, sessionFee: sessionFee, timeFees: timeFees,
                   free: free, furtherFees: furtherFees)
    }
}

/// One charge point of a station, with its operator, plugs and price.
struct StationChargePoint: Decodable, Hashable, Identifiable {
    let id: UUID
    let evseId: String?
    /// The charge point's operator; the station's where the source knows none per charge point. Differs from
    /// the station's where a station bundles several operators' sites (Spain, Switzerland).
    let operatorName: String?
    let connectors: [Connector]
    let price: AdHocPrice?

    private enum CodingKeys: String, CodingKey { case id, evseId, operatorName, connectors, price }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        evseId = try container.decodeIfPresent(String.self, forKey: .evseId)
        operatorName = try container.decodeIfPresent(String.self, forKey: .operatorName)
        connectors = try container.decodeIfPresent([Connector].self, forKey: .connectors) ?? []
        // A price the app cannot read costs that price, never the station's whole list.
        price = try? container.decodeIfPresent(AdHocPrice.self, forKey: .price)
    }

    init(id: UUID = UUID(), evseId: String? = nil, operatorName: String?, connectors: [Connector], price: AdHocPrice?) {
        self.id = id
        self.evseId = evseId
        self.operatorName = operatorName
        self.connectors = connectors
        self.price = price
    }
}

/// The charge points of one station with their prices, from `GET /stations/{id}/charge-points`.
struct StationChargePoints: Decodable, Hashable {
    let stationID: UUID
    /// The lowest energy price among the charge points, for the "from" price at the top of the station screen.
    let cheapestEnergyPerKwh: Decimal?
    let currency: String?
    let chargePoints: [StationChargePoint]
    /// Live sources whose prices are shown; their licences require crediting them.
    let sources: [LiveDataSource]

    private enum CodingKeys: String, CodingKey { case stationId, cheapestEnergyPerKwh, currency, chargePoints, sources }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        stationID = try container.decode(UUID.self, forKey: .stationId)
        cheapestEnergyPerKwh = try container.decodeIfPresent(Decimal.self, forKey: .cheapestEnergyPerKwh)
        currency = try container.decodeIfPresent(String.self, forKey: .currency)
        chargePoints = try container.decodeIfPresent([StationChargePoint].self, forKey: .chargePoints) ?? []
        sources = try container.decodeIfPresent([LiveDataSource].self, forKey: .sources) ?? []
    }

    init(stationID: UUID, cheapestEnergyPerKwh: Decimal?, currency: String?, chargePoints: [StationChargePoint],
         sources: [LiveDataSource] = []) {
        self.stationID = stationID
        self.cheapestEnergyPerKwh = cheapestEnergyPerKwh
        self.currency = currency
        self.chargePoints = chargePoints
        self.sources = sources
    }

    /// Whether any charge point has a price; without one the price section is not shown at all.
    var hasPrices: Bool { chargePoints.contains { $0.price != nil } }

    /// Whether every charge point is free — then the "from" price says so instead of naming an amount.
    var isFree: Bool { hasPrices && chargePoints.allSatisfy { $0.price?.free == true } }

    /// The priced charge points, grouped by operator, plugs and price so a station with twelve identical posts
    /// is one row, in the order their first charge point appears.
    func priceGroups(stationOperator: String?) -> [PriceGroup] {
        var groups: [PriceGroup] = []
        for chargePoint in chargePoints {
            guard let price = chargePoint.price else { continue }
            let plugs = PriceGroup.plugs(of: chargePoint.connectors)
            let operatorName = chargePoint.operatorName
            if let index = groups.firstIndex(where: { $0.operatorName == operatorName && $0.plugs == plugs && $0.price.amounts == price.amounts }) {
                groups[index].count += 1
                groups[index].observedAt = [groups[index].observedAt, price.observedAt].compactMap { $0 }.max()
            } else {
                let differs = operatorName != nil && operatorName?.caseInsensitiveCompare(stationOperator ?? "") != .orderedSame
                groups.append(PriceGroup(operatorName: operatorName, showsOperator: differs, plugs: plugs, count: 1,
                                         price: price, observedAt: price.observedAt))
            }
        }
        return groups
    }

    /// Charge points without a known price, said next to the groups so the list does not read as complete.
    var unpricedCount: Int { chargePoints.filter { $0.price == nil }.count }
}

/// Charge points that share operator, plugs and price.
struct PriceGroup: Hashable, Identifiable {
    let operatorName: String?
    /// Only where the operator differs from the station's; otherwise the header already names it.
    let showsOperator: Bool
    /// "CCS · 150 kW", the plugs of one of the charge points.
    let plugs: String
    var count: Int
    let price: AdHocPrice
    var observedAt: Date?

    var id: String { "\(operatorName ?? "")|\(plugs)|\(price.amounts.hashValue)" }

    static func plugs(of connectors: [Connector]) -> String {
        connectors
            .map { connector in
                [connector.connectorType, connector.powerKw.map(formattedPower(kW:))].compactMap { $0 }.joined(separator: " · ")
            }
            .joined(separator: ", ")
    }
}

/// How prices are written: "0,59 €/kWh", "Startgebühr 1,50 €", "ab Min. 240: 0,10 €/min".
enum PriceFormatter {
    /// Up to three decimals: French registers state tenths of a cent (0,371 €/kWh), and rounding them away would
    /// change the price.
    static func amount(_ value: Decimal, currency: String, locale: Locale = .current) -> String {
        value.formatted(.currency(code: currency).precision(.fractionLength(2...3)).locale(locale))
    }

    /// The parts of a price, each a short phrase, in reading order.
    static func parts(of price: AdHocPrice, locale: Locale = .current) -> [String] {
        if price.free { return [String(localized: "price.free")] }
        var parts: [String] = []
        if let energy = price.energyPerKwh {
            parts.append(String(format: String(localized: "price.perKwh"), amount(energy, currency: price.currency, locale: locale)))
        }
        if let session = price.sessionFee {
            parts.append(String(format: String(localized: "price.sessionFee"), amount(session, currency: price.currency, locale: locale)))
        }
        for fee in price.timeFees {
            parts.append(timeFee(fee, currency: price.currency, locale: locale))
        }
        return parts
    }

    static func timeFee(_ fee: AdHocPrice.TimeFee, currency: String, locale: Locale = .current) -> String {
        switch (fee.fromMinute, fee.perMinute) {
        case (0, let perMinute?):
            String(format: String(localized: "price.perMinute"), amount(perMinute, currency: currency, locale: locale))
        case (let minute, let perMinute?):
            String(format: String(localized: "price.perMinuteFrom"), minute, amount(perMinute, currency: currency, locale: locale))
        case (0, nil):
            String(localized: "price.timeBased")
        case (let minute, nil):
            String(format: String(localized: "price.timeBasedFrom"), minute)
        }
    }

    /// "ab 0,49 €/kWh" for the top of the station screen, or "kostenlos".
    static func from(_ prices: StationChargePoints, locale: Locale = .current) -> String? {
        if prices.isFree { return String(localized: "price.free") }
        guard let cheapest = prices.cheapestEnergyPerKwh else { return nil }
        return String(format: String(localized: "price.fromPerKwh"),
                      amount(cheapest, currency: prices.currency ?? "EUR", locale: locale))
    }
}
