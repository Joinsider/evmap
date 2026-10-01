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
    var stationChargePoints: Result<StationChargePoints, Error> = .failure(Failure(message: "no prices"))
    var providerList: Result<[ChargingProvider], Error> = .success([])
    var commentList: Result<[StationComment], Error> = .success([])
    var commentWrite: Result<StationComment, Error> = .failure(Failure(message: "no comment"))
    var deleteResult: Result<Void, Error> = .success(())
    var signIn: Result<String, Error> = .success("token")
    var signInProviderList: Result<[SignInProvider], Error> = .success([])
    private(set) var codeSignIns: [(provider: String, code: String, codeVerifier: String?)] = []
    private(set) var appleAuthorizationCodes: [String?] = []

    var reportResult: Result<Void, Error> = .success(())
    var blockResult: Result<Void, Error> = .success(())
    var unblockResult: Result<Void, Error> = .success(())
    var blockList: Result<[BlockedAuthor], Error> = .success([])
    var contributionList: Result<Contributions, Error> = .success(Contributions(comments: [], reports: []))
    var exportResult: Result<Data, Error> = .success(Data("{}".utf8))
    var accountDeletion: Result<Void, Error> = .success(())
    var legalInfo: Result<LegalInfo, Error> = .success(LegalInfo(privacyPolicyUrl: nil))
    var favoriteWrite: Result<Void, Error> = .success(())
    /// What the account holds; a merge answers with this plus what the device sent, like the backend.
    var accountFavorites: [Station] = []
    var mergeFailure: Error?
    var stationReportResult: Result<Void, Error> = .success(())
    private(set) var addedFavorites: [UUID] = []
    private(set) var removedFavorites: [UUID] = []
    private(set) var mergedFavoriteIDs: [[UUID]] = []
    private(set) var stationReports: [(id: UUID, reason: StationReportReason, note: String?)] = []
    private(set) var reports: [(id: UUID, reason: ReportReason)] = []
    private(set) var blockedComments: [UUID] = []
    private(set) var liftedBlocks: [UUID] = []
    private(set) var accountDeleted = false

    var alongRoute: Result<[RouteStation], Error> = .success([])
    private(set) var alongRouteQueries: [(route: [RouteCoordinate], corridorKm: Double, limit: Int, filter: StationFilter)] = []

    private(set) var nearbyFilters: [StationFilter] = []
    private(set) var deletedComments: [UUID] = []

    func nearby(latitude _: Double, longitude _: Double, radiusKm _: Double, limit _: Int, filter: StationFilter) async throws -> [Station] {
        nearbyFilters.append(filter)
        return try stations.get()
    }

    func stationsAlongRoute(route: [RouteCoordinate], corridorKm: Double, limit: Int, filter: StationFilter) async throws -> [RouteStation] {
        alongRouteQueries.append((route, corridorKm, limit, filter))
        return try alongRoute.get()
    }

    func detail(id _: UUID) async throws -> StationDetail { try stationDetail.get() }
    func liveAvailability(stationID _: UUID) async throws -> StationLiveAvailability { try liveStation.get() }
    func chargePoints(stationID _: UUID) async throws -> StationChargePoints { try stationChargePoints.get() }
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
    func signInWithApple(identityToken _: String, authorizationCode: String?) async throws -> String {
        appleAuthorizationCodes.append(authorizationCode)
        return try signIn.get()
    }
    func signInProviders() async throws -> [SignInProvider] { try signInProviderList.get() }
    func signIn(provider: String, code: String, codeVerifier: String?) async throws -> String {
        codeSignIns.append((provider, code, codeVerifier))
        return try signIn.get()
    }

    func reportComment(id: UUID, reason: ReportReason, accessToken _: String) async throws {
        try reportResult.get()
        reports.append((id, reason))
    }

    func blockAuthor(ofComment id: UUID, accessToken _: String) async throws {
        try blockResult.get()
        blockedComments.append(id)
    }

    func blockedAuthors(accessToken _: String) async throws -> [BlockedAuthor] { try blockList.get() }

    func unblock(id: UUID, accessToken _: String) async throws {
        try unblockResult.get()
        liftedBlocks.append(id)
    }

    func contributions(accessToken _: String) async throws -> Contributions { try contributionList.get() }
    func exportData(accessToken _: String) async throws -> Data { try exportResult.get() }

    func deleteAccount(accessToken _: String) async throws {
        try accountDeletion.get()
        accountDeleted = true
    }

    func legal() async throws -> LegalInfo { try legalInfo.get() }

    func addFavorite(stationID: UUID, accessToken _: String) async throws {
        try favoriteWrite.get()
        addedFavorites.append(stationID)
    }

    func removeFavorite(stationID: UUID, accessToken _: String) async throws {
        try favoriteWrite.get()
        removedFavorites.append(stationID)
    }

    func mergeFavorites(stationIDs: [UUID], accessToken _: String) async throws -> [Station] {
        if let mergeFailure { throw mergeFailure }
        mergedFavoriteIDs.append(stationIDs)
        return accountFavorites
    }

    func reportStation(id: UUID, reason: StationReportReason, note: String?, accessToken _: String) async throws {
        try stationReportResult.get()
        stationReports.append((id, reason, note))
    }
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
