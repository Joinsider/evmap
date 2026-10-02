import { TestBed } from '@angular/core/testing';
import { EvmapApi } from '../api/evmap-api';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { START_VIEWPORT } from '../../features/map/domain/viewport';
import { MapCallbacks, MapPinView } from './map-engine';
import { MapKitEngine } from './mapkit-engine';

type Listener = (event?: unknown) => void;

/** Just enough of the MapKit JS global to drive the engine: records what it is asked and answers like MapKit. */
class FakeMapKit {
  readonly tokens: string[] = [];
  readonly maps: FakeMap[] = [];
  readonly loaded: string[][] = [];
  initOptions: { language: string; authorizationCallback: (done: (token: string) => void) => void } | null = null;
  private readonly listeners = new Map<string, Listener[]>();
  rejectToken = false;
  searchFails = false;

  addEventListener(type: string, listener: Listener) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  init(options: NonNullable<FakeMapKit['initOptions']>) {
    this.initOptions = options;
    options.authorizationCallback((token) => {
      this.tokens.push(token);
      for (const listener of this.listeners.get(this.rejectToken ? 'error' : 'configuration-change') ?? []) listener();
    });
  }

  async load(libraries: string[]) {
    this.loaded.push(libraries);
  }

  readonly ColorScheme = { Light: 'light', Dark: 'dark' };
  readonly FeatureVisibility = { Hidden: 'hidden' };
  readonly Coordinate = class {
    constructor(
      readonly latitude: number,
      readonly longitude: number,
    ) {}
  };
  readonly CoordinateSpan = class {
    constructor(
      readonly latitudeDelta: number,
      readonly longitudeDelta: number,
    ) {}
  };
  readonly CoordinateRegion = class {
    constructor(
      readonly center: { latitude: number; longitude: number },
      readonly span: { latitudeDelta: number; longitudeDelta: number },
    ) {}
  };
  readonly MarkerAnnotation = class {
    constructor(
      readonly coordinate: unknown,
      readonly options: { color: string; glyphText: string; title: string; data: { id: string } },
    ) {}
  };
  readonly Map = ((kit: FakeMapKit) =>
    class extends FakeMap {
      constructor(element: HTMLElement, options: { region: FakeMap['region']; colorScheme: string }) {
        super(element, options);
        kit.maps.push(this);
      }
    })(this);
  readonly Search = ((kit: FakeMapKit) =>
    class {
      autocomplete(query: string, callback: (error: unknown, data: unknown) => void) {
        if (kit.searchFails) return callback(new Error('quota'), null);
        callback(null, { results: [{ displayLines: [query, 'Baden-Württemberg', 'Deutschland'] }] });
      }
      search(_ref: unknown, callback: (error: unknown, data: unknown) => void) {
        if (kit.searchFails) return callback(new Error('quota'), null);
        callback(null, { places: [{ name: 'Stuttgart', coordinate: { latitude: 48.78, longitude: 9.18 }, region: { span: { latitudeDelta: 0.3, longitudeDelta: 0.4 } } }] });
      }
    })(this);
}

class FakeMap {
  readonly annotations: { options: { color: string; data: { id: string } } }[] = [];
  readonly removed: unknown[] = [];
  readonly listeners = new Map<string, Listener>();
  selectedAnnotation: unknown = 'something';
  destroyed = false;
  region: { center: { latitude: number; longitude: number }; span: { latitudeDelta: number; longitudeDelta: number } };
  readonly colorScheme: string;
  animated: boolean | null = null;

  constructor(_element: HTMLElement, options: { region: FakeMap['region']; colorScheme: string }) {
    this.region = options.region;
    this.colorScheme = options.colorScheme;
  }

  addEventListener(type: string, listener: Listener) {
    this.listeners.set(type, listener);
  }

  addAnnotations(annotations: FakeMap['annotations']) {
    this.annotations.push(...annotations);
  }

  removeAnnotations(annotations: FakeMap['annotations']) {
    this.removed.push(...annotations);
    for (const annotation of annotations) this.annotations.splice(this.annotations.indexOf(annotation), 1);
  }

  setRegionAnimated(region: FakeMap['region'], animated: boolean) {
    this.region = region;
    this.animated = animated;
  }

  destroy() {
    this.destroyed = true;
  }
}

const pin = (id: string, color = '#d42a2a'): MapPinView => ({ id, latitude: 48, longitude: 9, color, glyph: '', title: id });

describe('MapKitEngine', () => {
  let api: FakeEvmapApi;
  let kit: FakeMapKit;
  const callbacks = (): MapCallbacks & { regions: unknown[]; selected: string[] } => {
    const regions: unknown[] = [];
    const selected: string[] = [];
    return { regions, selected, regionChanged: (viewport) => regions.push(viewport), pinSelected: (id) => selected.push(id) };
  };

  beforeEach(() => {
    api = new FakeEvmapApi();
    kit = new FakeMapKit();
    window.mapkit = kit;
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }, MapKitEngine] });
  });

  afterEach(() => {
    delete window.mapkit;
    document.head.querySelectorAll('script[src*="apple-mapkit"]').forEach((script) => script.remove());
  });

  it('authorizes with the backend token, loads the libraries once and opens the map on the given region', async () => {
    const engine = TestBed.inject(MapKitEngine);
    await engine.create(document.createElement('div'), START_VIEWPORT, callbacks());
    await engine.create(document.createElement('div'), START_VIEWPORT, callbacks());

    expect(kit.tokens).toEqual(['test-token']);
    expect(kit.initOptions!.language).toBe('de');
    expect(kit.loaded).toEqual([['map', 'annotations', 'services']]);
    expect(kit.maps).toHaveLength(2);
    expect(kit.maps[0].region.center).toMatchObject({ latitude: START_VIEWPORT.latitude, longitude: START_VIEWPORT.longitude });
    expect(kit.maps[0].colorScheme).toBe('light');
  });

  it('fetches a fresh token whenever MapKit asks again', async () => {
    const engine = TestBed.inject(MapKitEngine);
    await engine.create(document.createElement('div'), START_VIEWPORT, callbacks());
    api.token = { token: 'fresh', expiresAt: '2026-10-02T13:00:00Z' };

    kit.initOptions!.authorizationCallback((token) => kit.tokens.push(token));

    expect(kit.tokens).toEqual(['test-token', 'fresh']);
  });

  it('fails before loading anything when the backend has no Maps key, and tries again next time', async () => {
    delete window.mapkit;
    api.token = null;
    const engine = TestBed.inject(MapKitEngine);

    await expect(engine.create(document.createElement('div'), START_VIEWPORT, callbacks())).rejects.toThrow();
    expect(document.head.querySelector('script[src*="apple-mapkit"]')).toBeNull();

    api.token = { token: 'later', expiresAt: '2026-10-02T13:00:00Z' };
    const second = engine.create(document.createElement('div'), START_VIEWPORT, callbacks());
    await new Promise((resolve) => setTimeout(resolve));
    const script = document.head.querySelector<HTMLScriptElement>('script[src*="apple-mapkit"]')!;
    expect(script.src).toBe('https://cdn.apple-mapkit.com/mk/6/mapkit.core.js');
    expect(script.crossOrigin).toBe('anonymous');
    window.mapkit = kit;
    script.dispatchEvent(new Event('load'));
    await second;
    expect(kit.tokens).toEqual(['later']);
  });

  it('rejects when the script cannot be loaded or MapKit refuses the token', async () => {
    delete window.mapkit;
    const engine = TestBed.inject(MapKitEngine);
    const blocked = engine.create(document.createElement('div'), START_VIEWPORT, callbacks());
    await new Promise((resolve) => setTimeout(resolve));
    document.head.querySelector('script[src*="apple-mapkit"]')!.dispatchEvent(new Event('error'));
    await expect(blocked).rejects.toThrow('MapKit JS could not be loaded');

    window.mapkit = kit;
    kit.rejectToken = true;
    await expect(engine.create(document.createElement('div'), START_VIEWPORT, callbacks())).rejects.toThrow('rejected its token');
  });

  it('draws pins, replaces only what changed and removes what is gone', async () => {
    const handle = await TestBed.inject(MapKitEngine).create(document.createElement('div'), START_VIEWPORT, callbacks());
    const map = kit.maps[0];

    handle.setPins([pin('a'), pin('b'), pin('c')]);
    const [a] = map.annotations;
    handle.setPins([pin('a'), pin('b', '#2e9d4f')]);

    expect(map.annotations.map((annotation) => [annotation.options.data.id, annotation.options.color])).toEqual([
      ['a', '#d42a2a'],
      ['b', '#2e9d4f'],
    ]);
    expect(map.annotations[0]).toBe(a);
    expect(map.removed).toHaveLength(2);
  });

  it('reports settled regions and selections, and consumes the selection', async () => {
    const events = callbacks();
    const handle = await TestBed.inject(MapKitEngine).create(document.createElement('div'), START_VIEWPORT, events);
    const map = kit.maps[0];

    handle.setViewport({ latitude: 48.78, longitude: 9.18, latitudeSpan: 0.2, longitudeSpan: 0.3 }, true);
    map.listeners.get('region-change-end')!();
    map.listeners.get('select')!({ annotation: { data: { id: 'a' } } });

    expect(map.animated).toBe(true);
    expect(events.regions).toEqual([{ latitude: 48.78, longitude: 9.18, latitudeSpan: 0.2, longitudeSpan: 0.3 }]);
    expect(events.selected).toEqual(['a']);
    expect(map.selectedAnnotation).toBeNull();
    handle.destroy();
    expect(map.destroyed).toBe(true);
  });

  it('turns autocomplete results into suggestions and resolves one into a place', async () => {
    const engine = TestBed.inject(MapKitEngine);

    const [suggestion] = await engine.autocomplete('Stuttgart', START_VIEWPORT);
    expect(suggestion).toMatchObject({ title: 'Stuttgart', subtitle: 'Baden-Württemberg, Deutschland' });
    expect(await engine.resolve(suggestion)).toEqual({ name: 'Stuttgart', latitude: 48.78, longitude: 9.18, latitudeSpan: 0.3, longitudeSpan: 0.4 });

    kit.searchFails = true;
    expect(await engine.autocomplete('Stuttgart', START_VIEWPORT)).toEqual([]);
    expect(await engine.resolve(suggestion)).toBeNull();
  });
});
