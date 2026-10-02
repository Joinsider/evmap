import { TestBed } from '@angular/core/testing';
import { SETTINGS_KEY, StationSettingsStore } from './station-settings.store';
import { DEFAULT_SETTINGS } from '../domain/station-settings';

describe('StationSettingsStore', () => {
  beforeEach(() => localStorage.clear());

  it('reads what an earlier visit stored, leniently', () => {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify({ connectorTypes: ['CCS', 'Unknown'], unlistedProviders: 'hidden' }));
    const store = TestBed.inject(StationSettingsStore);

    expect(store.settings()).toMatchObject({ connectorTypes: ['CCS'], unlistedProviders: 'hidden' });
  });

  it('starts from the defaults when the stored value is not JSON', () => {
    localStorage.setItem(SETTINGS_KEY, '{oops');

    expect(TestBed.inject(StationSettingsStore).settings()).toEqual(DEFAULT_SETTINGS);
  });

  it('keeps working in memory when storage refuses writes', () => {
    const store = TestBed.inject(StationSettingsStore);
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    store.update((settings) => ({ ...settings, availabilityOnly: true }));

    expect(store.settings().availabilityOnly).toBe(true);
    setItem.mockRestore();
  });
});
