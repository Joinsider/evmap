import Foundation

/// The ad-hoc price of a charge point (ADR 0022): what charging costs without a charging card.
///
/// Every amount is gross and optional. An absent amount means "not published", never "free" — that is
/// ``free``. The backend sends a price only where it is certain of it; a charge point without one simply has
/// no price, which the screen says rather than guesses.
struct AdHocPrice: Decodable, Hashable {
    /// A recurring period of the week in local time, as the operator writes it (ADR 0022, L6p).
    struct TimeWindow: Decodable, Hashable {
        /// "08:00".
        let from: String
        /// "20:00", "24:00" for midnight at its end; earlier than `from` when it runs past midnight.
        let to: String
        /// "monday" … "sunday"; empty for every day.
        let days: [String]

        private enum CodingKeys: String, CodingKey { case from, to, days }

        init(from: String, to: String, days: [String] = []) {
            self.from = from
            self.to = to
            self.days = days
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            from = try container.decode(String.self, forKey: .from)
            to = try container.decode(String.self, forKey: .to)
            days = try container.decodeIfPresent([String].self, forKey: .days) ?? []
        }
    }

    /// A fee per minute of charging, from a minute of the session on.
    struct TimeFee: Decodable, Hashable {
        /// 0 for the whole session.
        let fromMinute: Int
        /// The minute from which the fee no longer applies; `nil` for the rest of the session.
        let toMinute: Int?
        /// `nil` when a time-based fee applies but its amount is not certain; the screen then names the fee
        /// without a number.
        let perMinute: Decimal?
        /// The most the fee comes to in one session.
        let cap: Decimal?
        /// When the fee applies; `nil` for always.
        let window: TimeWindow?

        init(fromMinute: Int, toMinute: Int? = nil, perMinute: Decimal?, cap: Decimal? = nil, window: TimeWindow? = nil) {
            self.fromMinute = fromMinute
            self.toMinute = toMinute
            self.perMinute = perMinute
            self.cap = cap
            self.window = window
        }
    }

    /// An energy price that applies only within `window`.
    struct EnergyWindow: Decodable, Hashable {
        let perKwh: Decimal
        let window: TimeWindow
    }

    let currency: String
    /// The price per kWh when it is the same at every hour.
    let energyPerKwh: Decimal?
    /// Prices per kWh by time of day, when they differ (then `energyPerKwh` is `nil`).
    private(set) var energyWindows: [EnergyWindow]
    let sessionFee: Decimal?
    let timeFees: [TimeFee]
    let free: Bool
    /// The source lists fees that are not shown (idle fees after charging), so the amounts are not complete.
    let furtherFees: Bool
    let observedAt: Date?
    /// How this price is paid ("qrCode", "emv", …), where a charge point has several prices that differ by it.
    private(set) var paymentMeans: [String]
    /// Who stated the price: a live source's credited name (`MobiData BW`), a publisher
    /// (`EnBW … via Mobilithek`) or the register's token (`IRVE`).
    private(set) var source: String?

    private enum CodingKeys: String, CodingKey {
        case currency, energyPerKwh, energyWindows, sessionFee, timeFees, free, furtherFees, observedAt, paymentMeans, source
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        currency = try container.decodeIfPresent(String.self, forKey: .currency) ?? "EUR"
        energyPerKwh = try container.decodeIfPresent(Decimal.self, forKey: .energyPerKwh)
        energyWindows = try container.decodeIfPresent([EnergyWindow].self, forKey: .energyWindows) ?? []
        sessionFee = try container.decodeIfPresent(Decimal.self, forKey: .sessionFee)
        timeFees = try container.decodeIfPresent([TimeFee].self, forKey: .timeFees) ?? []
        free = try container.decodeIfPresent(Bool.self, forKey: .free) ?? false
        furtherFees = try container.decodeIfPresent(Bool.self, forKey: .furtherFees) ?? false
        observedAt = try container.decodeIfPresent(Date.self, forKey: .observedAt)
        paymentMeans = try container.decodeIfPresent([String].self, forKey: .paymentMeans) ?? []
        source = try container.decodeIfPresent(String.self, forKey: .source)
    }

    init(currency: String = "EUR", energyPerKwh: Decimal? = nil, sessionFee: Decimal? = nil, timeFees: [TimeFee] = [],
         free: Bool = false, furtherFees: Bool = false, observedAt: Date? = nil) {
        self.currency = currency
        self.energyPerKwh = energyPerKwh
        self.energyWindows = []
        self.sessionFee = sessionFee
        self.timeFees = timeFees
        self.free = free
        self.furtherFees = furtherFees
        self.observedAt = observedAt
        self.paymentMeans = []
        self.source = nil
    }

    /// The same price with energy prices per time of day.
    func charging(_ windows: [EnergyWindow]) -> AdHocPrice {
        var price = self
        price.energyWindows = windows
        return price
    }

    /// The same price, paid by `means`.
    func paid(with means: [String]) -> AdHocPrice {
        var price = self
        price.paymentMeans = means
        return price
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
            .charging(energyWindows)
            .paid(with: paymentMeans)
    }
}

/// One charge point of a station, with its operator, plugs and prices.
struct StationChargePoint: Decodable, Hashable, Identifiable {
    let id: UUID
    let evseId: String?
    /// The charge point's operator; the station's where the source knows none per charge point. Differs from
    /// the station's where a station bundles several operators' sites (Spain, Switzerland).
    let operatorName: String?
    let connectors: [Connector]
    /// Usually one; several where they differ by payment means (ADR 0022, L6p). Empty when no price is known.
    let prices: [AdHocPrice]

    /// The one price, `nil` when there is none or several.
    var price: AdHocPrice? { prices.count == 1 ? prices[0] : nil }

    private enum CodingKeys: String, CodingKey { case id, evseId, operatorName, connectors, price, prices }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        evseId = try container.decodeIfPresent(String.self, forKey: .evseId)
        operatorName = try container.decodeIfPresent(String.self, forKey: .operatorName)
        connectors = try container.decodeIfPresent([Connector].self, forKey: .connectors) ?? []
        // A price the app cannot read costs that charge point's prices, never the station's whole list. `prices` is
        // the full answer since L6p; `price` alone is what an older backend sends.
        if let all = try? container.decodeIfPresent([AdHocPrice].self, forKey: .prices) {
            prices = all
        } else if container.contains(.prices) {
            prices = []
        } else {
            prices = (try? container.decodeIfPresent(AdHocPrice.self, forKey: .price)).flatMap { $0 }.map { [$0] } ?? []
        }
    }

    init(id: UUID = UUID(), evseId: String? = nil, operatorName: String?, connectors: [Connector], price: AdHocPrice?) {
        self.init(id: id, evseId: evseId, operatorName: operatorName, connectors: connectors, prices: price.map { [$0] } ?? [])
    }

    init(id: UUID = UUID(), evseId: String? = nil, operatorName: String?, connectors: [Connector], prices: [AdHocPrice]) {
        self.id = id
        self.evseId = evseId
        self.operatorName = operatorName
        self.connectors = connectors
        self.prices = prices
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
    var hasPrices: Bool { chargePoints.contains { !$0.prices.isEmpty } }

    /// Whether every charge point is free — then the "from" price says so instead of naming an amount.
    var isFree: Bool { hasPrices && chargePoints.allSatisfy { !$0.prices.isEmpty && $0.prices.allSatisfy(\.free) } }

    /// The priced charge points, grouped by operator, plugs and prices so a station with twelve identical posts
    /// is one row, in the order their first charge point appears.
    func priceGroups(stationOperator: String?) -> [PriceGroup] {
        var groups: [PriceGroup] = []
        for chargePoint in chargePoints where !chargePoint.prices.isEmpty {
            let prices = chargePoint.prices
            let plugs = PriceGroup.plugs(of: chargePoint.connectors)
            let operatorName = chargePoint.operatorName
            let observedAt = prices.compactMap(\.observedAt).max()
            if let index = groups.firstIndex(where: { $0.operatorName == operatorName && $0.plugs == plugs
                    && $0.prices.map(\.amounts) == prices.map(\.amounts) }) {
                groups[index].count += 1
                groups[index].observedAt = [groups[index].observedAt, observedAt].compactMap { $0 }.max()
            } else {
                let differs = operatorName != nil && operatorName?.caseInsensitiveCompare(stationOperator ?? "") != .orderedSame
                groups.append(PriceGroup(operatorName: operatorName, showsOperator: differs, plugs: plugs, count: 1,
                                         prices: prices, observedAt: observedAt))
            }
        }
        return groups
    }

    /// Charge points without a known price, said next to the groups so the list does not read as complete.
    var unpricedCount: Int { chargePoints.filter { $0.prices.isEmpty }.count }
}

/// Charge points that share operator, plugs and prices.
struct PriceGroup: Hashable, Identifiable {
    let operatorName: String?
    /// Only where the operator differs from the station's; otherwise the header already names it.
    let showsOperator: Bool
    /// "CCS · 150 kW", the plugs of one of the charge points.
    let plugs: String
    var count: Int
    /// Usually one; several where they differ by payment means, each then labelled.
    let prices: [AdHocPrice]
    var observedAt: Date?

    var id: String { "\(operatorName ?? "")|\(plugs)|\(prices.map(\.amounts).hashValue)" }

    /// Whether any of the prices lists fees that are not shown.
    var furtherFees: Bool { prices.contains(where: \.furtherFees) }

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
        for energy in price.energyWindows {
            let perKwh = String(format: String(localized: "price.perKwh"), amount(energy.perKwh, currency: price.currency, locale: locale))
            parts.append(withWindow(perKwh, energy.window, locale: locale))
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
        var text = switch (fee.fromMinute, fee.toMinute, fee.perMinute) {
        case (let from, let to?, let perMinute?):
            String(format: String(localized: "price.perMinuteRange"), from, to, amount(perMinute, currency: currency, locale: locale))
        case (let from, let to?, nil):
            String(format: String(localized: "price.timeBasedRange"), from, to)
        case (0, nil, let perMinute?):
            String(format: String(localized: "price.perMinute"), amount(perMinute, currency: currency, locale: locale))
        case (let minute, nil, let perMinute?):
            String(format: String(localized: "price.perMinuteFrom"), minute, amount(perMinute, currency: currency, locale: locale))
        case (0, nil, nil):
            String(localized: "price.timeBased")
        case (let minute, nil, nil):
            String(format: String(localized: "price.timeBasedFrom"), minute)
        }
        if let cap = fee.cap {
            text += ", " + String(format: String(localized: "price.cap"), amount(cap, currency: currency, locale: locale))
        }
        return fee.window.map { withWindow(text, $0, locale: locale) } ?? text
    }

    /// "0,10 €/min (Mo–Sa 08:00–20:00)".
    static func withWindow(_ text: String, _ window: AdHocPrice.TimeWindow, locale: Locale = .current) -> String {
        "\(text) (\(self.window(window, locale: locale)))"
    }

    /// "Mo–Sa 08:00–20:00", "22:00–08:00".
    static func window(_ window: AdHocPrice.TimeWindow, locale: Locale = .current) -> String {
        let times = "\(window.from)–\(window.to)"
        let days = days(window.days, locale: locale)
        return days.isEmpty ? times : "\(days) \(times)"
    }

    private static let week = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]

    /// Weekdays in the locale's short form, runs of three or more joined: "Mo–Fr", "Sa, So".
    static func days(_ days: [String], locale: Locale = .current) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        // Calendar counts from Sunday; the feeds' week starts on Monday.
        let symbols = calendar.shortStandaloneWeekdaySymbols
        let indices = Set(days.compactMap { week.firstIndex(of: $0.lowercased()) }).sorted()
        func symbol(_ index: Int) -> String { symbols[(index + 1) % 7] }
        var runs: [[Int]] = []
        for index in indices {
            if let last = runs.last?.last, last + 1 == index { runs[runs.count - 1].append(index) } else { runs.append([index]) }
        }
        return runs.flatMap { run -> [String] in
            run.count >= 3 ? ["\(symbol(run[0]))–\(symbol(run[run.count - 1]))"] : run.map(symbol)
        }.joined(separator: ", ")
    }

    /// How the price at `index` of a charge point's prices is paid: "QR-Code / App", or "Tarif 2" where the operator
    /// names nothing the app knows. Never a raw token.
    static func paymentLabel(of price: AdHocPrice, index: Int) -> String {
        let labels = price.paymentMeans.compactMap(paymentMeans)
        let unique = labels.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
        return unique.isEmpty ? String(format: String(localized: "price.rateNumber"), index + 1) : unique.joined(separator: " / ")
    }

    private static func paymentMeans(_ token: String) -> String? {
        switch token {
        case "qrCode": String(localized: "price.payment.qrCode")
        case "emv": String(localized: "price.payment.emv")
        case "nfc": String(localized: "price.payment.nfc")
        case "website": String(localized: "price.payment.website")
        case "mobileAccount": String(localized: "price.payment.mobileAccount")
        case "paymentCreditCard": String(localized: "price.payment.creditCard")
        case "paymentDebitCard": String(localized: "price.payment.debitCard")
        default: nil
        }
    }

    /// The lines of a group: one price as its parts; several, each led by how it is paid.
    static func lines(of prices: [AdHocPrice], locale: Locale = .current) -> [String] {
        if prices.count == 1 { return [parts(of: prices[0], locale: locale).joined(separator: " · ")] }
        return prices.enumerated().map { index, price in
            "\(paymentLabel(of: price, index: index)): " + parts(of: price, locale: locale).joined(separator: " · ")
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
