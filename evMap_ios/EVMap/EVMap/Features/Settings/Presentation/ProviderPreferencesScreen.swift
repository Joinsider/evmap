import SwiftUI

/// The networks the user has an opinion about — and a search at the bottom to add one.
///
/// The screen is a short, personal list rather than a directory: the ~2000 operator names in the
/// master data are something you *look up*, not something you scroll. So the list only ever holds
/// networks the user picked, and the search field sits in the bottom bar where the thumb is,
/// showing the largest networks until something is typed.
///
/// Above the list sits the switch that decides what every network *not* on it does. With it on the
/// list is a blocklist ("everything except these"), with it off an allowlist ("nothing except
/// these") — same rows, opposite reading, which is why the footer changes with it. See ADR 0014.
///
/// Picking a result adds a row and closes the search. Removing one is a swipe — or nothing at all:
/// a row left at `shown` was never stored (see `AppSettings.setPreference`), so it is gone the next
/// time the screen opens. That is why `listed` is view state and not derived from the settings.
/// Deriving it would make a row disappear under the finger the moment it is switched back on,
/// which is exactly when the user is most likely to want to switch it off again.
struct ProviderPreferencesScreen: View {
    @ObservedObject var model: SettingsViewModel
    let repository: any ChargingStationRepository
    @StateObject private var search: ProviderSearchViewModel
    @State private var query = ""
    @State private var isSearchActive = false
    /// Networks listed on this screen: everything with a stored preference when it opened, plus
    /// what has been added since — including rows currently sitting at the default.
    @State private var listed: [String] = []

    init(model: SettingsViewModel, repository: any ChargingStationRepository) {
        self.model = model
        self.repository = repository
        _search = StateObject(wrappedValue: ProviderSearchViewModel(repository: repository))
    }

    var body: some View {
        List {
            if isSearchActive {
                Section {
                    resultRows
                } header: {
                    // Before anything is typed these are the largest networks, not results.
                    Text(isSearching ? "provider.section.results" : "provider.section.common")
                }
            } else {
                Section {
                    Toggle("provider.unlisted", isOn: showsUnlistedProviders)
                } footer: {
                    Text(showsUnlistedProviders.wrappedValue ? "provider.unlisted.footer.shown" : "provider.unlisted.footer.hidden")
                }
                Section {
                    if listed.isEmpty {
                        // A row rather than a `ContentUnavailableView` overlay: the overlay would
                        // cover the switch above, which is the one control that still does
                        // something while nothing is listed.
                        Text("provider.listed.empty.description").foregroundStyle(.secondary)
                    }
                    ForEach(listed, id: \.self) { provider in
                        ProviderRow(name: provider, preference: binding(for: provider))
                    }
                    .onDelete(perform: remove)
                } header: {
                    Text("provider.section.configured")
                } footer: {
                    if !listed.isEmpty { Text("provider.section.configured.footer") }
                }
            }
        }
        .navigationTitle("settings.providers")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, isPresented: $isSearchActive, prompt: Text("provider.search.prompt"))
        .toolbar {
            // Puts the system search field into the bottom bar instead of under the title: this
            // screen is reached from a settings sheet held in one hand, and adding a network is
            // the only thing on it that starts with typing.
            DefaultToolbarItem(kind: .search, placement: .bottomBar)
        }
        .onChange(of: query) { _, query in search.queryChanged(to: query) }
        .overlay { if search.isLoading && search.providers.isEmpty { ProgressView() } }
        .task { search.load() }
        // Merged, never assigned: a preference stored on an earlier visit has to show up, but a row
        // the user just added and left at the default must not be dropped while the screen is open.
        .onAppear { listed.append(contentsOf: model.settings.configuredProviders.filter { !listed.contains($0) }) }
        .alert("error.title", isPresented: Binding(
            get: { search.errorMessage != nil },
            set: { if !$0 { search.errorMessage = nil } }
        )) {
            Button("action.ok", role: .cancel) { }
        } message: { Text(search.errorMessage ?? "") }
    }

    private var isSearching: Bool {
        !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// The global switch, as the two selectable cases seen as on/off.
    ///
    /// Written through `model.settings` rather than through a dedicated method because it is one
    /// stored field like `availabilityOnly`, not a per-provider decision — but flipping it does
    /// change what a per-provider decision *means*, which is what the footer explains.
    private var showsUnlistedProviders: Binding<Bool> {
        Binding(
            get: { !model.settings.unlistedProviders.hidesStations },
            set: { model.settings.unlistedProviders = $0 ? .shown : .hidden }
        )
    }

    @ViewBuilder
    private var resultRows: some View {
        if search.providers.isEmpty && !search.isLoading {
            Text("provider.empty").foregroundStyle(.secondary)
        }
        ForEach(search.providers) { provider in
            // Networks already listed stay in the results with a checkmark rather than being
            // filtered out: a search that silently drops what you are looking for reads as "this
            // network does not exist", and the answer to "did I already set this one?" is the
            // reason people search for a name they know.
            ProviderResultRow(provider: provider, isListed: listed.contains(provider.name)) {
                add(provider.name)
            }
        }
    }

    private func add(_ provider: String) {
        if !listed.contains(provider) {
            withAnimation { listed.insert(provider, at: 0) }
            AppLogger.settings.info("Provider added to the preference list (\(self.listed.count) listed)")
        }
        // Closing the search is what makes the new row visible — it is the whole point of the tap.
        query = ""
        isSearchActive = false
    }

    /// Swipe-to-delete means "I have no opinion about this network", so the row is dropped *and*
    /// the stored preference removed. Leaving the preference behind would hide stations from a map
    /// whose settings screen no longer lists the network doing the hiding.
    private func remove(atOffsets offsets: IndexSet) {
        for provider in offsets.map({ listed[$0] }) {
            model.setPreference(.shown, for: provider)
        }
        listed.remove(atOffsets: offsets)
    }

    private func binding(for provider: String) -> Binding<ProviderPreference> {
        Binding(
            get: { model.preference(for: provider) },
            set: { model.setPreference($0, for: provider) }
        )
    }
}

/// One network and what the user decided about it.
///
/// A `Picker` rather than a toggle even though only two choices are selectable today: `preferred`
/// and `avoided` are coming with route planning (ADR 0014), and a segmented control of two grows
/// into one of four without the row being redesigned — where a switch would have to be replaced,
/// taking its meaning ("on" = which of four?) with it.
///
/// The control sits on its own line under the name for *every* row, not only for the long ones.
/// Network names run from "EnBW" to "Autobahn Tank & Rast Anlagen Betreibergesellschaft mbH", so a
/// side-by-side layout wraps for some rows and not others, and the choices end up at a different
/// place in each row — the one thing the eye scans this list for. A fixed line is worth the height.
private struct ProviderRow: View {
    let name: String
    @Binding var preference: ProviderPreference

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(name)
            Picker(selection: $preference) {
                // Icons only: the two states are "visible" and "not visible", which the eye/eye.slash
                // pair says without a word of text — and without the labels the segments stay the
                // same width in every language. The name stays on the VoiceOver label, since an
                // image alone would announce nothing.
                ForEach(ProviderPreference.selectableCases) { preference in
                    Image(systemName: preference.systemImage)
                        .accessibilityLabel(Text(preference.displayName))
                        .tag(preference)
                }
            } label: {
                Text(name)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(maxWidth: 140, alignment: .leading)
        }
        .padding(.vertical, 2)
    }
}

/// A network as the search found it: name, how many stations carry it, and whether it is already
/// on the list. The station count is the only hint the user gets about how much of the map an
/// opinion about this network will change, so it belongs here rather than on the listed row —
/// where it would have to be fetched again for a name that may no longer exist at all.
private struct ProviderResultRow: View {
    let provider: ChargingProvider
    let isListed: Bool
    let add: () -> Void

    var body: some View {
        Button(action: add) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(provider.name)
                        .foregroundStyle(.primary)
                    Text("provider.stationCount \(provider.stationCount.formatted())")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                Spacer(minLength: 8)
                if isListed {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.secondary)
                        .accessibilityLabel(Text("provider.alreadyListed"))
                }
            }
        }
    }
}
