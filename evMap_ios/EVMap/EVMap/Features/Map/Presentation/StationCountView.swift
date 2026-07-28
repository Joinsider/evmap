import SwiftUI

struct StationCountView: View {
    let count: Int
    let isLoading: Bool

    var body: some View {
        HStack {
            if isLoading { ProgressView() }
            Text(isLoading ? "stations.loading" : String(localized: "stations.count \(count)"))
            Spacer()
        }
        .padding()
        .background(.thinMaterial)
    }
}
