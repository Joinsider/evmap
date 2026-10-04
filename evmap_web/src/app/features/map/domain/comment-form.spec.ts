import { MAX_BODY, MAX_EXPERIENCE, commentPayload, parsePrice, priceInput } from './comment-form';

describe('comment form', () => {
  it('reads a paid price in euros as people type it', () => {
    expect(parsePrice('')).toBeUndefined();
    expect(parsePrice('  ')).toBeUndefined();
    expect(parsePrice('21,40')).toBe(2140);
    expect(parsePrice('21.4')).toBe(2140);
    expect(parsePrice('21')).toBe(2100);
    expect(parsePrice('0,05')).toBe(5);
    expect(parsePrice('21,40 €')).toBe(2140);
    expect(parsePrice('-3')).toBeNull();
    expect(parsePrice('21,405')).toBeNull();
    expect(parsePrice('zwanzig')).toBeNull();
    expect(parsePrice('1.000,00')).toBeNull();
  });

  it('puts a stored price back in the page language', () => {
    expect(priceInput(2140, 'de')).toBe('21,40');
    expect(priceInput(2140, 'en')).toBe('21.40');
    expect(priceInput(123456, 'de')).toBe('1234,56');
    expect(priceInput(undefined, 'de')).toBe('');
    expect(priceInput(null, 'de')).toBe('');
  });

  it('sends trimmed text and leaves empty optional fields out', () => {
    expect(commentPayload('  Lief gut \n', '  ', '')).toEqual({ body: 'Lief gut', paidPriceCents: undefined, experience: undefined });
    expect(commentPayload('Lief gut', 'Schnell', '21,40')).toEqual({ body: 'Lief gut', paidPriceCents: 2140, experience: 'Schnell' });
  });

  it('cannot be sent blank, too long or with a price that is not one', () => {
    expect(commentPayload('   ', '', '')).toBeNull();
    expect(commentPayload('x'.repeat(MAX_BODY + 1), '', '')).toBeNull();
    expect(commentPayload('x'.repeat(MAX_BODY), '', '')).not.toBeNull();
    expect(commentPayload('Gut', 'x'.repeat(MAX_EXPERIENCE + 1), '')).toBeNull();
    expect(commentPayload('Gut', '', 'viel')).toBeNull();
  });
});
