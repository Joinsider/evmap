import SwiftUI

struct StationFilterScreen: View {
    @Binding var filter: StationFilter
    let apply: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("filter.connection") {
                    TextField("filter.connector", text: $filter.connectorType)
                    TextField("filter.operator", text: $filter.operatorName)
                    TextField("filter.minimumPower", value: $filter.minimumPower, format: .number).keyboardType(.decimalPad)
                }
                Section { Toggle("filter.availability", isOn: $filter.availabilityOnly) }
            }
            .navigationTitle("filter.title")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("action.apply") { apply(); dismiss() } } }
        }
    }
}
