//
//  EVMapApp.swift
//  EVMap
//
//  Created by Johannes Popp on 25.07.26.
//

import SwiftUI

@main
struct EVMapApp: App {
    private let repository: any ChargingStationRepository
    @StateObject private var authSession: AuthSession

    init() {
        let repository = RESTChargingStationRepository()
        self.repository = repository
        _authSession = StateObject(wrappedValue: AuthSession(repository: repository))
    }

    var body: some Scene {
        WindowGroup {
            MapScreen(repository: repository, authSession: authSession)
        }
    }
}
