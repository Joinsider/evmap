# 18. Additional identity providers, account linking, and an Angular web client

- Status: Accepted 2026-09-29 — nothing implemented yet; roadmap phase 1
- Date: 2026-09-29
- Deciders: Johannes Popp

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
- The access token is kept in memory; there is no cookie auth, so CSRF protection can stay disabled
  in the stateless API. Served as its own container behind the same reverse proxy as the API, on
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

## References

- Lastenheft §3 (i18n), §8, §10, §11 (v2)
- ADR 0002 (logging), `docs/privacy/data-processing.md`, `docs/roadmap.md`
