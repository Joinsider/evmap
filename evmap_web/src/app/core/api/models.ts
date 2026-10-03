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

/** Who runs this site, for the Impressum and the privacy policy (ADR 0024). Set per deployment, never in the code. */
export interface SiteOperator {
  name: string;
  street: string;
  /** Postcode and town, as it is written on a letter. */
  city: string;
  country: string;
  email: string;
  phone?: string;
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

/** One station of the map query (`GET /api/v1/stations`), as the list payload carries it. */
export interface StationSummary {
  id: string;
  displayName: string;
  street?: string;
  city?: string;
  postalCode?: string;
  countryCode?: string;
  operatorName?: string;
  latitude: number;
  longitude: number;
  /** The register's service state (`OPERATIONAL`, `MAINTENANCE`, `OUT_OF_SERVICE`, or a token this build does not know). */
  availabilityStatus?: string;
  /** Strongest connector; absent when no source reported a rating. Drives the pin colour (ADR 0009). */
  maxPowerKw?: number;
}

export interface Connector {
  connectorType: string;
  powerKw?: number;
  quantity: number;
}

export interface StationDetail {
  station: StationSummary;
  connectors: Connector[];
  sources: string[];
}

/** The criteria of one map query, already translated from the settings (`stationFilter`) and the viewport. */
export interface StationQuery {
  latitude: number;
  longitude: number;
  radiusKm: number;
  limit: number;
  connectorTypes: readonly string[];
  minPowerKw?: number;
  excludeOperators: readonly string[];
  /** Absent for "no restriction"; never empty on the wire (ADR 0014). */
  includeOperators?: readonly string[];
}

export interface GeoBounds {
  latMin: number;
  lonMin: number;
  latMax: number;
  lonMax: number;
}

/** A publisher of live data or prices, credited next to what it reported. */
export interface DataSource {
  name: string;
  licence?: string;
  url?: string;
}

export interface ChargePointLiveStatus {
  id: string;
  evseId?: string;
  /** `AVAILABLE`, `OCCUPIED`, `OUT_OF_ORDER`, `UNKNOWN` — anything else is read as unknown. */
  status: string;
  observedAt?: string;
  source?: string;
}

/** Live state of a station (ADR 0015); the viewport response leaves `chargePoints` empty. */
export interface StationAvailability {
  stationId: string;
  status: string;
  available: number;
  occupied: number;
  outOfOrder: number;
  unknown: number;
  observedAt?: string;
  chargePoints: ChargePointLiveStatus[];
  sources: DataSource[];
}

/** A recurring period of the week in local time, as the operator writes it (ADR 0022, L6p). */
export interface TimeWindow {
  /** "08:00". */
  from: string;
  /** "20:00", "24:00" for midnight at its end; earlier than `from` when it runs past midnight. */
  to: string;
  /** "monday" … "sunday"; empty or absent for every day. */
  days?: string[];
}

export interface TimeFee {
  /** 0 for the whole session. */
  fromMinute: number;
  /** The minute from which the fee no longer applies; absent for the rest of the session. */
  toMinute?: number;
  /** Absent when a time-based fee applies but its amount is not certain. */
  perMinute?: number;
  /** The most the fee comes to in one session. */
  cap?: number;
  /** When the fee applies; absent for always. */
  window?: TimeWindow;
}

/** An energy price that applies only within `window`. */
export interface EnergyWindow {
  perKwh: number;
  window: TimeWindow;
}

/** What charging costs without a charging card (ADR 0022). Gross; an absent amount means "not published", never "free". */
export interface AdHocPrice {
  currency: string;
  /** The price per kWh when it is the same at every hour. */
  energyPerKwh?: number;
  /** Prices per kWh by time of day, when they differ (then `energyPerKwh` is absent). */
  energyWindows?: EnergyWindow[];
  sessionFee?: number;
  timeFees: TimeFee[];
  free: boolean;
  furtherFees: boolean;
  observedAt?: string;
  /** How this price is paid ("qrCode", "emv", …), where a charge point has several prices that differ by it. */
  paymentMeans?: string[];
  source?: string;
}

export interface StationChargePoint {
  id: string;
  evseId?: string;
  operatorName?: string;
  connectors: Connector[];
  /** The one price in the shape before L6p; absent where there are several or it has limits an old client drops. */
  price?: AdHocPrice;
  /** Every price, usually one; several where they differ by payment means (ADR 0022, L6p). */
  prices?: AdHocPrice[];
}

export interface StationChargePoints {
  stationId: string;
  cheapestEnergyPerKwh?: number;
  currency?: string;
  chargePoints: StationChargePoint[];
  sources: DataSource[];
}

export interface StationComment {
  id: string;
  body: string;
  paidPriceCents?: number;
  experience?: string;
  createdAt: string;
  updatedAt: string;
  ownedByCurrentUser: boolean;
}

/** A charging network of the directory (ADR 0014); the name is its identity. */
export interface Operator {
  name: string;
  stationCount: number;
}

/** A MapKit JS token the backend signed (ADR 0023). */
export interface MapToken {
  token: string;
  expiresAt: string;
}
