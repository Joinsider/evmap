import { GeoBounds } from '../../../core/api/models';
import { StationFilter } from './station-settings';

/**
 * The map's visible rectangle, translated into the circular query the backend speaks — the web counterpart of the
 * iOS `MapViewport`, with the same numbers (ADR 0009). Every station on screen is inside the circumcircle; the
 * corners are overfetched on purpose, which is cheaper than a second request after every pan.
 */
export interface Viewport {
  latitude: number;
  longitude: number;
  /** Latitudinal extent in degrees: the zoom level in the form the clusterer needs. */
  latitudeSpan: number;
  longitudeSpan: number;
}

/** Backend ceiling (`StationService.MAX_RADIUS_KM`). */
export const MAX_RADIUS_KM = 1000;
/** Row ceiling per request; past it clustering carries the display anyway. */
export const MAX_STATIONS = 600;
/** Wider than this, only highpower chargers are asked for. */
export const OVERVIEW_LATITUDE_SPAN = 1.2;
export const OVERVIEW_MINIMUM_POWER_KW = 100;

const KM_PER_DEGREE = 111;

/** Germany at roughly 700 km, where the map opens before it knows anything else. */
export const START_VIEWPORT: Viewport = { latitude: 51.1, longitude: 10.4, latitudeSpan: 7, longitudeSpan: 9 };

/** Radius covering the viewport's corners, clamped to what the backend accepts. */
export function radiusKm(viewport: Viewport): number {
  const latitudeKm = viewport.latitudeSpan * KM_PER_DEGREE;
  const longitudeKm = viewport.longitudeSpan * KM_PER_DEGREE * Math.max(Math.cos((viewport.latitude * Math.PI) / 180), 0.01);
  const diagonalKm = Math.hypot(latitudeKm, longitudeKm);
  return Math.min(Math.max(diagonalKm / 2, 1), MAX_RADIUS_KM);
}

export function isOverview(viewport: Viewport): boolean {
  return viewport.latitudeSpan > OVERVIEW_LATITUDE_SPAN;
}

/** The visible rectangle, for live availability, which is asked by box (ADR 0015). */
export function bounds(viewport: Viewport): GeoBounds {
  return {
    latMin: viewport.latitude - viewport.latitudeSpan / 2,
    lonMin: viewport.longitude - viewport.longitudeSpan / 2,
    latMax: viewport.latitude + viewport.latitudeSpan / 2,
    lonMax: viewport.longitude + viewport.longitudeSpan / 2,
  };
}

/** At overview scale the power floor is raised to the highpower threshold, never lowered below the user's own. */
export function effectiveFilter(viewport: Viewport, filter: StationFilter): StationFilter {
  if (!isOverview(viewport)) return filter;
  return { ...filter, minimumPowerKw: Math.max(filter.minimumPowerKw ?? 0, OVERVIEW_MINIMUM_POWER_KW) };
}

/**
 * Whether stations fetched for `loaded` still cover `next` well enough to skip a request: the same side of the
 * overview threshold, a scale change within ±25 %, and a drift below a quarter of the loaded radius.
 */
export function isCovered(next: Viewport, loaded: Viewport): boolean {
  if (isOverview(next) !== isOverview(loaded)) return false;
  const scale = next.latitudeSpan / loaded.latitudeSpan;
  if (scale < 0.8 || scale > 1.25) return false;
  return distanceKm(next.latitude, next.longitude, loaded.latitude, loaded.longitude) < radiusKm(loaded) * 0.25;
}

/** Great-circle distance, as `CLLocation.distance(from:)` measures it closely enough for the coverage check. */
export function distanceKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLon = (lon2 - lon1) * rad;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(a)));
}
