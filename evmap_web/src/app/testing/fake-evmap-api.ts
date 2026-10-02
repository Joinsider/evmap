import { Observable, of, throwError } from 'rxjs';
import { EvmapApi } from '../core/api/evmap-api';
import {
  Account,
  AdminOverview,
  BlockedAuthor,
  Contributions,
  GeoBounds,
  MapToken,
  Operator,
  ProviderToken,
  ReportedComment,
  ReportedStation,
  SignInProvider,
  SiteOperator,
  StationAvailability,
  StationChargePoints,
  StationComment,
  StationDetail,
  StationQuery,
  StationReportReason,
  StationSummary,
  SyncRun,
} from '../core/api/models';

/** In-memory {@link EvmapApi}: what the tests swap in for REST, the same way a later GraphQL client would be. */
export class FakeEvmapApi extends EvmapApi {
  providers: SignInProvider[] = [];
  account: Account = { id: 'acc-1', admin: false, identities: [] };
  overview: AdminOverview = { stations: 0, chargePoints: 0, accounts: 0, comments: 0, openReports: 0, openStationReports: 0 };
  runs: SyncRun[] = [];
  exchanges: { provider: ProviderToken; code: string; codeVerifier?: string }[] = [];
  rejectExchange = false;
  /** Stands in for the browser holding the session cookie. */
  hasSession = false;
  contributionsData: Contributions = { comments: [], reports: [], stationReports: [] };
  stationReports: ReportedStation[] = [];
  blockList: BlockedAuthor[] = [];
  /** `null` stands in for a deployment without operator details (404). */
  operatorData: SiteOperator | null = {
    name: 'Erika Mustermann',
    street: 'Heidestraße 17',
    city: '51147 Köln',
    country: 'Deutschland',
    email: 'kontakt@evmap.example',
  };
  reported: ReportedComment[] = [];
  exportBlob = new Blob(['{}'], { type: 'application/json' });
  /** Calls that changed something, in order, for assertions. */
  calls: string[] = [];
  rejectDeletion = false;
  stationList: StationSummary[] = [];
  /** Every map query, in order. */
  stationQueries: StationQuery[] = [];
  details = new Map<string, StationDetail>();
  liveByStation = new Map<string, StationAvailability>();
  liveInBounds: StationAvailability[] = [];
  boundsQueries: GeoBounds[] = [];
  prices = new Map<string, StationChargePoints>();
  commentsByStation = new Map<string, StationComment[]>();
  directory: Operator[] = [];
  operatorQueries: string[] = [];
  token: MapToken | null = { token: 'test-token', expiresAt: '2026-10-02T12:30:00Z' };

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

  deleteAccount(): Observable<void> {
    this.calls.push('deleteAccount');
    if (this.rejectDeletion) return throwError(() => new Error('500'));
    this.hasSession = false;
    return of(undefined);
  }

  exportData(): Observable<Blob> {
    this.calls.push('exportData');
    return of(this.exportBlob);
  }

  contributions(): Observable<Contributions> {
    return of(this.contributionsData);
  }

  blocks(): Observable<BlockedAuthor[]> {
    return of(this.blockList);
  }

  unblock(id: string): Observable<void> {
    this.calls.push(`unblock:${id}`);
    this.blockList = this.blockList.filter((block) => block.id !== id);
    return of(undefined);
  }

  siteOperator(): Observable<SiteOperator> {
    return this.operatorData ? of(this.operatorData) : throwError(() => new Error('404'));
  }

  adminReports(): Observable<ReportedComment[]> {
    return of(this.reported);
  }

  adminDismissReports(commentId: string): Observable<void> {
    this.calls.push(`dismiss:${commentId}`);
    this.reported = this.reported.filter((item) => item.commentId !== commentId);
    return of(undefined);
  }

  adminRemoveComment(commentId: string): Observable<void> {
    this.calls.push(`remove:${commentId}`);
    this.reported = this.reported.filter((item) => item.commentId !== commentId);
    return of(undefined);
  }

  adminStationReports(): Observable<ReportedStation[]> {
    return of(this.stationReports);
  }

  adminCloseStationReports(stationId: string, reason: StationReportReason, outcome: 'resolve' | 'dismiss'): Observable<void> {
    this.calls.push(`${outcome}:${stationId}:${reason}`);
    this.stationReports = this.stationReports.filter((item) => !(item.stationId === stationId && item.reason === reason));
    return of(undefined);
  }

  stations(query: StationQuery): Observable<StationSummary[]> {
    this.stationQueries.push(query);
    return of(this.stationList);
  }

  station(id: string): Observable<StationDetail> {
    const detail = this.details.get(id);
    return detail ? of(detail) : throwError(() => new Error('404'));
  }

  stationAvailability(id: string): Observable<StationAvailability> {
    const live = this.liveByStation.get(id);
    return live ? of(live) : throwError(() => new Error('404'));
  }

  availabilityInBounds(bounds: GeoBounds): Observable<StationAvailability[]> {
    this.boundsQueries.push(bounds);
    return of(this.liveInBounds);
  }

  chargePoints(id: string): Observable<StationChargePoints> {
    const prices = this.prices.get(id);
    return prices ? of(prices) : throwError(() => new Error('404'));
  }

  comments(stationId: string): Observable<StationComment[]> {
    return of(this.commentsByStation.get(stationId) ?? []);
  }

  operators(query: string): Observable<Operator[]> {
    this.operatorQueries.push(query);
    const needle = query.trim().toLowerCase();
    return of(this.directory.filter((operator) => !needle || operator.name.toLowerCase().includes(needle)));
  }

  mapToken(): Observable<MapToken> {
    return this.token ? of(this.token) : throwError(() => new Error('404'));
  }
}
