import Foundation
import Testing

@testable import EVMap

// MARK: - Doubles

private final class InMemoryAppSettingsStore: AppSettingsStoring {
    var stored: AppSettings?
    private(set) var saveCount = 0
    private(set) var resetCount = 0

    init(_ stored: AppSettings? = nil) { self.stored = stored }

    func load() -> AppSettings { stored ?? .factoryDefaults }

    func save(_ settings: AppSettings) {
        stored = settings
        saveCount += 1
    }

    func reset() {
        stored = nil
        resetCount += 1
    }
}

/// `UserDefaults` with its own suite, so a test never touches the real one and two tests never
/// share a key.
private func isolatedDefaults() -> UserDefaults {
    let suite = "settings.tests.\(UUID().uuidString)"
    // `removePersistentDomain` is what actually makes the suite fresh: a suite name that was used
    // before in the same process would otherwise still hold its values.
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    return defaults
}

// MARK: - AppSettings

@Suite("Settings model")
struct AppSettingsTests {

    @Test("a fresh install has no criteria and knows it")
    func defaultIsEmpty() {
        let settings = AppSettings.factoryDefaults
        #expect(settings.isDefault)
        #expect(settings.connectorTypes.isEmpty)
        #expect(settings.minimumPower == nil)
        #expect(settings.availabilityOnly == false)
        #expect(settings.providerPreferences.isEmpty)
        #expect(settings.stationFilter == StationFilter())
    }

    @Test("a provider nobody has an opinion about is shown")
    func unknownProviderDefaultsToShown() {
        #expect(AppSettings.factoryDefaults.preference(for: "IONITY") == .shown)
    }

    @Test("setting a preference back to the default forgets it rather than storing it")
    func defaultPreferenceIsNotStored() {
        var settings = AppSettings.factoryDefaults
        settings.setPreference(.hidden, for: "IONITY")
        #expect(settings.providerPreferences == ["IONITY": .hidden])
        #expect(!settings.isDefault)

        settings.setPreference(.shown, for: "IONITY")
        #expect(settings.providerPreferences.isEmpty)
        // The point of not storing it: switching a network back on must be indistinguishable from
        // never having touched it, or the reset button stays enabled forever.
        #expect(settings.isDefault)
    }

    @Test("only hidden providers reach the query — an avoided one is a routing weight, not a filter")
    func onlyHiddenProvidersAreExcluded() {
        var settings = AppSettings.factoryDefaults
        settings.setPreference(.hidden, for: "Allego")
        settings.setPreference(.avoided, for: "Aral pulse")
        settings.setPreference(.preferred, for: "EnBW")

        #expect(settings.hiddenProviders == ["Allego"])
        #expect(settings.stationFilter.excludedProviders == ["Allego"])
        #expect(settings.configuredProviders == ["Allego", "Aral pulse", "EnBW"])
    }

    @Test("with the global switch off, the preference list becomes an allowlist")
    func hidingUnlistedProvidersInvertsTheList() {
        var settings = AppSettings.factoryDefaults
        settings.unlistedProviders = .hidden
        settings.setPreference(.shown, for: "IONITY")

        #expect(settings.preference(for: "Allego") == .hidden)
        #expect(settings.visibleProviders == ["IONITY"])
        // Nothing to exclude: everything not on the allowlist is already out.
        #expect(settings.hiddenProviders.isEmpty)
        #expect(settings.stationFilter.includedProviders == ["IONITY"])
        #expect(settings.stationFilter.excludedProviders.isEmpty)
    }

    @Test("hiding everything without an exception matches nothing — it does not fall back to no filter")
    func emptyAllowlistMatchesNothing() {
        var settings = AppSettings.factoryDefaults
        settings.unlistedProviders = .hidden

        // The distinction the whole allowlist path rests on: `[]` is "no network", `nil` is "any".
        #expect(settings.stationFilter.includedProviders == [])
        #expect(settings.stationFilter.matchesNothing)
        #expect(!AppSettings.factoryDefaults.stationFilter.matchesNothing)
    }

    @Test("what counts as 'no opinion' follows the global switch")
    func storedPreferenceIsRelativeToTheGlobalSwitch() {
        var settings = AppSettings.factoryDefaults
        settings.unlistedProviders = .hidden
        // Hiding one network while everything is hidden says nothing beyond the switch itself.
        settings.setPreference(.hidden, for: "Allego")
        #expect(settings.providerPreferences.isEmpty)

        settings.setPreference(.shown, for: "Allego")
        #expect(settings.providerPreferences == ["Allego": .shown])
    }

    @Test("flipping the switch keeps the individual opinions that were already stored")
    func flippingTheSwitchKeepsStoredPreferences() {
        var settings = AppSettings.factoryDefaults
        settings.setPreference(.hidden, for: "Allego")
        settings.unlistedProviders = .hidden

        // Redundant now, but the user set it — flipping back must not have quietly erased it.
        #expect(settings.providerPreferences == ["Allego": .hidden])
        settings.unlistedProviders = .shown
        #expect(settings.hiddenProviders == ["Allego"])
    }

    @Test("the filter carries every criterion the settings describe")
    func filterMirrorsSettings() {
        var settings = AppSettings.factoryDefaults
        settings.connectorTypes = [.ccs, .type2]
        settings.minimumPower = 150
        settings.availabilityOnly = true
        settings.setPreference(.hidden, for: "Tesla")

        let filter = settings.stationFilter
        #expect(filter.connectorTypes == [.ccs, .type2])
        #expect(filter.minimumPower == 150)
        #expect(filter.availabilityOnly)
        #expect(filter.excludedProviders == ["Tesla"])
    }
}

// MARK: - Persistence

@Suite("Settings persistence")
struct AppSettingsCodingTests {

    private func roundTrip(_ settings: AppSettings) throws -> AppSettings {
        try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(settings))
    }

    @Test("every field survives an encode/decode cycle")
    func roundTripsAllFields() throws {
        var settings = AppSettings.factoryDefaults
        settings.connectorTypes = [.ccs, .chademo]
        settings.minimumPower = 50
        settings.availabilityOnly = true
        settings.setPreference(.hidden, for: "Allego")
        settings.setPreference(.preferred, for: "EnBW")

        #expect(try roundTrip(settings) == settings)
    }

    @Test("an empty default round-trips as the default, not as something merely equal-looking")
    func roundTripsDefault() throws {
        #expect(try roundTrip(.factoryDefaults).isDefault)
    }

    @Test("settings written before a field existed still load, with that field at its default")
    func decodesPayloadMissingFields() throws {
        let json = Data(#"{"availabilityOnly":true}"#.utf8)
        let settings = try JSONDecoder().decode(AppSettings.self, from: json)
        #expect(settings.availabilityOnly)
        #expect(settings.connectorTypes.isEmpty)
        #expect(settings.minimumPower == nil)
        #expect(settings.providerPreferences.isEmpty)
    }

    @Test("settings written before the global switch existed load as 'show everything'")
    func decodesPayloadWithoutTheGlobalSwitch() throws {
        let json = Data(#"{"providerPreferences":{"Allego":"hidden"}}"#.utf8)
        let settings = try JSONDecoder().decode(AppSettings.self, from: json)
        // The blocklist reading is what those installs meant; loading them as an allowlist would
        // turn one hidden network into an empty map.
        #expect(settings.unlistedProviders == .shown)
        #expect(settings.stationFilter.includedProviders == nil)
        #expect(settings.stationFilter.excludedProviders == ["Allego"])
    }

    @Test("the global switch survives a store round-trip")
    func roundTripsTheGlobalSwitch() throws {
        var settings = AppSettings.factoryDefaults
        settings.unlistedProviders = .hidden
        settings.setPreference(.shown, for: "EnBW")

        #expect(try roundTrip(settings) == settings)
        #expect(try roundTrip(settings).visibleProviders == ["EnBW"])
    }

    @Test("a connector type this build does not know is dropped, the rest of the settings survive")
    func dropsUnknownConnectorType() throws {
        let json = Data(#"{"connectorTypes":["CCS","Warp Core"],"availabilityOnly":true}"#.utf8)
        let settings = try JSONDecoder().decode(AppSettings.self, from: json)
        #expect(settings.connectorTypes == [.ccs])
        #expect(settings.availabilityOnly)
    }

    @Test("a preference a later app version wrote degrades to the default instead of failing the load")
    func decodesUnknownPreferenceAsDefault() throws {
        let json = Data(#"{"providerPreferences":{"IONITY":"blocklisted","Allego":"hidden"}}"#.utf8)
        let settings = try JSONDecoder().decode(AppSettings.self, from: json)
        #expect(settings.preference(for: "IONITY") == .shown)
        // The whole point: the unknown value must not take the readable one down with it.
        #expect(settings.preference(for: "Allego") == .hidden)
    }

    @Test("the reserved preferences round-trip, so a future version's value is not lost by this one")
    func roundTripsReservedPreferences() throws {
        for preference in ProviderPreference.allCases {
            var settings = AppSettings.factoryDefaults
            settings.setPreference(preference, for: "EnBW")
            #expect(try roundTrip(settings).preference(for: "EnBW") == preference)
        }
    }

    @Test("only shown and hidden can be picked today")
    func selectableCasesAreTheImplementedOnes() {
        #expect(ProviderPreference.selectableCases == [.shown, .hidden])
        #expect(ProviderPreference.allCases.filter(\.hidesStations) == [.hidden])
    }
}

@Suite("Settings store")
struct AppSettingsStoreTests {

    @Test("settings survive a store being thrown away and rebuilt — i.e. an app restart")
    func persistsAcrossInstances() {
        let defaults = isolatedDefaults()
        var settings = AppSettings.factoryDefaults
        settings.minimumPower = 100
        settings.setPreference(.hidden, for: "Tesla")
        UserDefaultsAppSettingsStore(defaults: defaults).save(settings)

        #expect(UserDefaultsAppSettingsStore(defaults: defaults).load() == settings)
    }

    @Test("a fresh install loads the defaults")
    func loadsDefaultsWhenEmpty() {
        #expect(UserDefaultsAppSettingsStore(defaults: isolatedDefaults()).load().isDefault)
    }

    @Test("unreadable stored data is discarded rather than allowed to fail the launch")
    func recoversFromCorruptData() {
        let defaults = isolatedDefaults()
        defaults.set(Data("not json".utf8), forKey: "settings.v1")
        let store = UserDefaultsAppSettingsStore(defaults: defaults)

        #expect(store.load().isDefault)
        // Discarded, not merely ignored: a payload that fails once fails every launch.
        #expect(defaults.data(forKey: "settings.v1") == nil)
    }

    @Test("reset clears the stored payload, so the next launch starts fresh")
    func resetClearsStorage() {
        let defaults = isolatedDefaults()
        let store = UserDefaultsAppSettingsStore(defaults: defaults)
        var settings = AppSettings.factoryDefaults
        settings.availabilityOnly = true
        store.save(settings)

        store.reset()
        #expect(defaults.data(forKey: "settings.v1") == nil)
        #expect(store.load().isDefault)
    }
}

// MARK: - View model

@Suite("Settings view model")
@MainActor
struct SettingsViewModelTests {

    @Test("the view model starts from what was stored")
    func loadsStoredSettingsOnInit() {
        var stored = AppSettings.factoryDefaults
        stored.setPreference(.hidden, for: "Allego")
        let model = SettingsViewModel(store: InMemoryAppSettingsStore(stored))

        #expect(model.preference(for: "Allego") == .hidden)
    }

    @Test("a change is written through immediately — there is no save button to press")
    func persistsOnChange() {
        let store = InMemoryAppSettingsStore()
        let model = SettingsViewModel(store: store)

        model.settings.minimumPower = 150
        #expect(store.stored?.minimumPower == 150)

        model.setPreference(.hidden, for: "Tesla")
        #expect(store.stored?.hiddenProviders == ["Tesla"])
    }

    @Test("assigning the same value again does not write")
    func skipsRedundantWrites() {
        let store = InMemoryAppSettingsStore()
        let model = SettingsViewModel(store: store)

        model.settings.availabilityOnly = true
        let writes = store.saveCount
        // A slider reports the step it is already on, repeatedly, for the length of a drag.
        model.settings.availabilityOnly = true
        model.settings.availabilityOnly = true
        #expect(store.saveCount == writes)
    }

    @Test("reset clears both the storage and what the screen is showing")
    func resetRestoresDefaults() {
        let store = InMemoryAppSettingsStore()
        let model = SettingsViewModel(store: store)
        model.settings.connectorTypes = [.ccs]
        model.setPreference(.hidden, for: "IONITY")

        model.reset()
        #expect(model.settings.isDefault)
        #expect(store.resetCount == 1)
        #expect(store.stored == nil)
    }

    @Test("reset leaves the map with an empty filter")
    func resetClearsTheFilter() {
        let model = SettingsViewModel(store: InMemoryAppSettingsStore())
        model.settings.minimumPower = 300
        model.setPreference(.hidden, for: "Allego")

        model.reset()
        #expect(model.settings.stationFilter == StationFilter())
    }
}
