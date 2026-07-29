import CoreLocation
import Foundation

struct Station: Codable, Identifiable, Hashable {
    let id: UUID
    let displayName: String
    let street: String?
    let city: String?
    let postalCode: String?
    let countryCode: String?
    let operatorName: String?
    let latitude: Double
    let longitude: Double
    let availabilityStatus: String?
    /// Strongest connector at the station, `nil` when no source reported a rating.
    /// Present in the list payload because it drives the map pin's colour.
    let maxPowerKw: Double?

    var coordinate: CLLocationCoordinate2D { .init(latitude: latitude, longitude: longitude) }
    var address: String {
        [street, [postalCode, city].compactMap { $0 }.joined(separator: " ")]
            .compactMap { value in value?.isEmpty == false ? value : nil }
            .joined(separator: ", ")
    }
}
