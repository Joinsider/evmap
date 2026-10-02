import { StationAvailability, StationSummary } from '../../../core/api/models';

/** One pin: a single station or a group too close to draw apart (iOS `StationAnnotation`). */
export interface StationPin {
  id: string;
  latitude: number;
  longitude: number;
  stations: StationSummary[];
  /** The strongest member's power, so a lone HPC site stays visible inside a group of slow chargers. */
  maxPowerKw?: number;
  /** Charge points reported free across the members, absent when none of them is covered live. */
  liveAvailable?: number;
}

/** Roughly how many pins fit across the visible height before they start touching. */
const ROWS_PER_SCREEN = 14;

/**
 * Groups stations on an absolute lat/lon grid sized from the zoom, like the iOS `StationClusterer`: anchored to
 * coordinates rather than to the viewport, so panning never reshuffles membership — only zooming does. Cell width is
 * divided by cos(latitude) to compensate for the projection stretching longitude.
 */
export function clusterStations(
  stations: readonly StationSummary[],
  latitudeSpan: number,
  live: ReadonlyMap<string, StationAvailability> = new Map(),
): StationPin[] {
  if (stations.length === 0) return [];
  const cellHeight = latitudeSpan / ROWS_PER_SCREEN;
  if (!(cellHeight > 0)) return stations.map((station) => pin(station.id, [station], live));

  const cells = new Map<string, StationSummary[]>();
  for (const station of stations) {
    const cellWidth = cellHeight / Math.max(Math.cos((station.latitude * Math.PI) / 180), 0.01);
    const key = `${Math.floor(station.latitude / cellHeight)}-${Math.floor(station.longitude / cellWidth)}`;
    const members = cells.get(key);
    if (members) members.push(station);
    else cells.set(key, [station]);
  }
  return [...cells].map(([key, members]) => (members.length === 1 ? pin(members[0].id, members, live) : pin(`cluster-${key}`, members, live)));
}

function pin(id: string, members: StationSummary[], live: ReadonlyMap<string, StationAvailability>): StationPin {
  const powers = members.map((station) => station.maxPowerKw).filter((power): power is number => power !== undefined && power !== null);
  const covered = members.map((station) => live.get(station.id)).filter((entry): entry is StationAvailability => !!entry);
  return {
    id,
    // Centroid rather than cell centre: a cluster sits on its stations, not on a grid line.
    latitude: members.reduce((sum, station) => sum + station.latitude, 0) / members.length,
    longitude: members.reduce((sum, station) => sum + station.longitude, 0) / members.length,
    stations: members,
    maxPowerKw: powers.length ? Math.max(...powers) : undefined,
    liveAvailable: covered.length ? covered.reduce((sum, entry) => sum + entry.available, 0) : undefined,
  };
}
