import { Injectable, signal } from '@angular/core';
import { DEFAULT_SETTINGS, StationSettings, normalize, parseSettings } from '../domain/station-settings';

/** The browser counterpart of the iOS `AppSettingsStore`: one JSON value under one key. */
export const SETTINGS_KEY = 'evmap.stationSettings.v1';

/**
 * Filters and provider preferences, kept in this browser's `localStorage` (ADR 0023) — device-only like on iOS,
 * never sent anywhere except as the query they produce. Storage can be missing or throw (private windows, blocked
 * site data); then the settings simply live for the page's lifetime.
 */
@Injectable({ providedIn: 'root' })
export class StationSettingsStore {
  private readonly state = signal<StationSettings>(StationSettingsStore.read());
  readonly settings = this.state.asReadonly();

  update(change: (settings: StationSettings) => StationSettings) {
    const next = normalize(change(this.state()));
    this.state.set(next);
    try {
      localStorage.setItem(SETTINGS_KEY, JSON.stringify(next));
    } catch {
      // Not persisted; the session keeps working with the value in memory.
    }
  }

  /** Restores filters and provider preferences, nothing else. */
  reset() {
    this.update(() => DEFAULT_SETTINGS);
  }

  private static read(): StationSettings {
    try {
      const raw = localStorage.getItem(SETTINGS_KEY);
      return raw ? parseSettings(JSON.parse(raw)) : DEFAULT_SETTINGS;
    } catch {
      return DEFAULT_SETTINGS;
    }
  }
}
