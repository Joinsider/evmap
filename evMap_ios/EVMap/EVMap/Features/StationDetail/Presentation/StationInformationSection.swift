import SwiftUI

struct StationInformationSection: View {
    let station: Station

    var body: some View {
        Section("station.details") {
            Text(station.address.isEmpty ? String(localized: "station.addressUnavailable") : station.address)
            if let power = station.maxPowerKw, let tier = station.powerTier {
                // Names the tier the pin colour stands for, so the map's ramp is legible
                // to anyone who cannot rely on the colour itself.
                Label {
                    Text("\(formattedPower(kW: power)) · \(tier.displayName)")
                } icon: {
                    Image(systemName: "bolt.fill").foregroundStyle(tier.color)
                }
            }
            if let operatorName = station.operatorName { Label(operatorName, systemImage: "building.2") }
            if let availability = station.availability {
                Label(availability.displayName, systemImage: availability.systemImage)
            }
        }
    }
}
