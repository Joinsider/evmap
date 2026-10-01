import Foundation

/// What the routing feature keeps on the device (ADR 0017): the open plan for offline reading, the
/// named routes and the named places. All of it says where somebody goes, so none of it leaves the
/// device or reaches the log.
protocol RoutingStoring {
    func loadPlan() -> StoredRoutePlan?
    /// `nil` clears it.
    func savePlan(_ plan: StoredRoutePlan?)
    func loadRoutes() -> [SavedRoute]
    func saveRoutes(_ routes: [SavedRoute])
    func loadPlaces() -> [SavedPlace]
    func savePlaces(_ places: [SavedPlace])
}

/// JSON files in Application Support. Not `UserDefaults`: a route of a few hundred kilometres is some
/// hundreds of kilobytes, which the defaults database is not meant for and loads at every launch.
final class FileRoutingStore: RoutingStoring {
    private let directory: URL
    private let fileManager: FileManager

    init(directory: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        self.directory = directory ?? (try? fileManager.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                            appropriateFor: nil, create: true))?.appending(component: "Routing", directoryHint: .isDirectory)
            ?? fileManager.temporaryDirectory.appending(component: "Routing", directoryHint: .isDirectory)
    }

    func loadPlan() -> StoredRoutePlan? { read(StoredRoutePlan.self, named: "current-route") }

    func savePlan(_ plan: StoredRoutePlan?) {
        guard let plan else { return remove("current-route") }
        write(plan, named: "current-route")
    }

    func loadRoutes() -> [SavedRoute] { read([SavedRoute].self, named: "saved-routes") ?? [] }
    func saveRoutes(_ routes: [SavedRoute]) { write(routes, named: "saved-routes") }
    func loadPlaces() -> [SavedPlace] { read([SavedPlace].self, named: "saved-places") ?? [] }
    func savePlaces(_ places: [SavedPlace]) { write(places, named: "saved-places") }

    private func url(_ name: String) -> URL { directory.appending(component: "\(name).json") }

    private func read<T: Decodable>(_ type: T.Type, named name: String) -> T? {
        guard let data = try? Data(contentsOf: url(name)) else { return nil }
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            // A format change between versions must cost the plan, not the launch.
            AppLogger.routing.warning("Discarding unreadable \(name) — \(AppLogger.describe(error))")
            remove(name)
            return nil
        }
    }

    private func write<T: Encodable>(_ value: T, named name: String) {
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            try JSONEncoder().encode(value).write(to: url(name), options: .atomic)
        } catch {
            AppLogger.routing.error("Could not persist \(name)", error: error)
        }
    }

    private func remove(_ name: String) {
        try? fileManager.removeItem(at: url(name))
    }
}
