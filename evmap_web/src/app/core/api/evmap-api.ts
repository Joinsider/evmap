import { Observable } from 'rxjs';
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

  /** The charging-network directory: the biggest without a query, else names containing it. */
  abstract operators(query: string, limit: number): Observable<Operator[]>;

  /** A fresh MapKit JS token; fails with 404 while the backend has no Maps key (ADR 0023). */
  abstract mapToken(): Observable<MapToken>;
}
