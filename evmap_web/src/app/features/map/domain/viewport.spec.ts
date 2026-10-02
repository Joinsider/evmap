import { DEFAULT_SETTINGS, stationFilter } from './station-settings';
import { MAX_RADIUS_KM, Viewport, bounds, effectiveFilter, isCovered, isOverview, radiusKm } from './viewport';

describe('viewport', () => {
  const stuttgart: Viewport = { latitude: 48.78, longitude: 9.18, latitudeSpan: 0.2, longitudeSpan: 0.3 };

  it('covers the corners with the circumcircle', () => {
    const latKm = 0.2 * 111;
    const lonKm = 0.3 * 111 * Math.cos((48.78 * Math.PI) / 180);
    expect(radiusKm(stuttgart)).toBeCloseTo(Math.hypot(latKm, lonKm) / 2, 6);
  });

  it('clamps the radius to the backend ceiling and to at least a kilometre', () => {
    expect(radiusKm({ latitude: 0, longitude: 0, latitudeSpan: 80, longitudeSpan: 160 })).toBe(MAX_RADIUS_KM);
    expect(radiusKm({ latitude: 48, longitude: 9, latitudeSpan: 0.0001, longitudeSpan: 0.0001 })).toBe(1);
  });

  it('raises the power floor to 100 kW above 1.2° only, and never lowers the user\'s own', () => {
    const filter = stationFilter(DEFAULT_SETTINGS);
    const wide = { ...stuttgart, latitudeSpan: 2 };
    expect(isOverview(stuttgart)).toBe(false);
    expect(effectiveFilter(stuttgart, filter).minimumPowerKw).toBeUndefined();
    expect(effectiveFilter(wide, filter).minimumPowerKw).toBe(100);
    expect(effectiveFilter(wide, { ...filter, minimumPowerKw: 150 }).minimumPowerKw).toBe(150);
  });

  it('gives the visible box for live availability', () => {
    expect(bounds(stuttgart)).toEqual({ latMin: 48.68, lonMin: 9.03, latMax: 48.88, lonMax: 9.33 });
  });

  it('skips a request for a small drift at the same scale, but not across the overview threshold or a big zoom', () => {
    const nudged = { ...stuttgart, latitude: 48.79 };
    expect(isCovered(nudged, stuttgart)).toBe(true);
    expect(isCovered({ ...stuttgart, latitude: 48.9 }, stuttgart)).toBe(false);
    expect(isCovered({ ...stuttgart, latitudeSpan: 0.3 }, stuttgart)).toBe(false);
    expect(isCovered({ ...stuttgart, latitudeSpan: 1.3 }, { ...stuttgart, latitudeSpan: 1.15 })).toBe(false);
  });
});
