import { Observable, of, throwError } from 'rxjs';
import { EvmapApi } from '../core/api/evmap-api';
import { Account, AdminOverview, BlockedAuthor, Contributions, Legal, ProviderToken, ReportedComment, ReportedStation, SignInProvider, StationReportReason, SyncRun } from '../core/api/models';

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
  legalData: Legal = {};
  reported: ReportedComment[] = [];
  exportBlob = new Blob(['{}'], { type: 'application/json' });
  /** Calls that changed something, in order, for assertions. */
  calls: string[] = [];
  rejectDeletion = false;

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

  legal(): Observable<Legal> {
    return of(this.legalData);
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
}
