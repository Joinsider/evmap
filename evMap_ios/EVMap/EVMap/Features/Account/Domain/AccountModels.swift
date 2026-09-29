import Foundation

/// Why a comment is reported. The raw values are the backend's tokens (`api.moderation.ReportReason`);
/// adding one on either side without the other makes the report a 400.
enum ReportReason: String, CaseIterable, Codable, Identifiable {
    case spam, offensive, wrong, other

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .spam: String(localized: "report.reason.spam")
        case .offensive: String(localized: "report.reason.offensive")
        case .wrong: String(localized: "report.reason.wrong")
        case .other: String(localized: "report.reason.other")
        }
    }
}

/// What the signed-in person has contributed (`GET /api/v1/me/contributions`, ADR 0020).
struct Contributions: Decodable, Equatable {
    let comments: [CommentContribution]
    let reports: [ReportContribution]
}

struct CommentContribution: Decodable, Identifiable, Equatable {
    let id: UUID
    let stationName: String?
    let body: String
    let createdAt: Date
}

struct ReportContribution: Decodable, Identifiable, Equatable {
    let id: UUID
    let reason: String
    let status: String
    let stationName: String?
    let createdAt: Date

    /// The label for an open or closed report; a reason this app does not know yet is shown as "other".
    var reasonName: String { (ReportReason(rawValue: reason) ?? .other).displayName }
    var isOpen: Bool { status == "open" }
}

/// A block, listed without saying whom it concerns: `id` is only good for lifting it again.
struct BlockedAuthor: Decodable, Identifiable, Equatable {
    let id: UUID
    let createdAt: Date
}

/// Where the operator published the privacy policy (`GET /api/v1/legal`). Absent until configured.
struct LegalInfo: Decodable, Equatable {
    let privacyPolicyUrl: URL?
}
