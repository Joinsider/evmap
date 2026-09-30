import Foundation

/// The stations a person marked as favorites, newest first, each at most once (ADR 0021).
///
/// Holds whole stations, not just ids, so the list can be shown and drawn on the map without a request
/// — that is what makes favorites work signed out and offline.
struct FavoriteList: Equatable {
    private(set) var stations: [Station]

    init(_ stations: [Station] = []) {
        var seen = Set<UUID>()
        self.stations = stations.filter { seen.insert($0.id).inserted }
    }

    var ids: Set<UUID> { Set(stations.map(\.id)) }
    var isEmpty: Bool { stations.isEmpty }

    func contains(_ id: UUID) -> Bool { stations.contains { $0.id == id } }

    mutating func add(_ station: Station) {
        guard !contains(station.id) else { return }
        stations.insert(station, at: 0)
    }

    mutating func remove(_ id: UUID) {
        stations.removeAll { $0.id == id }
    }
}
