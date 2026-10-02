import { StationChargePoints } from '../../../core/api/models';
import { fromPrice, priceGroups, priceParts, registerSources, unpricedCount } from './prices';

const price = (energyPerKwh?: number, extra: object = {}) => ({ currency: 'EUR', energyPerKwh, timeFees: [], free: false, furtherFees: false, ...extra });

describe('prices', () => {
  const station: StationChargePoints = {
    stationId: 's1',
    cheapestEnergyPerKwh: 0.49,
    currency: 'EUR',
    sources: [{ name: 'MobiData BW', licence: 'dl-de/by-2.0' }],
    chargePoints: [
      { id: '1', operatorName: 'EnBW', connectors: [{ connectorType: 'CCS', powerKw: 150, quantity: 1 }], price: price(0.59, { observedAt: '2026-09-01T00:00:00Z', source: 'MobiData BW' }) },
      { id: '2', operatorName: 'EnBW', connectors: [{ connectorType: 'CCS', powerKw: 150, quantity: 1 }], price: price(0.59, { observedAt: '2026-09-15T00:00:00Z', source: 'MobiData BW' }) },
      { id: '3', operatorName: 'Partner AG', connectors: [{ connectorType: 'Type 2', powerKw: 22, quantity: 1 }], price: price(0.49, { source: 'IRVE' }) },
      { id: '4', operatorName: 'EnBW', connectors: [], price: undefined },
    ],
  };

  it('groups identical posts, keeps the newest date and names only a differing operator', () => {
    const groups = priceGroups(station, 'EnBW', 'de');
    expect(groups.map((group) => [group.count, group.plugs, group.showsOperator, group.observedAt])).toEqual([
      [2, 'CCS · 150 kW', false, '2026-09-15T00:00:00Z'],
      [1, 'Type 2 · 22 kW', true, undefined],
    ]);
    expect(unpricedCount(station)).toBe(1);
    expect(registerSources(station, groups)).toEqual(['IRVE']);
  });

  it('writes the parts of a price in German with up to three decimals', () => {
    const text = priceParts(price(0.371, { sessionFee: 1.5, timeFees: [{ fromMinute: 0, perMinute: 0.1 }, { fromMinute: 240, perMinute: 0.1 }, { fromMinute: 60 }] }), 'de');
    expect(text.map((part) => part.replace(/ /g, ' '))).toEqual([
      '0,371 €/kWh',
      'Startgebühr 1,50 €',
      '0,10 €/min',
      'ab Min. 240: 0,10 €/min',
      'ab Min. 60 zeitabhängige Gebühr',
    ]);
    expect(priceParts(price(undefined, { free: true }), 'de')).toEqual(['Kostenlos']);
  });

  it('says "ab" with the cheapest energy price, or "Kostenlos" when every charge point is free', () => {
    expect(fromPrice(station, 'de')!.replace(/ /g, ' ')).toBe('ab 0,49 €/kWh');
    const free: StationChargePoints = { ...station, chargePoints: [{ id: '1', connectors: [], price: price(undefined, { free: true }) }] };
    expect(fromPrice(free, 'de')).toBe('Kostenlos');
    expect(fromPrice({ ...station, cheapestEnergyPerKwh: undefined }, 'de')).toBeNull();
  });
});
