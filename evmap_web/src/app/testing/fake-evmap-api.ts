import { Observable, of, throwError } from 'rxjs';
import { EvmapApi } from '../core/api/evmap-api';
import { Account, AdminOverview, ProviderToken, SignInProvider, SyncRun } from '../core/api/models';

/** In-memory {@link EvmapApi}: what the tests swap in for REST, the same way a later GraphQL client would be. */
export class FakeEvmapApi extends EvmapApi {
  providers: SignInProvider[] = [];
  account: Account = { id: 'acc-1', admin: false, identities: [] };
  overview: AdminOverview = { stations: 0, chargePoints: 0, accounts: 0, comments: 0 };
  runs: SyncRun[] = [];
  exchanges: { provider: ProviderToken; code: string; codeVerifier?: string }[] = [];
  rejectExchange = false;
  /** Stands in for the browser holding the session cookie. */
  hasSession = false;

  signInProviders(): Observable<SignInProvider[]> {
    return of(this.providers);
  }

  exchangeCode(provider: ProviderToken, code: string, codeVerifier?: string): Observable<void> {
    this.exchanges.push({ provider, code, codeVerifier });
    if (this.rejectExchange) return throwError(() => new Error('401'));
    this.hasSession = true;
    return of(undefined);
  }

  signOut(): Observable<void> {
    this.hasSession = false;
    return of(undefined);
  }

  me(): Observable<Account> {
    return this.hasSession ? of(this.account) : throwError(() => new Error('401'));
  }

  adminOverview(): Observable<AdminOverview> {
    return of(this.overview);
  }

  adminSyncRuns(): Observable<SyncRun[]> {
    return of(this.runs);
  }
}
