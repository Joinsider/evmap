import { Component, LOCALE_ID, OnInit, inject, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Subject, catchError, debounceTime, distinctUntilChanged, map, of, startWith, switchMap } from 'rxjs';
import { EvmapApi } from '../../core/api/evmap-api';
import { Operator } from '../../core/api/models';
import { StationSettingsStore } from './data/station-settings.store';
import { formatPower } from './domain/format';
import {
  CONNECTOR_TYPES,
  ConnectorType,
  POWER_STEPS,
  ProviderPreference,
  SELECTABLE_PREFERENCES,
  configuredProviders,
  connectorLabel,
  preferenceFor,
  withPreference,
  withUnlistedProviders,
} from './domain/station-settings';

const DIRECTORY_LIMIT = 20;

/**
 * The station filter and the provider preferences of ADR 0014, as one panel beside the map. Every change is stored
 * at once; the map reloads when the panel closes, not on each tick of the slider.
 *
 * The provider list is personal, not a directory: it holds the networks this person picked, and a search below adds
 * one. `listed` is view state on purpose, as on iOS — a row switched back to the default stays visible until the
 * panel is opened again, instead of vanishing under the pointer.
 */
@Component({
  selector: 'app-filter-panel',
  templateUrl: './filter-panel.html',
  styleUrl: './filter-panel.scss',
})
export class FilterPanel implements OnInit {
  private readonly api = inject(EvmapApi);
  private readonly store = inject(StationSettingsStore);
  private readonly locale = inject(LOCALE_ID);

  readonly closed = output<void>();

  protected readonly settings = this.store.settings;
  protected readonly connectors = CONNECTOR_TYPES.map((type) => ({ type, label: connectorLabel(type) }));
  protected readonly steps = POWER_STEPS.length - 1;
  protected readonly selectable = SELECTABLE_PREFERENCES;
  protected readonly listed = signal<string[]>([]);
  protected readonly confirmingReset = signal(false);
  protected readonly providerQuery = signal('');

  private readonly queries = new Subject<string>();
  protected readonly directory = toSignal(
    this.queries.pipe(
      startWith(''),
      debounceTime(250),
      map((query) => query.trim()),
      distinctUntilChanged(),
      switchMap((query) =>
        this.api.operators(query, DIRECTORY_LIMIT).pipe(
          map((operators): { query: string; operators: Operator[] | null } => ({ query, operators })),
          catchError(() => of({ query, operators: null })),
        ),
      ),
    ),
    { initialValue: { query: '', operators: [] as Operator[] | null } },
  );

  ngOnInit() {
    this.listed.set(configuredProviders(this.settings()));
  }

  protected hasConnector(type: ConnectorType) {
    return this.settings().connectorTypes.includes(type);
  }

  protected toggleConnector(type: ConnectorType, on: boolean) {
    this.store.update((settings) => ({
      ...settings,
      connectorTypes: on ? [...settings.connectorTypes.filter((t) => t !== type), type] : settings.connectorTypes.filter((t) => t !== type),
    }));
  }

  /** Index of the highest step the stored power still satisfies, so an off-step value snaps down. */
  protected powerIndex() {
    const power = this.settings().minimumPowerKw;
    if (power === null) return 0;
    let index = 0;
    POWER_STEPS.forEach((step, i) => {
      if (step !== null && step <= power) index = i;
    });
    return index;
  }

  protected powerLabel() {
    const step = POWER_STEPS[this.powerIndex()];
    return step === null ? $localize`:@@filter.power.any:Beliebig` : formatPower(step, this.locale);
  }

  protected setPower(index: number) {
    this.store.update((settings) => ({ ...settings, minimumPowerKw: POWER_STEPS[Math.min(Math.max(index, 0), this.steps)] }));
  }

  protected setAvailabilityOnly(on: boolean) {
    this.store.update((settings) => ({ ...settings, availabilityOnly: on }));
  }

  protected showsUnlisted() {
    return this.settings().unlistedProviders !== 'hidden';
  }

  protected setShowsUnlisted(on: boolean) {
    this.store.update((settings) => withUnlistedProviders(settings, on ? 'shown' : 'hidden'));
  }

  protected preference(provider: string) {
    return preferenceFor(this.settings(), provider);
  }

  protected setPreference(provider: string, preference: ProviderPreference) {
    this.store.update((settings) => withPreference(settings, provider, preference));
  }

  protected preferenceLabel(preference: ProviderPreference) {
    switch (preference) {
      case 'shown':
        return $localize`:@@provider.preference.shown:Anzeigen`;
      case 'hidden':
        return $localize`:@@provider.preference.hidden:Nicht anzeigen`;
      case 'preferred':
        return $localize`:@@provider.preference.preferred:Bevorzugen`;
      case 'avoided':
        return $localize`:@@provider.preference.avoided:Meiden`;
    }
  }

  protected searchProviders(query: string) {
    this.providerQuery.set(query);
    this.queries.next(query);
  }

  protected add(provider: string) {
    if (!this.listed().includes(provider)) this.listed.update((names) => [provider, ...names]);
    this.providerQuery.set('');
    this.queries.next('');
  }

  /** "No opinion about this network": the row goes and so does the stored preference. */
  protected remove(provider: string) {
    this.store.update((settings) => withPreference(settings, provider, settings.unlistedProviders));
    this.listed.update((names) => names.filter((name) => name !== provider));
  }

  protected stationCount(operator: Operator) {
    const count = new Intl.NumberFormat(this.locale).format(operator.stationCount);
    return $localize`:@@provider.stationCount:${count}:count: Ladestationen`;
  }

  /** Filters and provider preferences only (ADR 0014). */
  protected reset() {
    this.store.reset();
    this.listed.set([]);
    this.confirmingReset.set(false);
  }
}
