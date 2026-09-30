import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { EvmapApi } from './evmap-api';
import { Account, AdminOverview, BlockedAuthor, Contributions, Legal, ProviderToken, ReportedComment, ReportedStation, SignInProvider, StationReportReason, SyncRun } from './models';

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

  exchangeCode(provider: ProviderToken, code: string, codeVerifier?: string): Observable<void> {
    return this.http.post<void>(`/api/v1/auth/${encodeURIComponent(provider)}/code`, { code, codeVerifier });
  }

  signOut(): Observable<void> {
    return this.http.post<void>('/api/v1/auth/logout', null);
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

  deleteAccount(): Observable<void> {
    return this.http.delete<void>('/api/v1/me');
  }

  exportData(): Observable<Blob> {
    return this.http.get('/api/v1/me/export', { responseType: 'blob' });
  }

  contributions(): Observable<Contributions> {
    return this.http.get<Contributions>('/api/v1/me/contributions');
  }

  blocks(): Observable<BlockedAuthor[]> {
    return this.http.get<BlockedAuthor[]>('/api/v1/me/blocks');
  }

  unblock(id: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/me/blocks/${encodeURIComponent(id)}`);
  }

  legal(): Observable<Legal> {
    return this.http.get<Legal>('/api/v1/legal');
  }

  adminReports(): Observable<ReportedComment[]> {
    return this.http.get<ReportedComment[]>('/api/v1/admin/reports');
  }

  adminDismissReports(commentId: string): Observable<void> {
    return this.http.post<void>(`/api/v1/admin/reports/${encodeURIComponent(commentId)}/dismiss`, null);
  }

  adminRemoveComment(commentId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/admin/comments/${encodeURIComponent(commentId)}`);
  }

  adminStationReports(): Observable<ReportedStation[]> {
    return this.http.get<ReportedStation[]>('/api/v1/admin/station-reports');
  }

  adminCloseStationReports(stationId: string, reason: StationReportReason, outcome: 'resolve' | 'dismiss'): Observable<void> {
    return this.http.post<void>(`/api/v1/admin/station-reports/${encodeURIComponent(stationId)}/${encodeURIComponent(reason)}/${outcome}`, null);
  }
}
