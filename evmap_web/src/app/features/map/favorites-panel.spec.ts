import { TestBed } from '@angular/core/testing';
import { EvmapApi } from '../../core/api/evmap-api';
import { StationSummary } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { FAVORITES_KEY } from './data/favorites.store';
import { FavoritesPanel } from './favorites-panel';

const enbw: StationSummary = { id: 'a', displayName: 'EnBW Stuttgart', operatorName: 'EnBW', street: 'Hauptstr. 1', postalCode: '70173', city: 'Stuttgart', latitude: 48.78, longitude: 9.18 };
const bare: StationSummary = { id: 'b', displayName: 'Ohne Adresse', latitude: 48, longitude: 9 };

describe('FavoritesPanel', () => {
  let api: FakeEvmapApi;

  beforeEach(() => {
    localStorage.clear();
    api = new FakeEvmapApi();
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }] });
  });

  async function open(signedIn = false) {
    api.hasSession = signedIn;
    await TestBed.inject(AuthService).restore();
    const fixture = TestBed.createComponent(FavoritesPanel);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  }

  it('says there are none yet and that they stay in this browser', async () => {
    const { root } = await open();

    expect(root.textContent).toContain('Noch keine Favoriten.');
    expect(root.textContent).toContain('nur in diesem Browser gespeichert');
  });

  it('lists favorites with operator and address, hands a pick to the map and removes one', async () => {
    localStorage.setItem(FAVORITES_KEY, JSON.stringify({ stations: [enbw, bare], synced: false }));
    const { fixture, root } = await open();
    const chosen: StationSummary[] = [];
    let closed = 0;
    fixture.componentInstance.chosen.subscribe((station) => chosen.push(station));
    fixture.componentInstance.closed.subscribe(() => closed++);

    const rows = root.querySelectorAll('li');
    expect(rows[0].textContent).toContain('EnBW · Hauptstr. 1, 70173 Stuttgart');
    expect(rows[1].querySelector('.muted')).toBeNull();

    (rows[0].querySelector('.open') as HTMLButtonElement).click();
    expect(chosen.map((s) => s.id)).toEqual(['a']);

    (rows[1].querySelector('.link') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(root.querySelectorAll('li')).toHaveLength(1);

    (root.querySelector('header button') as HTMLButtonElement).click();
    expect(closed).toBe(1);
  });

  it('says the list is the account\'s when signed in', async () => {
    const { root } = await open(true);

    expect(root.textContent).toContain('mit deinem Konto synchronisiert');
  });
});
