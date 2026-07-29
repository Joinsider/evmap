import SwiftUI

struct StationInfrastructureSections: View {
    let detail: StationDetail

    var body: some View {
        Section("station.connectors") {
            ForEach(detail.connectors) { connector in
                HStack {
                    Text(connector.connectorType)
                    Spacer()
                    Text(connector.powerKw.map(formattedPower(kW:)) ?? "—")
                }
            }
        }
        // The header already names what the row is; a "Data source:" label above the source
        // list only repeated it. The merge note stays, as a footer — that the record was
        // deduplicated across registers is something the list of names does not say by itself.
        Section {
            Text(detail.sources.joined(separator: ", "))
        } header: {
            Text("station.sources")
        } footer: {
            if detail.sources.count > 1 { Text("station.combinedSources") }
        }
    }
}
