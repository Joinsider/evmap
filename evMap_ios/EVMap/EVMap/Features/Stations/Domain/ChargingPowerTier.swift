import SwiftUI

/// Charging-speed bands used to colour map pins.
///
/// The boundaries are the tiers a driver actually plans around — a domestic outlet, three-phase AC,
/// the first DC generation, current highpower, and the 300 kW+ HPC corridors — not an even split of
/// the value range. They deliberately line up with `ChargingPowerStep`, so a station that passes the
/// filter's power slider lands in the tier the user selected rather than one below it.
///
/// The ramp runs cold-to-hot (blue → red) so "faster" reads as "hotter" without a legend. Colour is
/// never the only carrier of the information: annotations expose the power in their accessibility
/// label, and the detail screen names the tier in text.
enum ChargingPowerTier: CaseIterable {
    /// Below 22 kW — household socket or single-phase AC.
    case slow
    /// 22 kW up to 50 kW — three-phase AC.
    case standard
    /// 50 kW up to 150 kW — first-generation DC fast charging.
    case fast
    /// 150 kW up to 300 kW — highpower DC.
    case highPower
    /// 300 kW and above — HPC corridors.
    case ultra

    /// Lower bound of the tier in kW; the tier extends up to the next one's bound.
    var lowerBoundKw: Double {
        switch self {
        case .slow: 0
        case .standard: 22
        case .fast: 50
        case .highPower: 150
        case .ultra: 300
        }
    }

    init(powerKw: Double) {
        self = Self.allCases.last { powerKw >= $0.lowerBoundKw } ?? .slow
    }

    /// Tier of a station, `nil` when the source reported no power rating at all.
    init?(station: Station) {
        guard let power = station.maxPowerKw else { return nil }
        self.init(powerKw: power)
    }

    var color: Color {
        switch self {
        case .slow: .blue
        case .standard: .green
        case .fast: .yellow
        case .highPower: .orange
        case .ultra: .red
        }
    }

    var displayName: String {
        switch self {
        case .slow: String(localized: "power.tier.slow")
        case .standard: String(localized: "power.tier.standard")
        case .fast: String(localized: "power.tier.fast")
        case .highPower: String(localized: "power.tier.highPower")
        case .ultra: String(localized: "power.tier.ultra")
        }
    }

    /// Threshold as it appears in the map legend — bare numerals, since the legend prints the unit once.
    var legendLabel: String {
        switch self {
        case .slow: "<\(Self.standard.roundedBound)"
        case .ultra: "\(roundedBound)+"
        default: roundedBound
        }
    }

    private var roundedBound: String { lowerBoundKw.formatted(.number.precision(.fractionLength(0))) }
}

extension Station {
    var powerTier: ChargingPowerTier? { ChargingPowerTier(station: self) }

    /// Pin colour: grey stands for "no rating reported", which is a different statement
    /// from "slow" and must not be shown as the bottom of the ramp.
    var powerColor: Color { powerTier?.color ?? .gray }
}

/// Formats a power rating for display, e.g. `150 kW`.
///
/// Goes through the `station.power %@` resource with a **string** argument on purpose: interpolating
/// the `Double` directly would build the key `station.power %lf`, which no strings file contains, so
/// the lookup silently falls through to the raw key and the unit disappears.
func formattedPower(kW: Double) -> String {
    let value = kW.formatted(.number.precision(.fractionLength(0...1)))
    return String(localized: "station.power \(value)")
}
