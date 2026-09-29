import SwiftUI

struct CommentListSection: View {
    let comments: [StationComment]
    /// Whether other people's comments can be reported and their authors blocked: needs a sign-in.
    let canModerate: Bool
    let edit: (StationComment) -> Void
    let delete: (StationComment) -> Void
    let moderate: (StationComment) -> Void

    var body: some View {
        Section("comments.title") {
            if comments.isEmpty { Text("comments.empty").foregroundStyle(.secondary) }
            ForEach(comments) { comment in
                CommentRow(comment: comment, canModerate: canModerate, edit: { edit(comment) }, delete: { delete(comment) }, moderate: { moderate(comment) })
            }
        }
    }
}

private struct CommentRow: View {
    let comment: StationComment
    let canModerate: Bool
    let edit: () -> Void
    let delete: () -> Void
    let moderate: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(comment.body)
            if let experience = comment.experience, !experience.isEmpty { Text(experience).font(.subheadline).foregroundStyle(.secondary) }
            if let price = comment.paidPriceCents { Text(Double(price) / 100, format: .currency(code: "EUR")) }
        }
        .contextMenu {
            if comment.ownedByCurrentUser {
                Button("action.edit", action: edit)
                Button("action.delete", role: .destructive, action: delete)
            } else if canModerate {
                Button("comments.moderate", systemImage: "flag", action: moderate)
            }
        }
    }
}
