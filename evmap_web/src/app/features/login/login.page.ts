import { Component, inject, input, signal } from '@angular/core';
import { EvmapApi } from '../../core/api/evmap-api';
import { SignInProvider } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { load } from '../../core/loaded';
import { signInLabel } from './provider-labels';

@Component({
  selector: 'app-login',
  template: `
    <section class="card login">
      <h1 i18n="@@login.title">Anmelden</h1>
      <p i18n="@@login.hint">Konten mit derselben bestätigten E-Mail-Adresse werden automatisch zusammengeführt.</p>
      @let options = providers();
      @if (options.state === 'ready') {
        @for (provider of options.value; track provider.provider) {
          <button type="button" class="provider" [class]="provider.provider" [disabled]="leaving()" (click)="signIn(provider)">
            {{ label(provider) }}
          </button>
        } @empty {
          <p i18n="@@login.none">Auf diesem Server ist noch kein Anmeldeverfahren eingerichtet.</p>
        }
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
      padding: 0.75rem 1rem;
      border-radius: 0.5rem;
      border: 1px solid var(--border);
      background: var(--surface);
      color: var(--text);
      font-weight: 600;
    }
    .provider.apple {
      background: #000;
      color: #fff;
      border-color: #000;
    }
  `,
})
export class LoginPage {
  private readonly api = inject(EvmapApi);
  private readonly auth = inject(AuthService);

  /** Bound from the query string by the router. */
  readonly returnUrl = input<string>();
  protected readonly leaving = signal(false);

  protected readonly providers = load(this.api.signInProviders());

  protected label(provider: SignInProvider) {
    return signInLabel(provider.provider);
  }

  protected async signIn(provider: SignInProvider) {
    this.leaving.set(true);
    // Only in-app paths: an absolute URL here would turn the login into an open redirect.
    const target = this.returnUrl();
    await this.auth.begin(provider, target?.startsWith('/') && !target.startsWith('//') ? target : '/');
  }
}
