import SwiftUI

/// Tells us what is wrong with a station (ADR 0021): a reason and, if the person wants, a short text.
/// The report goes to an admin queue; the station's data does not change by it.
struct StationReportScreen: View {
    /// Sends the report and answers whether it was accepted; the sheet closes only when it was.
    let submit: (StationReportReason, String?) async -> Bool
    @State private var reason: StationReportReason = .defective
    @State private var note = ""
    @State private var isSending = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("station.report.reason") {
                    Picker("station.report.reason", selection: $reason) {
                        ForEach(StationReportReason.allCases) { Text($0.displayName).tag($0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
                Section {
                    TextField("station.report.note", text: $note, axis: .vertical).lineLimit(3...6)
                        .onChange(of: note) { _, text in
                            if text.count > StationReportLimits.noteLength { note = String(text.prefix(StationReportLimits.noteLength)) }
                        }
                } footer: { Text("station.report.footer") }
            }
            .navigationTitle("station.report.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("action.cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("station.report.send") { Task { await send() } }.disabled(isSending)
                }
            }
            .interactiveDismissDisabled(isSending)
        }
    }

    private func send() async {
        isSending = true
        defer { isSending = false }
        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
        if await submit(reason, trimmed.isEmpty ? nil : trimmed) { dismiss() }
    }
}
