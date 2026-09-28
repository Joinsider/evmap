import Foundation
import Testing

@testable import EVMap

/// The payloads below are shaped like the backend's actual responses, including the fact that it
/// omits null fields (`spring.jackson.default-property-inclusion: non_null`) — a decoder that
/// required them would fail on exactly the stations with the least data.
@Suite("Live availability")
struct LiveAvailabilityTests {
    private func decode<T: Decodable>(_ json: String, as _: T.Type = T.self) throws -> T {
        try JSONDecoder.evmap.decode(T.self, from: Data(json.utf8))
    }

    @Test("Decodes a station's live status with its charge points")
    func decodesStationPayload() throws {
        let availability: StationLiveAvailability = try decode("""
        {"stationId":"6C8B4E1E-6C2C-4E9A-9B77-9F0A2E7B1C31","status":"AVAILABLE",
         "available":2,"occupied":1,"outOfOrder":0,"unknown":1,
         "observedAt":"2026-08-24T02:01:16Z",
         "chargePoints":[
           {"id":"1B0F0F5A-1111-4111-8111-111111111111","evseId":"DE*EBW*E912316*1",
            "status":"AVAILABLE","observedAt":"2026-08-24T02:01:16Z"},
           {"id":"1B0F0F5A-2222-4222-8222-222222222222","evseId":"DE*EBW*E912316*2",
            "status":"OCCUPIED","observedAt":"2026-08-24T02:00:00Z"}]}
        """)

        #expect(availability.status == .available)
        #expect(availability.available == 2)
        #expect(availability.unknown == 1)
        // Counts only what actually resolved — the unknown charge point is not in the denominator.
        #expect(availability.resolvedCount == 3)
        #expect(availability.observedAt == ISO8601DateFormatter().date(from: "2026-08-24T02:01:16Z"))
        #expect(availability.chargePoints.count == 2)
        // Shown as the operator publishes it, not in the backend's normalized comparison form.
        #expect(availability.chargePoints.first?.evseId == "DE*EBW*E912316*1")
        #expect(availability.chargePoints.last?.status == .occupied)
    }

    @Test("Decodes the source credits, per station and per charge point")
    func decodesSources() throws {
        let availability: StationLiveAvailability = try decode("""
        {"stationId":"6C8B4E1E-6C2C-4E9A-9B77-9F0A2E7B1C31","status":"AVAILABLE",
         "available":1,"occupied":0,"outOfOrder":0,"unknown":1,
         "chargePoints":[
           {"id":"1B0F0F5A-1111-4111-8111-111111111111","evseId":"FRS37E219940",
            "status":"AVAILABLE","observedAt":"2026-09-27T19:01:37Z","source":"transport.data.gouv.fr"},
           {"id":"1B0F0F5A-2222-4222-8222-222222222222","status":"UNKNOWN"}],
         "sources":[{"name":"transport.data.gouv.fr","licence":"Licence Ouverte 2.0",
                     "url":"https://transport.data.gouv.fr/resources/84098"}]}
        """)

        let source = try #require(availability.sources.first)
        #expect(availability.sources.count == 1)
        #expect(source.name == "transport.data.gouv.fr")
        #expect(source.licence == "Licence Ouverte 2.0")
        #expect(source.url?.host() == "transport.data.gouv.fr")
        #expect(availability.chargePoints.first?.source == "transport.data.gouv.fr")
        // An unresolved charge point credits nobody.
        #expect(availability.chargePoints.last?.source == nil)
    }

    @Test("A source without licence or with a malformed URL still decodes and keeps its name")
    func decodesPartialSource() throws {
        let source: LiveDataSource = try decode("""
        {"name":"MobiData BW","url":42}
        """)

        #expect(source.name == "MobiData BW")
        #expect(source.licence == nil)
        #expect(source.url == nil)
    }

    @Test("Decodes a station nothing is known about, whose null fields the backend omits")
    func decodesUnknownPayload() throws {
        let availability: StationLiveAvailability = try decode("""
        {"stationId":"6C8B4E1E-6C2C-4E9A-9B77-9F0A2E7B1C31","status":"UNKNOWN","unknown":4}
        """)

        #expect(availability.status == .unknown)
        #expect(availability.observedAt == nil)
        #expect(availability.chargePoints.isEmpty)
        #expect(availability.sources.isEmpty)
        #expect(availability.available == 0)
        // Nothing resolved, so the detail screen renders no live section at all rather than an
        // empty one that reads as a broken feature.
        #expect(!availability.isKnown)
    }

    @Test("A status the app does not know becomes unknown rather than raw text")
    func degradesUnknownTokens() throws {
        // Unlike the register's own AvailabilityStatus, which shows an unmapped token verbatim: this
        // value sits where a driver reads "free" or "occupied", and a word they cannot interpret in
        // that position is worse than admitting we do not know.
        let availability: StationLiveAvailability = try decode("""
        {"stationId":"6C8B4E1E-6C2C-4E9A-9B77-9F0A2E7B1C31","status":"SOMETHING_NEW","available":1}
        """)

        #expect(availability.status == .unknown)
    }

    @Test("A summary claiming a status but resolving nothing is not shown")
    func requiresResolvedChargePoints() {
        // Defends the display rule rather than the decoder: `isKnown` is what keeps the section off
        // the screen, and status alone is not enough to earn it.
        let empty = StationLiveAvailability(stationID: UUID(), status: .available,
                                            counts: .init(available: 0, occupied: 0, outOfOrder: 0, unknown: 3),
                                            observedAt: nil, chargePoints: [])
        #expect(!empty.isKnown)
    }
}

@Suite("Live availability on the map")
struct LiveAvailabilityClusteringTests {
    private func station(latitude: Double, longitude: Double) -> Station {
        Station(id: UUID(), displayName: "Station", street: nil, city: nil, postalCode: nil,
                countryCode: "DE", operatorName: nil, latitude: latitude, longitude: longitude,
                availabilityStatus: nil, maxPowerKw: nil)
    }

    private func live(_ station: Station, available: Int) -> StationLiveAvailability {
        StationLiveAvailability(stationID: station.id, status: available > 0 ? .available : .occupied,
                                counts: .init(available: available, occupied: available > 0 ? 0 : 2,
                                              outOfOrder: 0, unknown: 0),
                                observedAt: Date(), chargePoints: [])
    }

    @Test("A cluster sums the free charge points of its members")
    func clusterSumsAvailability() {
        // The question asked of a group of pins is whether anything in there is free; a badge that
        // answered for one arbitrary member would mislead.
        let first = station(latitude: 52.5200, longitude: 13.4050)
        let second = station(latitude: 52.5210, longitude: 13.4060)
        let annotations = StationClusterer.cluster([first, second], latitudeSpan: 5,
                                                   liveAvailability: [first.id: live(first, available: 2),
                                                                      second.id: live(second, available: 1)])

        #expect(annotations.count == 1)
        #expect(annotations[0].liveAvailableCount == 3)
        #expect(annotations[0].hasLiveAvailability)
    }

    @Test("A pin no source covers carries no badge at all")
    func uncoveredPinHasNoBadge() {
        // Distinct from a covered pin reporting zero free: that one is drawn, because "0 frei" is
        // real information, while an absent badge means nobody knows.
        let annotations = StationClusterer.cluster([station(latitude: 52.52, longitude: 13.40)],
                                                   latitudeSpan: 0.005)
        #expect(annotations[0].liveAvailableCount == nil)
        #expect(!annotations[0].hasLiveAvailability)
    }

    @Test("A covered pin with nothing free still reports a count")
    func coveredPinReportsZero() {
        let only = station(latitude: 52.52, longitude: 13.40)
        let annotations = StationClusterer.cluster([only], latitudeSpan: 0.005,
                                                   liveAvailability: [only.id: live(only, available: 0)])
        #expect(annotations[0].liveAvailableCount == 0)
        #expect(!annotations[0].hasLiveAvailability)
    }

    @Test("An annotation never carries status for stations it does not stand for")
    func narrowsToItsOwnMembers() {
        // The viewport-wide map is handed to every annotation; each must keep only its own members,
        // otherwise a zoomed-in pin would sum the whole screen.
        let mine = station(latitude: 52.5200, longitude: 13.4050)
        let elsewhere = station(latitude: 48.1370, longitude: 11.5750)
        let annotations = StationClusterer.cluster([mine, elsewhere], latitudeSpan: 0.005,
                                                   liveAvailability: [mine.id: live(mine, available: 2),
                                                                      elsewhere.id: live(elsewhere, available: 5)])

        let mineAnnotation = try? #require(annotations.first { $0.station?.id == mine.id })
        #expect(mineAnnotation?.liveAvailableCount == 2)
    }
}
