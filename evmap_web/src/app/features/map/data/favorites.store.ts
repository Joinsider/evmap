import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';
import { EvmapApi } from '../../../core/api/evmap-api';
import { StationSummary } from '../../../core/api/models';
import { AuthService } from '../../../core/auth/auth.service';

/** One JSON value under one key, like the station settings. */
export const FAVORITES_KEY = 'evmap.favorites.v1';

/** What is stored: whole stations, so the list shows without a request, and whether it is the account's copy. */
interface StoredFavorites {
  stations: StationSummary[];
  /** True once the list was merged with an account: it then belongs to that account and leaves with the session. */
  synced: boolean;
}

/**
 * The favorites of the web client (ADR 0021, ADR 0023): in this browser's `localStorage` while signed out,
 * synchronized with the account once signed in — the port of the iOS `FavoritesViewModel`.
 *
 * Signing in merges both lists (nothing is lost on either side); when the session ends, a list that came from the
 * account is emptied, so a signed-out browser carries nothing of the account, which keeps its favorites for the next
 * sign-in. A list that never met an account is never emptied — the web counterpart of iOS not clearing at a signed-out
 * launch. Every change applies at once and is undone if the backend refuses it, so the star never lies.
 */
@Injectable({ providedIn: 'root' })
export class FavoritesStore {
  private readonly api = inject(EvmapApi);
  private readonly auth = inject(AuthService);

  private readonly state = signal<StoredFavorites>(FavoritesStore.read());
  /** The account the list was last merged with in this page's lifetime; a different one merges again. */
  private mergedWith: string | null = null;

  readonly stations = computed(() => this.state().stations);
  readonly ids = computed(() => new Set(this.stations().map((station) => station.id)));
  readonly synced = computed(() => this.auth.signedIn());
  /** Set when the backend refused a change or the merge; the list is already back to what the account holds. */
  readonly failed = signal(false);

  constructor() {
    // The app initializer has settled the session before anything injects this store, so the first run sees the
    // real state: a restored session merges, a signed-out start with an account's leftover list empties it.
    effect(() => {
      const account = this.auth.account();
      untracked(() => {
        if (account) {
          if (this.mergedWith !== account.id) void this.merge(account.id);
        } else {
          this.mergedWith = null;
          if (this.state().synced) this.save({ stations: [], synced: false });
        }
      });
    });
  }

  isFavorite(id: string): boolean {
    return this.ids().has(id);
  }

  toggle(station: StationSummary): Promise<void> {
    return this.isFavorite(station.id) ? this.remove(station.id) : this.add(station);
  }

  async add(station: StationSummary): Promise<void> {
    if (this.isFavorite(station.id)) return;
    await this.change({ ...this.state(), stations: [FavoritesStore.summary(station), ...this.stations()] }, () => this.api.addFavorite(station.id));
  }

  async remove(id: string): Promise<void> {
    if (!this.isFavorite(id)) return;
    await this.change({ ...this.state(), stations: this.stations().filter((station) => station.id !== id) }, () => this.api.removeFavorite(id));
  }

  private async change(next: StoredFavorites, write: () => Observable<void>) {
    const before = this.state();
    this.failed.set(false);
    this.save(next);
    if (!this.auth.signedIn()) return;
    try {
      await firstValueFrom(write(), { defaultValue: undefined });
    } catch {
      // A 401 also signs out (auth interceptor), which then empties the account's list anyway.
      if (this.state() === next) this.save(before);
      this.failed.set(true);
    }
  }

  private async merge(accountId: string) {
    this.mergedWith = accountId;
    try {
      const merged = await firstValueFrom(this.api.mergeFavorites(this.stations().map((station) => station.id)));
      // Signed out or switched accounts while the merge was under way: its answer belongs to nobody any more.
      if (this.auth.account()?.id !== accountId) return;
      this.save({ stations: merged.map(FavoritesStore.summary), synced: true });
    } catch {
      // The browser keeps its list; the next sign-in or page load merges again.
      if (this.mergedWith === accountId) this.mergedWith = null;
      this.failed.set(true);
    }
  }

  private save(next: StoredFavorites) {
    this.state.set(next);
    try {
      localStorage.setItem(FAVORITES_KEY, JSON.stringify(next));
    } catch {
      // Not persisted; the page keeps working with the list in memory.
    }
  }

  /** Only the map's fields: `favoritedAt` and whatever else the wire carries stay out of storage. */
  private static summary(station: StationSummary): StationSummary {
    const { id, displayName, street, city, postalCode, countryCode, operatorName, latitude, longitude, availabilityStatus, maxPowerKw } = station;
    return { id, displayName, street, city, postalCode, countryCode, operatorName, latitude, longitude, availabilityStatus, maxPowerKw };
  }

  /** Lenient, like the settings: a broken entry is dropped, a broken value costs the list, never the page. */
  static read(): StoredFavorites {
    try {
      const raw = JSON.parse(localStorage.getItem(FAVORITES_KEY) ?? 'null') as Partial<StoredFavorites> | null;
      const seen = new Set<string>();
      const stations = (Array.isArray(raw?.stations) ? raw.stations : []).filter(
        (station): station is StationSummary =>
          typeof station?.id === 'string' &&
          typeof station.displayName === 'string' &&
          Number.isFinite(station.latitude) &&
          Number.isFinite(station.longitude) &&
          !seen.has(station.id) &&
          !!seen.add(station.id),
      );
      return { stations, synced: raw?.synced === true };
    } catch {
      return { stations: [], synced: false };
    }
  }
}
