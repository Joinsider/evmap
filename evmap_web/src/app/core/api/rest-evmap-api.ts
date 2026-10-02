import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { EvmapApi } from './evmap-api';
import {
  Account,
  AdminOverview,
  BlockedAuthor,
  Contributions,
  GeoBounds,
  Legal,
  MapToken,
  Operator,
  ProviderToken,
  ReportedComment,
  ReportedStation,
  SignInProvider,
  StationAvailability,
  StationChargePoints,
  StationComment,
  StationDetail,
  StationQuery,
  StationReportReason,
  StationSummary,
  SyncRun,
} from './models';

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

  stations(query: StationQuery): Observable<StationSummary[]> {
    // The backend takes whole kilometres; rounding up keeps the viewport's corners covered.
    let params = new HttpParams()
      .set('latitude', query.latitude)
      .set('longitude', query.longitude)
      .set('radiusKm', Math.ceil(query.radiusKm))
      .set('limit', query.limit);
    for (const connector of [...query.connectorTypes].sort()) params = params.append('connectorType', connector);
    if (query.minPowerKw !== undefined) params = params.set('minPowerKw', query.minPowerKw);
    for (const name of [...query.excludeOperators].sort()) params = params.append('excludeOperator', name);
    for (const name of [...(query.includeOperators ?? [])].sort()) params = params.append('includeOperator', name);
    return this.http.get<StationSummary[]>('/api/v1/stations', { params });
  }

  station(id: string): Observable<StationDetail> {
    return this.http.get<StationDetail>(`/api/v1/stations/${encodeURIComponent(id)}`);
  }

  stationAvailability(id: string): Observable<StationAvailability> {
    return this.http.get<StationAvailability>(`/api/v1/stations/${encodeURIComponent(id)}/availability`);
  }

  availabilityInBounds(bounds: GeoBounds): Observable<StationAvailability[]> {
    const params = new HttpParams()
      .set('latMin', bounds.latMin)
      .set('lonMin', bounds.lonMin)
      .set('latMax', bounds.latMax)
      .set('lonMax', bounds.lonMax);
    return this.http.get<StationAvailability[]>('/api/v1/stations/availability', { params });
  }

  chargePoints(id: string): Observable<StationChargePoints> {
    return this.http.get<StationChargePoints>(`/api/v1/stations/${encodeURIComponent(id)}/charge-points`);
  }

  comments(stationId: string): Observable<StationComment[]> {
    return this.http.get<StationComment[]>(`/api/v1/stations/${encodeURIComponent(stationId)}/comments`);
  }

  operators(query: string, limit: number): Observable<Operator[]> {
    let params = new HttpParams().set('limit', limit);
    if (query.trim()) params = params.set('query', query.trim());
    return this.http.get<Operator[]>('/api/v1/operators', { params });
  }

  mapToken(): Observable<MapToken> {
    return this.http.get<MapToken>('/api/v1/map/token');
  }
}
