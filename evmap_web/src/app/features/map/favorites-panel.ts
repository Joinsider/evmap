import { Component, inject, output } from '@angular/core';
import { StationSummary } from '../../core/api/models';
import { FavoritesStore } from './data/favorites.store';

/**
 * The favorite stations as a list over the map (ADR 0021, ADR 0023) — the web counterpart of the iOS sheet. Picking one
 * hands it to the map, which centers on it and opens it; the footer says where the list lives.
 */
@Component({
  selector: 'app-favorites-panel',
  template: `
    <dialog open class="panel" aria-labelledby="favorites-title">
      <header>
        <h2 id="favorites-title" i18n="@@favorites.title">Favoriten</h2>
        <button type="button" class="primary" (click)="closed.emit()" i18n="@@filter.done">Fertig</button>
      </header>
      @if (favorites.failed()) {
        <p class="error" role="alert" i18n="@@favorites.failed">Die Favoriten konnten nicht mit deinem Konto abgeglichen werden.</p>
      }
      <ul class="list">
        @for (station of favorites.stations(); track station.id) {
          <li>
            <button type="button" class="open" (click)="chosen.emit(station)">
              <span class="name">{{ station.displayName }}</span>
              @if (details(station); as text) {
                <span class="muted">{{ text }}</span>
              }
            </button>
            <button type="button" class="link" (click)="remove(station)" i18n="@@favorites.removeShort">Entfernen</button>
          </li>
        } @empty {
          <li class="muted" i18n="@@favorites.empty.web">Noch keine Favoriten. Klicke in einer Station auf den Stern.</li>
        }
      </ul>
      <p class="muted footer">
        @if (favorites.synced()) {
          <ng-container i18n="@@favorites.synced.web">Deine Favoriten sind mit deinem Konto synchronisiert. Beim Abmelden werden sie aus diesem Browser entfernt.</ng-container>
        } @else {
          <ng-container i18n="@@favorites.deviceOnly.web">Deine Favoriten sind nur in diesem Browser gespeichert. Melde dich an, um sie mit deinem Konto zu synchronisieren.</ng-container>
        }
      </p>
    </dialog>
  `,
  styleUrl: './favorites-panel.scss',
})
export class FavoritesPanel {
  protected readonly favorites = inject(FavoritesStore);

  readonly closed = output<void>();
  readonly chosen = output<StationSummary>();

  /** "EnBW · Hauptstr. 1, 70173 Stuttgart", as the iOS row reads. */
  protected details(station: StationSummary) {
    const place = [station.postalCode, station.city].filter(Boolean).join(' ');
    const address = [station.street, place].filter(Boolean).join(', ');
    return [station.operatorName, address].filter(Boolean).join(' · ');
  }

  protected remove(station: StationSummary) {
    void this.favorites.remove(station.id);
  }
}
