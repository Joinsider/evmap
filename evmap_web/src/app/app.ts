import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth/auth.service';
import { LegalService } from './core/legal.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="bar">
      <a class="brand" routerLink="/">EVMap</a>
      <nav>
        @if (auth.signedIn()) {
          <a routerLink="/account" routerLinkActive="active" i18n="@@nav.account">Mein Konto</a>
        }
        @if (auth.isAdmin()) {
          <a routerLink="/admin" routerLinkActive="active" i18n="@@nav.admin">Admin</a>
        }
      </nav>
      <div class="session">
        @if (auth.signedIn()) {
          <button type="button" class="link" (click)="signOut()" i18n="@@nav.signOut">Abmelden</button>
        } @else {
          <a routerLink="/login" i18n="@@nav.signIn">Anmelden</a>
        }
      </div>
    </header>
    <main>
      <router-outlet />
    </main>
    <footer>
      <!-- Each language is its own build under /de/ and /en/; switching loads the page anew. -->
      <a href="/de/" hreflang="de" lang="de">Deutsch</a>
      <a href="/en/" hreflang="en" lang="en">English</a>
      @if (privacyPolicyUrl(); as url) {
        <a [href]="url" target="_blank" rel="noopener" i18n="@@footer.privacy">Datenschutz</a>
      }
    </footer>
  `,
  styleUrl: './app.scss',
})
export class App {
  protected readonly auth = inject(AuthService);
  protected readonly privacyPolicyUrl = inject(LegalService).privacyPolicyUrl;
  private readonly router = inject(Router);

  protected async signOut() {
    await this.auth.signOut();
    void this.router.navigateByUrl('/');
  }
}
