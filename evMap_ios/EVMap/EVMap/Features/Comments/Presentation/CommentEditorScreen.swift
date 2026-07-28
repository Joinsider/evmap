import SwiftUI

struct CommentEditorScreen: View {
    let comment: StationComment?
    let save: (CommentPayload) async -> Void
    @State private var bodyText: String
    @State private var experience: String
    @State private var price: String
    @Environment(\.dismiss) private var dismiss

    init(comment: StationComment? = nil, save: @escaping (CommentPayload) async -> Void) {
        self.comment = comment
        self.save = save
        _bodyText = State(initialValue: comment?.body ?? "")
        _experience = State(initialValue: comment?.experience ?? "")
        _price = State(initialValue: comment?.paidPriceCents.map(String.init) ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("comments.message", text: $bodyText, axis: .vertical).lineLimit(3...8)
                TextField("comments.experience", text: $experience)
                TextField("comments.priceCents", text: $price).keyboardType(.numberPad)
            }
            .navigationTitle(comment == nil ? "comments.add" : "action.edit")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("action.cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("action.save") {
                        Task {
                            await save(CommentPayload(body: bodyText, paidPriceCents: Int(price), experience: experience.isEmpty ? nil : experience))
                        }
                    }.disabled(bodyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
