import SwiftUI

/// The ad-hoc price of a station's charge points (ADR 0022): what charging costs without a charging card.
///
/// One row per group of charge points with the same operator, plugs and price, so twelve identical posts are
/// one line. Shown only when at least one charge point has a price — the backend sends one only where it is
/// certain, and a section that only ever says "unknown" would read as broken.
///
/// Every price is credited and dated: it is what the operator states, it changes, and the live sources'
/// licences require naming them. A charging card can be cheaper or dearer; the footer says so, because the
/// number here is the price for someone paying at the post.
struct StationPriceSection: View {
    let prices: StationChargePoints
    let stationOperator: String?

    var body: some View {
        let groups = prices.priceGroups(stationOperator: stationOperator)
        Section {
            ForEach(groups) { group in
                VStack(alignment: .leading, spacing: 3) {
                    Text(PriceFormatter.parts(of: group.price).joined(separator: " · "))
                        .font(.headline)
                    Text(details(of: group))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if group.price.furtherFees {
                        Label("price.furtherFees", systemImage: "info.circle")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            if prices.unpricedCount > 0 {
                Text(String(format: String(localized: "price.unpriced"), prices.unpricedCount))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("price.title")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text("price.disclaimer")
                credits(groups)
            }
        }
    }

    /// "2 × CCS · 150 kW · Partner AG · Stand 01.04.2026": how many, which plugs, the operator where it is not the
    /// station's, and since when the price stands. Per row, because one station's prices can be months apart.
    private func details(of group: PriceGroup) -> String {
        var parts: [String] = []
        if group.count > 1 { parts.append(String(format: String(localized: "price.chargePointCount"), group.count)) }
        if !group.plugs.isEmpty { parts.append(group.plugs) }
        if group.showsOperator, let operatorName = group.operatorName { parts.append(operatorName) }
        if let observedAt = group.observedAt {
            parts.append(String(format: String(localized: "price.observedAt"), observedAt.formatted(date: .numeric, time: .omitted)))
        }
        return parts.isEmpty ? String(localized: "station.live.chargePoint") : parts.joined(separator: " · ")
    }

    /// Live sources with their licence and link; a register's price is credited by its token, which the
    /// station's own source list already names in full.
    @ViewBuilder
    private func credits(_ groups: [PriceGroup]) -> some View {
        let live = Set(prices.sources.map(\.name))
        let registers = Array(Set(groups.compactMap(\.price.source).filter { !live.contains($0) })).sorted()
        ForEach(prices.sources, id: \.self) { source in
            let text = String(format: String(localized: "price.source"),
                              source.licence.map { String(format: String(localized: "station.live.source.licence"), source.name, $0) } ?? source.name)
            if let url = source.url { Link(text, destination: url) } else { Text(text) }
        }
        if !registers.isEmpty {
            Text(String(format: String(localized: "price.source"), registers.joined(separator: ", ")))
        }
    }
}
