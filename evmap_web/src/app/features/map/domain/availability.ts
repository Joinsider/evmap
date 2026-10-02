import { StationAvailability } from '../../../core/api/models';

/**
 * The register's service state (iOS `AvailabilityStatus`). An unknown token is shown as it is rather than hidden:
 * it is at least a description of a state.
 */
export function serviceStateLabel(raw: string): string {
  switch (raw) {
    case 'OPERATIONAL':
      return $localize`:@@station.availability.operational:In Betrieb`;
    case 'MAINTENANCE':
      return $localize`:@@station.availability.maintenance:In Wartung`;
    case 'OUT_OF_SERVICE':
      return $localize`:@@station.availability.outOfService:Außer Betrieb`;
    default:
      return raw;
  }
}

/** Whether a driver can expect to charge here; an unrecognised state is not a promise that the station works. */
export function isUsable(raw: string | undefined): boolean {
  return raw === 'OPERATIONAL';
}

export type LiveState = 'available' | 'occupied' | 'outOfOrder' | 'unknown';

/**
 * Live state of a charge point (iOS `LiveAvailability`). Unlike the service state an unknown token is *not* shown:
 * it would sit where a driver reads "free", and a word they cannot interpret there is worse than "unknown".
 */
export function liveState(raw: string): LiveState {
  switch (raw) {
    case 'AVAILABLE':
      return 'available';
    case 'OCCUPIED':
      return 'occupied';
    case 'OUT_OF_ORDER':
      return 'outOfOrder';
    default:
      return 'unknown';
  }
}

export function liveLabel(state: LiveState): string {
  switch (state) {
    case 'available':
      return $localize`:@@station.live.available:Frei`;
    case 'occupied':
      return $localize`:@@station.live.occupied:Belegt`;
    case 'outOfOrder':
      return $localize`:@@station.live.outOfOrder:Gestört`;
    case 'unknown':
      return $localize`:@@station.live.unknown:Unbekannt`;
  }
}

/** Charge points with a live answer; zero means nothing here is live data. */
export function resolvedCount(live: StationAvailability): number {
  return live.available + live.occupied + live.outOfOrder;
}

/** A summary of nothing is not shown at all rather than as an "unknown" row that reads as broken. */
export function isKnown(live: StationAvailability): boolean {
  return liveState(live.status) !== 'unknown' && resolvedCount(live) > 0;
}

/** "2 von 4 Ladepunkten frei", counting only charge points that have an answer. */
export function occupancy(live: StationAvailability): string {
  const available = live.available;
  const resolved = resolvedCount(live);
  return $localize`:@@station.live.occupancy:${available}:available: von ${resolved}:resolved: Ladepunkten frei`;
}
