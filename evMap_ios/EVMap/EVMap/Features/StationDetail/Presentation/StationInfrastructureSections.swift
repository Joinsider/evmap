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
        Section("station.sources") {
            Text(detail.sources.count > 1 ? String(localized: "station.combinedSources") : String(localized: "station.dataSource"))
            Text(detail.sources.joined(separator: ", "))
        }
    }
}
