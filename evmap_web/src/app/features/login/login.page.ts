import { Component, inject, input, signal } from '@angular/core';
import { EvmapApi } from '../../core/api/evmap-api';
import { ProviderToken, SignInProvider } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { load } from '../../core/loaded';
import { providerName, signInLabel } from './provider-labels';

@Component({
  selector: 'app-login',
  host: { '(window:pageshow)': 'returned($event)' },
  template: `
    <section class="card login">
      <h1 i18n="@@login.title">Anmelden</h1>
      <p i18n="@@login.hint">Konten mit derselben bestätigten E-Mail-Adresse werden automatisch zusammengeführt.</p>
      @let options = providers();
      @if (options.state === 'ready') {
        @for (provider of options.value; track provider.provider) {
          <button
            type="button"
            class="provider"
            [class]="provider.provider"
            [disabled]="leavingTo() !== null"
            [attr.aria-busy]="leavingTo() === provider.provider"
            (click)="signIn(provider)"
          >
            @if (leavingTo() === provider.provider) {
              <span class="spinner" aria-hidden="true"></span>
            }
            {{ label(provider) }}
          </button>
        } @empty {
          <p i18n="@@login.none">Auf diesem Server ist noch kein Anmeldeverfahren eingerichtet.</p>
        }
        <p class="status" role="status">
          @if (leavingTo(); as leaving) {
            <ng-container i18n="@@login.leaving">Weiter zu {{ name(leaving) }} …</ng-container>
          }
        </p>
      } @else if (options.state === 'failed') {
        <p class="error" i18n="@@login.unavailable">Die Anmeldung ist gerade nicht erreichbar.</p>
      } @else {
        <p i18n="@@common.loading">Wird geladen …</p>
      }
    </section>
  `,
  styles: `
    .login {
      max-width: 24rem;
      margin: 2rem auto;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }
    .provider {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 0.5rem;
      padding: 0.75rem 1rem;
      border-radius: 0.5rem;
      border: 1px solid var(--border);
      background: var(--surface);
      color: var(--text);
      font-weight: 600;
    }
    .provider:disabled {
      cursor: progress;
      opacity: 0.6;
    }
    .provider[aria-busy='true'] {
      opacity: 1;
    }
    .provider.apple {
      background: #000;
      color: #fff;
      border-color: #000;
    }
    .status {
      min-height: 1.5em;
      margin: 0;
      color: var(--muted);
      text-align: center;
    }
  `,
})
export class LoginPage {
  private readonly api = inject(EvmapApi);
  private readonly auth = inject(AuthService);

  /** Bound from the query string by the router. */
  readonly returnUrl = input<string>();
  /** The provider the page is on its way to; while set, the buttons are off and that one shows it is busy. */
  protected readonly leavingTo = signal<ProviderToken | null>(null);

  protected readonly providers = load(this.api.signInProviders());

  protected label(provider: SignInProvider) {
    return signInLabel(provider.provider);
  }

  protected name(provider: ProviderToken) {
    return providerName(provider);
  }

  protected async signIn(provider: SignInProvider) {
    this.leavingTo.set(provider.provider);
    // Only in-app paths: an absolute URL here would turn the login into an open redirect.
    const target = this.returnUrl();
    try {
      await this.auth.begin(provider, target?.startsWith('/') && !target.startsWith('//') ? target : '/');
    } catch {
      this.leavingTo.set(null);
    }
  }

  /**
   * Back from the provider without signing in (cancelled at Apple, or the Back button): the browser
   * restores this page from its back-forward cache as it was left, buttons disabled. Make it usable
   * again and drop the sign-in that will not come back.
   */
  protected returned(event: PageTransitionEvent) {
    if (!event.persisted || this.leavingTo() === null) return;
    this.leavingTo.set(null);
    this.auth.abandon();
  }
}
