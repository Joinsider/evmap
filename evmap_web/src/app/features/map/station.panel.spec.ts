import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { EvmapApi } from '../../core/api/evmap-api';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { FavoritesStore } from './data/favorites.store';
import { StationPanel } from './station.panel';
import { StationSelection } from './station-selection';

describe('StationPanel', () => {
  let api: FakeEvmapApi;

  beforeEach(() => {
    localStorage.clear();
    api = new FakeEvmapApi();
    api.details.set('s1', {
      station: { id: 's1', displayName: 'EnBW Stuttgart', street: 'Hauptstr. 1', postalCode: '70173', city: 'Stuttgart', operatorName: 'EnBW', latitude: 48.78, longitude: 9.18, availabilityStatus: 'OPERATIONAL', maxPowerKw: 150 },
      connectors: [{ connectorType: 'CCS', powerKw: 150, quantity: 2 }],
      sources: ['BNetzA', 'OCM'],
    });
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }, StationSelection, provideRouter([])] });
  });

  async function open(id = 's1') {
    const fixture = TestBed.createComponent(StationPanel);
    fixture.componentRef.setInput('id', id);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the station with its power tier, state, connectors and sources, and reports where it is', async () => {
    const root = await open();
    const text = root.textContent!.replace(/\s+/g, ' ');

    expect(text).toContain('EnBW Stuttgart');
    expect(text).toContain('Hauptstr. 1, 70173 Stuttgart');
    expect(text).toContain('150 kW · Highpower (DC)');
    expect(text).toContain('In Betrieb');
    expect(text).toContain('2 × CCS');
    expect(text).toContain('Datenquellen: BNetzA, OCM');
    expect(TestBed.inject(StationSelection).position()).toEqual({ latitude: 48.78, longitude: 9.18 });
  });

  it('leaves out live and price sections when their sources have nothing, without failing the station', async () => {
    const root = await open();

    expect(root.textContent).not.toContain('Live-Verfügbarkeit');
    expect(root.textContent).not.toContain('Ad-hoc-Preis');
    expect(root.textContent).toContain('Noch keine Kommentare.');
  });

  it('shows live occupancy with its age and credit, and prices with the from-price and disclaimer', async () => {
    api.liveByStation.set('s1', {
      stationId: 's1', status: 'AVAILABLE', available: 1, occupied: 1, outOfOrder: 0, unknown: 2, observedAt: '2026-10-02T10:00:00Z',
      chargePoints: [{ id: 'c1', evseId: 'DE*EBW*E1', status: 'AVAILABLE' }, { id: 'c2', evseId: 'DE*EBW*E2', status: 'OCCUPIED' }],
      sources: [{ name: 'MobiData BW', licence: 'dl-de/by-2.0', url: 'https://mobidata-bw.de' }],
    });
    api.prices.set('s1', {
      stationId: 's1', cheapestEnergyPerKwh: 0.59, currency: 'EUR', sources: [{ name: 'MobiData BW', licence: 'dl-de/by-2.0' }],
      chargePoints: [{ id: 'c1', operatorName: 'EnBW', connectors: [{ connectorType: 'CCS', powerKw: 150, quantity: 1 }], price: { currency: 'EUR', energyPerKwh: 0.59, timeFees: [], free: false, furtherFees: true, source: 'MobiData BW' } }, { id: 'c2', connectors: [] }],
    });
    const root = await open();
    const text = root.textContent!.replace(/\s+/g, ' ').replace(/ /g, ' ');

    expect(text).toContain('1 von 2 Ladepunkten frei');
    expect(text).toContain('Zuletzt gemeldet');
    const occupied = Array.from(root.querySelectorAll('.row')).find((row) => row.textContent!.includes('DE*EBW*E2'))!;
    expect(occupied.querySelector('.live-occupied')!.textContent).toBe('Belegt');
    expect(text).toContain('2 weitere Ladepunkte ohne Live-Daten');
    expect(root.querySelector('a[href="https://mobidata-bw.de"]')!.textContent).toBe('MobiData BW (dl-de/by-2.0)');
    expect(text).toContain('ab 0,59 €/kWh');
    expect(text).toContain('0,59 €/kWh');
    expect(text).toContain('Weitere Gebühren möglich');
    expect(text).toContain('Ladepunkte ohne bekannten Preis: 1');
    expect(text).toContain('Preis ohne Ladekarte');
  });

  it('shows several prices of a charge point, each led by how it is paid, with their limits and publisher', async () => {
    api.prices.set('s1', {
      stationId: 's1', cheapestEnergyPerKwh: 0.5, currency: 'EUR', sources: [],
      chargePoints: [{
        id: 'c1', connectors: [], prices: [
          { currency: 'EUR', energyPerKwh: 0.5, timeFees: [{ fromMinute: 240, toMinute: 390, perMinute: 0.1, cap: 15, window: { from: '08:00', to: '20:00' } }], free: false, furtherFees: false, paymentMeans: ['qrCode'], source: 'Grid & Co. GmbH via Mobilithek' },
          { currency: 'EUR', energyPerKwh: 0.59, timeFees: [], free: false, furtherFees: false, paymentMeans: ['emv'], source: 'Grid & Co. GmbH via Mobilithek' },
        ],
      }],
    });
    const root = await open();
    const lines = Array.from(root.querySelectorAll('.price strong')).map((line) => line.textContent!.replace(/\u00a0|\u202f/g, ' '));

    expect(lines).toEqual(['QR-Code: 0,50 €/kWh · Min. 240–390: 0,10 €/min, max. 15,00 € (08:00–20:00)', 'Kartenterminal: 0,59 €/kWh']);
    expect(root.textContent).toContain('Preisdaten: Grid & Co. GmbH via Mobilithek');
  });

  it('lists comments read-only', async () => {
    api.commentsByStation.set('s1', [{ id: 'k1', body: 'Lädt zuverlässig', paidPriceCents: 1290, experience: 'Schnell', createdAt: '2026-09-01T10:00:00Z', updatedAt: '2026-09-01T10:00:00Z', ownedByCurrentUser: false }]);
    const root = await open();
    const text = root.textContent!.replace(/\s+/g, ' ').replace(/ /g, ' ');

    expect(text).toContain('Lädt zuverlässig');
    expect(text).toContain('Bezahlt: 12,90 €');
    expect(root.querySelector('textarea, form')).toBeNull();
  });

  it('says so for a station that does not exist', async () => {
    const root = await open('missing');

    expect(root.textContent).toContain('Diese Station gibt es nicht');
  });

  it('marks the station as a favorite with the star and unmarks it again, signed out too', async () => {
    const root = await open();
    const star = root.querySelector('.star') as HTMLButtonElement;
    expect(star.getAttribute('aria-pressed')).toBe('false');
    expect(star.getAttribute('aria-label')).toBe('Zu Favoriten hinzufügen');

    const favorites = TestBed.inject(FavoritesStore);

    star.click();
    expect(favorites.stations()).toMatchObject([{ id: 's1', displayName: 'EnBW Stuttgart' }]);
    star.click();
    expect(favorites.isFavorite('s1')).toBe(false);
  });

  it('shows the error report entry below the comments', async () => {
    const root = await open();

    expect(root.querySelector('app-station-report-form')!.textContent).toContain('Zum Melden eines Fehlers bitte anmelden.');
  });
});
