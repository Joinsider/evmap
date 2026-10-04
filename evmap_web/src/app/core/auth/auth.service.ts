import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { EvmapApi } from '../api/evmap-api';
import { Account, ProviderToken, SignInProvider } from '../api/models';
import { codeChallenge, randomToken } from './pkce';

/** What survives the redirect to the provider and back. Holds no credential, only the flow's secrets. */
interface PendingSignIn {
  provider: ProviderToken;
  state: string;
  codeVerifier?: string;
  returnUrl: string;
}

export type SignInError = 'state' | 'provider' | 'exchange';

const PENDING_KEY = 'evmap.pendingSignIn';

/**
 * Sign-in state of the web client (ADR 0018).
 *
 * The session is an `HttpOnly` cookie set by the backend, so this class never holds a credential
 * and page scripts cannot read one. "Signed in" therefore means "the backend answered `/me` with an
 * account": {@link restore} asks once at startup, which is what keeps a reload or a language switch
 * signed in. The price of the cookie is CSRF, handled by the backend's double-submit token.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(EvmapApi);

  private readonly currentAccount = signal<Account | null>(null);

  readonly account = this.currentAccount.asReadonly();
  readonly signedIn = computed(() => this.currentAccount() !== null);
  readonly isAdmin = computed(() => this.currentAccount()?.admin === true);

  /** Leaves for the provider. Resolves never in practice: the page navigates away. */
  async begin(provider: SignInProvider, returnUrl: string, navigate: (url: string) => void = (url) => location.assign(url)) {
    const pending: PendingSignIn = { provider: provider.provider, state: randomToken(), returnUrl };
    const url = new URL(provider.authorizationEndpoint);
    for (const [key, value] of Object.entries(provider.parameters)) url.searchParams.set(key, value);
    url.searchParams.set('state', pending.state);
    if (provider.pkce) {
      pending.codeVerifier = randomToken(48);
      url.searchParams.set('code_challenge', await codeChallenge(pending.codeVerifier));
      url.searchParams.set('code_challenge_method', 'S256');
    }
    // sessionStorage, not memory: it has to survive the round trip through the provider, and it is
    // scoped to this tab. It is removed as soon as the callback reads it.
    sessionStorage.setItem(PENDING_KEY, JSON.stringify(pending));
    navigate(url.toString());
  }

  /**
   * Drops a sign-in that was started but never came back, e.g. when the person cancelled at the
   * provider and returned with the browser's Back button.
   */
  abandon() {
    sessionStorage.removeItem(PENDING_KEY);
  }

  /**
   * Finishes a sign-in on the callback route. Returns where to go next, or the reason it failed.
   * The state check is what ties the answer to the tab that asked; for Apple, which has no PKCE,
   * it is the only such binding.
   */
  async complete(provider: string, query: URLSearchParams): Promise<{ returnUrl: string } | { error: SignInError }> {
    const stored = sessionStorage.getItem(PENDING_KEY);
    sessionStorage.removeItem(PENDING_KEY);
    const pending: PendingSignIn | null = stored ? JSON.parse(stored) : null;
    const code = query.get('code');

    if (query.get('error')) return { error: 'provider' };
    if (pending?.provider !== provider || !code || query.get('state') !== pending.state) return { error: 'state' };

    try {
      await firstValueFrom(this.api.exchangeCode(pending.provider, code, pending.codeVerifier), { defaultValue: undefined });
      if (!(await this.refreshAccount())) throw new Error('no session');
      return { returnUrl: pending.returnUrl };
    } catch {
      this.forget();
      return { error: 'exchange' };
    }
  }

  /** Asks the backend who the session cookie belongs to; `null` when there is none. */
  async refreshAccount(): Promise<Account | null> {
    try {
      const account = await firstValueFrom(this.api.me());
      this.currentAccount.set(account);
      return account;
    } catch {
      this.currentAccount.set(null);
      return null;
    }
  }

  /** Picks the sign-in up after a full page load. */
  restore(): Promise<Account | null> {
    return this.refreshAccount();
  }

  async signOut() {
    this.forget();
    await firstValueFrom(this.api.signOut(), { defaultValue: undefined }).catch(() => undefined);
  }

  /**
   * Deletes the account for good. Only after the backend confirmed is the local state dropped, so a
   * failed deletion leaves the person signed in and able to try again.
   */
  async deleteAccount() {
    await firstValueFrom(this.api.deleteAccount(), { defaultValue: undefined });
    this.forget();
  }

  /** Drops the local state only; used when the backend already said the session is gone. */
  forget() {
    this.currentAccount.set(null);
  }
}
