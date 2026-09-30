import CoreLocation
import Foundation
import Testing

@testable import EVMap

/// The closed vocabularies the UI renders: every case must have a label and an icon, and the
/// lenient decoding the settings rely on must stay lenient.
@Suite("Domain vocabulary")
@MainActor
struct DomainVocabularyTests {

    @Test("every live status has a localised label and an icon", arguments: [
        LiveAvailability.available, .occupied, .outOfOrder, .unknown
    ])
    func liveStatusLabels(status: LiveAvailability) {
        #expect(!status.displayName.isEmpty)
        #expect(!status.displayName.hasPrefix("station.live."))
        #expect(!status.systemImage.isEmpty)
    }

    @Test("every station report reason has a localised label and the backend's token", arguments: StationReportReason.allCases)
    func stationReportReasons(reason: StationReportReason) {
        #expect(!reason.displayName.isEmpty)
        #expect(!reason.displayName.hasPrefix("stationReport.reason."))
        #expect(StationReportReason(rawValue: reason.rawValue) == reason)
    }

    @Test("the station report tokens are the backend's closed set")
    func stationReportTokens() {
        #expect(StationReportReason.allCases.map(\.rawValue) == ["gone", "wrong_power", "wrong_connector", "defective", "other"])
    }

    @Test("the occupancy line counts only resolved charge points")
    func occupancyDescription() {
        let live = Fixtures.live(stationID: UUID(), available: 2, occupied: 1, unknown: 5)
        #expect(live.resolvedCount == 3)
        #expect(live.occupancyDescription.contains("2"))
        #expect(live.occupancyDescription.contains("3"))
        #expect(live.id == live.stationID)
    }

    @Test("a live status token from a newer backend decodes as unknown rather than as raw text")
    func unknownLiveToken() {
        #expect(LiveAvailability(rawValue: "CHARGING_SOON") == .unknown)
        #expect(LiveAvailability(rawValue: "OUT_OF_ORDER") == .outOfOrder)
    }

    @Test("every provider preference has a label and an icon; only the implemented two are offered",
          arguments: ProviderPreference.allCases)
    func providerPreferenceLabels(preference: ProviderPreference) {
        #expect(!preference.displayName.hasPrefix("provider.preference."))
        #expect(!preference.systemImage.isEmpty)
        #expect(preference.id == preference.rawValue)
        #expect(preference.hidesStations == (preference == .hidden))
    }

    @Test("an unknown stored preference decodes as the fallback instead of resetting the settings")
    func lenientPreferenceDecoding() throws {
        let decoded = try JSONDecoder().decode([ProviderPreference].self, from: Data(#"["hidden","favourite"]"#.utf8))
        #expect(decoded == [.hidden, .fallback])
        #expect(ProviderPreference.selectableCases == [.shown, .hidden])
    }

    @Test("the filter's log line names what is set and counts, never lists, the providers")
    func filterLogDescription() {
        #expect(StationFilter().logDescription == "(no filter)")

        var filter = StationFilter()
        filter.connectorTypes = [.type2, .ccs]
        filter.minimumPower = 150
        filter.excludedProviders = ["Tesla", "Aral pulse"]
        filter.includedProviders = ["IONITY"]
        filter.availabilityOnly = true
        let line = filter.logDescription
        #expect(line.contains("connectors=CCS|Type 2"))
        #expect(line.contains("minPower=150kW"))
        #expect(line.contains("hiddenProviders=2"))
        #expect(line.contains("onlyProviders=1"))
        #expect(line.contains("availableOnly"))
        #expect(!line.contains("Tesla"))
    }

    @Test("the power slider maps between steps and kilowatts at its ends and in between")
    func powerSteps() {
        #expect(ChargingPowerStep.index(for: nil) == 0)
        #expect(ChargingPowerStep.index(for: 150) == 6)
        #expect(ChargingPowerStep.index(for: 120) == 5)
        #expect(ChargingPowerStep.power(atIndex: -3) == nil)
        #expect(ChargingPowerStep.power(atIndex: 99) == 600)
        #expect(ChargingPowerStep.sliderRange == 0...9)
    }

    @Test("a provider is identified by its name, which is also what preferences are keyed by")
    func providerIdentity() {
        #expect(ChargingProvider(name: "IONITY", stationCount: 412).id == "IONITY")
    }

    @Test("a station's address skips missing parts instead of printing empty separators")
    func stationAddress() {
        let station = Station(id: UUID(), displayName: "X", street: nil, city: "Stuttgart", postalCode: "70173",
                              countryCode: "DE", operatorName: nil, latitude: 48.7, longitude: 9.1,
                              availabilityStatus: nil, maxPowerKw: nil)
        #expect(station.address == "70173 Stuttgart")
        #expect(station.coordinate.latitude == 48.7)
    }
}
