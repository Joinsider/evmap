import { Observable } from 'rxjs';
import { Account, AdminOverview, BlockedAuthor, Contributions, Legal, ProviderToken, ReportedComment, SignInProvider, SyncRun } from './models';

/**
 * The only way features talk to the backend — the web counterpart of the iOS
 * `ChargingStationRepository` (ADR 0018). Components and services depend on this abstract class,
 * never on `HttpClient`, so the transport (REST today, GraphQL as a late option) can be swapped
 * without touching a feature.
 */
export abstract class EvmapApi {
  abstract signInProviders(): Observable<SignInProvider[]>;

  /** Redeems an authorization code. The session lives in an HttpOnly cookie the backend sets; nothing comes back to read. */
  abstract exchangeCode(provider: ProviderToken, code: string, codeVerifier?: string): Observable<void>;

  /** Ends the session: only the backend can remove an HttpOnly cookie. */
  abstract signOut(): Observable<void>;

  abstract me(): Observable<Account>;

  abstract adminOverview(): Observable<AdminOverview>;

  abstract adminSyncRuns(limit: number): Observable<SyncRun[]>;

  /** Deletes the signed-in account with everything attached, at once and for good. */
  abstract deleteAccount(): Observable<void>;

  /** The account's data as one JSON file (GDPR Art. 15/20). */
  abstract exportData(): Observable<Blob>;

  abstract contributions(): Observable<Contributions>;

  abstract blocks(): Observable<BlockedAuthor[]>;

  abstract unblock(id: string): Observable<void>;

  abstract legal(): Observable<Legal>;

  abstract adminReports(): Observable<ReportedComment[]>;

  /** Closes the open reports on a comment and keeps the comment. */
  abstract adminDismissReports(commentId: string): Observable<void>;

  abstract adminRemoveComment(commentId: string): Observable<void>;
}
