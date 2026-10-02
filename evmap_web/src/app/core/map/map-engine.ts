import { Viewport } from '../../features/map/domain/viewport';

/** A pin as the engine draws it; everything about its meaning is decided before it gets here. */
export interface MapPinView {
  id: string;
  latitude: number;
  longitude: number;
  color: string;
  /** Shown on the pin: the member count of a cluster, empty for a single station. */
  glyph: string;
  /** What a screen reader and the tooltip say. */
  title: string;
  /** Charge points free right now, when a live source covers the pin. */
  liveAvailable?: number;
}

export interface MapCallbacks {
  /** The camera settled after a gesture or a programmatic move — the debounce of ADR 0009. */
  regionChanged(viewport: Viewport): void;
  pinSelected(id: string): void;
}

export interface MapHandle {
  setPins(pins: readonly MapPinView[]): void;
  setViewport(viewport: Viewport, animated: boolean): void;
  viewport(): Viewport;
  destroy(): void;
}

/** One autocomplete suggestion. `ref` is the engine's own object, handed back to {@link MapEngine.resolve}. */
export interface PlaceSuggestion {
  title: string;
  subtitle: string;
  ref: unknown;
}

export interface Place {
  name: string;
  latitude: number;
  longitude: number;
  /** The place's own extent where the engine knows it (a city is wider than a street). */
  latitudeSpan?: number;
  longitudeSpan?: number;
}

/**
 * The seam to the map library — MapKit JS today (ADR 0023). Pages talk to this, never to `mapkit`, so the map can
 * be tested with a fake and swapped (MapLibre is the v3 candidate) without touching a feature. Search goes through
 * the same seam because MapKit JS's search is part of the same script and token.
 */
export abstract class MapEngine {
  /** Rejects when the map cannot be shown at all (no token configured, script blocked, init failed). */
  abstract create(element: HTMLElement, start: Viewport, callbacks: MapCallbacks): Promise<MapHandle>;

  abstract autocomplete(query: string, near: Viewport): Promise<PlaceSuggestion[]>;

  abstract resolve(suggestion: PlaceSuggestion): Promise<Place | null>;
}
