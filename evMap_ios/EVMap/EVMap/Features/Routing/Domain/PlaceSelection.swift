import CoreLocation
import Foundation

/// A place the person picked on the map or in the search, shown on the info card before any route exists.
struct PlaceSelection: Identifiable, Hashable {
    let id = UUID()
    var title: String
    var subtitle: String
    var latitude: Double
    var longitude: Double

    var coordinate: CLLocationCoordinate2D { .init(latitude: latitude, longitude: longitude) }

    init(title: String, subtitle: String = "", coordinate: CLLocationCoordinate2D) {
        self.title = title
        self.subtitle = subtitle
        self.latitude = coordinate.latitude
        self.longitude = coordinate.longitude
    }

    init(place: SearchedPlace) {
        self.init(title: place.title, subtitle: place.subtitle, coordinate: place.coordinate)
    }

    var waypoint: RouteWaypoint { RouteWaypoint(name: title, subtitle: subtitle, latitude: latitude, longitude: longitude) }
}

/// Somewhere to take a break near the route that is not a charger: food, a toilet, a bed (ADR 0017).
/// MapKit's own points of interest; the station data has no amenity fields.
struct BreakSuggestion: Identifiable, Hashable {
    enum Category: String, CaseIterable {
        case restaurant, cafe, bakery, restroom, hotel

        var symbol: String {
            switch self {
            case .restaurant: "fork.knife"
            case .cafe: "cup.and.saucer"
            case .bakery: "birthday.cake"
            case .restroom: "figure.dress.line.vertical.figure"
            case .hotel: "bed.double"
            }
        }

        var title: String {
            switch self {
            case .restaurant: String(localized: "route.break.restaurant")
            case .cafe: String(localized: "route.break.cafe")
            case .bakery: String(localized: "route.break.bakery")
            case .restroom: String(localized: "route.break.restroom")
            case .hotel: String(localized: "route.break.hotel")
            }
        }
    }

    let id: String
    let name: String
    let category: Category
    let latitude: Double
    let longitude: Double
    /// Kilometres along the route of the point this suggestion was searched around.
    var distanceAlongRouteKm: Double

    var coordinate: CLLocationCoordinate2D { .init(latitude: latitude, longitude: longitude) }
    var waypoint: RouteWaypoint { RouteWaypoint(name: name, subtitle: category.title, latitude: latitude, longitude: longitude) }
}
