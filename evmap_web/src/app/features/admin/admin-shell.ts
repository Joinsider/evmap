import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

/** Frame of the admin area: a sub-navigation over whichever admin page is open. */
@Component({
  selector: 'app-admin-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <nav class="tabs" aria-label="Admin">
      <a routerLink="." routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }" i18n="@@admin.nav.overview">Übersicht</a>
      <a routerLink="reports" routerLinkActive="active" i18n="@@admin.nav.reports">Meldungen</a>
    </nav>
    <router-outlet />
  `,
  styles: `
    .tabs {
      display: flex;
      gap: 1rem;
      margin-bottom: 1rem;
      border-bottom: 1px solid var(--border);
    }
    .tabs a {
      padding: 0.5rem 0;
      text-decoration: none;
    }
    .tabs a.active {
      font-weight: 600;
      border-bottom: 2px solid var(--accent);
    }
  `,
})
export class AdminShell {}
