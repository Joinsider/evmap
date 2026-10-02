import { Viewport } from '../features/map/domain/viewport';
import { MapCallbacks, MapEngine, MapHandle, MapPinView, Place, PlaceSuggestion } from '../core/map/map-engine';

/** A map that draws nothing and remembers everything, for the map page's tests. */
export class FakeMapEngine extends MapEngine {
  unavailable = false;
  callbacks: MapCallbacks | null = null;
  pins: readonly MapPinView[] = [];
  current: Viewport | null = null;
  moves: { viewport: Viewport; animated: boolean }[] = [];
  suggestions: PlaceSuggestion[] = [];
  queries: string[] = [];
  places = new Map<string, Place>();

  async create(_element: HTMLElement, start: Viewport, callbacks: MapCallbacks): Promise<MapHandle> {
    if (this.unavailable) throw new Error('no token');
    this.callbacks = callbacks;
    this.current = start;
    return {
      setPins: (pins) => (this.pins = pins),
      setViewport: (viewport, animated) => {
        this.moves.push({ viewport, animated });
        this.current = viewport;
      },
      viewport: () => this.current!,
      destroy: () => (this.callbacks = null),
    };
  }

  /** What MapKit does after a gesture settles. */
  settle(viewport: Viewport) {
    this.current = viewport;
    this.callbacks!.regionChanged(viewport);
  }

  async autocomplete(query: string): Promise<PlaceSuggestion[]> {
    this.queries.push(query);
    return this.suggestions;
  }

  async resolve(suggestion: PlaceSuggestion): Promise<Place | null> {
    return this.places.get(suggestion.title) ?? null;
  }
}
