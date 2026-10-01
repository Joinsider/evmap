import SwiftUI

struct StationInformationSection: View {
    let station: Station
    /// "ab 0,49 €/kWh", the lowest ad-hoc price of the station (ADR 0022); `nil` while unknown. At the top so it is
    /// the first thing seen after tapping a station, even at the sheet's medium height.
    var fromPrice: String?

    var body: some View {
        Section("station.details") {
            Text(station.address.isEmpty ? String(localized: "station.addressUnavailable") : station.address)
            if let fromPrice {
                Label(fromPrice, systemImage: "eurosign.circle")
                    .accessibilityLabel(String(format: String(localized: "price.from.accessibility"), fromPrice))
            }
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
