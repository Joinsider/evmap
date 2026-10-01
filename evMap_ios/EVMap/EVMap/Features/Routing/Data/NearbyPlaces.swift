import CoreLocation
import Foundation
import MapKit

/// Points of interest around a coordinate, behind a protocol like the other MapKit seams.
@MainActor
protocol NearbyPlacesProviding: AnyObject {
    func places(near center: CLLocationCoordinate2D, radiusMeters: Double,
                categories: [BreakSuggestion.Category]) async throws -> [BreakSuggestion]
}

/// `MKLocalPointsOfInterestRequest`. Asked only when the person asks for break suggestions or opens a
/// station's surroundings, never in the background: MapKit throttles and a route is long.
@MainActor
final class MapKitNearbyPlacesProvider: NearbyPlacesProviding {
    typealias Search = (MKLocalPointsOfInterestRequest) async throws -> [MKMapItem]

    private let search: Search

    /// `nil` means MapKit's own search; a test hands in its own answer.
    init(search: Search? = nil) {
        self.search = search ?? { try await MKLocalSearch(request: $0).start().mapItems }
    }

    func places(near center: CLLocationCoordinate2D, radiusMeters: Double,
                categories: [BreakSuggestion.Category]) async throws -> [BreakSuggestion] {
        let request = MKLocalPointsOfInterestRequest(center: center, radius: radiusMeters)
        request.pointOfInterestFilter = MKPointOfInterestFilter(including: categories.map(Self.mapKitCategory))
        return try await search(request).compactMap { item in
            Self.suggestion(name: item.name, category: item.pointOfInterestCategory, coordinate: item.location.coordinate)
        }
    }

    /// A place of interest as a break suggestion; `nil` for one without a name or outside the categories asked for.
    static func suggestion(name: String?, category: MKPointOfInterestCategory?, coordinate: CLLocationCoordinate2D) -> BreakSuggestion? {
        guard let name, let category = category.flatMap(Self.category) else { return nil }
        return BreakSuggestion(id: "\(category.rawValue)|\(name)|\(String(format: "%.4f,%.4f", coordinate.latitude, coordinate.longitude))",
                               name: name, category: category, latitude: coordinate.latitude,
                               longitude: coordinate.longitude, distanceAlongRouteKm: 0)
    }

    static func mapKitCategory(_ category: BreakSuggestion.Category) -> MKPointOfInterestCategory {
        switch category {
        case .restaurant: .restaurant
        case .cafe: .cafe
        case .bakery: .bakery
        case .restroom: .restroom
        case .hotel: .hotel
        }
    }

    static func category(_ category: MKPointOfInterestCategory) -> BreakSuggestion.Category? {
        BreakSuggestion.Category.allCases.first { mapKitCategory($0) == category }
    }
}
