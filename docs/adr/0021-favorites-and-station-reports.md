# 21. Favorites and station error reports

- Status: Accepted 2026-09-30 — implemented in roadmap phase 3 (`feature/phase-3-favorites-and-reports`); no 👤 steps
- Date: 2026-09-30
- Deciders: Joinsider

## Context

Phase 3 of the v2 roadmap adds two small, everyday features (Lastenheft §11, "Community und Konto"):

- **Favorites**: remember stations, list them, highlight them on the map. Without an account they live
  on the device only; once signed in they are synchronized with the account, so app, web and CarPlay
  (phase 9) see the same list. A synchronized favorite is user data: it belongs to the account, goes
  into the data export and disappears with the account (ADR 0020).
- **Station error reports**: a user tells us a station does not exist any more, has the wrong power or
  connector, or is defective. The report is user data and lands in the admin queue; it never changes
  master data directly, because `master.*` is owned by the sync (ADR 0012, per-field provenance).

## Decisions

Agreed with the product owner when the phase started (2026-09-30). Points are added as they are
decided.

### Signing in merges the favorites of device and account (union)

On sign-in the device's favorites are uploaded to the account and the account's favorites come down to
the device; afterwards both hold the union. Nothing is lost on either side. Rejected: "the account wins"
(local favorites of someone who had used the app before signing in would silently vanish) and asking at
the first sign-in (one more dialog for a choice almost nobody wants to make differently).

### Signing out clears the local favorites

Signing out empties the device's favorite list, so a device carries nothing of the account once it is
signed out (shared devices, and the local copy is otherwise a second, unmanaged copy of synchronized
user data). The favorites stay in the account and come back at the next sign-in. Because the merge at
sign-in has already uploaded everything local, nothing is lost by this. The same clearing applies when
the session ends without a tap (a 401 in the account area, ADR 0020). Rejected: keeping the list
(a signed-out device would still show the account's favorites) and asking on every sign-out.

### A station report is a closed reason plus an optional short text

Reasons are a closed set (`gone`, `wrong_power`, `wrong_connector`, `defective`, `other`), as with
comment reports (ADR 0020), plus an optional free text of at most 500 characters so an admin can tell
what is wrong (e.g. which power is right). The text is user data like a comment body: part of the
export and of "my contributions", removed with the account, and never logged (ADR 0002). Rejected:
reason only (an admin could not act on "wrong power") and structured fields per reason (more UI and
schema for a queue that a human reads anyway).

### Only signed-in users can report a station

Like comments, `POST` on a station report needs an account. A report can be tied to an account for
limiting and deletion, and no anonymous write endpoint appears (there is no rate limiting or spam
protection yet, ADR 0020 open point 1). Signed out, the app shows the normal sign-in prompt. Favorites,
in contrast, work signed out (device only).

### Admins resolve or dismiss; master data is never touched

A station report is `open`, `resolved` (the admin confirmed it or fixed it at the source) or
`dismissed`. Neither outcome edits `master.*`: the next sync run is the only writer, so a correction
that should stick has to happen at the source or in the source mapping. The reporter sees the status
in "my contributions". The admin queue groups open reports of one station and reason, so ten people
reporting the same defect are one line, not ten. No admin note to the reporter: that would be a second
free text to moderate (ADR 0020 open point 2, notifying reporters, stays open).

### The favorite list is a sheet opened from a map toolbar button

iOS gets a star button next to the locate and filter buttons; it opens the list as a sheet, and a tap on
an entry centers the map and opens the station. The map stays the app's only main screen. Rejected: a
tab bar (a larger restructuring that belongs with the route planner, phase 4, when a second area
exists) and a place inside the settings sheet (an everyday feature nobody would find there).

### Defaults chosen without a question

- A favorite is `(account, station)`; the station id is the master UUID, `ON DELETE CASCADE` from
  `master.charging_station` like comments, so a station the sync removes drops out of every list. The
  device copy drops ids the API no longer knows at the next sync.
- An account holds at most 500 favorites, enforced in the API, so the table stays bounded.
- Favorites are written idempotently (`PUT`/`DELETE` of one station, bulk `PUT` for the sign-in merge);
  the device's list is not part of "reset settings" (ADR 0014 resets filters and provider preferences only).
- One report per account, station and reason while it is open; a repeat is idempotent, like comment
  reports. Rate limiting stays open together with ADR 0020 point 1.
- Favorites do not go to the web in this phase: the web client has no station view until phase 8. The web
  gets only the admin queue for station reports.

## What phase 3 built (2026-09-30)

**Schema.** `010-favorites-and-station-reports.sql`: `user_data.favorite_station` (primary key
`(account_id, station_id)`) and `user_data.station_report` (closed reason, optional note ≤ 500, status
`open`/`resolved`/`dismissed`, a partial unique index on `(reporter, station, reason) WHERE status = 'open'`).
Both cascade from `user_data.account` and from `master.charging_station`, and neither touches `master.*`.

**Backend.**
- `api.favorite`: `GET /me/favorites` (the map's station fields plus `favoritedAt`, newest first),
  `PUT`/`DELETE /me/favorites/{stationId}` (idempotent; an unknown station is a 404, the 500th favorite a
  400), `POST /me/favorites/merge` (the sign-in merge: adds what is known and fits, answers with the union).
- `api.stationreport`: `POST /stations/{id}/reports` (signed in; reason and note validated, a repeat of an
  open report is a no-op), and under `/admin`: `GET /station-reports` (grouped per station and reason with
  the count and up to five latest notes, never a reporter), `POST /station-reports/{stationId}/{reason}/resolve`
  and `/dismiss`. Logs carry ids, reason and counts; the note is logged only as a yes/no.
- Export and "my contributions" carry `favorites` and `stationReports` (`MyDataRepository`,
  `MyDataTests.exportKnowsEveryUserDataTable` now lists the two tables). The admin overview gained
  `openStationReports`. `StationNotFoundException`'s constructor became public for the new packages.

**Web.** `/admin/station-reports` (queue with "Erledigt" and "Abweisen", no reporter), a tab and an overview
card for it, station reports in `/account`, all texts in German and English.

**iOS.** `Features/Favorites` (`FavoriteList`, `FavoritesStoring`/`UserDefaultsFavoritesStore`,
`FavoritesViewModel`, `FavoritesScreen`): a star button in the map toolbar opens the list, a star in the
station screen toggles, favorites carry a star badge on their pin (or on a cluster that holds one). The
view model follows the session (`sessionChanged`, driven by `.task(id:)` in `MapScreen`): merge on sign-in,
clear on sign-out, never clear at a signed-out launch. Changes apply at once and are undone when the backend
refuses them; a 401 signs the session out. "Report a problem" (signed in only) opens `StationReportScreen`;
the account screen lists station reports.

### Deviations from the plan

- The backend's `GET /me/favorites` is not used by iOS (the merge response already is the list); it exists
  for the web (phase 8) and CarPlay (phase 9), which would otherwise need their own read path.
- A merge that would exceed the limit adds what fits instead of failing, so a sign-in never breaks over it.

## Open points

1. **Rate limiting of report endpoints.** As ADR 0020 open point 1, now also for station reports: one open
   report per account, station and reason, but no limit on how many stations one account reports. Options:
   (a) leave as is and watch the logs (recommended while the effect is bounded to an admin queue);
   (b) a per-account limit in the API; (c) `limit_req` in the web container's nginx.
2. **Telling the reporter what was decided.** They see the status in "my contributions" only; ADR 0020
   open point 2 (push or e-mail) covers this too.
3. **Favorites on the web.** The web has no station view before phase 8; the endpoints are ready for it.

## References

- ADR 0020 (export and deletion of user data, admin queue), ADR 0018 (accounts)
- Lastenheft §11; `docs/roadmap.md`, phase 3
