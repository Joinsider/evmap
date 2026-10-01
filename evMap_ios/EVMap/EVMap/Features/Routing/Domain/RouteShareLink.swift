import Foundation

/// The share link of a route (ADR 0017): a universal link on the web domain that carries the whole
/// route in its query string, so nothing about it is stored on a server.
///
/// ```
/// https://evmap.joinside.de/route?w=48.77580,9.18290,0,,Stuttgart&w=here&w=48.13740,11.57550,30,,M%C3%BCnchen&o=tm
/// ```
/// Each `w` is `latitude,longitude,dwellMinutes,stationID,name` with the name last (it may contain a
/// comma); `w=here` is the device's position, which is never written out as a coordinate — whoever opens
/// the link starts from where *they* are. `o` holds option letters: `t` avoids tolls, `m` motorways.
///
/// A link arrives from outside the app, so everything read from it is bounded and range-checked.
enum RouteShareLink {
    static let host = "evmap.joinside.de"
    static let path = "/route"
    static let maximumStops = 10
    private static let maximumNameLength = 80

    /// A name that survives as one query value: everything but unreserved characters is escaped, so a
    /// `&`, `=`, `+` or `,` in a place name cannot be mistaken for structure.
    private static let nameCharacters = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    struct Route: Equatable {
        var waypoints: [RouteWaypoint]
        var options: RouteOptions
    }

    /// `nil` when there is no route to share: fewer than two filled stops.
    static func url(for waypoints: [RouteWaypoint], options: RouteOptions) -> URL? {
        guard waypoints.count >= 2, waypoints.count <= maximumStops else { return nil }
        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.path = path
        var items = waypoints.map { URLQueryItem(name: "w", value: encode($0)) }
        var letters = ""
        if options.avoidTolls { letters += "t" }
        if options.avoidMotorways { letters += "m" }
        if !letters.isEmpty { items.append(URLQueryItem(name: "o", value: letters)) }
        components.percentEncodedQueryItems = items
        return components.url
    }

    /// The route a link describes, or `nil` for anything that is not one of ours or is malformed.
    static func parse(_ url: URL) -> Route? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme == "https", components.host == host, components.path == path,
              let items = components.percentEncodedQueryItems else { return nil }
        let waypoints = items.filter { $0.name == "w" }.compactMap { $0.value.flatMap(decode) }
        let stops = items.filter { $0.name == "w" }.count
        // One bad stop invalidates the route: dropping it would silently plan a different trip.
        guard waypoints.count == stops, (2...maximumStops).contains(stops) else { return nil }
        let letters = items.first { $0.name == "o" }?.value ?? ""
        return Route(waypoints: waypoints, options: RouteOptions(avoidTolls: letters.contains("t"), avoidMotorways: letters.contains("m")))
    }

    private static func encode(_ waypoint: RouteWaypoint) -> String {
        if waypoint.kind == .currentLocation { return "here" }
        let name = waypoint.name.addingPercentEncoding(withAllowedCharacters: nameCharacters) ?? ""
        return [format(waypoint.latitude), format(waypoint.longitude), String(waypoint.dwellMinutes),
                waypoint.stationID?.uuidString ?? "", name].joined(separator: ",")
    }

    private static func decode(_ value: String) -> RouteWaypoint? {
        if value == "here" { return RouteWaypoint(kind: .currentLocation, name: "", latitude: 0, longitude: 0) }
        let parts = value.split(separator: ",", maxSplits: 4, omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 5, let latitude = Double(parts[0]), let longitude = Double(parts[1]),
              abs(latitude) <= 90, abs(longitude) <= 180, let dwell = Int(parts[2]),
              (0...RouteWaypoint.maximumDwellMinutes).contains(dwell) else { return nil }
        let stationID = parts[3].isEmpty ? nil : UUID(uuidString: parts[3])
        guard parts[3].isEmpty || stationID != nil else { return nil }
        let name = String((parts[4].removingPercentEncoding ?? "").prefix(maximumNameLength))
        return RouteWaypoint(kind: stationID == nil ? .place : .station, name: name, latitude: latitude,
                             longitude: longitude, stationID: stationID, dwellMinutes: dwell)
    }

    /// Five decimals are about a metre; the rest only makes the link longer.
    private static func format(_ value: Double) -> String { String(format: "%.5f", value) }
}
