import { StationChargePoints } from '../../../core/api/models';
import { formatDays, fromPrice, hasFurtherFees, hasPrices, isFree, paymentLabel, priceGroups, priceLines, priceParts, pricesOf, registerSources, unpricedCount } from './prices';

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

  it('reads several prices, and the single price of an older backend', () => {
    const qr = price(0.5, { paymentMeans: ['qrCode'] });
    expect(pricesOf({ id: '1', connectors: [], price: price(0.59), prices: [qr, price(0.6)] })).toHaveLength(2);
    expect(pricesOf({ id: '1', connectors: [], price: qr })).toEqual([qr]);
    expect(pricesOf({ id: '1', connectors: [] })).toEqual([]);
  });

  it('writes ends, caps, time windows and weekdays as delivered', () => {
    const text = priceParts(
      price(undefined, {
        energyWindows: [{ perKwh: 0.49, window: { from: '08:00', to: '22:00' } }],
        timeFees: [
          { fromMinute: 240, toMinute: 390, perMinute: 0.1, cap: 15, window: { from: '08:00', to: '20:00', days: ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'sunday'] } },
          { fromMinute: 45, toMinute: 90 },
        ],
      }),
      'de',
    );
    expect(text.map((part) => part.replace(/\u00a0|\u202f/g, ' '))).toEqual([
      '0,49 €/kWh (08:00–22:00)',
      'Min. 240–390: 0,10 €/min, max. 15,00 € (Mo–Fr, So 08:00–20:00)',
      'Min. 45–90 zeitabhängige Gebühr',
    ]);
    expect(formatDays(['saturday', 'monday', 'tuesday'], 'de')).toBe('Mo, Di, Sa');
    expect(formatDays([], 'de')).toBe('');
  });

  it('leads several prices with how they are paid, never with a raw token', () => {
    const qr = price(0.5, { paymentMeans: ['qrCode', 'mobileAccount', 'qrCode'] });
    const unknown = price(0.5355, { paymentMeans: ['somethingNew'] });
    expect(paymentLabel(qr, 0)).toBe('QR-Code / App');
    expect(paymentLabel(unknown, 1)).toBe('Tarif 2');
    expect(priceLines([qr, unknown], 'de').map((line) => line.replace(/\u00a0|\u202f/g, ' '))).toEqual(['QR-Code / App: 0,50 €/kWh', 'Tarif 2: 0,536 €/kWh']);
    expect(priceLines([qr], 'de').map((line) => line.replace(/\u00a0|\u202f/g, ' '))).toEqual(['0,50 €/kWh']);
  });

  it('groups charge points with the same several prices; free means every price is free', () => {
    const qr = price(0.5, { paymentMeans: ['qrCode'] });
    const card = price(0.59, { paymentMeans: ['emv'], furtherFees: true, source: 'Grid & Co. GmbH via Mobilithek' });
    const several: StationChargePoints = {
      ...station,
      sources: [],
      chargePoints: [
        { id: '1', connectors: [], prices: [qr, card] },
        { id: '2', connectors: [], prices: [qr, card] },
        { id: '3', connectors: [], prices: [qr] },
      ],
    };
    const groups = priceGroups(several, undefined, 'de');
    expect(groups.map((group) => [group.count, group.prices.length, hasFurtherFees(group)])).toEqual([
      [2, 2, true],
      [1, 1, false],
    ]);
    expect(registerSources(several, groups)).toEqual(['Grid & Co. GmbH via Mobilithek']);
    expect(isFree(several)).toBe(false);
    const free: StationChargePoints = { ...several, chargePoints: [{ id: '1', connectors: [], prices: [price(undefined, { free: true }), price(undefined, { free: true })] }] };
    expect(isFree(free)).toBe(true);
    expect(hasPrices({ ...several, chargePoints: [{ id: '1', connectors: [], prices: [] }] })).toBe(false);
  });
});
