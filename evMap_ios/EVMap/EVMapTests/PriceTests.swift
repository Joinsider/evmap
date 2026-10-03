import Foundation
import Testing

@testable import EVMap

/// The ad-hoc prices of ADR 0022: decoding the backend's payload (null fields omitted), grouping identical
/// charge points into one row, and writing the amounts the way a price is read.
@Suite("Prices")
@MainActor
struct PriceTests {
    private static let german = Locale(identifier: "de_DE")
    private let stationID = UUID()

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder.evmap.decode(T.self, from: Data(json.utf8))
    }

    @Test("decodes a station's charge points with live and register prices, omitted nulls included")
    func decodesPayload() throws {
        let prices = try decode(StationChargePoints.self, """
        {"stationId": "\(stationID.uuidString)", "cheapestEnergyPerKwh": 0.371, "currency": "EUR",
         "chargePoints": [
           {"id": "\(UUID().uuidString)", "evseId": "DE*LID*E1", "operatorName": "Lidl",
            "connectors": [{"connectorType": "CCS", "powerKw": 150, "quantity": 1}],
            "price": {"currency": "EUR", "energyPerKwh": 0.55, "sessionFee": 1.5,
                      "timeFees": [{"fromMinute": 240, "perMinute": 0.1}, {"fromMinute": 300}],
                      "free": false, "furtherFees": true, "observedAt": "2026-09-30T21:53:12Z", "source": "MobiData BW"}},
           {"id": "\(UUID().uuidString)", "operatorName": "Partner AG", "connectors": []}
         ],
         "sources": [{"name": "MobiData BW", "licence": "dl-de/by-2.0", "url": "https://www.mobidata-bw.de"}]}
        """)

        #expect(prices.stationID == stationID)
        #expect(prices.cheapestEnergyPerKwh == Decimal(string: "0.371"))
        #expect(prices.chargePoints.count == 2)
        let price = try #require(prices.chargePoints[0].price)
        #expect(price.energyPerKwh == Decimal(string: "0.55"))
        #expect(price.timeFees == [.init(fromMinute: 240, perMinute: Decimal(string: "0.1")), .init(fromMinute: 300, perMinute: nil)])
        #expect(price.furtherFees)
        #expect(price.source == "MobiData BW")
        #expect(prices.chargePoints[1].price == nil)
        #expect(prices.sources.first?.name == "MobiData BW")
        #expect(prices.hasPrices)
        #expect(prices.unpricedCount == 1)
    }

    @Test("a price the app cannot read costs that price, not the list")
    func unreadablePrice() throws {
        let prices = try decode(StationChargePoints.self, """
        {"stationId": "\(stationID.uuidString)", "chargePoints": [
           {"id": "\(UUID().uuidString)", "connectors": [], "price": {"energyPerKwh": "not a number"}}]}
        """)

        #expect(prices.chargePoints.count == 1)
        #expect(prices.chargePoints[0].price == nil)
        #expect(!prices.hasPrices)
    }

    @Test("groups charge points by operator, plugs and price, and names an operator only where it differs")
    func groupsChargePoints() {
        let ccs = [Connector(connectorType: "CCS", powerKw: 150, quantity: 1)]
        let type2 = [Connector(connectorType: "Type 2", powerKw: 22, quantity: 1)]
        let dc = AdHocPrice(energyPerKwh: Decimal(string: "0.59"), observedAt: Date(timeIntervalSince1970: 100))
        let dcLater = AdHocPrice(energyPerKwh: Decimal(string: "0.59"), observedAt: Date(timeIntervalSince1970: 200))
        let ac = AdHocPrice(energyPerKwh: Decimal(string: "0.49"))
        let prices = StationChargePoints(stationID: stationID, cheapestEnergyPerKwh: Decimal(string: "0.49"), currency: "EUR",
                                         chargePoints: [
                                            .init(operatorName: "Endesa", connectors: ccs, price: dc),
                                            .init(operatorName: "Endesa", connectors: ccs, price: dcLater),
                                            .init(operatorName: "Iberdrola", connectors: type2, price: ac),
                                            .init(operatorName: "Endesa", connectors: type2, price: nil)
                                         ])

        let groups = prices.priceGroups(stationOperator: "endesa")

        #expect(groups.count == 2)
        #expect(groups[0].count == 2)
        #expect(groups[0].observedAt == Date(timeIntervalSince1970: 200))
        #expect(!groups[0].showsOperator)
        #expect(groups[1].showsOperator)
        #expect(groups[1].operatorName == "Iberdrola")
        #expect(prices.unpricedCount == 1)
    }

    @Test("writes a price in reading order, keeping tenths of a cent")
    func formatsParts() {
        let price = AdHocPrice(energyPerKwh: Decimal(string: "0.371"), sessionFee: Decimal(string: "1.5"),
                               timeFees: [.init(fromMinute: 0, perMinute: Decimal(string: "0.025")),
                                          .init(fromMinute: 240, perMinute: Decimal(string: "0.1")),
                                          .init(fromMinute: 300, perMinute: nil)])

        let parts = PriceFormatter.parts(of: price, locale: Self.german)

        #expect(parts.count == 5)
        #expect(parts[0].contains("0,371"))
        #expect(parts[0].hasSuffix("/kWh"))
        #expect(parts[1].contains("1,50"))
        #expect(parts[2].contains("0,025"))
        #expect(parts[3].contains("240"))
        #expect(parts[3].contains("0,10"))
        #expect(parts[4].contains("300"))
        #expect(PriceFormatter.parts(of: AdHocPrice(free: true)) == [String(localized: "price.free")])
    }

    @Test("decodes several prices with windows, ends, caps and payment means, and prefers them over the single price")
    func decodesPriceDetails() throws {
        let prices = try decode(StationChargePoints.self, """
        {"stationId": "\(stationID.uuidString)", "chargePoints": [
           {"id": "\(UUID().uuidString)", "connectors": [], "prices": [
             {"currency": "EUR", "energyPerKwh": 0.5, "paymentMeans": ["qrCode"],
              "timeFees": [{"fromMinute": 240, "toMinute": 390, "perMinute": 0.1, "cap": 12,
                            "window": {"from": "08:00", "to": "20:00", "days": ["monday"]}}],
              "free": false, "furtherFees": false, "source": "Grid & Co. GmbH via Mobilithek"},
             {"currency": "EUR", "energyWindows": [{"perKwh": 0.59, "window": {"from": "22:00", "to": "08:00"}}],
              "timeFees": [], "free": false, "furtherFees": false, "paymentMeans": ["emv"]}]},
           {"id": "\(UUID().uuidString)", "connectors": [], "price": {"energyPerKwh": 0.49},
            "prices": [{"energyPerKwh": 0.49}]},
           {"id": "\(UUID().uuidString)", "connectors": [], "price": {"energyPerKwh": 0.39}},
           {"id": "\(UUID().uuidString)", "connectors": [], "prices": [{"energyPerKwh": "not a number"}]}]}
        """)

        let several = prices.chargePoints[0]
        #expect(several.prices.count == 2)
        #expect(several.price == nil)
        #expect(several.prices[0].timeFees == [.init(fromMinute: 240, toMinute: 390, perMinute: Decimal(string: "0.1"),
                                                     cap: 12, window: .init(from: "08:00", to: "20:00", days: ["monday"]))])
        #expect(several.prices[0].paymentMeans == ["qrCode"])
        #expect(several.prices[1].energyWindows == [.init(perKwh: Decimal(string: "0.59")!, window: .init(from: "22:00", to: "08:00"))])
        #expect(prices.chargePoints[1].prices.count == 1)
        // An older backend sends only the single price.
        #expect(prices.chargePoints[2].price?.energyPerKwh == Decimal(string: "0.39"))
        #expect(prices.chargePoints[3].prices.isEmpty)
        #expect(prices.unpricedCount == 1)
    }

    @Test("writes ends, caps, windows and weekdays as delivered")
    func formatsLimits() {
        let window = AdHocPrice.TimeWindow(from: "08:00", to: "20:00", days: ["monday", "tuesday", "wednesday", "thursday", "friday", "sunday"])
        let price = AdHocPrice(energyWindows: [.init(perKwh: Decimal(string: "0.49")!, window: .init(from: "08:00", to: "22:00"))],
                               timeFees: [.init(fromMinute: 240, toMinute: 390, perMinute: Decimal(string: "0.1"), cap: 15, window: window),
                                          .init(fromMinute: 45, toMinute: 90, perMinute: nil)])

        let parts = PriceFormatter.parts(of: price, locale: Self.german)

        #expect(parts.count == 3)
        #expect(parts[0].contains("0,49"))
        #expect(parts[0].hasSuffix("(08:00–22:00)"))
        #expect(parts[1].contains("240–390"))
        #expect(parts[1].contains("0,10"))
        #expect(parts[1].contains("15,00"))
        #expect(parts[1].hasSuffix("08:00–20:00)"))
        #expect(parts[2].contains("45–90"))
        #expect(PriceFormatter.days(["saturday", "monday", "tuesday"], locale: Self.german) == "Mo, Di, Sa")
        #expect(PriceFormatter.days(["monday", "tuesday", "wednesday", "sunday"], locale: Self.german) == "Mo–Mi, So")
        #expect(PriceFormatter.days([], locale: Self.german).isEmpty)
    }

    @Test("several prices of a charge point are each led by how they are paid, never by a raw token")
    func labelsPaymentMeans() {
        let qr = AdHocPrice(energyPerKwh: 0.5, paymentMeans: ["qrCode", "mobileAccount"])
        let unknown = AdHocPrice(energyPerKwh: Decimal(string: "0.5355"), paymentMeans: ["somethingNew"])

        let lines = PriceFormatter.lines(of: [qr, unknown], locale: Self.german)

        #expect(lines.count == 2)
        #expect(lines[0].hasPrefix(String(localized: "price.payment.qrCode") + " / " + String(localized: "price.payment.mobileAccount") + ": "))
        #expect(lines[1].hasPrefix(String(format: String(localized: "price.rateNumber"), 2) + ": "))
        #expect(!lines[1].contains("somethingNew"))
        #expect(PriceFormatter.lines(of: [qr], locale: Self.german) == [PriceFormatter.parts(of: qr, locale: Self.german).joined(separator: " · ")])
    }

    @Test("charge points with the same several prices form one group; free means every price is free")
    func groupsSeveralPrices() {
        let qr = AdHocPrice(energyPerKwh: 0.5, paymentMeans: ["qrCode"])
        let card = AdHocPrice(energyPerKwh: 0.59, furtherFees: true, paymentMeans: ["emv"])
        let prices = StationChargePoints(stationID: stationID, cheapestEnergyPerKwh: 0.5, currency: "EUR", chargePoints: [
            .init(operatorName: nil, connectors: [], prices: [qr, card]),
            .init(operatorName: nil, connectors: [], prices: [qr, card]),
            .init(operatorName: nil, connectors: [], prices: [qr])
        ])

        let groups = prices.priceGroups(stationOperator: nil)

        #expect(groups.count == 2)
        #expect(groups[0].count == 2)
        #expect(groups[0].furtherFees)
        #expect(!groups[1].furtherFees)
        #expect(!prices.isFree)
        let free = StationChargePoints(stationID: stationID, cheapestEnergyPerKwh: nil, currency: nil, chargePoints: [
            .init(operatorName: nil, connectors: [], prices: [AdHocPrice(free: true), AdHocPrice(free: true)])])
        #expect(free.isFree)
    }

    @Test("the from price names the cheapest energy price, or free")
    func fromPrice() throws {
        let priced = StationChargePoints(stationID: stationID, cheapestEnergyPerKwh: Decimal(string: "0.49"), currency: "EUR",
                                         chargePoints: [.init(operatorName: nil, connectors: [], price: AdHocPrice(energyPerKwh: 0.49))])
        let free = StationChargePoints(stationID: stationID, cheapestEnergyPerKwh: nil, currency: nil,
                                       chargePoints: [.init(operatorName: nil, connectors: [], price: AdHocPrice(free: true))])
        let timeOnly = StationChargePoints(stationID: stationID, cheapestEnergyPerKwh: nil, currency: nil,
                                           chargePoints: [.init(operatorName: nil, connectors: [],
                                                                price: AdHocPrice(timeFees: [.init(fromMinute: 0, perMinute: 0.1)]))])

        #expect(try #require(PriceFormatter.from(priced, locale: Self.german)).contains("0,49"))
        #expect(PriceFormatter.from(free) == String(localized: "price.free"))
        #expect(PriceFormatter.from(timeOnly) == nil)
    }
}

@Suite("Station detail prices")
@MainActor
struct StationDetailPriceTests {
    private let station = Fixtures.station()

    @Test("loads the prices with the station, and keeps none when nothing is priced or the source is down")
    func loadsPrices() async {
        let repository = StubStationRepository()
        repository.stationDetail = .success(Fixtures.detail(for: station))
        repository.stationChargePoints = .success(StationChargePoints(
            stationID: station.id, cheapestEnergyPerKwh: 0.59, currency: "EUR",
            chargePoints: [.init(operatorName: nil, connectors: [], price: AdHocPrice(energyPerKwh: 0.59))]))
        let model = StationDetailViewModel(stationID: station.id, repository: repository)

        await model.load(accessToken: nil)
        #expect(model.prices?.cheapestEnergyPerKwh == 0.59)

        repository.stationChargePoints = .success(StationChargePoints(
            stationID: station.id, cheapestEnergyPerKwh: nil, currency: nil,
            chargePoints: [.init(operatorName: nil, connectors: [], price: nil)]))
        await model.loadPrices()
        #expect(model.prices == nil)

        repository.stationChargePoints = .failure(StubStationRepository.Failure(message: "down"))
        await model.loadPrices()
        #expect(model.prices == nil)
        #expect(model.errorMessage == nil)
    }
}
