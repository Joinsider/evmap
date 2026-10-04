import { CommentPayload } from '../../../core/api/models';

/** The backend's limits (`CommentService.validate`, `station_comment.experience VARCHAR(32)`). */
export const MAX_BODY = 2000;
export const MAX_EXPERIENCE = 32;

/**
 * The paid price as typed, in euros: "21,40", "21.40", "21" or "21,4". iOS asks for cents; a browser form can take the
 * amount as people read it off the receipt. `undefined` for an empty field, `null` for anything that is not an amount.
 */
export function parsePrice(text: string): number | undefined | null {
  const value = text.trim().replace(/\s*€$/, '');
  if (!value) return undefined;
  const match = /^(\d{1,5})(?:[.,](\d{1,2}))?$/.exec(value);
  if (!match) return null;
  return Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'));
}

/** A stored price back in the form, in the page's decimal separator: 2140 → "21,40" (de) or "21.40" (en). */
export function priceInput(cents: number | undefined | null, locale: string): string {
  if (cents === undefined || cents === null) return '';
  return new Intl.NumberFormat(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: false }).format(cents / 100);
}

/** What the form sends, or `null` while it cannot be sent: blank or too long a text, or a price that is not one. */
export function commentPayload(body: string, experience: string, price: string): CommentPayload | null {
  const text = body.trim();
  const paid = parsePrice(price);
  const how = experience.trim();
  if (!text || text.length > MAX_BODY || paid === null || how.length > MAX_EXPERIENCE) return null;
  return { body: text, paidPriceCents: paid, experience: how || undefined };
}
