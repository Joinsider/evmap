import SwiftUI

struct CommentListSection: View {
    let comments: [StationComment]
    let edit: (StationComment) -> Void
    let delete: (StationComment) -> Void

    var body: some View {
        Section("comments.title") {
            if comments.isEmpty { Text("comments.empty").foregroundStyle(.secondary) }
            ForEach(comments) { comment in
                CommentRow(comment: comment, edit: { edit(comment) }, delete: { delete(comment) })
            }
        }
    }
}

private struct CommentRow: View {
    let comment: StationComment
    let edit: () -> Void
    let delete: () -> Void

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
            }
        }
    }
}
