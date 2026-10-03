import { AdHocPrice, Connector, StationChargePoint, StationChargePoints, TimeFee, TimeWindow } from '../../../core/api/models';
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
  /** Usually one; several where they differ by payment means, each then labelled. */
  prices: AdHocPrice[];
  observedAt?: string;
}

/** A charge point's prices: `prices` since L6p, the single `price` of an older backend otherwise. */
export function pricesOf(chargePoint: StationChargePoint): AdHocPrice[] {
  if (chargePoint.prices) return chargePoint.prices;
  return chargePoint.price ? [chargePoint.price] : [];
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
function amountsKey(prices: readonly AdHocPrice[]): string {
  return JSON.stringify(
    prices.map((price) => [
      price.currency,
      price.energyPerKwh ?? null,
      (price.energyWindows ?? []).map((window) => [window.perKwh, windowKey(window.window)]),
      price.sessionFee ?? null,
      price.timeFees.map((fee) => [fee.fromMinute, fee.toMinute ?? null, fee.perMinute ?? null, fee.cap ?? null, windowKey(fee.window)]),
      price.free,
      price.furtherFees,
      price.paymentMeans ?? [],
    ]),
  );
}

function windowKey(window: TimeWindow | undefined): unknown {
  return window ? [window.from, window.to, window.days ?? []] : null;
}

/** Priced charge points grouped by operator, plugs and prices, in the order their first charge point appears. */
export function priceGroups(prices: StationChargePoints, stationOperator: string | undefined, locale: string): PriceGroup[] {
  const groups: (PriceGroup & { key: string })[] = [];
  for (const chargePoint of prices.chargePoints) {
    const all = pricesOf(chargePoint);
    if (all.length === 0) continue;
    const plugs = plugsOf(chargePoint.connectors, locale);
    const key = `${chargePoint.operatorName ?? ''}|${plugs}|${amountsKey(all)}`;
    const observedAt = all.map((price) => price.observedAt).filter((at): at is string => !!at).sort().at(-1);
    const existing = groups.find((group) => group.key === key);
    if (existing) {
      existing.count += 1;
      if (observedAt && (!existing.observedAt || observedAt > existing.observedAt)) existing.observedAt = observedAt;
    } else {
      const differs = !!chargePoint.operatorName && chargePoint.operatorName.toLocaleLowerCase() !== (stationOperator ?? '').toLocaleLowerCase();
      groups.push({ key, operatorName: chargePoint.operatorName, showsOperator: differs, plugs, count: 1, prices: all, observedAt });
    }
  }
  return groups.map(({ key: _key, ...group }) => group);
}

export function hasPrices(prices: StationChargePoints): boolean {
  return prices.chargePoints.some((chargePoint) => pricesOf(chargePoint).length > 0);
}

export function isFree(prices: StationChargePoints): boolean {
  return hasPrices(prices) && prices.chargePoints.every((chargePoint) => {
    const all = pricesOf(chargePoint);
    return all.length > 0 && all.every((price) => price.free);
  });
}

export function unpricedCount(prices: StationChargePoints): number {
  return prices.chargePoints.filter((chargePoint) => pricesOf(chargePoint).length === 0).length;
}

/** Whether any price of the group lists fees that are not shown. */
export function hasFurtherFees(group: PriceGroup): boolean {
  return group.prices.some((price) => price.furtherFees);
}

/** The parts of a price, each a short phrase, in reading order: "0,59 €/kWh", "Startgebühr 1,50 €", … */
export function priceParts(price: AdHocPrice, locale: string): string[] {
  if (price.free) return [$localize`:@@price.free:Kostenlos`];
  const parts: string[] = [];
  if (price.energyPerKwh !== undefined && price.energyPerKwh !== null) {
    const amount = formatAmount(price.energyPerKwh, price.currency, locale);
    parts.push($localize`:@@price.perKwh:${amount}:amount:/kWh`);
  }
  for (const energy of price.energyWindows ?? []) {
    const amount = formatAmount(energy.perKwh, price.currency, locale);
    parts.push(withWindow($localize`:@@price.perKwh:${amount}:amount:/kWh`, energy.window, locale));
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
  const until = fee.toMinute;
  let text: string;
  if (fee.perMinute !== undefined && fee.perMinute !== null) {
    const amount = formatAmount(fee.perMinute, currency, locale);
    if (until !== undefined && until !== null) text = $localize`:@@price.perMinuteRange:Min. ${minute}:minute:–${until}:until:: ${amount}:amount:/min`;
    else text = minute === 0 ? $localize`:@@price.perMinute:${amount}:amount:/min` : $localize`:@@price.perMinuteFrom:ab Min. ${minute}:minute:: ${amount}:amount:/min`;
  } else if (until !== undefined && until !== null) {
    text = $localize`:@@price.timeBasedRange:Min. ${minute}:minute:–${until}:until: zeitabhängige Gebühr`;
  } else {
    text = minute === 0 ? $localize`:@@price.timeBased:zzgl. zeitabhängiger Gebühr` : $localize`:@@price.timeBasedFrom:ab Min. ${minute}:minute: zeitabhängige Gebühr`;
  }
  if (fee.cap !== undefined && fee.cap !== null) {
    const cap = formatAmount(fee.cap, currency, locale);
    text += ', ' + $localize`:@@price.cap:max. ${cap}:amount:`;
  }
  return fee.window ? withWindow(text, fee.window, locale) : text;
}

/** "0,10 €/min (Mo–Sa 08:00–20:00)". */
function withWindow(text: string, window: TimeWindow, locale: string): string {
  return `${text} (${formatWindow(window, locale)})`;
}

/** "Mo–Sa 08:00–20:00", "22:00–08:00": local time as the operator writes it. */
export function formatWindow(window: TimeWindow, locale: string): string {
  const times = `${window.from}–${window.to}`;
  const days = formatDays(window.days ?? [], locale);
  return days ? `${days} ${times}` : times;
}

const WEEK = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];

/** Weekdays in the locale's short form, runs of three or more joined: "Mo–Fr", "Sa, So". */
export function formatDays(days: readonly string[], locale: string): string {
  const format = new Intl.DateTimeFormat(locale, { weekday: 'short', timeZone: 'UTC' });
  // 2024-01-01 was a Monday.
  const symbol = (index: number) => format.format(new Date(Date.UTC(2024, 0, 1 + index))).replace(/\.$/, '');
  const indices = [...new Set(days.map((day) => WEEK.indexOf(day.toLowerCase())).filter((index) => index >= 0))].sort((a, b) => a - b);
  const runs: number[][] = [];
  for (const index of indices) {
    const last = runs.at(-1);
    if (last && last.at(-1) === index - 1) last.push(index);
    else runs.push([index]);
  }
  return runs.flatMap((run) => (run.length >= 3 ? [`${symbol(run[0])}–${symbol(run[run.length - 1])}`] : run.map(symbol))).join(', ');
}

/** How the price at `index` is paid: "QR-Code / App", or "Tarif 2" where the operator names nothing known. Never a raw token. */
export function paymentLabel(price: AdHocPrice, index: number): string {
  const labels = [...new Set((price.paymentMeans ?? []).map(paymentMeans).filter((label): label is string => !!label))];
  const number = index + 1;
  return labels.length ? labels.join(' / ') : $localize`:@@price.rateNumber:Tarif ${number}:number:`;
}

function paymentMeans(token: string): string | null {
  switch (token) {
    case 'qrCode':
      return $localize`:@@price.payment.qrCode:QR-Code`;
    case 'emv':
      return $localize`:@@price.payment.emv:Kartenterminal`;
    case 'nfc':
      return $localize`:@@price.payment.nfc:NFC`;
    case 'website':
      return $localize`:@@price.payment.website:Website`;
    case 'mobileAccount':
      return $localize`:@@price.payment.mobileAccount:App`;
    case 'paymentCreditCard':
      return $localize`:@@price.payment.creditCard:Kreditkarte`;
    case 'paymentDebitCard':
      return $localize`:@@price.payment.debitCard:Debitkarte`;
    default:
      return null;
  }
}

/** The lines of a group: one price as its parts; several, each led by how it is paid. */
export function priceLines(prices: readonly AdHocPrice[], locale: string): string[] {
  if (prices.length === 1) return [priceParts(prices[0], locale).join(' · ')];
  return prices.map((price, index) => `${paymentLabel(price, index)}: ${priceParts(price, locale).join(' · ')}`);
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
  return [...new Set(groups.flatMap((group) => group.prices.map((price) => price.source)).filter((source): source is string => !!source && !live.has(source)))].sort((a, b) =>
    a.localeCompare(b),
  );
}
