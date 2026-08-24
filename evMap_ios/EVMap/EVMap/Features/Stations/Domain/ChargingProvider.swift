import Foundation

/// A charging network as the master data knows it.
///
/// There is no operator table behind this and no operator id: `name` is what the source adapters
/// wrote onto the stations, which makes the string itself the identity — and two spellings of one
/// company two providers. That is why preferences are keyed by name. See ADR 0014.
struct ChargingProvider: Codable, Identifiable, Hashable {
    let name: String
    /// How many stations carry this name. The list's ranking key, and the only hint the user gets
    /// about how much hiding this network will change the map.
    let stationCount: Int

    var id: String { name }
}
