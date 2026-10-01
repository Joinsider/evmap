import Foundation

/// Douglas–Peucker simplification of a route (ADR 0017).
///
/// MapKit hands over a route as tens of thousands of vertices. The backend's corridor query and the
/// request body want a few hundred, and a line that is a few hundred metres off in the middle of a
/// straight stretch costs nothing: the corridor is kilometres wide.
enum PolylineSimplifier {
    /// The first tolerance tried. Below the width of a road, so short routes pass through almost untouched.
    private static let startingTolerance = 25.0

    /// Simplifies until at most `maxPoints` remain, by widening the tolerance step by step. The ends
    /// always survive, so the result still starts and finishes where the route does.
    static func simplify(_ points: [RouteCoordinate], maxPoints: Int) -> [RouteCoordinate] {
        let limit = max(maxPoints, 2)
        guard points.count > limit else { return points }
        var tolerance = startingTolerance
        var result = simplify(points, toleranceMeters: tolerance)
        // A growing tolerance converges: at the extreme only the two ends are left.
        while result.count > limit {
            tolerance *= 1.6
            result = simplify(points, toleranceMeters: tolerance)
        }
        return result
    }

    static func simplify(_ points: [RouteCoordinate], toleranceMeters: Double) -> [RouteCoordinate] {
        guard points.count > 2 else { return points }
        // Metres on a plane around the middle of the route. Exact enough for a tolerance of tens of
        // metres, and it keeps the distance test to a few multiplications.
        let meanLatitude = points.reduce(0) { $0 + $1.latitude } / Double(points.count)
        let metersPerDegreeLongitude = 111_320 * cos(meanLatitude * .pi / 180)
        let metersPerDegreeLatitude = 110_540.0
        let plane = points.map { (x: $0.longitude * metersPerDegreeLongitude, y: $0.latitude * metersPerDegreeLatitude) }

        var keep = [Bool](repeating: false, count: points.count)
        keep[0] = true
        keep[points.count - 1] = true
        // An explicit stack: a 20 000-vertex motorway would recurse that deep on a long straight.
        var stack = [(first: Int, last: Int)]()
        stack.append((0, points.count - 1))
        while let (first, last) = stack.popLast() {
            guard last - first > 1 else { continue }
            var farthest = -1
            var farthestDistance = toleranceMeters
            for index in (first + 1)..<last {
                let distance = distanceToSegment(plane[index], plane[first], plane[last])
                if distance > farthestDistance {
                    farthestDistance = distance
                    farthest = index
                }
            }
            guard farthest >= 0 else { continue }
            keep[farthest] = true
            stack.append((first, farthest))
            stack.append((farthest, last))
        }
        return points.indices.filter { keep[$0] }.map { points[$0] }
    }

    private static func distanceToSegment(_ point: (x: Double, y: Double), _ start: (x: Double, y: Double),
                                          _ end: (x: Double, y: Double)) -> Double {
        let dx = end.x - start.x
        let dy = end.y - start.y
        let lengthSquared = dx * dx + dy * dy
        guard lengthSquared > 0 else { return hypot(point.x - start.x, point.y - start.y) }
        let t = max(0, min(1, ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared))
        return hypot(point.x - (start.x + t * dx), point.y - (start.y + t * dy))
    }
}
