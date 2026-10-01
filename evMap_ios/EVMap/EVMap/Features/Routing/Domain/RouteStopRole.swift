import Foundation

/// What a row of the plan is: where it starts, a stop on the way, or where it ends. Decided by position
/// alone, so reordering the rows changes the roles with it.
enum RouteStopRole: Equatable {
    case start
    case waypoint
    case destination

    init(index: Int, count: Int) {
        if index == 0 {
            self = .start
        } else if index == count - 1 {
            self = .destination
        } else {
            self = .waypoint
        }
    }

    /// The letter or number on the map: A for the start, B for the destination, 1, 2 … between.
    func monogram(index: Int) -> String {
        switch self {
        case .start: "A"
        case .destination: "B"
        case .waypoint: String(index)
        }
    }
}
