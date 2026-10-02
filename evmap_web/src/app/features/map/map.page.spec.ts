import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { throwError } from 'rxjs';
import { EvmapApi } from '../../core/api/evmap-api';
import { StationSummary } from '../../core/api/models';
import { MapEngine } from '../../core/map/map-engine';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { FakeMapEngine } from '../../testing/fake-map-engine';
import { StationSettingsStore } from './data/station-settings.store';
import { withPreference, withUnlistedProviders } from './domain/station-settings';
import { MapPage } from './map.page';
import { StationSelection } from './station-selection';

const station = (id: string, latitude: number, longitude: number, maxPowerKw?: number): StationSummary => ({
  id,
  displayName: `Station ${id}`,
  latitude,
  longitude,
  maxPowerKw,
  availabilityStatus: 'OPERATIONAL',
});

const tick = () => new Promise((resolve) => setTimeout(resolve));

describe('MapPage', () => {
  let api: FakeEvmapApi;
  let engine: FakeMapEngine;

  beforeEach(() => {
    localStorage.clear();
    api = new FakeEvmapApi();
    engine = new FakeMapEngine();
    TestBed.configureTestingModule({
      providers: [{ provide: EvmapApi, useValue: api }, { provide: MapEngine, useValue: engine }, provideRouter([])],
    });
  });

  async function open() {
    const fixture = TestBed.createComponent(MapPage);
    fixture.detectChanges();
    await tick();
    await fixture.whenStable();
    return fixture;
  }

  it('asks for the start viewport with the overview power floor and draws coloured pins', async () => {
    api.stationList = [station('a', 48.78, 9.18, 300), station('b', 52.5, 13.4)];
    await open();

    expect(api.stationQueries).toHaveLength(1);
    expect(api.stationQueries[0]).toMatchObject({ limit: 600, minPowerKw: 100, excludeOperators: [] });
    expect(engine.pins.map((pin) => [pin.id, pin.color])).toEqual([
      ['a', '#d42a2a'],
      ['b', '#8a8f8c'],
    ]);
    expect(api.boundsQueries).toHaveLength(1);
  });

  it('loads nothing for a small drift and reloads once the camera leaves the loaded area', async () => {
    await open();
    const start = engine.current!;

    engine.settle({ ...start, latitude: start.latitude + 0.1 });
    expect(api.stationQueries).toHaveLength(1);

    engine.settle({ latitude: 48.78, longitude: 9.18, latitudeSpan: 0.2, longitudeSpan: 0.3 });
    expect(api.stationQueries).toHaveLength(2);
    expect(api.stationQueries[1].minPowerKw).toBeUndefined();
  });

  it('sends hidden networks as exclusions and never sends an empty allowlist', async () => {
    TestBed.inject(StationSettingsStore).update((settings) => withPreference(settings, 'EnBW', 'hidden'));
    await open();
    expect(api.stationQueries[0].excludeOperators).toEqual(['EnBW']);

    TestBed.inject(StationSettingsStore).update((settings) => withUnlistedProviders(settings, 'hidden'));
    engine.settle({ latitude: 48.78, longitude: 9.18, latitudeSpan: 0.2, longitudeSpan: 0.3 });
    expect(api.stationQueries).toHaveLength(1);
    expect(engine.pins).toEqual([]);
  });

  it('says when only the strongest stations are shown', async () => {
    api.stationList = Array.from({ length: 600 }, (_, i) => station(`s${i}`, 48 + i * 0.001, 9));
    const fixture = await open();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Nur die stärksten Ladestationen');
  });

  it('drops stations out of service when only usable ones are wanted', async () => {
    api.stationList = [station('a', 48.78, 9.18), { ...station('b', 52.5, 13.4), availabilityStatus: 'OUT_OF_SERVICE' }];
    TestBed.inject(StationSettingsStore).update((settings) => ({ ...settings, availabilityOnly: true }));
    await open();

    expect(engine.pins.map((pin) => pin.id)).toEqual(['a']);
  });

  it('opens a station on its pin and zooms into a cluster', async () => {
    api.stationList = [station('a', 50.0001, 9.0001), station('b', 50.0002, 9.0002), station('c', 53, 13)];
    await open();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    engine.callbacks!.pinSelected('c');
    expect(navigate).toHaveBeenCalledWith(['/station', 'c']);

    const cluster = engine.pins.find((pin) => pin.glyph === '2')!;
    expect(cluster.title).toBe('2 Ladestationen');
    engine.callbacks!.pinSelected(cluster.id);
    expect(engine.moves.at(-1)!.viewport.latitudeSpan).toBeCloseTo(7 / 4);
  });

  it('says so when the map cannot be shown', async () => {
    engine.unavailable = true;
    const fixture = await open();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Die Karte ist gerade nicht verfügbar');
    expect(api.stationQueries).toEqual([]);
  });

  it('reloads when the filter panel closes, and moves to a searched place without loading by itself', async () => {
    const fixture = await open();
    const page = fixture.componentInstance as unknown as { closeFilter(): void; placeChosen(place: object): void; openFilter(): void };

    page.openFilter();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('app-filter-panel')).not.toBeNull();
    page.closeFilter();
    expect(api.stationQueries).toHaveLength(2);

    page.placeChosen({ name: 'Stuttgart', latitude: 48.78, longitude: 9.18 });
    expect(engine.moves.at(-1)).toEqual({ viewport: { latitude: 48.78, longitude: 9.18, latitudeSpan: 0.05, longitudeSpan: 0.05 }, animated: true });
    expect(api.stationQueries).toHaveLength(2);
  });

  it('says when stations fail to load and tries again on the next camera change', async () => {
    const failing = api.stations.bind(api);
    api.stations = (query) => {
      api.stationQueries.push(query);
      return throwError(() => new Error('503'));
    };
    const fixture = await open();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Stationen konnten nicht geladen werden.');

    api.stations = failing;
    engine.settle({ ...engine.current!, latitude: engine.current!.latitude + 0.01 });
    expect(api.stationQueries).toHaveLength(2);
  });

  it('keeps the pins when live availability fails, and counts free charge points when it answers', async () => {
    api.stationList = [station('a', 48.78, 9.18)];
    api.liveInBounds = [{ stationId: 'a', status: 'AVAILABLE', available: 3, occupied: 0, outOfOrder: 0, unknown: 0, chargePoints: [], sources: [] }];
    await open();
    expect(engine.pins[0].liveAvailable).toBe(3);

    api.availabilityInBounds = () => throwError(() => new Error('502'));
    engine.settle({ latitude: 48.78, longitude: 9.18, latitudeSpan: 0.2, longitudeSpan: 0.3 });
    expect(engine.pins.map((pin) => pin.id)).toEqual(['a']);
  });

  it('brings a station opened by link into view, but leaves the camera alone when it is already visible', async () => {
    const fixture = await open();
    const selection = fixture.debugElement.injector.get(StationSelection);

    selection.position.set({ latitude: 51, longitude: 10 });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(engine.moves).toEqual([]);

    selection.position.set({ latitude: 40, longitude: -3.7 });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(engine.moves).toEqual([{ viewport: { latitude: 40, longitude: -3.7, latitudeSpan: 0.05, longitudeSpan: 0.05 }, animated: false }]);
  });
});
