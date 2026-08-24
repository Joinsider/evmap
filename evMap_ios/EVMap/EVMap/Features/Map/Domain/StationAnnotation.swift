import CoreLocation
import Foundation

/// One pin on the map: either a single station or a group of stations too close to draw apart.
struct StationAnnotation: Identifiable, Hashable {
    let id: String
    let latitude: Double
    let longitude: Double
    let stations: [Station]
    /// Live occupancy of the stations behind this pin, for the few that have any.
    ///
    /// Attached to the annotation rather than looked up while drawing, so that the pin is a complete
    /// description of itself and clustering does not have to reach back into the view model.
    var liveAvailability: [UUID: StationLiveAvailability] = [:]

    var coordinate: CLLocationCoordinate2D { .init(latitude: latitude, longitude: longitude) }
    var count: Int { stations.count }
    /// The station this pin stands for, or `nil` when it stands for several.
    var station: Station? { count == 1 ? stations.first : nil }

    /// Charge points reported free across this pin's stations, `nil` when none of them is covered.
    ///
    /// A cluster sums its members: the question a driver asks of a group of pins is whether anything
    /// in there is free, and a badge that answered only for one arbitrary member would mislead.
    var liveAvailableCount: Int? {
        let covered = stations.compactMap { liveAvailability[$0.id] }
        guard !covered.isEmpty else { return nil }
        return covered.reduce(0) { $0 + $1.available }
    }

    /// Whether anything behind this pin is known to be free right now.
    var hasLiveAvailability: Bool { (liveAvailableCount ?? 0) > 0 }
    /// A cluster takes the colour of its strongest member, so a lone HPC site stays visible
    /// after being folded into a group of slow chargers.
    var maxPowerKw: Double? { stations.compactMap(\.maxPowerKw).max() }
    var powerTier: ChargingPowerTier? { maxPowerKw.map(ChargingPowerTier.init(powerKw:)) }
}

/// Groups stations into a lat/lon grid so that pins never overlap illegibly.
///
/// The grid is anchored to absolute coordinates rather than to the viewport, so panning does not
/// reshuffle which stations belong together — cluster membership changes only on zoom. Cell width is
/// divided by `cos(latitude)` because MapKit's projection stretches longitude, which would otherwise
/// make cells wider on screen than they are tall.
enum StationClusterer {
    /// Roughly how many pins fit across the visible height before they start touching.
    private static let rowsPerScreen = 14.0

    /// - Parameter liveAvailability: live status by station id, for the minority of stations any
    ///   source covers. Passed in rather than fetched here so clustering stays a pure function of
    ///   its inputs, and so a live response arriving after the stations only needs a recluster.
    static func cluster(_ stations: [Station],
                        latitudeSpan: Double,
                        liveAvailability: [UUID: StationLiveAvailability] = [:]) -> [StationAnnotation] {
        guard !stations.isEmpty else { return [] }
        let cellHeight = latitudeSpan / rowsPerScreen
        guard cellHeight > 0 else { return stations.map { individual($0, liveAvailability) } }

        var cells: [Cell: [Station]] = [:]
        for station in stations {
            let cellWidth = cellHeight / max(cos(station.latitude * .pi / 180), 0.01)
            let cell = Cell(row: Int((station.latitude / cellHeight).rounded(.down)),
                            column: Int((station.longitude / cellWidth).rounded(.down)))
            cells[cell, default: []].append(station)
        }

        return cells.map { cell, members in
            guard members.count > 1 else { return individual(members[0], liveAvailability) }
            // Centroid rather than cell centre: a cluster should sit on its stations, not on the
            // arbitrary grid line that happened to catch them.
            return StationAnnotation(
                id: "cluster-\(cell.row)-\(cell.column)",
                latitude: members.reduce(0) { $0 + $1.latitude } / Double(members.count),
                longitude: members.reduce(0) { $0 + $1.longitude } / Double(members.count),
                stations: members,
                liveAvailability: live(for: members, liveAvailability)
            )
        }
    }

    private static func individual(_ station: Station,
                                   _ liveAvailability: [UUID: StationLiveAvailability]) -> StationAnnotation {
        StationAnnotation(id: station.id.uuidString, latitude: station.latitude, longitude: station.longitude,
                          stations: [station], liveAvailability: live(for: [station], liveAvailability))
    }

    /// Narrows the viewport-wide live map to this pin's members, so an annotation never carries
    /// status for stations it does not stand for.
    private static func live(for members: [Station],
                             _ liveAvailability: [UUID: StationLiveAvailability]) -> [UUID: StationLiveAvailability] {
        guard !liveAvailability.isEmpty else { return [:] }
        return liveAvailability.filter { entry in members.contains { $0.id == entry.key } }
    }

    private struct Cell: Hashable { let row: Int; let column: Int }
}
