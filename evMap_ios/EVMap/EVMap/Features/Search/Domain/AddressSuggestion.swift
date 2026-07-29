import CoreLocation
import Foundation

/// One row of the address autocomplete: a place the user could mean, not yet located.
///
/// Deliberately free of MapKit types. The completion object needed to resolve a suggestion
/// precisely is a detail of the search provider, which keeps its own handle to it — a suggestion
/// restored from disk has no such handle and still has to be resolvable.
struct AddressSuggestion: Identifiable, Hashable {
    /// The place itself, e.g. `Alexanderplatz`.
    let title: String
    /// Where it is, e.g. `Berlin, Deutschland`. Empty for a bare query completion.
    let subtitle: String

    var id: String { subtitle.isEmpty ? title : "\(title)|\(subtitle)" }

    /// The suggestion as a single line of text, for the fallback path that has to search for it
    /// by name rather than by completion handle.
    var searchText: String { subtitle.isEmpty ? title : "\(title), \(subtitle)" }
}

/// A search that resolved: the place the map is pointing at.
///
/// Carries its coordinate so a remembered search replays without asking MapKit again — the point
/// of the recents list is to get back somewhere instantly, including with no network.
struct SearchedPlace: Identifiable, Hashable, Codable {
    let title: String
    let subtitle: String
    let latitude: Double
    let longitude: Double

    var id: String { subtitle.isEmpty ? title : "\(title)|\(subtitle)" }
    var coordinate: CLLocationCoordinate2D { .init(latitude: latitude, longitude: longitude) }
    /// What the search field and the map pin show once the search is done.
    var displayName: String { title }

    init(title: String, subtitle: String, coordinate: CLLocationCoordinate2D) {
        self.title = title
        self.subtitle = subtitle
        self.latitude = coordinate.latitude
        self.longitude = coordinate.longitude
    }

    var suggestion: AddressSuggestion { AddressSuggestion(title: title, subtitle: subtitle) }
}
