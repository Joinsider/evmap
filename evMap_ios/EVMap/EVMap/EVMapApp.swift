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
    /// Owned here for the same reason: the map draws from it and the station screen changes it.
    @StateObject private var favorites: FavoritesViewModel

    init() {
        // First line of every run: without it, a console full of request logs
        // gives no clue which backend they were aimed at.
        AppLogger.app.notice("EVMap \(Bundle.main.appVersion) launched against \(APIEnvironment.baseURL.absoluteString)")
        let repository = RESTChargingStationRepository()
        self.repository = repository
        let authSession = AuthSession(repository: repository)
        _authSession = StateObject(wrappedValue: authSession)
        _favorites = StateObject(wrappedValue: FavoritesViewModel(repository: repository, authSession: authSession))
    }

    var body: some Scene {
        WindowGroup {
            MapScreen(repository: repository, authSession: authSession, settings: settings, favorites: favorites)
        }
    }
}
