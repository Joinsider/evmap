import { Injectable, LOCALE_ID, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { EvmapApi } from '../api/evmap-api';
import { Viewport } from '../../features/map/domain/viewport';
import { MapCallbacks, MapEngine, MapHandle, MapPinView, Place, PlaceSuggestion } from './map-engine';

/** Major version only: Apple's autoupdate URL picks the newest compatible release. */
const SCRIPT_URL = 'https://cdn.apple-mapkit.com/mk/6/mapkit.core.js';
const LIBRARIES = ['map', 'annotations', 'services'];
const INIT_TIMEOUT_MS = 15_000;

/**
 * The global MapKit JS installs. Typed loosely on purpose: the surface used here is small, and `@types/apple-mapkit`
 * would pin a type version to an autoupdating script. Everything that touches it stays in this file.
 */
type MapKit = any;

declare global {
  interface Window {
    mapkit?: MapKit;
  }
}

/**
 * {@link MapEngine} on MapKit JS. The script is loaded on first use, not with the page, so the account and admin
 * areas never fetch it. The token comes from the backend (`GET /api/v1/map/token`, ADR 0023) through MapKit's
 * `authorizationCallback`, which MapKit calls again whenever the token runs out.
 */
@Injectable()
export class MapKitEngine extends MapEngine {
  private readonly api = inject(EvmapApi);
  private readonly locale = inject(LOCALE_ID);
  private ready: Promise<MapKit> | null = null;

  private mapkit(): Promise<MapKit> {
    this.ready ??= this.load().catch((error) => {
      // A failed load may be a transient network error; the next map attempt starts over.
      this.ready = null;
      throw error;
    });
    return this.ready;
  }

  private async load(): Promise<MapKit> {
    // Asked first, so a backend without a Maps key fails here instead of after downloading Apple's script.
    const first = await firstValueFrom(this.api.mapToken());
    if (!window.mapkit) {
      await new Promise<void>((resolve, reject) => {
        const script = document.createElement('script');
        script.src = SCRIPT_URL;
        script.crossOrigin = 'anonymous';
        script.async = true;
        script.addEventListener('load', () => resolve(), { once: true });
        script.addEventListener('error', () => reject(new Error('MapKit JS could not be loaded')), { once: true });
        document.head.appendChild(script);
      });
    }
    const mapkit = window.mapkit;
    if (!mapkit) throw new Error('MapKit JS did not install itself');
    let token: string | null = first.token;
    await new Promise<void>((resolve, reject) => {
      // Neither event may come (a blocked request to Apple); the page must not wait forever on "loading".
      const timeout = setTimeout(() => reject(new Error('MapKit JS did not initialize')), INIT_TIMEOUT_MS);
      mapkit.addEventListener('configuration-change', () => {
        clearTimeout(timeout);
        resolve();
      });
      mapkit.addEventListener('error', () => {
        clearTimeout(timeout);
        reject(new Error('MapKit JS rejected its token'));
      });
      mapkit.init({
        language: this.locale,
        authorizationCallback: (done: (token: string) => void) => {
          // The first call uses the token fetched above; every later one is a refresh.
          if (token) {
            done(token);
            token = null;
            return;
          }
          this.api.mapToken().subscribe({ next: (fresh) => done(fresh.token), error: () => done('') });
        },
      });
    });
    await mapkit.load(LIBRARIES);
    return mapkit;
  }

  async create(element: HTMLElement, start: Viewport, callbacks: MapCallbacks): Promise<MapHandle> {
    const mapkit = await this.mapkit();
    const schemes = mapkit.ColorScheme ?? mapkit.Map?.ColorSchemes ?? {};
    const dark = window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
    const map = new mapkit.Map(element, {
      region: region(mapkit, start),
      colorScheme: dark ? schemes.Dark : schemes.Light,
      showsPointsOfInterest: true,
      showsUserLocationControl: true,
    });
    return new MapKitHandle(mapkit, map, callbacks);
  }

  async autocomplete(query: string, near: Viewport): Promise<PlaceSuggestion[]> {
    const mapkit = await this.mapkit();
    const search = new mapkit.Search({ language: this.locale, region: region(mapkit, near) });
    return new Promise((resolve) => {
      search.autocomplete(query, (error: unknown, data: { results?: { displayLines?: string[] }[] }) => {
        if (error || !data?.results) return resolve([]);
        resolve(
          data.results.map((result) => ({
            title: result.displayLines?.[0] ?? '',
            subtitle: result.displayLines?.slice(1).join(', ') ?? '',
            ref: result,
          })),
        );
      });
    });
  }

  async resolve(suggestion: PlaceSuggestion): Promise<Place | null> {
    const mapkit = await this.mapkit();
    const search = new mapkit.Search({ language: this.locale });
    return new Promise((resolve) => {
      search.search(suggestion.ref, (error: unknown, data: { places?: MapKitPlace[] }) => {
        const place = data?.places?.[0];
        if (error || !place?.coordinate) return resolve(null);
        resolve({
          name: place.name ?? suggestion.title,
          latitude: place.coordinate.latitude,
          longitude: place.coordinate.longitude,
          latitudeSpan: place.region?.span?.latitudeDelta,
          longitudeSpan: place.region?.span?.longitudeDelta,
        });
      });
    });
  }
}

interface MapKitPlace {
  name?: string;
  coordinate?: { latitude: number; longitude: number };
  region?: { span?: { latitudeDelta: number; longitudeDelta: number } };
}

function region(mapkit: MapKit, viewport: Viewport): unknown {
  return new mapkit.CoordinateRegion(
    new mapkit.Coordinate(viewport.latitude, viewport.longitude),
    new mapkit.CoordinateSpan(viewport.latitudeSpan, viewport.longitudeSpan),
  );
}

class MapKitHandle implements MapHandle {
  /** Drawn annotations by pin id, with the signature they were drawn from, so an update replaces only what changed. */
  private readonly drawn = new Map<string, { annotation: unknown; signature: string }>();

  constructor(
    private readonly mapkit: MapKit,
    private readonly map: MapKit,
    callbacks: MapCallbacks,
  ) {
    map.addEventListener('region-change-end', () => callbacks.regionChanged(this.viewport()));
    map.addEventListener('select', (event: { annotation?: { data?: { id?: string } } }) => {
      const id = event.annotation?.data?.id;
      // Consumed at once, so no pin stays highlighted behind the station panel (as on iOS).
      map.selectedAnnotation = null;
      if (id) callbacks.pinSelected(id);
    });
  }

  setPins(pins: readonly MapPinView[]): void {
    const wanted = new Map(pins.map((pin) => [pin.id, pin]));
    const removed: unknown[] = [];
    for (const [id, entry] of this.drawn) {
      const pin = wanted.get(id);
      if (!pin || signature(pin) !== entry.signature) {
        removed.push(entry.annotation);
        this.drawn.delete(id);
      }
    }
    if (removed.length) this.map.removeAnnotations(removed);
    const added: unknown[] = [];
    for (const pin of pins) {
      if (this.drawn.has(pin.id)) continue;
      const annotation = new this.mapkit.MarkerAnnotation(new this.mapkit.Coordinate(pin.latitude, pin.longitude), {
        color: pin.color,
        glyphText: pin.glyph || (pin.liveAvailable ? String(pin.liveAvailable) : '⚡'),
        title: pin.title,
        titleVisibility: this.mapkit.FeatureVisibility?.Hidden ?? 'hidden',
        subtitleVisibility: this.mapkit.FeatureVisibility?.Hidden ?? 'hidden',
        data: { id: pin.id },
      });
      this.drawn.set(pin.id, { annotation, signature: signature(pin) });
      added.push(annotation);
    }
    if (added.length) this.map.addAnnotations(added);
  }

  setViewport(viewport: Viewport, animated: boolean): void {
    this.map.setRegionAnimated(region(this.mapkit, viewport), animated);
  }

  viewport(): Viewport {
    const current = this.map.region;
    return {
      latitude: current.center.latitude,
      longitude: current.center.longitude,
      latitudeSpan: current.span.latitudeDelta,
      longitudeSpan: current.span.longitudeDelta,
    };
  }

  destroy(): void {
    this.map.destroy();
    this.drawn.clear();
  }
}

function signature(pin: MapPinView): string {
  return `${pin.latitude},${pin.longitude},${pin.color},${pin.glyph},${pin.title},${pin.liveAvailable ?? ''}`;
}
