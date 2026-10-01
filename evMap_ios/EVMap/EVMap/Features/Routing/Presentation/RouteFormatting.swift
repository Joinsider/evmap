import Foundation

/// How the planner writes durations, distances and arrival times. One place so the summary bar, the
/// planner and the station rows agree, and all follow the device's language and units.
enum RouteFormat {
    /// `2 h 31 min`; rounded up to the minute, because a route is never shorter than it says.
    static func duration(_ seconds: TimeInterval) -> String {
        let minutes = Int((max(seconds, 0) / 60).rounded(.up))
        return Duration.seconds(minutes * 60).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated, zeroValueUnits: .hide))
    }

    static func minutes(_ minutes: Int) -> String { duration(TimeInterval(minutes * 60)) }

    static func distance(meters: Double) -> String {
        Measurement(value: meters, unit: UnitLength.meters).formatted(.measurement(width: .abbreviated, usage: .road, numberFormatStyle: .number.precision(.fractionLength(0))))
    }

    static func kilometers(_ km: Double) -> String { distance(meters: km * 1_000) }

    /// The clock time `offset` seconds after leaving now.
    static func arrival(after offset: TimeInterval, from start: Date = Date()) -> String {
        start.addingTimeInterval(offset).formatted(date: .omitted, time: .shortened)
    }

    /// `+4 min`, with `≈` for an estimate that has not been computed by MapKit.
    static func detour(_ candidate: RouteStopCandidate) -> String {
        let minutes = Int(candidate.rankingMinutes.rounded())
        let text = String(format: String(localized: "route.detour.minutes"), minutes)
        return candidate.isDetourExact ? text : "≈ " + text
    }
}
