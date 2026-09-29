/** Wire types of the EVMap REST API (`evmap_service`), as the web client uses them. */

export type ProviderToken = 'apple' | 'google' | 'github';

/** One sign-in option the backend has credentials for (`GET /api/v1/auth/providers`). */
export interface SignInProvider {
  provider: ProviderToken;
  authorizationEndpoint: string;
  /** Appended to the endpoint as they are; the client adds `state` and, if `pkce`, the challenge. */
  parameters: Record<string, string>;
  pkce: boolean;
}

export interface LinkedIdentity {
  provider: ProviderToken;
  email?: string;
}

export interface Account {
  id: string;
  admin: boolean;
  identities: LinkedIdentity[];
}

export interface AdminOverview {
  stations: number;
  chargePoints: number;
  accounts: number;
  comments: number;
}

export type SyncRunStatus = 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'SKIPPED';

export interface SyncRun {
  id: string;
  startedAt: string;
  finishedAt?: string;
  status: SyncRunStatus;
  processed: number;
  created: number;
  updated: number;
  unchanged: number;
  failed: number;
  errorMessage?: string;
}
