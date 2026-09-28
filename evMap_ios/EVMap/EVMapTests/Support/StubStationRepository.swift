import Foundation

@testable import EVMap

/// A repository whose every answer is set by the test, recording what it was asked.
///
/// Main-actor isolated rather than an `actor`: the app target defaults to `MainActor` isolation,
/// which makes `ChargingStationRepository` a main-actor protocol that an actor cannot conform to.
@MainActor
final class StubStationRepository: ChargingStationRepository {
    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    var stations: Result<[Station], Error> = .success([])
    var stationDetail: Result<StationDetail, Error> = .failure(Failure(message: "no detail"))
    var liveStation: Result<StationLiveAvailability, Error> = .failure(Failure(message: "no live data"))
    var liveViewport: Result<[StationLiveAvailability], Error> = .success([])
    var providerList: Result<[ChargingProvider], Error> = .success([])
    var commentList: Result<[StationComment], Error> = .success([])
    var commentWrite: Result<StationComment, Error> = .failure(Failure(message: "no comment"))
    var deleteResult: Result<Void, Error> = .success(())
    var signIn: Result<String, Error> = .success("token")

    private(set) var nearbyFilters: [StationFilter] = []
    private(set) var deletedComments: [UUID] = []

    func nearby(latitude _: Double, longitude _: Double, radiusKm _: Double, limit _: Int, filter: StationFilter) async throws -> [Station] {
        nearbyFilters.append(filter)
        return try stations.get()
    }

    func detail(id _: UUID) async throws -> StationDetail { try stationDetail.get() }
    func liveAvailability(stationID _: UUID) async throws -> StationLiveAvailability { try liveStation.get() }
    func liveAvailability(latMin _: Double, lonMin _: Double, latMax _: Double, lonMax _: Double) async throws -> [StationLiveAvailability] {
        try liveViewport.get()
    }
    func providers(matching _: String, limit _: Int) async throws -> [ChargingProvider] { try providerList.get() }
    func comments(stationID _: UUID, accessToken _: String?) async throws -> [StationComment] { try commentList.get() }
    func createComment(stationID _: UUID, payload _: CommentPayload, accessToken _: String) async throws -> StationComment {
        try commentWrite.get()
    }
    func updateComment(id _: UUID, payload _: CommentPayload, accessToken _: String) async throws -> StationComment {
        try commentWrite.get()
    }
    func deleteComment(id: UUID, accessToken _: String) async throws {
        try deleteResult.get()
        deletedComments.append(id)
    }
    func signInWithApple(identityToken _: String) async throws -> String { try signIn.get() }
}

/// Small factories for domain values, so tests state only what they are about.
enum Fixtures {
    static func station(id: UUID = UUID(), name: String = "EnBW Mitte", latitude: Double = 48.7758,
                        longitude: Double = 9.1829, maxPowerKw: Double? = 150) -> Station {
        Station(id: id, displayName: name, street: "Hauptstraße 1", city: "Stuttgart", postalCode: "70173",
                countryCode: "DE", operatorName: "EnBW", latitude: latitude, longitude: longitude,
                availabilityStatus: "OPERATIONAL", maxPowerKw: maxPowerKw)
    }

    static func detail(for station: Station) -> StationDetail {
        StationDetail(station: station,
                      connectors: [Connector(connectorType: "CCS", powerKw: 150, quantity: 2),
                                   Connector(connectorType: "TYPE_2", powerKw: 22, quantity: 4)],
                      sources: ["BNetzA", "OCM"])
    }

    static func comment(id: UUID = UUID(), body: String = "Works fine", ownedByCurrentUser: Bool = true) -> StationComment {
        StationComment(id: id, body: body, paidPriceCents: 4_900, experience: "POSITIVE",
                       createdAt: Date(timeIntervalSince1970: 1_790_000_000),
                       updatedAt: Date(timeIntervalSince1970: 1_790_000_000),
                       ownedByCurrentUser: ownedByCurrentUser)
    }

    static func live(stationID: UUID, available: Int = 1, occupied: Int = 1, outOfOrder: Int = 0,
                     unknown: Int = 1, sources: [LiveDataSource] = [mobiData]) -> StationLiveAvailability {
        let points = [
            ChargePointLiveStatus(id: UUID(), evseId: "DE*EBW*E912316*1", status: .available, observedAt: Date(),
                                  source: sources.first?.name),
            ChargePointLiveStatus(id: UUID(), evseId: "DE*EBW*E912316*2", status: .occupied, observedAt: Date(),
                                  source: sources.last?.name),
            ChargePointLiveStatus(id: UUID(), evseId: nil, status: .unknown, observedAt: nil)
        ]
        return StationLiveAvailability(stationID: stationID, status: available > 0 ? .available : .occupied,
                                       counts: .init(available: available, occupied: occupied,
                                                     outOfOrder: outOfOrder, unknown: unknown),
                                       observedAt: Date(), chargePoints: points, sources: sources)
    }

    static let mobiData = LiveDataSource(name: "MobiData BW", licence: "Datenlizenz Deutschland – Namensnennung – 2.0",
                                         url: URL(string: "https://www.mobidata-bw.de"))
    static let transport = LiveDataSource(name: "transport.data.gouv.fr", licence: nil, url: nil)
}
