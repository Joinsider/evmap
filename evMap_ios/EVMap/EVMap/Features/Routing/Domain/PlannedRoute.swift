import Foundation

/// One leg between two consecutive stops, as MapKit measured it.
struct RouteLeg: Codable, Hashable {
    let distance: Double
    let travelTime: TimeInterval
}

/// A route MapKit computed, stored whole so the plan stays readable without a network (ADR 0017).
struct PlannedRoute: Codable, Equatable, Identifiable {
    let id: UUID
    /// MapKit's own description of the road taken, e.g. `A8`; may be empty.
    let name: String
    let coordinates: [RouteCoordinate]
    let legs: [RouteLeg]
    let hasTolls: Bool
    /// Metres from the start to each vertex, derived once. Not stored: it follows from `coordinates`.
    private let cumulativeMeters: [Double]

    init(id: UUID = UUID(), name: String, coordinates: [RouteCoordinate], legs: [RouteLeg], hasTolls: Bool = false) {
        self.id = id
        self.name = name
        self.coordinates = coordinates
        self.legs = legs
        self.hasTolls = hasTolls
        self.cumulativeMeters = Self.cumulative(coordinates)
    }

    private enum CodingKeys: String, CodingKey { case id, name, coordinates, legs, hasTolls }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        let coordinates = try values.decode([RouteCoordinate].self, forKey: .coordinates)
        self.init(id: try values.decode(UUID.self, forKey: .id), name: try values.decode(String.self, forKey: .name),
                  coordinates: coordinates, legs: try values.decode([RouteLeg].self, forKey: .legs),
                  hasTolls: try values.decodeIfPresent(Bool.self, forKey: .hasTolls) ?? false)
    }

    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(id, forKey: .id)
        try values.encode(name, forKey: .name)
        try values.encode(coordinates, forKey: .coordinates)
        try values.encode(legs, forKey: .legs)
        try values.encode(hasTolls, forKey: .hasTolls)
    }

    var distance: Double { legs.reduce(0) { $0 + $1.distance } }
    var travelTime: TimeInterval { legs.reduce(0) { $0 + $1.travelTime } }

    /// The polyline's own length, which differs a little from MapKit's `distance`: positions along the
    /// route are measured on it, so they stay consistent with the coordinates they are looked up in.
    var polylineMeters: Double { cumulativeMeters.last ?? 0 }

    /// The point `meters` along the route, clamped to its ends.
    func coordinate(atMeters meters: Double) -> RouteCoordinate? {
        guard let first = coordinates.first, let last = coordinates.last else { return nil }
        if meters <= 0 { return first }
        if meters >= polylineMeters { return last }
        // First vertex at or beyond the target; the point lies on the segment before it.
        var low = 0, high = cumulativeMeters.count - 1
        while low < high {
            let middle = (low + high) / 2
            if cumulativeMeters[middle] < meters { low = middle + 1 } else { high = middle }
        }
        let next = max(low, 1)
        let start = coordinates[next - 1], end = coordinates[next]
        let span = cumulativeMeters[next] - cumulativeMeters[next - 1]
        let fraction = span > 0 ? (meters - cumulativeMeters[next - 1]) / span : 0
        return RouteCoordinate(latitude: start.latitude + (end.latitude - start.latitude) * fraction,
                               longitude: start.longitude + (end.longitude - start.longitude) * fraction)
    }

    /// Driving time between two positions along the route. MapKit gives a time per leg, not per road
    /// segment, so within a route it is taken as proportional to distance — good enough to subtract
    /// from a detour, which is a difference of two nearby times.
    func travelTime(fromMeters start: Double, toMeters end: Double) -> TimeInterval {
        guard polylineMeters > 0 else { return 0 }
        let from = min(max(start, 0), polylineMeters), to = min(max(end, 0), polylineMeters)
        return travelTime * abs(to - from) / polylineMeters
    }

    private static func cumulative(_ coordinates: [RouteCoordinate]) -> [Double] {
        var total = 0.0
        var result = [Double]()
        result.reserveCapacity(coordinates.count)
        for (index, coordinate) in coordinates.enumerated() {
            if index > 0 { total += coordinates[index - 1].meters(to: coordinate) }
            result.append(total)
        }
        return result
    }
}
