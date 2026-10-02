/** "150 kW" — the number formatted first, so the unit is never lost (the iOS bug of ADR 0009, §6). */
export function formatPower(kw: number, locale: string): string {
  const value = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(kw);
  return $localize`:@@station.power:${value}:value: kW`;
}

/** A date as the station screen dates prices and live readings: numeric, in the page's language. */
export function formatDate(iso: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(iso));
}

export function formatDateTime(iso: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'short', timeStyle: 'short' }).format(new Date(iso));
}
