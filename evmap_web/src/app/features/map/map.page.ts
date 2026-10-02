import { AfterViewInit, Component, ElementRef, LOCALE_ID, OnDestroy, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { Subscription } from 'rxjs';
import { EvmapApi } from '../../core/api/evmap-api';
import { StationAvailability, StationSummary } from '../../core/api/models';
import { MapEngine, MapHandle, MapPinView, Place } from '../../core/map/map-engine';
import { StationSettingsStore } from './data/station-settings.store';
import { isUsable } from './domain/availability';
import { StationPin, clusterStations } from './domain/clusters';
import { formatPower } from './domain/format';
import { POWER_TIERS, legendLabel, powerTier, tierColor, tierLabel } from './domain/power-tier';
import { matchesNothing, stationFilter } from './domain/station-settings';
import { MAX_STATIONS, START_VIEWPORT, Viewport, bounds, effectiveFilter, isCovered, radiusKm } from './domain/viewport';
import { FilterPanel } from './filter-panel';
import { PlaceSearch } from './place-search';
import { StationSelection } from './station-selection';

/** Where the camera goes for a station opened by link, and for a place without an extent of its own. */
const CLOSE_SPAN = 0.05;

/**
 * The start page: MapKit JS map, place search, filter and the station panel as a child route (ADR 0023). Loading
 * follows ADR 0009 like on iOS — the settled camera is the query, a covered viewport loads nothing, a newer viewport
 * cancels the request in flight, pins are clustered on every camera change.
 */
@Component({
  selector: 'app-map',
  imports: [RouterOutlet, FilterPanel, PlaceSearch],
  providers: [StationSelection],
  templateUrl: './map.page.html',
  styleUrl: './map.page.scss',
})
export class MapPage implements AfterViewInit, OnDestroy {
  private readonly api = inject(EvmapApi);
  private readonly engine = inject(MapEngine);
  private readonly store = inject(StationSettingsStore);
  private readonly router = inject(Router);
  private readonly selection = inject(StationSelection);
  private readonly locale = inject(LOCALE_ID);
  private readonly canvas = viewChild.required<ElementRef<HTMLElement>>('canvas');

  protected readonly state = signal<'loading' | 'ready' | 'unavailable'>('loading');
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);
  protected readonly truncated = signal(false);
  protected readonly filterOpen = signal(false);
  protected readonly viewport = signal<Viewport>(START_VIEWPORT);
  protected readonly legend = POWER_TIERS.map(({ tier }) => ({ color: tierColor(tier), label: legendLabel(tier), name: tierLabel(tier) }));

  private readonly stations = signal<StationSummary[]>([]);
  private readonly live = signal<ReadonlyMap<string, StationAvailability>>(new Map());
  private pins: StationPin[] = [];
  private handle: MapHandle | null = null;
  private loaded: Viewport | null = null;
  private request: Subscription | null = null;
  private liveRequest: Subscription | null = null;

  constructor() {
    // A station opened by link (or reloaded) is brought into view once the panel knows where it is.
    effect(() => {
      const position = this.selection.position();
      if (!position || this.state() !== 'ready') return;
      untracked(() => {
        const box = bounds(this.handle!.viewport());
        const visible = position.latitude > box.latMin && position.latitude < box.latMax && position.longitude > box.lonMin && position.longitude < box.lonMax;
        if (!visible) this.handle!.setViewport({ ...position, latitudeSpan: CLOSE_SPAN, longitudeSpan: CLOSE_SPAN }, false);
      });
    });
  }

  ngAfterViewInit() {
    void this.start();
  }

  private async start() {
    try {
      this.handle = await this.engine.create(this.canvas().nativeElement, START_VIEWPORT, {
        regionChanged: (viewport) => this.regionChanged(viewport),
        pinSelected: (id) => this.pinSelected(id),
      });
    } catch {
      this.state.set('unavailable');
      return;
    }
    this.state.set('ready');
    this.regionChanged(this.handle.viewport());
  }

  ngOnDestroy() {
    this.request?.unsubscribe();
    this.liveRequest?.unsubscribe();
    this.handle?.destroy();
  }

  protected openFilter() {
    this.filterOpen.set(true);
  }

  /** Settings persist as they change; the map reloads only when the panel closes, as on iOS (ADR 0014). */
  protected closeFilter() {
    this.filterOpen.set(false);
    if (this.handle) this.load(this.handle.viewport());
  }

  /** The search moves the camera and nothing else; the settled camera then loads stations (ADR 0011). */
  protected placeChosen(place: Place) {
    this.handle?.setViewport(
      { latitude: place.latitude, longitude: place.longitude, latitudeSpan: place.latitudeSpan ?? CLOSE_SPAN, longitudeSpan: place.longitudeSpan ?? CLOSE_SPAN },
      true,
    );
  }

  private regionChanged(viewport: Viewport) {
    this.viewport.set(viewport);
    this.redraw();
    if (this.loaded && isCovered(viewport, this.loaded)) return;
    this.load(viewport);
  }

  private load(viewport: Viewport) {
    this.request?.unsubscribe();
    this.liveRequest?.unsubscribe();
    const filter = effectiveFilter(viewport, stationFilter(this.store.settings()));
    this.loaded = viewport;
    this.loadFailed.set(false);
    if (matchesNothing(filter)) {
      // An empty allowlist has no query-string form; sent, it would come back unrestricted (ADR 0014).
      this.stations.set([]);
      this.truncated.set(false);
      this.redraw();
      return;
    }
    this.loading.set(true);
    this.request = this.api
      .stations({
        latitude: viewport.latitude,
        longitude: viewport.longitude,
        radiusKm: radiusKm(viewport),
        limit: MAX_STATIONS,
        connectorTypes: filter.connectorTypes,
        minPowerKw: filter.minimumPowerKw,
        excludeOperators: filter.excludedProviders,
        includeOperators: filter.includedProviders,
      })
      .subscribe({
        next: (stations) => {
          this.loading.set(false);
          this.truncated.set(stations.length >= MAX_STATIONS);
          this.stations.set(filter.availabilityOnly ? stations.filter((station) => isUsable(station.availabilityStatus)) : stations);
          this.redraw();
          this.loadLive(viewport);
        },
        error: () => {
          this.loading.set(false);
          this.loadFailed.set(true);
          // Forget the viewport, so the next camera change tries again instead of counting as covered.
          this.loaded = null;
        },
      });
  }

  /** Live occupancy for the few stations a source covers; a failure leaves the pins as they are (ADR 0015). */
  private loadLive(viewport: Viewport) {
    this.liveRequest = this.api.availabilityInBounds(bounds(viewport)).subscribe({
      next: (entries) => {
        this.live.set(new Map(entries.map((entry) => [entry.stationId, entry])));
        this.redraw();
      },
      error: () => this.live.set(new Map()),
    });
  }

  private redraw() {
    if (!this.handle) return;
    this.pins = clusterStations(this.stations(), this.viewport().latitudeSpan, this.live());
    this.handle.setPins(this.pins.map((pin) => this.pinView(pin)));
  }

  private pinView(pin: StationPin): MapPinView {
    const count = pin.stations.length;
    const station = pin.stations[0];
    const title =
      count > 1
        ? $localize`:@@map.cluster:${count}:count: Ladestationen`
        : [station.displayName, pin.maxPowerKw !== undefined ? formatPower(pin.maxPowerKw, this.locale) : null].filter(Boolean).join(' · ');
    return {
      id: pin.id,
      latitude: pin.latitude,
      longitude: pin.longitude,
      color: tierColor(powerTier(pin.maxPowerKw)),
      glyph: count > 1 ? String(count) : '',
      title,
      liveAvailable: pin.liveAvailable,
    };
  }

  /** A cluster zooms to a quarter of the span around it; a single station opens its panel. */
  private pinSelected(id: string) {
    const pin = this.pins.find((candidate) => candidate.id === id);
    if (!pin) return;
    if (pin.stations.length > 1) {
      const current = this.viewport();
      this.handle?.setViewport(
        { latitude: pin.latitude, longitude: pin.longitude, latitudeSpan: current.latitudeSpan / 4, longitudeSpan: current.longitudeSpan / 4 },
        true,
      );
      return;
    }
    void this.router.navigate(['/station', pin.stations[0].id]);
  }
}
