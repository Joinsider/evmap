import { Component, LOCALE_ID, computed, effect, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { EvmapApi } from '../../core/api/evmap-api';
import { DataSource, StationAvailability, StationChargePoints, StationComment, StationSummary } from '../../core/api/models';
import { isKnown, liveLabel, liveState, occupancy, serviceStateLabel } from './domain/availability';
import { formatDate, formatDateTime, formatPower } from './domain/format';
import { powerTier, tierColor, tierLabel } from './domain/power-tier';
import { PriceGroup, formatAmount, fromPrice, hasFurtherFees, hasPrices, priceGroups, priceLines, registerSources, unpricedCount } from './domain/prices';
import { StationSelection } from './station-selection';

/**
 * One station beside the map, under `/station/:id` so it can be linked and reloaded (ADR 0023). Detail, live state,
 * prices and comments load independently: a live or tariff source that is down leaves its section out, never the
 * station (ADR 0015, ADR 0022). Read-only in 8a; writing, reporting and favorites follow in 8b.
 */
@Component({
  selector: 'app-station-panel',
  imports: [RouterLink],
  templateUrl: './station.panel.html',
  styleUrl: './station.panel.scss',
})
export class StationPanel {
  private readonly api = inject(EvmapApi);
  private readonly selection = inject(StationSelection);
  protected readonly locale = inject(LOCALE_ID);

  /** From the route (`withComponentInputBinding`). */
  readonly id = input.required<string>();

  protected readonly noAddress = $localize`:@@station.addressUnavailable:Keine Adresse verfügbar`;
  protected readonly chargePointLabel = $localize`:@@station.live.chargePoint:Ladepunkt`;

  protected readonly detail = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.station(params) });
  protected readonly live = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.stationAvailability(params) });
  protected readonly prices = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.chargePoints(params) });
  protected readonly comments = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.comments(params) });

  protected readonly knownLive = computed(() => {
    const live = this.live.hasValue() ? this.live.value() : undefined;
    return live && isKnown(live) ? live : null;
  });
  protected readonly priced = computed(() => {
    const prices = this.prices.hasValue() ? this.prices.value() : undefined;
    return prices && hasPrices(prices) ? prices : null;
  });

  constructor() {
    effect(() => {
      const station = this.detail.hasValue() ? this.detail.value().station : null;
      if (station) this.selection.position.set({ latitude: station.latitude, longitude: station.longitude });
    });
  }

  protected address(station: StationSummary) {
    const place = [station.postalCode, station.city].filter(Boolean).join(' ');
    return [station.street, place].filter(Boolean).join(', ');
  }

  protected power(kw: number) {
    return formatPower(kw, this.locale);
  }

  protected tier(kw: number | undefined) {
    const tier = powerTier(kw);
    return tier ? { label: tierLabel(tier), color: tierColor(tier) } : null;
  }

  protected serviceState = serviceStateLabel;

  protected liveStateLabel(raw: string) {
    return liveLabel(liveState(raw));
  }

  protected liveStateClass(raw: string) {
    return `live-${liveState(raw)}`;
  }

  protected occupancy(live: StationAvailability) {
    return occupancy(live);
  }

  protected observedAt(iso: string) {
    const date = formatDateTime(iso, this.locale);
    return $localize`:@@station.live.observedAt:Zuletzt gemeldet ${date}:date:`;
  }

  protected unresolved(live: StationAvailability) {
    const count = live.unknown;
    return $localize`:@@station.live.unresolved:${count}:count: weitere Ladepunkte ohne Live-Daten`;
  }

  protected credit(source: DataSource) {
    return source.licence ? `${source.name} (${source.licence})` : source.name;
  }

  protected fromPrice(prices: StationChargePoints) {
    return fromPrice(prices, this.locale);
  }

  protected groups(prices: StationChargePoints, station: StationSummary) {
    return priceGroups(prices, station.operatorName, this.locale);
  }

  protected priceLines(group: PriceGroup) {
    return priceLines(group.prices, this.locale);
  }

  protected furtherFees(group: PriceGroup) {
    return hasFurtherFees(group);
  }

  protected priceDetails(group: PriceGroup) {
    const parts: string[] = [];
    const count = group.count;
    const times = count > 1 ? $localize`:@@price.chargePointCount:${count}:count: ×` : '';
    const plugs = [times, group.plugs].filter(Boolean).join(' ');
    if (plugs) parts.push(plugs);
    if (group.showsOperator && group.operatorName) parts.push(group.operatorName);
    if (group.observedAt) {
      const date = formatDate(group.observedAt, this.locale);
      parts.push($localize`:@@price.observedAt:Stand ${date}:date:`);
    }
    return parts.length ? parts.join(' · ') : $localize`:@@station.live.chargePoint:Ladepunkt`;
  }

  protected unpriced(prices: StationChargePoints) {
    const count = unpricedCount(prices);
    return count ? $localize`:@@price.unpriced:Ladepunkte ohne bekannten Preis: ${count}:count:` : null;
  }

  protected registerSources(prices: StationChargePoints, groups: PriceGroup[]) {
    return registerSources(prices, groups).join(', ');
  }

  /** "28.09.2026 · Bezahlt: 21,40 € · Schnell": when, what was paid, how it went. */
  protected commentMeta(comment: StationComment) {
    const parts = [formatDate(comment.createdAt, this.locale)];
    if (comment.paidPriceCents !== undefined && comment.paidPriceCents !== null) {
      const amount = formatAmount(comment.paidPriceCents / 100, 'EUR', this.locale);
      parts.push($localize`:@@comments.paid:Bezahlt: ${amount}:amount:`);
    }
    if (comment.experience) parts.push(comment.experience);
    return parts.join(' · ');
  }
}
