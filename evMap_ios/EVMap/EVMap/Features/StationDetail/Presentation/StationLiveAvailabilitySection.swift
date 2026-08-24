import SwiftUI

/// Live occupancy of a station's charge points.
///
/// Shown only when the backend actually resolved at least one charge point — the section is absent
/// rather than present-and-empty, because a live panel that always says "unknown" reads as a broken
/// feature and invites the user to stop trusting the ones that do answer.
///
/// Every value is rendered with its age. A live status without a timestamp cannot be told from a
/// stale one, and the whole point of this feature over the register's own daily status is that it is
/// current — so the freshness is part of the claim, not decoration.
struct StationLiveAvailabilitySection: View {
    let availability: StationLiveAvailability

    var body: some View {
        Section {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(availability.status.displayName).font(.headline)
                    Text(availability.occupancyDescription)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: availability.status.systemImage)
                    .foregroundStyle(color(for: availability.status))
            }

            // Only when some charge points resolved and others did not. Saying so keeps the count
            // above honest: "1 von 2 frei" means something different when two more are unaccounted for.
            if availability.unknown > 0 {
                Label {
                    Text(String(format: String(localized: "station.live.unresolved"), availability.unknown))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } icon: {
                    Image(systemName: "questionmark.circle").foregroundStyle(.secondary)
                }
            }

            // The per-charge-point breakdown is worth listing only when there is more than one, and
            // only for the ones that carry an identifier the driver could match to a physical post.
            ForEach(availability.chargePoints.filter { $0.status != .unknown }) { chargePoint in
                if availability.resolvedCount > 1 {
                    LabeledContent {
                        Text(chargePoint.status.displayName)
                            .foregroundStyle(color(for: chargePoint.status))
                    } label: {
                        Text(chargePoint.evseId ?? String(localized: "station.live.chargePoint"))
                            .font(.caption)
                            .monospaced()
                    }
                }
            }
        } header: {
            Text("station.live.title")
        } footer: {
            if let observedAt = availability.observedAt {
                Text(String(format: String(localized: "station.live.observedAt"),
                            observedAt.formatted(.relative(presentation: .named))))
            }
        }
    }

    /// Colour carries the same meaning as the icon, never on its own — the icon and the text both
    /// state the status, so this stays legible without colour vision.
    private func color(for status: LiveAvailability) -> Color {
        switch status {
        case .available: .green
        case .occupied: .orange
        case .outOfOrder: .red
        case .unknown: .secondary
        }
    }
}
