import Foundation

struct StationFilter: Equatable {
    var connectorType = ""
    var minimumPower: Double?
    var operatorName = ""
    var availabilityOnly = false
}
