# 24. Impressum and privacy policy pages

- Status: Accepted 2026-10-02 — implemented on `claude/impressum-datenschutz-pages-e69ce5`; the 👤 steps in
  `docs/operations/legal-pages.md` are pending
- Date: 2026-10-02
- Deciders: Johannes Popp

## Context

The web client had neither an Impressum nor a privacy policy. ADR 0020 left the policy to be "published wherever
the product owner chooses" behind `PRIVACY_POLICY_URL`, and nothing was published, so the footer showed no link at
all. A German website needs an Impressum that is reachable from every page (§ 5 DDG), and the GDPR needs a
privacy policy (Art. 13). The App Store needs a privacy policy URL as well. `docs/privacy/data-processing.md` already
lists every processing in technical terms; what was missing was the text for visitors.

## Decisions

Agreed with the product owner on 2026-10-02, one question at a time.

### Two pages in the web app, one policy for web and iOS

`/impressum` and `/datenschutz` (English aliases `/imprint`, `/privacy`) are lazy routes in the Angular app,
linked from the footer on every page, the map included, and from the account page. The privacy policy covers the
website **and** the iOS app, so `PRIVACY_POLICY_URL` and App Store Connect point at
`https://evmap.joinside.de/de/datenschutz` and there is one text to keep current. The German text is the source and
the binding version; the English build is a translation and says so.

The policy is the readable form of `docs/privacy/data-processing.md`. **A change to one is a change to the other**:
new personal data, a new third party, a new retention period or cookie goes into both in the same PR.

### Operator: a private person, details from the container's environment

EVMap is run by a private person, non-commercially: the Impressum names name, postal address and e-mail (phone
optional); there is no register entry or VAT id, and no consumer-dispute statement, which only businesses owe.

Name and address are **not** in the repository. The web container writes them at start from `OPERATOR_NAME`,
`OPERATOR_STREET`, `OPERATOR_CITY`, `OPERATOR_COUNTRY`, `OPERATOR_EMAIL` and optionally `OPERATOR_PHONE` into
`/site-operator.json` (`docker/41-site-operator.sh`, the same pattern as the apple-app-site-association), and both
pages read it through `EvmapApi.siteOperator()`. It is served by nginx, not by the API, so the Impressum stays up when
the API is down, and an address change needs a restart, not a release. Without `OPERATOR_NAME` the file is a 404,
the entrypoint logs a warning and the pages say the details could not be loaded.

Rejected: the details in the code (the address would sit in the Git history, a move would need a release) and in
`GET /api/v1/legal` (no Impressum while the API is down).

### Self-hosted, no hosting processor

API, web and database run on the product owner's own server, the backups on a second one of their own, so the policy
names no hosting provider. **If the hosting moves to a provider, the policy and a data processing agreement change
with it.**

### Access logs keep shortened IP addresses only

The privacy policy promises that access logs hold no full IP address:

- The web container logs with its own format `evmap_anon`: the client is the *last* `X-Forwarded-For` entry (the one
  the reverse proxy appended), IPv4 without its last octet, IPv6 cut to its /48 prefix, anything else as `-`. The
  API logs no IP addresses at all. Docker rotates the web container's log (`json-file`, 3 × 10 MB).
- The reverse proxy (Nginx Proxy Manager) writes **no** access log for the EVMap host (`access_log off;`), and its
  error logs, which can name the client's full IP, are rotated daily and kept for 14 days. That is a 👤 step on the
  server, described in `docs/operations/legal-pages.md`; the policy says "14 days" for error logs because of it.

### Third parties named in the policy

Apple (MapKit JS on the web, Apple Maps in the app, Sign in with Apple), Google and GitHub (sign-in), MobiData BW and
transport.data.gouv.fr (fetched by the server without user data). US transfers rest on the EU-US Data Privacy
Framework (Art. 45 GDPR). The Impressum also lists the data sources and their licences, because CC BY and dl-de/by
ask for a named source.

## Consequences

- `PRIVACY_POLICY_URL` stays (iOS reads it from `GET /api/v1/legal`), but the web client no longer uses it: its links
  go to its own page. `LegalService` and `EvmapApi.legal()` are gone from the web client.
- The policy's "Stand" date changes whenever its content does.
- The text is not legal advice. It was written against the code as of this date; a review by a lawyer before the
  public launch is recommended, particularly for the Data Privacy Framework statements and the legal bases.

## Open points

1. 👤 Set the `OPERATOR_*` variables and the NPM settings on the server (`docs/operations/legal-pages.md`).
2. 👤 Set `PRIVACY_POLICY_URL=https://evmap.joinside.de/de/datenschutz` and enter the same URL in App Store Connect.
3. The iOS app links to the privacy policy but not to the Impressum. An app sold in the German App Store is a
   digital service of its own; a link from the iOS settings to `/impressum` (for example as a second URL in
   `GET /api/v1/legal`) would close that. Not part of this change.
