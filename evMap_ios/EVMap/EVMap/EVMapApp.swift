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
    /// Loaded once at launch and owned here rather than by a screen, because the map needs the
    /// stored filter before it builds its first query.
    @StateObject private var settings = SettingsViewModel()

    init() {
        // First line of every run: without it, a console full of request logs
        // gives no clue which backend they were aimed at.
        AppLogger.app.notice("EVMap \(Bundle.main.appVersion) launched against \(APIEnvironment.baseURL.absoluteString)")
        let repository = RESTChargingStationRepository()
        self.repository = repository
        _authSession = StateObject(wrappedValue: AuthSession(repository: repository))
    }

    var body: some Scene {
        WindowGroup {
            MapScreen(repository: repository, authSession: authSession, settings: settings)
        }
    }
}
