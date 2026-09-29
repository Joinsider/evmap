import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { EvmapApi } from './evmap-api';
import { Account, AdminOverview, ProviderToken, SignInProvider, SyncRun } from './models';

/**
 * REST implementation of {@link EvmapApi}. Paths are relative: the web container's nginx serves the
 * app and proxies `/api/**` on the same origin, so there is no base URL and no CORS.
 */
@Injectable()
export class RestEvmapApi extends EvmapApi {
  private readonly http = inject(HttpClient);

  signInProviders(): Observable<SignInProvider[]> {
    return this.http.get<SignInProvider[]>('/api/v1/auth/providers');
  }

  exchangeCode(provider: ProviderToken, code: string, codeVerifier?: string): Observable<string> {
    return this.http
      .post<{ accessToken: string }>(`/api/v1/auth/${encodeURIComponent(provider)}/code`, { code, codeVerifier })
      .pipe(map((response) => response.accessToken));
  }

  me(): Observable<Account> {
    return this.http.get<Account>('/api/v1/me');
  }

  adminOverview(): Observable<AdminOverview> {
    return this.http.get<AdminOverview>('/api/v1/admin/overview');
  }

  adminSyncRuns(limit: number): Observable<SyncRun[]> {
    return this.http.get<SyncRun[]>('/api/v1/admin/sync-runs', { params: new HttpParams().set('limit', limit) });
  }
}
