import Foundation
import SwiftUI
import Testing

@testable import EVMap

@Suite("Station clustering")
struct StationClustererTests {
    private func station(latitude: Double, longitude: Double, powerKw: Double? = nil) -> Station {
        Station(id: UUID(), displayName: "Station", street: nil, city: nil, postalCode: nil,
                countryCode: "DE", operatorName: nil, latitude: latitude, longitude: longitude,
                availabilityStatus: nil, maxPowerKw: powerKw)
    }

    @Test("Neighbouring stations merge into one pin when zoomed out")
    func mergesNeighboursAtLowZoom() {
        let stations = [
            station(latitude: 52.5200, longitude: 13.4050),
            station(latitude: 52.5210, longitude: 13.4060),
            station(latitude: 52.5205, longitude: 13.4055)
        ]
        let annotations = StationClusterer.cluster(stations, latitudeSpan: 5)
        #expect(annotations.count == 1)
        #expect(annotations[0].count == 3)
        #expect(annotations[0].station == nil)
    }

    @Test("The same stations stay separate when zoomed in")
    func keepsNeighboursApartAtHighZoom() {
        let stations = [
            station(latitude: 52.5200, longitude: 13.4050),
            station(latitude: 52.5300, longitude: 13.4150)
        ]
        let annotations = StationClusterer.cluster(stations, latitudeSpan: 0.005)
        #expect(annotations.count == 2)
        #expect(annotations.allSatisfy { $0.station != nil })
    }

    @Test("A cluster sits on the centroid of its members")
    func clusterUsesCentroid() {
        let annotations = StationClusterer.cluster([
            station(latitude: 52.50, longitude: 13.40),
            station(latitude: 52.60, longitude: 13.50)
        ], latitudeSpan: 20)
        #expect(annotations.count == 1)
        #expect(abs(annotations[0].latitude - 52.55) < 0.0001)
        #expect(abs(annotations[0].longitude - 13.45) < 0.0001)
    }

    @Test("A cluster takes the colour of its strongest member")
    func clusterReportsStrongestMember() {
        let annotations = StationClusterer.cluster([
            station(latitude: 52.5200, longitude: 13.4050, powerKw: 11),
            station(latitude: 52.5205, longitude: 13.4055, powerKw: 350),
            station(latitude: 52.5210, longitude: 13.4060, powerKw: nil)
        ], latitudeSpan: 5)
        #expect(annotations.count == 1)
        #expect(annotations[0].maxPowerKw == 350)
        #expect(annotations[0].powerTier == .ultra)
    }

    @Test("A single station keeps its own identity as the pin id")
    func singleStationKeepsIdentity() {
        let only = station(latitude: 52.52, longitude: 13.405)
        let annotations = StationClusterer.cluster([only], latitudeSpan: 5)
        #expect(annotations == [StationAnnotation(id: only.id.uuidString, latitude: only.latitude, longitude: only.longitude, stations: [only])])
        #expect(annotations[0].station == only)
    }

    @Test("Clustering an empty result yields no pins")
    func emptyInput() {
        #expect(StationClusterer.cluster([], latitudeSpan: 5).isEmpty)
    }
}

@Suite("Charging power tiers")
struct ChargingPowerTierTests {
    @Test("Boundaries land in the tier they open, not the one below",
          arguments: [(0.0, ChargingPowerTier.slow), (11, .slow), (21.9, .slow), (22, .standard),
                      (43, .standard), (50, .fast), (149, .fast), (150, .highPower),
                      (299, .highPower), (300, .ultra), (400, .ultra)])
    func tierBoundaries(powerKw: Double, expected: ChargingPowerTier) {
        #expect(ChargingPowerTier(powerKw: powerKw) == expected)
    }

    @Test("A station without a reported rating has no tier and stays grey")
    func unratedStationHasNoTier() {
        let unrated = Station(id: UUID(), displayName: "Station", street: nil, city: nil, postalCode: nil,
                              countryCode: "DE", operatorName: nil, latitude: 52.52, longitude: 13.405,
                              availabilityStatus: nil, maxPowerKw: nil)
        #expect(unrated.powerTier == nil)
        #expect(unrated.powerColor == .gray)
    }

    @Test("Power formatting resolves the localized resource instead of falling through to the key")
    func powerFormattingIsLocalized() {
        // The bug this guards: interpolating the Double directly builds the key `station.power %lf`,
        // which no strings file declares, so the unit silently disappears.
        #expect(formattedPower(kW: 150).contains("kW"))
        #expect(formattedPower(kW: 150).contains("150"))
        #expect(!formattedPower(kW: 150).contains("station.power"))
    }
}
