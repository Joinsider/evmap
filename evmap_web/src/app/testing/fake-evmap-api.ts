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

  signInProviders(): Observable<SignInProvider[]> {
    return of(this.providers);
  }

  exchangeCode(provider: ProviderToken, code: string, codeVerifier?: string): Observable<string> {
    this.exchanges.push({ provider, code, codeVerifier });
    return this.rejectExchange ? throwError(() => new Error('401')) : of('access-token');
  }

  me(): Observable<Account> {
    return of(this.account);
  }

  adminOverview(): Observable<AdminOverview> {
    return of(this.overview);
  }

  adminSyncRuns(): Observable<SyncRun[]> {
    return of(this.runs);
  }
}
