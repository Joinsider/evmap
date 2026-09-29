import { Observable } from 'rxjs';
import { Account, AdminOverview, ProviderToken, SignInProvider, SyncRun } from './models';

/**
 * The only way features talk to the backend — the web counterpart of the iOS
 * `ChargingStationRepository` (ADR 0018). Components and services depend on this abstract class,
 * never on `HttpClient`, so the transport (REST today, GraphQL as a late option) can be swapped
 * without touching a feature.
 */
export abstract class EvmapApi {
  abstract signInProviders(): Observable<SignInProvider[]>;

  /** Redeems an authorization code; answers with the backend's own access token. */
  abstract exchangeCode(provider: ProviderToken, code: string, codeVerifier?: string): Observable<string>;

  abstract me(): Observable<Account>;

  abstract adminOverview(): Observable<AdminOverview>;

  abstract adminSyncRuns(limit: number): Observable<SyncRun[]>;
}
