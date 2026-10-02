/**
 * What the web app remembers about which stations someone wants to see — the counterpart of the iOS `AppSettings`
 * and `StationFilter` split (ADR 0014). `StationSettings` is what the filter panel edits and the browser stores;
 * `StationFilter` is the criteria of one query and is built only by {@link stationFilter} (and raised by the
 * viewport at overview scale), never assembled by hand.
 */

/** Connector standards; the value is what goes to the backend as `connectorType` (backend `ConnectorTypes`). */
export const CONNECTOR_TYPES = ['Type 2', 'CCS', 'CHAdeMO', 'Type 1', 'Tesla', 'Schuko', 'CEE'] as const;
export type ConnectorType = (typeof CONNECTOR_TYPES)[number];

/** Brand and standard names, identical across locales. */
export function connectorLabel(type: ConnectorType): string {
  switch (type) {
    case 'Type 2':
      return 'Type 2 (Mennekes)';
    case 'CCS':
      return 'CCS (Combo 2)';
    case 'CHAdeMO':
      return 'CHAdeMO';
    case 'Type 1':
      return 'Type 1';
    case 'Tesla':
      return 'Tesla Supercharger';
    case 'Schuko':
      return 'Schuko (230 V)';
    case 'CEE':
      return 'CEE (Blue/Red)';
  }
}

/** Slider positions of the power filter: "any", then the common European charging speeds (iOS `ChargingPowerStep`). */
export const POWER_STEPS: readonly (number | null)[] = [null, 3, 11, 22, 50, 100, 150, 300, 400, 600];

/** The four cases of ADR 0014; only `shown` and `hidden` are offered, the other two are kept when read. */
export type ProviderPreference = 'shown' | 'hidden' | 'preferred' | 'avoided';
const PREFERENCES: readonly ProviderPreference[] = ['shown', 'hidden', 'preferred', 'avoided'];
export const SELECTABLE_PREFERENCES: readonly ProviderPreference[] = ['shown', 'hidden'];

export interface StationSettings {
  connectorTypes: readonly ConnectorType[];
  minimumPowerKw: number | null;
  availabilityOnly: boolean;
  /** Keyed by operator name exactly as the backend reports it; only entries that differ from `unlistedProviders`. */
  providerPreferences: Readonly<Record<string, ProviderPreference>>;
  /** The "show all / hide all" switch: `hidden` turns the preferences from a blocklist into an allowlist. */
  unlistedProviders: ProviderPreference;
}

export const DEFAULT_SETTINGS: StationSettings = {
  connectorTypes: [],
  minimumPowerKw: null,
  availabilityOnly: false,
  providerPreferences: {},
  unlistedProviders: 'shown',
};

export interface StationFilter {
  connectorTypes: readonly ConnectorType[];
  minimumPowerKw?: number;
  availabilityOnly: boolean;
  excludedProviders: readonly string[];
  /** `undefined` for no restriction; an empty list matches nothing and must never reach the wire. */
  includedProviders?: readonly string[];
}

const hides = (preference: ProviderPreference) => preference === 'hidden';

export function isDefault(settings: StationSettings): boolean {
  return JSON.stringify(normalize(settings)) === JSON.stringify(normalize(DEFAULT_SETTINGS));
}

export function preferenceFor(settings: StationSettings, provider: string): ProviderPreference {
  return settings.providerPreferences[provider] ?? settings.unlistedProviders;
}

/** Stores an opinion, or forgets one that only repeats the global switch, so a network switched back is untouched. */
export function withPreference(settings: StationSettings, provider: string, preference: ProviderPreference): StationSettings {
  const providerPreferences = { ...settings.providerPreferences };
  if (preference === settings.unlistedProviders) delete providerPreferences[provider];
  else providerPreferences[provider] = preference;
  return { ...settings, providerPreferences };
}

/**
 * Flips the global switch and nothing else, as on iOS: the listed networks keep their stored opinions, so switching
 * back restores the list as it was. A listed network whose opinion repeats the switch is simply redundant.
 */
export function withUnlistedProviders(settings: StationSettings, unlisted: ProviderPreference): StationSettings {
  return { ...settings, unlistedProviders: unlisted };
}

export function configuredProviders(settings: StationSettings): string[] {
  return Object.keys(settings.providerPreferences).sort();
}

/** The one place the settings vocabulary becomes query terms. */
export function stationFilter(settings: StationSettings): StationFilter {
  const entries = Object.entries(settings.providerPreferences);
  const allowlist = hides(settings.unlistedProviders);
  return {
    connectorTypes: [...settings.connectorTypes].sort(),
    minimumPowerKw: settings.minimumPowerKw ?? undefined,
    availabilityOnly: settings.availabilityOnly,
    excludedProviders: allowlist ? [] : entries.filter(([, preference]) => hides(preference)).map(([name]) => name).sort(),
    includedProviders: allowlist ? entries.filter(([, preference]) => !hides(preference)).map(([name]) => name).sort() : undefined,
  };
}

/** Only an empty allowlist matches nothing; such a query is not sent, or it would arrive unrestricted. */
export function matchesNothing(filter: StationFilter): boolean {
  return filter.includedProviders !== undefined && filter.includedProviders.length === 0;
}

/**
 * Reads stored settings leniently, field by field, like the iOS decoder: a missing or malformed field falls back to
 * its default, an unknown connector is dropped, an unknown preference becomes `shown`. Throwing would reset every
 * other setting the person made.
 */
export function parseSettings(stored: unknown): StationSettings {
  if (!stored || typeof stored !== 'object') return DEFAULT_SETTINGS;
  const value = stored as Record<string, unknown>;
  const connectors = Array.isArray(value['connectorTypes'])
    ? CONNECTOR_TYPES.filter((type) => (value['connectorTypes'] as unknown[]).includes(type))
    : [];
  const power = value['minimumPowerKw'];
  const preferences: Record<string, ProviderPreference> = {};
  const storedPreferences = value['providerPreferences'];
  if (storedPreferences && typeof storedPreferences === 'object' && !Array.isArray(storedPreferences)) {
    for (const [name, preference] of Object.entries(storedPreferences as Record<string, unknown>)) {
      preferences[name] = readPreference(preference);
    }
  }
  return {
    connectorTypes: connectors,
    minimumPowerKw: typeof power === 'number' && Number.isFinite(power) && power > 0 ? power : null,
    availabilityOnly: value['availabilityOnly'] === true,
    providerPreferences: preferences,
    unlistedProviders: readPreference(value['unlistedProviders']),
  };
}

function readPreference(value: unknown): ProviderPreference {
  return PREFERENCES.includes(value as ProviderPreference) ? (value as ProviderPreference) : 'shown';
}

/** Stable form for storage and comparison: sorted connectors and keys. */
export function normalize(settings: StationSettings): StationSettings {
  return {
    connectorTypes: [...settings.connectorTypes].sort(),
    minimumPowerKw: settings.minimumPowerKw,
    availabilityOnly: settings.availabilityOnly,
    providerPreferences: Object.fromEntries(Object.entries(settings.providerPreferences).sort(([a], [b]) => a.localeCompare(b))),
    unlistedProviders: settings.unlistedProviders,
  };
}
