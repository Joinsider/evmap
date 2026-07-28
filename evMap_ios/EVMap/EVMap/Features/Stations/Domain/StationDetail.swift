import Foundation

struct StationDetail: Codable {
    let station: Station
    let connectors: [Connector]
    let sources: [String]
}

struct Connector: Codable, Hashable, Identifiable {
    var id: String { "\(connectorType)-\(powerKw ?? 0)-\(quantity)" }
    let connectorType: String
    let powerKw: Double?
    let quantity: Int
}
