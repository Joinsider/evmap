import { StationAvailability } from '../../../core/api/models';
import { isKnown, isUsable, liveLabel, liveState, occupancy, serviceStateLabel } from './availability';

describe('availability', () => {
  it('names service states and shows an unknown token as it is', () => {
    expect(serviceStateLabel('OPERATIONAL')).toBe('In Betrieb');
    expect(serviceStateLabel('PLANNED')).toBe('PLANNED');
    expect(isUsable('OPERATIONAL')).toBe(true);
    expect(isUsable('PLANNED')).toBe(false);
  });

  it('reads an unknown live token as unknown, never as a word in the "free" position', () => {
    expect(liveState('RESERVED')).toBe('unknown');
    expect(liveLabel(liveState('OCCUPIED'))).toBe('Belegt');
  });

  it('counts only charge points with an answer, and hides a summary of nothing', () => {
    const live: StationAvailability = { stationId: 's', status: 'AVAILABLE', available: 2, occupied: 1, outOfOrder: 1, unknown: 3, chargePoints: [], sources: [] };
    expect(occupancy(live)).toBe('2 von 4 Ladepunkten frei');
    expect(isKnown(live)).toBe(true);
    expect(isKnown({ ...live, status: 'UNKNOWN' })).toBe(false);
    expect(isKnown({ ...live, available: 0, occupied: 0, outOfOrder: 0 })).toBe(false);
  });

  it('names every known service and live state', () => {
    expect(['MAINTENANCE', 'OUT_OF_SERVICE'].map(serviceStateLabel)).toEqual(['In Wartung', 'Außer Betrieb']);
    expect(['AVAILABLE', 'OCCUPIED', 'OUT_OF_ORDER', 'UNKNOWN'].map((raw) => liveLabel(liveState(raw)))).toEqual(['Frei', 'Belegt', 'Gestört', 'Unbekannt']);
  });
});
