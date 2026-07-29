import SwiftUI

struct StationFilterScreen: View {
    @Binding var filter: StationFilter
    let apply: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("filter.connector") {
                    ForEach(ConnectorType.allCases) { connector in
                        Button { toggle(connector) } label: {
                            HStack {
                                Text(connector.displayName).foregroundStyle(.primary)
                                Spacer()
                                if filter.connectorTypes.contains(connector) {
                                    Image(systemName: "checkmark").foregroundStyle(.tint)
                                }
                            }
                        }
                        .accessibilityAddTraits(filter.connectorTypes.contains(connector) ? .isSelected : [])
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
                    TextField("filter.operator", text: $filter.operatorName)
                    Toggle("filter.availability", isOn: $filter.availabilityOnly)
                }
            }
            .navigationTitle("filter.title")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("action.apply") { apply(); dismiss() } } }
        }
    }

    private var powerStepIndex: Binding<Double> {
        Binding(
            get: { Double(ChargingPowerStep.index(for: filter.minimumPower)) },
            set: { filter.minimumPower = ChargingPowerStep.power(atIndex: Int($0.rounded())) }
        )
    }

    private var powerLabel: String {
        guard let power = filter.minimumPower else { return String(localized: "filter.power.any") }
        return formattedPower(kW: power)
    }

    private func toggle(_ connector: ConnectorType) {
        if filter.connectorTypes.contains(connector) {
            filter.connectorTypes.remove(connector)
        } else {
            filter.connectorTypes.insert(connector)
        }
    }
}
