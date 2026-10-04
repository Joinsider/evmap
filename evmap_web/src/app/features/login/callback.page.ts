import { Component, OnInit, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService, SignInError } from '../../core/auth/auth.service';

/** Where every provider sends the browser back to. Exchanges the code, then continues. */
@Component({
  selector: 'app-callback',
  imports: [RouterLink],
  template: `
    <section class="card">
      @switch (error()) {
        @case (null) {
          <p class="working" role="status" aria-busy="true">
            <span class="spinner" aria-hidden="true"></span>
            <ng-container i18n="@@callback.working">Anmeldung wird abgeschlossen …</ng-container>
          </p>
        }
        @case ('provider') {
          <p class="error" i18n="@@callback.cancelled">Die Anmeldung wurde abgebrochen.</p>
        }
        @case ('state') {
          <p class="error" i18n="@@callback.state">Die Anmeldung ist abgelaufen oder stammt aus einem anderen Fenster. Bitte erneut versuchen.</p>
        }
        @default {
          <p class="error" i18n="@@callback.failed">Die Anmeldung ist fehlgeschlagen.</p>
        }
      }
      @if (error() !== null) {
        <a routerLink="/login" i18n="@@callback.retry">Erneut anmelden</a>
      }
    </section>
  `,
  styles: `
    .working {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin: 0;
    }
  `,
})
export class CallbackPage implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly provider = input.required<string>();
  protected readonly error = signal<SignInError | null>(null);

  async ngOnInit() {
    const result = await this.auth.complete(this.provider(), new URLSearchParams(location.search));
    if ('error' in result) this.error.set(result.error);
    // replaceUrl: the code is single-use; it should not stay in the history for Back to replay.
    else await this.router.navigateByUrl(result.returnUrl, { replaceUrl: true });
  }
}
