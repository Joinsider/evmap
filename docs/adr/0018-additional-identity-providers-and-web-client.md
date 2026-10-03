# 18. Additional identity providers, account linking, and an Angular web client

- Status: Accepted 2026-09-29 — implemented in roadmap phase 1 (`feature/phase-1-login-web`); account
  deletion and Apple token revocation followed in phase 2 ([ADR 0020](0020-account-area-and-app-store-obligations.md))
- Date: 2026-09-29
- Deciders: Joinsider

## Context

Moderation (reported comments, error reports, later vehicle submissions — see the roadmap) needs an
admin interface. The product owner wants it as a small **web interface in Angular**, built so that
it can grow into a **web version of the app** later. Alongside it, sign-in should offer
**Google and GitHub** in addition to Apple, in the iOS app and on the web.

Lastenheft §8 and §10 limit v1 to Sign in with Apple, so this is a v2 change. The groundwork is
there: `UserIdentity`/`UserIdentityService` is deliberately a thin internal ID layer "so additional
auth providers can be added later", and every user reference already goes through its UUID.

## Decision

### Providers are integrated directly in the backend

No identity server (Keycloak, Zitadel): one container less on the VPS, and existing Apple accounts
stay as they are. The backend keeps issuing its own access token (`AccessTokenService`); what
changes is how a provider identity is established:

- **Apple (iOS):** unchanged. The app sends the identity token and `AppleIdentityTokenVerifier`
  checks it. From Phase 2 on, the backend also exchanges the authorization code for a refresh token,
  because Apple requires revoking it when an account is deleted.
- **Apple (web), Google, GitHub:** authorization code flow with PKCE, **exchanged in the backend**.
  GitHub has no OpenID Connect for users, so it cannot be done with a token the client checks
  itself. The iOS app runs the same flow through `ASWebAuthenticationSession`, so there is one code
  path per provider rather than one per client.

Apple's rule that apps offering third-party sign-in must also offer Sign in with Apple is met.

### Accounts are linked automatically by verified e-mail

- Schema: `user_identity` is split into an internal **account** (id, admin flag, timestamps) and
  **provider identities** (provider, subject, e-mail, account id). The Liquibase migration creates
  one account per existing identity, **reusing its UUID**, so every existing foreign key (comments)
  keeps pointing at the same person.
- A new sign-in whose e-mail matches an existing identity's is attached to that account, **only if
  the provider marks the address as verified**: Google `email_verified`, GitHub's primary address
  with `verified: true`, and Apple's `email_verified`. An unverified match creates a separate
  account. Otherwise anyone who can register an unverified address at some provider could take
  over an account.
- Apple private-relay addresses match nothing. Such accounts stay separate.
- **A link cannot be undone** by the user; only deleting the whole account removes it. A wrong
  link can therefore only be fixed by hand in the database, which is one more reason the
  verified-address rule is not negotiable.
- The e-mail address becomes stored personal data: `docs/privacy/data-processing.md`, the data
  export and account deletion must include it. It is never logged (ADR 0002).

### Admin is a flag set by hand

`is_admin` on the account, set **only by a manual database update**. There is no API or UI that
grants it. Admin endpoints live under `/api/v1/admin/**` and require the flag in
`SecurityConfiguration`. The admin area is **publicly reachable** in the same web client as the
later user web app and protected by sign-in and that flag alone, without a network restriction or
an extra second factor. The backend check is the boundary; the Angular route guard only hides the
UI.

### The web client is an Angular workspace shaped for a full web app

- One Angular application with a shared core (auth, API client, i18n de/en, layout) and
  **lazy-loaded feature modules**. `admin` is the first, behind a route guard on the admin flag.
  The user-facing web app (map, stations, comments, later route planning) arrives as further
  modules, without restructuring.
- The web API client sits behind an interface, like `ChargingStationRepository` on iOS, so the
  GraphQL option (roadmap) stays open for both clients.
- The web session is an `HttpOnly` cookie (see *Web session cookie and CSRF* below), so page
  scripts never hold a credential. Served as its own container behind the same reverse proxy as the API, on
  the same origin, so no CORS configuration is needed.
- App strings go through i18n resources, like on iOS (Lastenheft §3).

## Consequences

### Positive

- One admin interface for every moderation queue that the roadmap brings.
- A web app later needs no auth or layout rework.
- Accounts stay identified by internal UUIDs; logs keep using them.

### Negative / accepted risks

- A provider-specific login flow per provider in our own code, and OAuth secrets (Google, GitHub,
  Apple key) to rotate and keep out of logs.
- Storing e-mail addresses widens the personal data held, even if only in `user_data`.
- Automatic linking trusts each provider's verification claim.

## Resolved points (2026-09-29)

- **Unlinking:** not offered (see above).
- **Admin access:** public, flag only (see above). Revisit if the admin area ever gets more
  than read-and-moderate powers.
- **Web map (for the later user web app):** MapKit JS, token signed by the backend; a switch to
  MapLibre is evaluated together with turn-by-turn in v3 (see roadmap).

## Phase 1 decisions (2026-09-29)

Agreed with the product owner when the phase started:

- **Apple e-mail on iOS.** The app now requests the `email` scope. Without it Apple's identity token
  carries no address and an Apple account could never be linked. Users can still choose "Hide My
  Email"; the relay address then links nothing. Existing Apple users keep an account without an
  address until Apple issues a token with one.
- **iOS redirect via HTTPS.** `ASWebAuthenticationSession` uses an `.https` callback on the web
  domain (iOS 17.4+; the app targets 26.5), backed by an associated domain whose
  `apple-app-site-association` the web container serves. One redirect URI per provider serves app
  and web alike. A custom URL scheme was rejected: GitHub allows one callback URL per OAuth app, so
  it would have needed a second GitHub app and a separate Google iOS client.
- **Routing.** The web container's nginx serves the Angular app and proxies `/api/**` to the API
  container, so the reverse proxy needs one rule for the web domain and API and web share an origin.
- **Admin content in phase 1.** A read-only overview of recent sync runs (`master.sync_run`) and a
  few counts. Moderation queues arrive in phase 2.
- **Web i18n.** `@angular/localize`: messages marked in templates, XLIFF files, one build per
  locale, nginx picks by `Accept-Language`. A missing translation fails the build.
- **Web domain.** `evmap.joinside.de`, the domain the API already has. The reverse proxy points it
  at the web container, which proxies `/api/**` — so the iOS app's base URL does not change.

## What phase 1 built (2026-09-29)

**Schema** (`007-accounts-and-provider-identities.sql`): `user_data.account` (id, `is_admin`,
timestamps) and `user_identity` renamed to `provider_identity` with `account_id`, `email`,
`email_verified`. Every existing identity got an account with the same uuid; a new account's first
identity reuses the account's uuid too, so the rollback stays clean. `station_comment.user_identity_id`
became `account_id` and references the account. The access token's `sub` is the account id —
tokens issued before the migration stay valid because the ids did not change.

**Backend** (`api.auth`, `api.admin`):
- `AccountService.signIn(VerifiedIdentity)` implements the linking rule. Linking happens only at an
  identity's *first* sign-in; an address that becomes verified later is refreshed but never merges
  two existing accounts, because that would silently move one person's contributions to another.
- `CodeSignIn` is one interface per code flow: `GoogleSignIn` (OIDC, ID token checked against
  Google's keys), `GitHubSignIn` (token, then `/user` and `/user/emails`; subject is the numeric id,
  the address is the *primary* one) and `AppleWebSignIn` (client secret signed per exchange with the
  `.p8` key by `AppleClientSecret`). `AppleIdentityTokenVerifier` accepts both audiences, the bundle
  id and the Services ID.
- Endpoints: `GET /api/v1/auth/providers` (enabled flows with client id, redirect URI and scope —
  clients add `state` and the PKCE challenge), `POST /api/v1/auth/{provider}/code`,
  `POST /api/v1/auth/apple/callback` (relays Apple's `form_post` to the web route as a 303),
  `GET /api/v1/me`, `GET /api/v1/admin/overview` and `/admin/sync-runs`.
- A provider with blank credentials is off: not listed, and its code endpoint answers 404. A
  rejected code is a 401 (`SignInFailedException`).
- The admin flag is read from the database per request (`AdminAccounts`), not put into the token, so
  revoking it works at once.

**Web** (`evmap_web/`): Angular 22, standalone components, lazy feature areas (`login`, `admin`,
`home`). `EvmapApi` is the seam, `RestEvmapApi` the implementation. `AuthService` holds no credential
at all, only the account `/me` answered with; PKCE verifier and `state` survive the provider round trip in `sessionStorage` and are
removed when read. The container (`nginxinc/nginx-unprivileged`) proxies `/api/**`, resolves the API
per request (so it starts before the API does), redirects locale-less paths by `Accept-Language`,
sends a strict CSP (critical-CSS inlining is off because it needs an inline script) and writes
`apple-app-site-association` from `APPLE_TEAM_ID`. Released as `ghcr.io/joinsider/evmap-web`.

**iOS**: `SignInPrompt` (replacing `AppleSignInPrompt`) shows the native Apple button — now with the
`email` scope — and one button per web provider the backend lists. `AuthSession.signIn(with:)` runs
SwiftUI's `WebAuthenticationSession` with an `.https` callback on the provider's redirect URI and
redeems the code through `ChargingStationRepository`. Entitlement:
`webcredentials:evmap.joinside.de`.

### Deviations from the plan

- **Apple on the web has no PKCE.** Apple's authorization endpoint does not support it. The client's
  `state` check is what binds Apple's answer to the tab that asked, and the code is useless without
  the client secret only the backend can sign.
- **Apple's `form_post`.** Requesting the e-mail scope forces `response_mode=form_post`, which only a
  server can receive; hence the relay endpoint, so the browser still has one callback route.
- **Reload signs out on the web.** A consequence of the in-memory token, accepted: signing in again
  is one click while the provider session lasts. The language switch is a full page load too.
- **`.env.example` files** were not updated by the agent (they are outside what it may read); the
  variables are listed in both compose files and in `docs/operations/sign-in-providers.md`.

## Open points (phase 1)

- **Refresh token for Apple revocation** — done in phase 2 (ADR 0020).
- **Rate limiting of the sign-in endpoints** is not built. Every code exchange costs a provider
  round trip; if abuse shows up in the logs, options are (a) a per-IP limit in the web container's
  nginx (`limit_req`, recommended — no code), or (b) a bucket in the API.

## Web session cookie and CSRF

Amendment. The web client first held the token in memory only, which signed users out on every
reload or language switch (both are full page loads). Decision of the product owner: a server-set
session cookie instead of script-readable storage.

- `POST /api/v1/auth/{provider}/code` answers `204` and sets `evmap_session` (`HttpOnly`, `Secure`,
  `SameSite=Lax`, `Path=/api`, `Max-Age` = `jwt-ttl`); the token is never in a body the page can read.
  `POST /api/v1/auth/logout` expires it. The value is the same signed token iOS sends as a bearer
  header; `BearerTokenFilter` reads the cookie only when there is no `Authorization` header.
- The web client restores its state on start (`provideAppInitializer` -> `GET /me`); 401 means signed out.
- **CSRF is enabled** (chosen option: `SameSite=Lax` + Spring CSRF token). `CookieCsrfTokenRepository`
  writes a script-readable `XSRF-TOKEN` cookie on every response; Angular's built-in XSRF support echoes
  it as `X-XSRF-TOKEN` on writes. A write is checked only when it carries the session cookie and no
  `Authorization` header, so iOS and anonymous callers are unaffected. The sign-in exchanges
  (`/apple`, `/apple/callback`, `/{provider}/code`) are exempt: Apple's `form_post` is cross-site by
  design and each exchange is bound to its flow by `state`/PKCE. The plain (non-XOR) request handler
  is required for the Angular echo. Same-origin via nginx still means no CORS.
- Development: the cookie is `Secure` by default (`SESSION_COOKIE_SECURE`); Chrome accepts that on
  `http://localhost`, set it to `false` for Safari or other plain-http origins.
- Trade-off: the browser now attaches the credential itself (hence CSRF); in exchange an XSS can no
  longer read or exfiltrate it, only act while the page is open.

## References

- Lastenheft §3 (i18n), §8, §10, §11 (v2)
- ADR 0002 (logging), `docs/privacy/data-processing.md`, `docs/roadmap.md`
