import SwiftUI

struct StationInformationSection: View {
    let station: Station

    var body: some View {
        Section("station.details") {
            Text(station.address.isEmpty ? String(localized: "station.addressUnavailable") : station.address)
            if let operatorName = station.operatorName { Label(operatorName, systemImage: "building.2") }
            if let availability = station.availabilityStatus { Label(availability, systemImage: "checkmark.circle") }
        }
    }
}
