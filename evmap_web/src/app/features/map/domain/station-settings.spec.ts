import { DEFAULT_SETTINGS, isDefault, matchesNothing, parseSettings, preferenceFor, stationFilter, withPreference, withUnlistedProviders } from './station-settings';

describe('station settings', () => {
  it('turns hidden networks into an exclusion list while everything else is shown', () => {
    const settings = withPreference(withPreference(DEFAULT_SETTINGS, 'Zeta', 'hidden'), 'Alpha', 'hidden');
    expect(stationFilter(settings)).toEqual({ connectorTypes: [], minimumPowerKw: undefined, availabilityOnly: false, excludedProviders: ['Alpha', 'Zeta'], includedProviders: undefined });
  });

  it('turns the list into an allowlist once everything else is hidden, and an empty one matches nothing', () => {
    const hiddenByDefault = withUnlistedProviders(DEFAULT_SETTINGS, 'hidden');
    expect(matchesNothing(stationFilter(hiddenByDefault))).toBe(true);
    const onlyEnbw = withPreference(hiddenByDefault, 'EnBW', 'shown');
    expect(stationFilter(onlyEnbw).includedProviders).toEqual(['EnBW']);
    expect(stationFilter(onlyEnbw).excludedProviders).toEqual([]);
  });

  it('forgets an opinion that repeats the global switch, so switching a network back leaves no trace', () => {
    const hidden = withPreference(DEFAULT_SETTINGS, 'EnBW', 'hidden');
    expect(preferenceFor(hidden, 'EnBW')).toBe('hidden');
    expect(isDefault(withPreference(hidden, 'EnBW', 'shown'))).toBe(true);
  });

  it('keeps the listed opinions when the global switch flips back', () => {
    const settings = withUnlistedProviders(withUnlistedProviders(withPreference(DEFAULT_SETTINGS, 'EnBW', 'hidden'), 'hidden'), 'shown');
    expect(stationFilter(settings).excludedProviders).toEqual(['EnBW']);
  });

  it('reads stored settings leniently instead of resetting them', () => {
    expect(parseSettings(null)).toEqual(DEFAULT_SETTINGS);
    expect(
      parseSettings({ connectorTypes: ['CCS', 'Warp drive'], minimumPowerKw: 'fast', providerPreferences: { EnBW: 'hidden', Ionity: 'teleport' }, unlistedProviders: 'sometimes' }),
    ).toEqual({ connectorTypes: ['CCS'], minimumPowerKw: null, availabilityOnly: false, providerPreferences: { EnBW: 'hidden', Ionity: 'shown' }, unlistedProviders: 'shown' });
    expect(parseSettings({ providerPreferences: { A: 'preferred' } }).providerPreferences).toEqual({ A: 'preferred' });
  });
});
