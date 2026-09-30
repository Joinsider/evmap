# 19. Database backups to a second host, and monitoring from outside the VPS

- Status: Accepted — roadmap phase 0; restore rehearsal on the VPS pending (👤)
- Date: 2026-09-29
- Deciders: Johannes Popp

## Context

Everything EVMap runs on is one private VPS (Lastenheft §6): the API, the sync container and the
PostGIS database in one Compose stack (`deploy/docker-compose.yml`). The database lives in a Docker
volume, and nothing copies it anywhere. Master data could be re-crawled — slowly, and only while
BNetzA, IRVE and Open Charge Map cooperate — but user data (identities, comments) exists nowhere
else. Phases 1–3 of the roadmap add more of it: linked sign-ins, reports, favourites.

Failures are equally invisible. [ADR 0004](0004-ingestion-run-history-and-health-reporting.md) made
ingestion state readable over `/actuator/health`, but nothing asks. A `PARTIAL` run — a source that
broke, or records that could not be ingested ([ADR 0013](0013-per-source-isolation-in-the-sync-run.md))
— leaves even that endpoint `UP` by design, so the one outcome most likely to go unnoticed for weeks
had no signal at all. Nor does anything watch the disk the database writes to.

The third item of phase 0, restricting `permitAll` on `/api/v1/stations/**` to `GET`, was already
done in PR #8 and is not part of this change.

## Decision

### 1. Nightly logical backup with restic, into S3-compatible storage on another host

A `backup` service joins `deploy/docker-compose.yml`. Its image (`deploy/backup/`,
`ghcr.io/joinsider/evmap-backup`, published by the release workflow next to the API image) is
`postgres:17-alpine` plus restic and curl, driven by one script, `evmap-backup`:

- **What:** the whole database — `master`, `user_data` and the Liquibase history — as one `pg_dump`
  in custom format. A restore is one step, and the map is complete again at once instead of after a
  day of throttled crawling. `pg_dump` 17 matches the `postgis/postgis:17` server.
- **Where:** a restic repository on an **S3-compatible** endpoint (`RESTIC_REPOSITORY=s3:https://…`);
  the product owner runs **SeaweedFS** for it. The endpoint **must be on a different host** than the
  VPS, or it is not a second location. restic encrypts client-side, so the store never sees
  plaintext user data.
- **When and how long:** daily at `BACKUP_AT` (03:30 Europe/Berlin); `restic forget --prune` keeps
  **7 daily, 4 weekly, 3 monthly** snapshots. Nothing in any backup is older than about three months,
  which is the bound the privacy notes give for deleted accounts surviving in backups.
- The dump is uncompressed (`--compress=0`) so restic can deduplicate unchanged tables from night to
  night and compress once; `--stdin-from-command` lets restic see `pg_dump`'s exit code, so a failed
  dump never becomes a snapshot.
- The repository is created by an explicit, one-time `init`, not on demand: restic retries a missing
  bucket for minutes instead of failing, and a typo in `RESTIC_REPOSITORY` must raise an alarm about
  the real repository rather than silently start a new, empty one.

### 2. The restore is rehearsed every week, automatically

On `RESTORE_TEST_WEEKDAY` (Sunday) the nightly job restores the newest snapshot into a scratch
database `evmap_restore_test` on the same server, checks that the Liquibase history came back whole
(at least as many changesets as the live database), logs per-table row counts of both side by side,
drops the scratch database, and runs `restic check`. Any failure reports DOWN on the backup
heartbeat. A backup nobody has restored is a hope, not a backup; this makes the rehearsal a standing
property instead of a one-off.

The real restore (`evmap-backup restore [snapshot]`) drops and recreates the live database and
refuses to run without `RESTORE_CONFIRM=evmap`. The procedure — stop `api` and `sync`, restore,
start `api` (it migrates) then `sync` — is in `docs/operations/backup-and-restore.md`.

`deploy/backup/test/run.sh` rehearses the whole cycle — init, backup, rotation, restore test, a real
restore of deleted user data, heartbeat failure, disk threshold — against throwaway PostGIS and
SeaweedFS containers. CI runs it on every change to `deploy/backup/**`.

### 3. Monitoring runs on another host; the VPS only answers and pushes

Uptime Kuma runs **outside the VPS** (set up by the product owner next to SeaweedFS), so a dead VPS
is noticed. Alerts go out through **ntfy** as push notifications. The repository's side:

| Monitor (Uptime Kuma) | Type | Healthy when | Meaning of DOWN |
| --- | --- | --- | --- |
| `/actuator/health/container` | HTTP | 200 | the API cannot serve requests |
| `/actuator/health` | HTTP | 200 | ingestion failed or is older than `SYNC_MAX_AGE` (ADR 0004) |
| `/actuator/health/sync` | HTTP | 200 | as above, **or the last run was `PARTIAL`** |
| backup heartbeat | Push | pushed `up` within 25 h | backup, restore test or repository check failed, or the container is dead |
| disk heartbeat | Push | pushed `up` within 15 min | the database volume is ≥ `DISK_ALERT_PERCENT` (85 %) full, or the container is dead |

The push monitors are the reason no alerting daemon runs on the VPS: a heartbeat that stops arriving
is itself the alarm, so a crashed backup container is caught by the same rule as a failed backup.

### 4. `PARTIAL` gets its own health group, not the root endpoint

A new indicator, `ingestionCompleteness`, reports the custom status **`INCOMPLETE`** when the last
completed run was `PARTIAL`. `application.yaml` orders it **below `UP`** for the root endpoint, so
`/actuator/health` keeps exactly ADR 0004's meaning, and defines a group **`sync`**
(`ingestion` + `ingestionCompleteness`) in which `INCOMPLETE` ranks above `UP` and maps to **503**.
A lost source and a dead ingestion are therefore two different monitors with two different
priorities. The `container` group is untouched, so the cold-start deadlock ADR 0004 rules out still
cannot be expressed. `HealthGroupTests` pins both indicators and asserts the three URLs' status codes
against the real `management.*` configuration.

## Consequences

### Positive

- User data survives the loss of the VPS, encrypted, with a restore that is exercised weekly and in CI.
- A `PARTIAL` run, a full disk, a missed backup and a dead VPS each reach the owner's phone.
- Nothing new listens on the VPS: the backup container has no port, the monitor pulls public health
  URLs and receives pushes on its own host.

### Negative / accepted risks

- **Logical backup only.** A nightly dump means up to 24 h of user data can be lost; point-in-time
  recovery (WAL archiving) would close that and is not worth its complexity at the current volume.
- **The restic password is a single point of failure.** Lose it and every snapshot is unreadable. It
  has to be stored off the VPS (password manager); the runbook says so.
- **The restore test runs on the production database server.** It briefly doubles the database's
  disk use and costs I/O on Sunday night. Acceptable at today's size; revisit if the dump grows into
  gigabytes.
- **A second image to release.** `evmap-backup` is versioned with the API image; a change to
  `deploy/backup/**` alone triggers a release.
- **`/actuator/health/sync` is public**, like the other health URLs. It exposes status only
  (`show-details: never`), no counts or error messages.
- **Uptime Kuma, SeaweedFS and ntfy are outside this repository.** Their setup is documented, not
  automated; the product owner operates them.

## Open points

1. **Retention of a deleted account in backups.** *Resolved in phase 2 (ADR 0020): option (a), the
   3-month bound is accepted and documented.* With phase 2's account deletion, the privacy notes
   must state that deleted data leaves backups after at most ~3 months. Options: (a) accept and
   document the 3-month bound (recommended — standard practice, and restic cannot rewrite old
   snapshots cheaply); (b) additionally keep a deletion log and re-apply it after any restore, so a
   restore never resurrects a deleted account. To be decided in phase 2.

## References

- `deploy/backup/Dockerfile`, `deploy/backup/evmap-backup.sh`, `deploy/backup/test/run.sh`
- `deploy/docker-compose.yml` — the `backup` service
- `docs/operations/backup-and-restore.md` — setup, monitors, restore runbook
- `evmap_service/src/main/java/de/joinside/evmap_service/api/health/IngestionCompletenessHealthIndicator.java`
- `evmap_service/src/main/resources/application.yaml` — status order and the `sync` group
- [ADR 0004](0004-ingestion-run-history-and-health-reporting.md), [ADR 0013](0013-per-source-isolation-in-the-sync-run.md)
