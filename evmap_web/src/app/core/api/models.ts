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
  /** Reported comments still waiting for a decision. */
  openReports: number;
}

export type ReportReason = 'spam' | 'offensive' | 'wrong' | 'other';

/** A comment with open reports, as the moderation queue lists it. Never says who reported it. */
export interface ReportedComment {
  commentId: string;
  stationId: string;
  stationName?: string;
  body: string;
  paidPriceCents?: number;
  experience?: string;
  commentCreatedAt: string;
  lastReportedAt: string;
  /** Number of open reports per reason. */
  reasons: Partial<Record<ReportReason, number>>;
}

export interface CommentContribution {
  id: string;
  stationId: string;
  stationName?: string;
  body: string;
  paidPriceCents?: number;
  experience?: string;
  createdAt: string;
  updatedAt: string;
}

export interface ReportContribution {
  id: string;
  reason: ReportReason;
  status: 'open' | 'dismissed';
  stationName?: string;
  createdAt: string;
  resolvedAt?: string;
}

/** What the signed-in person has contributed ("Meine Beiträge"). */
export interface Contributions {
  comments: CommentContribution[];
  reports: ReportContribution[];
}

/** A block, listed without saying whom it concerns: `id` is only good for lifting it again. */
export interface BlockedAuthor {
  id: string;
  createdAt: string;
}

export interface Legal {
  /** Absent when the operator has not configured one. */
  privacyPolicyUrl?: string;
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
