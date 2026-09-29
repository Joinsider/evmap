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
 * The access token lives in memory only — never in `localStorage` or a cookie — so no other script
 * run on this origin later can pick it up from storage, and the stateless API needs no CSRF defence.
 * The price is that a full page load (reload, language switch) signs the user out; signing in again
 * is one click while the provider's own session lasts.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(EvmapApi);

  private readonly token = signal<string | null>(null);
  private readonly currentAccount = signal<Account | null>(null);

  readonly accessToken = this.token.asReadonly();
  readonly account = this.currentAccount.asReadonly();
  readonly signedIn = computed(() => this.token() !== null);
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
      const token = await firstValueFrom(this.api.exchangeCode(pending.provider, code, pending.codeVerifier));
      this.token.set(token);
      await this.refreshAccount();
      return { returnUrl: pending.returnUrl };
    } catch {
      this.signOut();
      return { error: 'exchange' };
    }
  }

  async refreshAccount(): Promise<Account | null> {
    if (!this.token()) return null;
    const account = await firstValueFrom(this.api.me());
    this.currentAccount.set(account);
    return account;
  }

  signOut() {
    this.token.set(null);
    this.currentAccount.set(null);
  }
}
