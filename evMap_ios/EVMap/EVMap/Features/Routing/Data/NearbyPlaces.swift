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
    func places(near center: CLLocationCoordinate2D, radiusMeters: Double,
                categories: [BreakSuggestion.Category]) async throws -> [BreakSuggestion] {
        let request = MKLocalPointsOfInterestRequest(center: center, radius: radiusMeters)
        request.pointOfInterestFilter = MKPointOfInterestFilter(including: categories.map(Self.mapKitCategory))
        let response = try await MKLocalSearch(request: request).start()
        return response.mapItems.compactMap { item in
            guard let name = item.name, let category = item.pointOfInterestCategory.flatMap(Self.category) else { return nil }
            let location = item.location.coordinate
            return BreakSuggestion(id: "\(category.rawValue)|\(name)|\(String(format: "%.4f,%.4f", location.latitude, location.longitude))",
                                   name: name, category: category, latitude: location.latitude,
                                   longitude: location.longitude, distanceAlongRouteKm: 0)
        }
    }

    private static func mapKitCategory(_ category: BreakSuggestion.Category) -> MKPointOfInterestCategory {
        switch category {
        case .restaurant: .restaurant
        case .cafe: .cafe
        case .bakery: .bakery
        case .restroom: .restroom
        case .hotel: .hotel
        }
    }

    private static func category(_ category: MKPointOfInterestCategory) -> BreakSuggestion.Category? {
        BreakSuggestion.Category.allCases.first { mapKitCategory($0) == category }
    }
}
