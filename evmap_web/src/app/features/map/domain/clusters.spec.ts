import { StationSummary } from '../../../core/api/models';
import { clusterStations } from './clusters';

const station = (id: string, latitude: number, longitude: number, maxPowerKw?: number): StationSummary => ({ id, displayName: id, latitude, longitude, maxPowerKw });

describe('clusterStations', () => {
  it('keeps distant stations apart and folds neighbours into one pin on their centroid, coloured by the strongest', () => {
    const pins = clusterStations([station('a', 48.0001, 9.0001, 11), station('b', 48.0003, 9.0003, 300), station('c', 49, 10)], 1.4);
    expect(pins).toHaveLength(2);
    const cluster = pins.find((pin) => pin.stations.length === 2)!;
    expect(cluster.id.startsWith('cluster-')).toBe(true);
    expect(cluster.latitude).toBeCloseTo(48.0002, 6);
    expect(cluster.maxPowerKw).toBe(300);
    const single = pins.find((pin) => pin.stations.length === 1)!;
    expect(single.id).toBe('c');
    expect(single.maxPowerKw).toBeUndefined();
  });

  it('sums live availability over a pin\'s members, absent when none is covered', () => {
    const live = new Map([['a', { stationId: 'a', status: 'AVAILABLE', available: 2, occupied: 0, outOfOrder: 0, unknown: 0, chargePoints: [], sources: [] }]]);
    const pins = clusterStations([station('a', 48.0001, 9.0001), station('b', 48.0002, 9.0002), station('c', 49, 10)], 1.4, live);
    expect(pins.find((pin) => pin.stations.length === 2)!.liveAvailable).toBe(2);
    expect(pins.find((pin) => pin.id === 'c')!.liveAvailable).toBeUndefined();
  });

  it('does not reshuffle membership when the map pans at the same zoom', () => {
    const stations = [station('a', 48.01, 9.01), station('b', 48.02, 9.02), station('c', 48.5, 9.5)];
    const ids = (span: number) => clusterStations(stations, span).map((pin) => pin.stations.map((s) => s.id).join()).sort();
    expect(ids(1.4)).toEqual(ids(1.4));
    expect(ids(0.001)).toEqual(['a', 'b', 'c']);
  });
});
