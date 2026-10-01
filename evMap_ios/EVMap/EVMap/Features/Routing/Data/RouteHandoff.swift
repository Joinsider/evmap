import CoreLocation
import Foundation
import MapKit

/// Hands a route over to a navigation app (ADR 0017). EVMap plans; it does not guide.
///
/// Apple Maps takes reliably only a start and a destination, so it gets either that or one leg at a
/// time. Google Maps takes the whole route through the `waypoints` parameter of its directions URL, as
/// far as its limit allows, and longer routes fall back to leg by leg.
enum RouteHandoff {
    /// Google Maps' directions URL accepts nine waypoints between origin and destination.
    static let googleMapsWaypointLimit = 9

    /// The whole route as one Google Maps link, or `nil` when it has more stops than that allows. A start
    /// that is the device's position is left out, which is how Google Maps says "from here".
    static func googleMapsURL(for stops: [RouteWaypoint], options: RouteOptions) -> URL? {
        guard stops.count >= 2, stops.count - 2 <= googleMapsWaypointLimit, let destination = stops.last else { return nil }
        var items = [URLQueryItem(name: "api", value: "1")]
        if let origin = stops.first, origin.kind != .currentLocation {
            items.append(URLQueryItem(name: "origin", value: pair(origin)))
        }
        items.append(URLQueryItem(name: "destination", value: pair(destination)))
        let via = stops.dropFirst().dropLast()
        if !via.isEmpty { items.append(URLQueryItem(name: "waypoints", value: via.map(pair).joined(separator: "|"))) }
        items.append(URLQueryItem(name: "travelmode", value: "driving"))
        let avoided = [options.avoidTolls ? "tolls" : nil, options.avoidMotorways ? "highways" : nil].compactMap { $0 }
        if !avoided.isEmpty { items.append(URLQueryItem(name: "avoid", value: avoided.joined(separator: "|"))) }
        var components = URLComponents(string: "https://www.google.com/maps/dir/")!
        components.queryItems = items
        return components.url
    }

    /// Leg `index` (from stop `index` to stop `index + 1`) as the two map items Apple Maps is opened with.
    @MainActor
    static func appleMapsItems(for stops: [RouteWaypoint], leg index: Int) -> [MKMapItem]? {
        guard stops.indices.contains(index), stops.indices.contains(index + 1) else { return nil }
        return [mapItem(stops[index]), mapItem(stops[index + 1])]
    }

    @MainActor
    static func openInAppleMaps(_ items: [MKMapItem]) {
        // Apple Maps takes no avoid flags from a handoff; the route it computes is its own.
        MKMapItem.openMaps(with: items, launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDriving])
    }

    @MainActor
    private static func mapItem(_ waypoint: RouteWaypoint) -> MKMapItem {
        if waypoint.kind == .currentLocation { return .forCurrentLocation() }
        let item = MKMapItem(location: CLLocation(latitude: waypoint.latitude, longitude: waypoint.longitude), address: nil)
        item.name = waypoint.name
        return item
    }

    private static func pair(_ waypoint: RouteWaypoint) -> String {
        String(format: "%.6f,%.6f", waypoint.latitude, waypoint.longitude)
    }
}
