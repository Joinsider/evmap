import SwiftUI

/// Everything the user can decide about which stations the map shows, in one place.
///
/// Replaces the former filter sheet: those criteria are now remembered between launches, which
/// makes them settings rather than a per-session filter, and the provider preferences belong beside
/// them rather than in a second screen that means almost the same thing. See ADR 0014.
struct SettingsScreen: View {
    @ObservedObject var model: SettingsViewModel
    let repository: any ChargingStationRepository
    @Environment(\.dismiss) private var dismiss
    @State private var isConfirmingReset = false

    var body: some View {
        NavigationStack {
            Form {
                Section("filter.connector") {
                    ForEach(ConnectorType.allCases) { connector in
                        Button { toggle(connector) } label: {
                            HStack {
                                Text(connector.displayName).foregroundStyle(.primary)
                                Spacer()
                                if model.settings.connectorTypes.contains(connector) {
                                    Image(systemName: "checkmark").foregroundStyle(.tint)
                                }
                            }
                        }
                        .accessibilityAddTraits(model.settings.connectorTypes.contains(connector) ? .isSelected : [])
                    }
                }
                Section("filter.minimumPower") {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(powerLabel).foregroundStyle(.secondary).monospacedDigit()
                        Slider(value: powerStepIndex, in: ChargingPowerStep.sliderRange, step: 1)
                            .accessibilityLabel("filter.minimumPower")
                            .accessibilityValue(powerLabel)
                    }
                }
                Section("filter.connection") {
                    Toggle("filter.availability", isOn: $model.settings.availabilityOnly)
                }
                Section {
                    NavigationLink {
                        ProviderPreferencesScreen(model: model, repository: repository)
                    } label: {
                        LabeledContent("settings.providers") { Text(providerSummary) }
                    }
                } header: {
                    Text("settings.providers")
                } footer: {
                    Text("settings.providers.footer")
                }
                Section {
                    Button("settings.reset", role: .destructive) { isConfirmingReset = true }
                        // Nothing to undo when nothing was changed, and a live button that does
                        // nothing invites the tap that teaches the user it does nothing.
                        .disabled(model.settings.isDefault)
                } footer: {
                    Text("settings.reset.footer")
                }
            }
            .navigationTitle("settings.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("action.done") { dismiss() } }
            }
            .confirmationDialog("settings.reset.confirm", isPresented: $isConfirmingReset, titleVisibility: .visible) {
                Button("settings.reset", role: .destructive) { model.reset() }
                Button("action.cancel", role: .cancel) { }
            } message: {
                Text("settings.reset.message")
            }
        }
    }

    /// What the providers row says on the right. Which number is worth showing depends on the
    /// global switch: with it on, the exceptions are the hidden networks; with it off, the map
    /// shows nothing *but* the exceptions, and a count of hidden networks would be meaningless
    /// (it is every network there is, minus a handful).
    private var providerSummary: String {
        // Formatted into a string first: interpolating the `Int` would look up a `%lld` key that
        // no strings file declares.
        if let visible = model.settings.visibleProviders {
            guard !visible.isEmpty else { return String(localized: "settings.providers.noneVisible") }
            return String(localized: "settings.providers.only \(visible.count.formatted())")
        }
        let hidden = model.settings.hiddenProviders.count
        guard hidden > 0 else { return String(localized: "settings.providers.none") }
        return String(localized: "settings.providers.hidden \(hidden.formatted())")
    }

    private var powerStepIndex: Binding<Double> {
        Binding(
            get: { Double(ChargingPowerStep.index(for: model.settings.minimumPower)) },
            set: { model.settings.minimumPower = ChargingPowerStep.power(atIndex: Int($0.rounded())) }
        )
    }

    private var powerLabel: String {
        guard let power = model.settings.minimumPower else { return String(localized: "filter.power.any") }
        return formattedPower(kW: power)
    }

    private func toggle(_ connector: ConnectorType) {
        if model.settings.connectorTypes.contains(connector) {
            model.settings.connectorTypes.remove(connector)
        } else {
            model.settings.connectorTypes.insert(connector)
        }
    }
}
