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
  /** Open station error reports, counted per station and reason as the queue lists them. */
  openStationReports: number;
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

export type StationReportReason = 'gone' | 'wrong_power' | 'wrong_connector' | 'defective' | 'other';

/** One line of the station report queue: the open reports of one station for one reason. Never says who reported. */
export interface ReportedStation {
  stationId: string;
  stationName?: string;
  operatorName?: string;
  city?: string;
  reason: StationReportReason;
  count: number;
  lastReportedAt: string;
  /** The latest free texts of the reporters, newest first. */
  notes: string[];
}

export interface StationReportContribution {
  id: string;
  stationId: string;
  stationName?: string;
  reason: StationReportReason;
  note?: string;
  status: 'open' | 'resolved' | 'dismissed';
  createdAt: string;
  resolvedAt?: string;
}

/** What the signed-in person has contributed ("Meine Beiträge"). */
export interface Contributions {
  comments: CommentContribution[];
  reports: ReportContribution[];
  stationReports: StationReportContribution[];
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
