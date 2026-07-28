import Foundation

struct StationComment: Codable, Identifiable, Hashable {
    let id: UUID
    let body: String
    let paidPriceCents: Int?
    let experience: String?
    let createdAt: Date
    let updatedAt: Date
    let ownedByCurrentUser: Bool
}

struct CommentPayload: Codable {
    let body: String
    let paidPriceCents: Int?
    let experience: String?
}
