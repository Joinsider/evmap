import { TestBed } from '@angular/core/testing';
import { EvmapApi } from '../../../core/api/evmap-api';
import { StationSummary } from '../../../core/api/models';
import { AuthService } from '../../../core/auth/auth.service';
import { FakeEvmapApi } from '../../../testing/fake-evmap-api';
import { FAVORITES_KEY, FavoritesStore } from './favorites.store';

const station = (id: string): StationSummary => ({ id, displayName: `Station ${id}`, latitude: 48, longitude: 9 });

const tick = () => new Promise((resolve) => setTimeout(resolve));

describe('FavoritesStore', () => {
  let api: FakeEvmapApi;

  beforeEach(() => {
    localStorage.clear();
    api = new FakeEvmapApi();
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }] });
  });

  /** Creates the store the way the app does: after the initializer has settled the session. */
  async function create(signedIn = false) {
    api.hasSession = signedIn;
    await TestBed.inject(AuthService).restore();
    const store = TestBed.inject(FavoritesStore);
    TestBed.tick();
    await tick();
    return store;
  }

  function stored() {
    return JSON.parse(localStorage.getItem(FAVORITES_KEY)!);
  }

  it('keeps favorites in this browser while signed out, newest first, each once', async () => {
    const store = await create();

    await store.add(station('a'));
    await store.add(station('b'));
    await store.add(station('a'));

    expect(store.stations().map((s) => s.id)).toEqual(['b', 'a']);
    expect(store.isFavorite('a')).toBe(true);
    expect(stored()).toEqual({ stations: [station('b'), station('a')], synced: false });
    expect(api.calls).toEqual([]);
    expect(store.synced()).toBe(false);

    await store.toggle(station('a'));
    expect(store.stations().map((s) => s.id)).toEqual(['b']);
  });

  it('does not store what the wire carries beyond the map fields', async () => {
    const store = await create();

    await store.add({ ...station('a'), favoritedAt: '2026-10-04T10:00:00Z' } as StationSummary);

    expect(stored().stations[0].favoritedAt).toBeUndefined();
  });

  it('merges the browser list with the account at a restored session and marks it as the account copy', async () => {
    localStorage.setItem(FAVORITES_KEY, JSON.stringify({ stations: [station('local')], synced: false }));
    api.accountFavorites = [{ ...station('remote'), favoritedAt: '2026-10-01T00:00:00Z' }];

    const store = await create(true);

    expect(api.merges).toEqual([['local']]);
    expect(store.stations().map((s) => s.id)).toEqual(['local', 'remote']);
    expect(stored().synced).toBe(true);
    expect(store.synced()).toBe(true);
  });

  it('merges at a sign-in and empties the account copy at the sign-out', async () => {
    const store = await create();
    await store.add(station('local'));
    api.accountFavorites = [station('remote')];
    const auth = TestBed.inject(AuthService);

    api.hasSession = true;
    await auth.refreshAccount();
    TestBed.tick();
    await tick();
    expect(store.stations().map((s) => s.id)).toEqual(['local', 'remote']);

    await auth.signOut();
    TestBed.tick();
    expect(store.stations()).toEqual([]);
    expect(stored()).toEqual({ stations: [], synced: false });
  });

  it('empties an account copy left behind when the page starts signed out, but never a list that met no account', async () => {
    localStorage.setItem(FAVORITES_KEY, JSON.stringify({ stations: [station('a')], synced: true }));
    expect((await create()).stations()).toEqual([]);

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }] });
    localStorage.setItem(FAVORITES_KEY, JSON.stringify({ stations: [station('a')], synced: false }));
    expect((await create()).stations().map((s) => s.id)).toEqual(['a']);
  });

  it('writes changes through to the account when signed in', async () => {
    const store = await create(true);

    await store.add(station('a'));
    await store.remove('a');

    expect(api.calls).toEqual(['addFavorite:a', 'removeFavorite:a']);
    expect(store.failed()).toBe(false);
  });

  it('undoes a change the backend refuses and says so', async () => {
    const store = await create(true);
    await store.add(station('a'));
    api.rejectWrites = true;

    await store.add(station('b'));
    expect(store.stations().map((s) => s.id)).toEqual(['a']);
    expect(store.failed()).toBe(true);

    await store.remove('a');
    expect(store.stations().map((s) => s.id)).toEqual(['a']);
    expect(stored().stations.map((s: StationSummary) => s.id)).toEqual(['a']);
  });

  it('keeps the browser list when the merge fails and tries again at the next sign-in', async () => {
    localStorage.setItem(FAVORITES_KEY, JSON.stringify({ stations: [station('local')], synced: false }));
    api.rejectWrites = true;

    const store = await create(true);
    expect(store.stations().map((s) => s.id)).toEqual(['local']);
    expect(store.failed()).toBe(true);
    expect(stored().synced).toBe(false);

    api.rejectWrites = false;
    const auth = TestBed.inject(AuthService);
    auth.forget();
    TestBed.tick();
    await auth.refreshAccount();
    TestBed.tick();
    await tick();
    expect(api.merges).toHaveLength(2);
    expect(stored().synced).toBe(true);
  });

  it('drops broken entries and survives a broken or missing storage', async () => {
    localStorage.setItem(
      FAVORITES_KEY,
      JSON.stringify({ stations: [station('a'), { id: 'x' }, null, station('a'), { ...station('b'), latitude: 'north' }, station('c')], synced: 'yes' }),
    );
    expect(FavoritesStore.read()).toEqual({ stations: [station('a'), station('c')], synced: false });

    localStorage.setItem(FAVORITES_KEY, '{nope');
    expect(FavoritesStore.read()).toEqual({ stations: [], synced: false });

    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const store = await create();
    await store.add(station('a'));
    expect(store.stations().map((s) => s.id)).toEqual(['a']);
    getItem.mockRestore();
    setItem.mockRestore();
  });
});
