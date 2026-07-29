import SwiftUI

/// What the search field drops down: recents while nothing is typed, live completions afterwards.
///
/// An empty field showing the last places somebody looked up is the whole reason the history is
/// kept — it turns the common case (going back somewhere) into one tap and no typing.
struct AddressSearchSuggestions: View {
    @ObservedObject var viewModel: AddressSearchViewModel

    var body: some View {
        if viewModel.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            recentRows
        } else {
            ForEach(viewModel.suggestions) { suggestion in
                Button { viewModel.select(suggestion) } label: {
                    AddressSuggestionRow(icon: "mappin.circle", title: suggestion.title, subtitle: suggestion.subtitle)
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private var recentRows: some View {
        if !viewModel.recents.isEmpty {
            Section {
                ForEach(viewModel.recents) { place in
                    Button { viewModel.show(place) } label: {
                        AddressSuggestionRow(icon: "clock.arrow.circlepath", title: place.title, subtitle: place.subtitle)
                    }
                    .buttonStyle(.plain)
                    .swipeActions {
                        Button("action.delete", systemImage: "trash", role: .destructive) { viewModel.forget(place) }
                    }
                }
            } header: {
                HStack {
                    Text("search.recent")
                    Spacer()
                    Button("search.recent.clear") { viewModel.clearRecents() }
                        .font(.caption)
                        .buttonStyle(.plain)
                        .foregroundStyle(.tint)
                }
            }
        }
    }
}

private struct AddressSuggestionRow: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } icon: {
            Image(systemName: icon).foregroundStyle(.secondary)
        }
        // The two lines are one destination, not two things to swipe past.
        .accessibilityElement(children: .combine)
    }
}
