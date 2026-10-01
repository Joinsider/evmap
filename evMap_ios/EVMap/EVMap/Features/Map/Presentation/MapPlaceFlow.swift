import Combine
import CoreLocation
import Foundation

/// The sheets of the map that lead into the route planner (ADR 0017): the info card of a place, the
/// station screen, and the planner itself, and what has to happen between them.
///
/// SwiftUI cannot swap one sheet for another in a single step — the second only opens once the first has
/// gone — so a choice made on a card is parked here and carried out in the card's `onDismiss`. Kept out of
/// the view so that order can be tested without presenting anything.
@MainActor
final class MapPlaceFlow: ObservableObject {
    /// The place on the info card: a search hit or a tapped town or place of interest.
    @Published var place: PlaceSelection?
    /// The station screen.
    @Published var station: Station?

    private let planner: RoutePlannerViewModel
    private var pendingRoute: (intent: RouteIntent, waypoint: RouteWaypoint)?
    private var pendingStation: Station?

    init(planner: RoutePlannerViewModel) {
        self.planner = planner
    }

    /// A pin was tapped: a station opens its screen; a cluster is handed back for the map to zoom into.
    func tap(_ annotation: StationAnnotation) -> StationAnnotation? {
        guard let tapped = annotation.station else { return annotation }
        station = tapped
        return nil
    }

    /// Opens the card for a place MapKit already described.
    func show(_ searched: SearchedPlace) {
        place = PlaceSelection(place: searched)
    }

    /// Opens the card for a tapped map feature at once and fills in its address when `address` answers —
    /// unless another place has taken the card by then.
    func show(title: String?, coordinate: CLLocationCoordinate2D, address: @escaping () async -> String?) {
        let selected = PlaceSelection(title: title ?? String(localized: "route.place.unnamed"), coordinate: coordinate)
        place = selected
        Task { [weak self] in
            guard let found = await address(), self?.place?.id == selected.id else { return }
            self?.place?.subtitle = found
        }
    }

    /// A route action was chosen on the card or in the station screen: close both, then start it once the
    /// sheet is gone (`sheetDismissed`).
    func choose(_ intent: RouteIntent, for waypoint: RouteWaypoint) {
        pendingRoute = (intent, waypoint)
        place = nil
        station = nil
    }

    /// The card or the station screen has gone.
    func sheetDismissed() {
        guard let pending = pendingRoute else { return }
        pendingRoute = nil
        planner.apply(pending.intent, to: pending.waypoint)
    }

    /// A station was picked in the planner: close it, and open the station once it has gone.
    func showStationAfterPlanner(_ picked: Station) {
        pendingStation = picked
        planner.isPlannerPresented = false
    }

    /// The planner has gone: the station that was picked in it, now opened, for the map to center on.
    func plannerDismissed() -> Station? {
        guard let picked = pendingStation else { return nil }
        pendingStation = nil
        station = picked
        return picked
    }
}
