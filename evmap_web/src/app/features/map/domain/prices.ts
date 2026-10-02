import { AdHocPrice, Connector, StationChargePoints, TimeFee } from '../../../core/api/models';
import { formatPower } from './format';

/**
 * Ad-hoc prices as the station screen shows them (ADR 0022), the same grouping and wording as the iOS
 * `PriceFormatter` and `priceGroups`. Every amount is gross and shown as delivered; an absent amount is "not
 * published", never "free".
 */
export interface PriceGroup {
  operatorName?: string;
  /** Only where the operator differs from the station's; otherwise the header already names it. */
  showsOperator: boolean;
  /** "CCS · 150 kW", the plugs of one of the charge points. */
  plugs: string;
  count: number;
  price: AdHocPrice;
  observedAt?: string;
}

/** Up to three decimals: French registers state tenths of a cent, and rounding them away would change the price. */
export function formatAmount(value: number, currency: string, locale: string): string {
  return new Intl.NumberFormat(locale, { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 3 }).format(value);
}

export function plugsOf(connectors: readonly Connector[], locale: string): string {
  return connectors
    .map((connector) => [connector.connectorType, connector.powerKw !== undefined && connector.powerKw !== null ? formatPower(connector.powerKw, locale) : null].filter(Boolean).join(' · '))
    .join(', ');
}

/** The amounts alone, regardless of when and by whom they were stated: what makes two charge points one row. */
function amountsKey(price: AdHocPrice): string {
  return JSON.stringify([price.currency, price.energyPerKwh ?? null, price.sessionFee ?? null, price.timeFees.map((fee) => [fee.fromMinute, fee.perMinute ?? null]), price.free, price.furtherFees]);
}

/** Priced charge points grouped by operator, plugs and price, in the order their first charge point appears. */
export function priceGroups(prices: StationChargePoints, stationOperator: string | undefined, locale: string): PriceGroup[] {
  const groups: (PriceGroup & { key: string })[] = [];
  for (const chargePoint of prices.chargePoints) {
    const price = chargePoint.price;
    if (!price) continue;
    const plugs = plugsOf(chargePoint.connectors, locale);
    const key = `${chargePoint.operatorName ?? ''}|${plugs}|${amountsKey(price)}`;
    const existing = groups.find((group) => group.key === key);
    if (existing) {
      existing.count += 1;
      if (price.observedAt && (!existing.observedAt || price.observedAt > existing.observedAt)) existing.observedAt = price.observedAt;
    } else {
      const differs = !!chargePoint.operatorName && chargePoint.operatorName.toLocaleLowerCase() !== (stationOperator ?? '').toLocaleLowerCase();
      groups.push({ key, operatorName: chargePoint.operatorName, showsOperator: differs, plugs, count: 1, price, observedAt: price.observedAt });
    }
  }
  return groups.map(({ key: _key, ...group }) => group);
}

export function hasPrices(prices: StationChargePoints): boolean {
  return prices.chargePoints.some((chargePoint) => !!chargePoint.price);
}

export function isFree(prices: StationChargePoints): boolean {
  return hasPrices(prices) && prices.chargePoints.every((chargePoint) => chargePoint.price?.free === true);
}

export function unpricedCount(prices: StationChargePoints): number {
  return prices.chargePoints.filter((chargePoint) => !chargePoint.price).length;
}

/** The parts of a price, each a short phrase, in reading order: "0,59 €/kWh", "Startgebühr 1,50 €", … */
export function priceParts(price: AdHocPrice, locale: string): string[] {
  if (price.free) return [$localize`:@@price.free:Kostenlos`];
  const parts: string[] = [];
  if (price.energyPerKwh !== undefined && price.energyPerKwh !== null) {
    const amount = formatAmount(price.energyPerKwh, price.currency, locale);
    parts.push($localize`:@@price.perKwh:${amount}:amount:/kWh`);
  }
  if (price.sessionFee !== undefined && price.sessionFee !== null) {
    const amount = formatAmount(price.sessionFee, price.currency, locale);
    parts.push($localize`:@@price.sessionFee:Startgebühr ${amount}:amount:`);
  }
  for (const fee of price.timeFees) parts.push(timeFee(fee, price.currency, locale));
  return parts;
}

export function timeFee(fee: TimeFee, currency: string, locale: string): string {
  const minute = fee.fromMinute;
  if (fee.perMinute !== undefined && fee.perMinute !== null) {
    const amount = formatAmount(fee.perMinute, currency, locale);
    return minute === 0 ? $localize`:@@price.perMinute:${amount}:amount:/min` : $localize`:@@price.perMinuteFrom:ab Min. ${minute}:minute:: ${amount}:amount:/min`;
  }
  return minute === 0 ? $localize`:@@price.timeBased:zzgl. zeitabhängiger Gebühr` : $localize`:@@price.timeBasedFrom:ab Min. ${minute}:minute: zeitabhängige Gebühr`;
}

/** "ab 0,49 €/kWh" for the top of the station screen, "Kostenlos", or nothing. */
export function fromPrice(prices: StationChargePoints, locale: string): string | null {
  if (isFree(prices)) return $localize`:@@price.free:Kostenlos`;
  if (prices.cheapestEnergyPerKwh === undefined || prices.cheapestEnergyPerKwh === null) return null;
  const amount = formatAmount(prices.cheapestEnergyPerKwh, prices.currency ?? 'EUR', locale);
  return $localize`:@@price.fromPerKwh:ab ${amount}:amount:/kWh`;
}

/** Register sources credited by their token, minus the live sources the response credits in full. */
export function registerSources(prices: StationChargePoints, groups: readonly PriceGroup[]): string[] {
  const live = new Set(prices.sources.map((source) => source.name));
  return [...new Set(groups.map((group) => group.price.source).filter((source): source is string => !!source && !live.has(source)))].sort((a, b) => a.localeCompare(b));
}
