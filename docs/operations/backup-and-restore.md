# Backup, restore and monitoring

Operator runbook for the deployment stack (`deploy/docker-compose.yml`). The why is in
[ADR 0019](../adr/0019-backups-and-monitoring.md). All commands run on the VPS from the repository
checkout (or wherever `deploy/docker-compose.yml` and `deploy/.env` live):

```sh
alias evmap='docker compose -f deploy/docker-compose.yml'
```

## 1. One-time setup

### Backup target (SeaweedFS, on a host other than the VPS)

1. Create a bucket, e.g. `evmap-backup`, and an S3 identity that may `Read`, `Write`, `List` and
   `Tagging` on it — nothing else. Signed requests fail without a configured identity.
2. Make the S3 endpoint reachable from the VPS over **HTTPS** (reverse proxy or SeaweedFS' own TLS).
3. Generate a long random restic password (`openssl rand -base64 32`) and store it in your password
   manager **before** you use it. Without it no backup can ever be read again.

### `deploy/.env`

Add, next to the existing secrets:

| Variable | Example | Notes |
| --- | --- | --- |
| `RESTIC_REPOSITORY` | `s3:https://s3.example.org/evmap-backup` | restic's S3 syntax: endpoint, then bucket |
| `RESTIC_PASSWORD` | *(from the password manager)* | encrypts the repository |
| `BACKUP_S3_ACCESS_KEY` / `BACKUP_S3_SECRET_KEY` | | the SeaweedFS identity from above |
| `BACKUP_PUSH_URL` | `https://kuma.example.org/api/push/AbC123` | Uptime Kuma push URL **without** `?status=…` |
| `DISK_PUSH_URL` | `https://kuma.example.org/api/push/XyZ789` | second push monitor |
| `BACKUP_AT` | `03:30` | optional, local time (`TZ`, default `Europe/Berlin`) |
| `DISK_ALERT_PERCENT` | `85` | optional |

### Initialise the repository, then start

```sh
evmap pull backup
evmap run --rm backup init       # once per repository; fails loudly if the bucket is unreachable
evmap up -d backup
evmap run --rm backup backup     # first snapshot now instead of tonight
evmap run --rm backup snapshots
```

## 2. Monitors in Uptime Kuma

Uptime Kuma runs outside the VPS. Create a notification of type **ntfy** (topic of your choice,
subscribed in the ntfy app) and attach it to all five monitors:

| Name | Type | Target | Interval | Notes |
| --- | --- | --- | --- | --- |
| EVMap API | HTTP(s) | `https://evmap.joinside.de/actuator/health/container` | 60 s | the service is up |
| EVMap ingestion | HTTP(s) | `https://evmap.joinside.de/actuator/health` | 15 min | failed or stale sync (> 48 h) |
| EVMap sync complete | HTTP(s) | `https://evmap.joinside.de/actuator/health/sync` | 1 h | also DOWN when the last run was `PARTIAL`; lower priority |
| EVMap backup | Push | → `BACKUP_PUSH_URL` | heartbeat 25 h | one push per night; DOWN on failure or silence |
| EVMap disk | Push | → `DISK_PUSH_URL` | heartbeat 15 min | pushed every 5 min |

Health URLs answer with status only; details stay in the logs and in `master.sync_run`.

## 3. Weekly restore test

Every Sunday the nightly job restores the newest snapshot into a scratch database, compares it with
the live one and drops it again. Read the result with:

```sh
evmap logs backup | grep 'restore test'
```

A failure turns the backup monitor DOWN. To run it by hand at any time:

```sh
evmap run --rm backup restore-test
```

## 4. Restoring the live database

Replaces **everything** in the `evmap` database with a snapshot. User data written after that
snapshot is lost.

```sh
evmap run --rm backup snapshots                  # pick a snapshot id, or use "latest"
evmap stop api sync                              # nothing may write during the restore
evmap run --rm -e RESTORE_CONFIRM=evmap backup restore <snapshot-id>
evmap start api                                  # migrates forward if the snapshot predates a release
evmap start sync                                 # waits for the API to report healthy
```

Then check `/actuator/health/container`, open the app, and look at a recent comment.

### After losing the VPS entirely

1. New host with Docker; check out the repository at the release tag you were running.
2. Restore `deploy/.env` from your password manager (the restic password in particular).
3. `evmap up -d database` and wait for it to report healthy.
4. Restore as above (`api` and `sync` are not running yet, so skip the `stop`), then `evmap up -d`.

## 5. First rehearsal (phase 0 definition of done)

Once, after setup, prove the chain on the real VPS:

1. `evmap run --rm backup backup` and `evmap run --rm backup restore-test` — the log must end in
   `restore test passed` with matching row counts.
2. Point the push URLs at Uptime Kuma and confirm both monitors turn green.
3. Temporarily set `DISK_ALERT_PERCENT=1`, `evmap up -d backup`, confirm the ntfy alert arrives,
   and set it back.

Record the date in ADR 0019 once done.
