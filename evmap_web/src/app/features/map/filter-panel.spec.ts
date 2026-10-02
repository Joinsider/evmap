import { TestBed } from '@angular/core/testing';
import { throwError } from 'rxjs';
import { EvmapApi } from '../../core/api/evmap-api';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { SETTINGS_KEY, StationSettingsStore } from './data/station-settings.store';
import { withPreference } from './domain/station-settings';
import { FilterPanel } from './filter-panel';

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

describe('FilterPanel', () => {
  let api: FakeEvmapApi;

  beforeEach(() => {
    localStorage.clear();
    api = new FakeEvmapApi();
    api.directory = [
      { name: 'EnBW', stationCount: 4200 },
      { name: 'Ionity', stationCount: 300 },
    ];
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }] });
  });

  async function open() {
    const fixture = TestBed.createComponent(FilterPanel);
    await fixture.whenStable();
    await wait(300);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement, store: TestBed.inject(StationSettingsStore) };
  }

  const button = (root: HTMLElement, label: string) => Array.from(root.querySelectorAll('button')).find((b) => b.textContent!.trim() === label)!;

  it('stores connector, power and availability choices in the browser at once', async () => {
    const { root, store } = await open();
    const ccs = Array.from(root.querySelectorAll<HTMLLabelElement>('label')).find((label) => label.textContent!.includes('CCS (Combo 2)'))!.querySelector('input')!;
    ccs.click();
    const slider = root.querySelector<HTMLInputElement>('input[type=range]')!;
    slider.value = '5';
    slider.dispatchEvent(new Event('input'));

    expect(store.settings().connectorTypes).toEqual(['CCS']);
    expect(store.settings().minimumPowerKw).toBe(100);
    expect(JSON.parse(localStorage.getItem(SETTINGS_KEY)!).minimumPowerKw).toBe(100);
  });

  it('adds a network from the directory and hides it, then forgets it on remove', async () => {
    const { fixture, root, store } = await open();
    expect(root.textContent).toContain('Größte Anbieter');
    expect(root.textContent).toContain('4.200 Ladestationen');

    button(root, 'Hinzufügen').click();
    fixture.detectChanges();
    expect(root.textContent).toContain('Bereits in der Liste');
    button(root, 'Nicht anzeigen').click();
    expect(store.settings().providerPreferences).toEqual({ EnBW: 'hidden' });

    button(root, 'Entfernen').click();
    expect(store.settings().providerPreferences).toEqual({});
  });

  it('lists the networks already set when it opens, and resets after a confirmation', async () => {
    TestBed.inject(StationSettingsStore).update((settings) => withPreference(settings, 'Ionity', 'hidden'));
    const { fixture, root, store } = await open();
    expect(root.querySelector('.providers')!.textContent).toContain('Ionity');

    button(root, 'Einstellungen zurücksetzen').click();
    fixture.detectChanges();
    expect(store.settings().providerPreferences).toEqual({ Ionity: 'hidden' });
    button(root, 'Einstellungen zurücksetzen').click();
    fixture.detectChanges();

    expect(store.settings().providerPreferences).toEqual({});
    expect(root.querySelector('.providers')!.textContent).not.toContain('Ionity');
  });

  it('searches the directory as the person types', async () => {
    const { fixture, root } = await open();
    const search = root.querySelector<HTMLInputElement>('input[type=search]')!;
    search.value = 'ion';
    search.dispatchEvent(new Event('input'));
    await wait(300);
    fixture.detectChanges();

    expect(api.operatorQueries.at(-1)).toBe('ion');
    expect(root.textContent).toContain('Suchergebnisse');
    expect(root.querySelector('.directory')!.textContent).not.toContain('EnBW');
  });

  it('switches a connector off again, takes the availability switch and snaps an off-step power down', async () => {
    TestBed.inject(StationSettingsStore).update((settings) => ({ ...settings, connectorTypes: ['CCS'], minimumPowerKw: 120 }));
    const { fixture, root, store } = await open();
    expect(root.querySelector('output')!.textContent).toBe('100 kW');

    const label = (text: string) => Array.from(root.querySelectorAll<HTMLLabelElement>('label')).find((l) => l.textContent!.includes(text))!.querySelector('input')!;
    label('CCS (Combo 2)').click();
    label('Nur betriebsbereite').click();
    const slider = root.querySelector<HTMLInputElement>('input[type=range]')!;
    slider.value = '0';
    slider.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(store.settings()).toMatchObject({ connectorTypes: [], availabilityOnly: true, minimumPowerKw: null });
    expect(root.querySelector('output')!.textContent).toBe('Beliebig');
  });

  it('explains the allowlist once every other network is hidden', async () => {
    const { fixture, root, store } = await open();
    const unlisted = Array.from(root.querySelectorAll<HTMLLabelElement>('label')).find((l) => l.textContent!.includes('Alle übrigen'))!.querySelector('input')!;
    unlisted.click();
    fixture.detectChanges();

    expect(store.settings().unlistedProviders).toBe('hidden');
    expect(root.textContent).toContain('Die Karte zeigt nur die Anbieter aus dieser Liste');
  });

  it('says when the directory finds nothing or cannot be reached', async () => {
    const { fixture, root } = await open();
    const search = root.querySelector<HTMLInputElement>('input[type=search]')!;
    search.value = 'zzz';
    search.dispatchEvent(new Event('input'));
    await wait(300);
    fixture.detectChanges();
    expect(root.textContent).toContain('Keine Anbieter gefunden.');

    api.operators = () => throwError(() => new Error('500'));
    search.value = 'abc';
    search.dispatchEvent(new Event('input'));
    await wait(300);
    fixture.detectChanges();
    expect(root.textContent).toContain('Die Anbieter konnten nicht geladen werden.');
  });

  it('names the two reserved preferences too, for values a later version stored', async () => {
    const { fixture } = await open();
    const panel = fixture.componentInstance as unknown as { preferenceLabel(p: string): string };
    expect(['shown', 'hidden', 'preferred', 'avoided'].map((p) => panel.preferenceLabel(p))).toEqual(['Anzeigen', 'Nicht anzeigen', 'Bevorzugen', 'Meiden']);
  });
});
