import { Observable } from 'rxjs';
import {
  Account,
  AdminOverview,
  BlockedAuthor,
  CommentPayload,
  Contributions,
  FavoriteStation,
  GeoBounds,
  MapToken,
  Operator,
  ProviderToken,
  ReportReason,
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
} from './models';

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

  /**
   * The operator's details for the Impressum and the privacy policy. Served by the web container from its
   * environment, not by the API, so the legal pages stay up when the API is down (ADR 0024).
   */
  abstract siteOperator(): Observable<SiteOperator>;

  abstract adminReports(): Observable<ReportedComment[]>;

  /** Closes the open reports on a comment and keeps the comment. */
  abstract adminDismissReports(commentId: string): Observable<void>;

  abstract adminRemoveComment(commentId: string): Observable<void>;

  abstract adminStationReports(): Observable<ReportedStation[]>;

  /** Closes the open reports of one station for one reason. Master data is never touched (ADR 0021). */
  abstract adminCloseStationReports(stationId: string, reason: StationReportReason, outcome: 'resolve' | 'dismiss'): Observable<void>;

  /** Stations for one map viewport, strongest first, at most `query.limit` (ADR 0009). */
  abstract stations(query: StationQuery): Observable<StationSummary[]>;

  abstract station(id: string): Observable<StationDetail>;

  /** Live state of one station; fails where the live axis is switched off, which the screen treats as "unknown". */
  abstract stationAvailability(id: string): Observable<StationAvailability>;

  /** Live state of the stations in a box that have any; empty past the backend's widest span. */
  abstract availabilityInBounds(bounds: GeoBounds): Observable<StationAvailability[]>;

  /** The station's charge points with operator and ad-hoc price (ADR 0022). */
  abstract chargePoints(id: string): Observable<StationChargePoints>;

  abstract comments(stationId: string): Observable<StationComment[]>;

  abstract createComment(stationId: string, payload: CommentPayload): Observable<StationComment>;

  /** Only the author's own comments; any other id answers 404. */
  abstract updateComment(id: string, payload: CommentPayload): Observable<StationComment>;

  abstract deleteComment(id: string): Observable<void>;

  /** Reports someone else's comment; from then on it is hidden for the reporter alone (ADR 0020). */
  abstract reportComment(id: string, reason: ReportReason): Observable<void>;

  /** Blocks the author of a comment without learning who it is (ADR 0020). */
  abstract blockAuthor(commentId: string): Observable<void>;

  /** Files an error report on a station; master data stays untouched (ADR 0021). */
  abstract reportStation(stationId: string, reason: StationReportReason, note?: string): Observable<void>;

  /** The account's favorites, newest first (ADR 0021). */
  abstract favorites(): Observable<FavoriteStation[]>;

  abstract addFavorite(stationId: string): Observable<void>;

  abstract removeFavorite(stationId: string): Observable<void>;

  /** The sign-in merge: adds the device's favorites to the account's and answers with the union. */
  abstract mergeFavorites(stationIds: readonly string[]): Observable<FavoriteStation[]>;

  /** The charging-network directory: the biggest without a query, else names containing it. */
  abstract operators(query: string, limit: number): Observable<Operator[]>;

  /** A fresh MapKit JS token; fails with 404 while the backend has no Maps key (ADR 0023). */
  abstract mapToken(): Observable<MapToken>;
}
