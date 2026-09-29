#!/bin/sh
# shellcheck shell=busybox
# EVMap database backup (ADR 0019). One script, several commands:
#
#   init           create the restic repository (once, when setting up a new backup target)
#   schedule       default: daily backup at BACKUP_AT, weekly restore test, disk heartbeat
#   backup         one backup now: pg_dump -> restic, rotation, heartbeat
#   restore-test   restore the newest snapshot into a scratch database, check it, drop it
#   restore [id]   replace the live database with a snapshot (needs RESTORE_CONFIRM=evmap)
#   snapshots      list the snapshots in the repository
#   disk-check     one disk-usage check and heartbeat
#
# Never prints credentials or row contents: only counts, sizes and snapshot ids reach the log.
set -eu
# set -e is suspended inside any function called from an `if` or `||` — which is how nightly() calls
# everything — so each step below checks its own exit status instead of relying on it.
# busybox ash supports pipefail; without it a failing pg_restore behind a pipe would look green.
set -o pipefail

: "${PGHOST:=database}"
: "${PGUSER:=evmap}"
: "${PGDATABASE:=evmap}"
export PGHOST PGUSER PGDATABASE

: "${BACKUP_AT:=03:30}"
: "${BACKUP_KEEP_DAILY:=7}"
: "${BACKUP_KEEP_WEEKLY:=4}"
: "${BACKUP_KEEP_MONTHLY:=3}"
# ISO weekday (1 = Monday … 7 = Sunday) on which the backup is followed by a restore test and a
# repository check. Empty disables both.
: "${RESTORE_TEST_WEEKDAY:=7}"
: "${DISK_PATH:=/pgdata}"
: "${DISK_ALERT_PERCENT:=85}"
: "${DISK_CHECK_INTERVAL:=300}"
: "${BACKUP_PUSH_URL:=}"
: "${DISK_PUSH_URL:=}"

SNAPSHOT_HOST=evmap
SNAPSHOT_TAG=evmap-db
DUMP_NAME=evmap.dump
SCRATCH_DB=evmap_restore_test

log() {
    echo "$(date '+%Y-%m-%dT%H:%M:%S%z') evmap-backup: $*"
}

# Uptime Kuma push monitor: status=up|down plus a short message. The URL is configured without its
# query string. A monitor that is not pushed to goes DOWN on its own, which also catches a dead
# container — so a failing push is logged, never fatal.
push() {
    url=$1 status=$2 message=$3
    [ -n "$url" ] || return 0
    curl -fsS -m 10 -o /dev/null --get \
        --data-urlencode "status=$status" --data-urlencode "msg=$message" "$url" \
        || log "heartbeat push failed (status=$status)"
}

require_repository() {
    for name in RESTIC_REPOSITORY RESTIC_PASSWORD PGPASSWORD; do
        eval "value=\${$name:-}"
        if [ -z "$value" ]; then
            log "$name is not set"
            exit 2
        fi
    done
}

# Deliberately a separate, manual step rather than "init if missing": restic retries a missing bucket
# for minutes instead of failing, and a typo in RESTIC_REPOSITORY would otherwise quietly start a new,
# empty repository instead of raising an alarm about the real one.
init() {
    require_repository
    log "initialising restic repository"
    restic init
}

backup() {
    require_repository
    log "dumping database $PGDATABASE"
    # Uncompressed custom format: restic deduplicates unchanged tables between days and compresses
    # itself; a compressed dump would look entirely new every night. --stdin-from-command lets restic
    # see pg_dump's exit code, so a failed dump never becomes a snapshot.
    restic backup --quiet --host "$SNAPSHOT_HOST" --tag "$SNAPSHOT_TAG" \
        --stdin-from-command --stdin-filename "$DUMP_NAME" -- \
        pg_dump --format=custom --compress=0 --no-password \
        || { log "backup FAILED: dump or upload did not complete"; return 1; }
    log "rotating: keep $BACKUP_KEEP_DAILY daily, $BACKUP_KEEP_WEEKLY weekly, $BACKUP_KEEP_MONTHLY monthly"
    restic forget --quiet --host "$SNAPSHOT_HOST" --tag "$SNAPSHOT_TAG" \
        --keep-daily "$BACKUP_KEEP_DAILY" --keep-weekly "$BACKUP_KEEP_WEEKLY" \
        --keep-monthly "$BACKUP_KEEP_MONTHLY" --prune \
        || { log "backup FAILED: rotation did not complete"; return 1; }
    log "backup finished: $(latest_snapshot)"
}

latest_snapshot() {
    restic snapshots --host "$SNAPSHOT_HOST" --tag "$SNAPSHOT_TAG" --latest 1 --compact \
        | awk '$1 ~ /^[0-9a-f]{8}$/ { print $1 " " $2 " " $3 }' | tail -n 1
}

# pg_restore of a snapshot into an existing, empty database.
restore_into() {
    target=$1 snapshot=$2
    restic dump --host "$SNAPSHOT_HOST" --tag "$SNAPSHOT_TAG" "$snapshot" "$DUMP_NAME" \
        | pg_restore --no-password --no-owner --no-privileges --exit-on-error --dbname "$target"
}

restore_test() {
    require_repository
    log "restore test: restoring the newest snapshot into $SCRATCH_DB"
    if ! { dropdb --no-password --if-exists --force --maintenance-db=postgres "$SCRATCH_DB" \
        && createdb --no-password --maintenance-db=postgres --template=template0 "$SCRATCH_DB"; }; then
        log "restore test FAILED: cannot create $SCRATCH_DB"
        return 1
    fi
    if ! restore_into "$SCRATCH_DB" latest; then
        dropdb --no-password --if-exists --force --maintenance-db=postgres "$SCRATCH_DB" || true
        log "restore test FAILED: pg_restore did not complete"
        return 1
    fi

    # The Liquibase history proves the schema came back whole; the table counts are for the log,
    # where the operator can compare them with the live database printed next to them.
    migrations=$(psql --no-password -d "$SCRATCH_DB" -Atc "SELECT count(*) FROM databasechangelog" 2>/dev/null || echo 0)
    live_migrations=$(psql --no-password -Atc "SELECT count(*) FROM databasechangelog" 2>/dev/null || echo 0)
    log "restore test: $migrations Liquibase changesets restored (live: $live_migrations)"
    count_tables "$SCRATCH_DB" | while read -r line; do log "restored  $line"; done
    count_tables "$PGDATABASE" | while read -r line; do log "live      $line"; done
    dropdb --no-password --if-exists --force --maintenance-db=postgres "$SCRATCH_DB" \
        || log "could not drop $SCRATCH_DB; the next restore test replaces it"

    if [ "$migrations" -eq 0 ] || [ "$migrations" -lt "$live_migrations" ]; then
        log "restore test FAILED: schema history missing or behind the live database"
        return 1
    fi
    log "restore test passed"
}

# Row counts of every master/user_data table — exact, since these are small enough to count.
count_tables() {
    psql --no-password -d "$1" -At <<'SQL'
SELECT format('SELECT %L || '' '' || count(*) FROM %I.%I;', schemaname || '.' || tablename, schemaname, tablename)
FROM pg_tables WHERE schemaname IN ('master', 'user_data') ORDER BY 1
\gexec
SQL
}

restore() {
    require_repository
    snapshot=${1:-latest}
    if [ "${RESTORE_CONFIRM:-}" != "$PGDATABASE" ]; then
        log "refusing to replace database $PGDATABASE: set RESTORE_CONFIRM=$PGDATABASE (see docs/operations/backup-and-restore.md)"
        exit 2
    fi
    log "replacing database $PGDATABASE with snapshot $snapshot"
    dropdb --no-password --if-exists --force --maintenance-db=postgres "$PGDATABASE"
    createdb --no-password --maintenance-db=postgres --template=template0 --owner="$PGUSER" "$PGDATABASE"
    restore_into "$PGDATABASE" "$snapshot"
    log "restore finished; start the api and sync containers again"
}

disk_check() {
    used=$(df -P "$DISK_PATH" | awk 'NR == 2 { sub("%", "", $5); print $5 }')
    if [ -z "$used" ]; then
        log "disk check: cannot read usage of $DISK_PATH"
        push "$DISK_PUSH_URL" down "cannot read disk usage"
        return 1
    fi
    if [ "$used" -ge "$DISK_ALERT_PERCENT" ]; then
        log "disk check: $DISK_PATH is ${used}% full (alert at ${DISK_ALERT_PERCENT}%)"
        push "$DISK_PUSH_URL" down "disk ${used}% full"
    else
        push "$DISK_PUSH_URL" up "disk ${used}% full"
    fi
}

# One nightly job: backup, and on RESTORE_TEST_WEEKDAY a restore test plus a repository check.
# Reports the outcome as one heartbeat, so the monitor alerts on any of the three failing.
nightly() {
    if ! backup; then
        push "$BACKUP_PUSH_URL" down "backup failed"
        return 1
    fi
    if [ "$(date +%u)" = "$RESTORE_TEST_WEEKDAY" ]; then
        if ! restore_test; then
            push "$BACKUP_PUSH_URL" down "restore test failed"
            return 1
        fi
        if ! restic check --quiet; then
            log "repository check FAILED"
            push "$BACKUP_PUSH_URL" down "repository check failed"
            return 1
        fi
        push "$BACKUP_PUSH_URL" up "backup and restore test ok"
    else
        push "$BACKUP_PUSH_URL" up "backup ok"
    fi
}

# Epoch seconds of the next BACKUP_AT in the container's TZ.
next_run() {
    at=$(date -d "$(date +%Y-%m-%d) $BACKUP_AT" +%s)
    [ "$at" -gt "$(date +%s)" ] || at=$((at + 86400))
    echo "$at"
}

schedule() {
    require_repository
    next=$(next_run)
    log "scheduled: daily at $BACKUP_AT ($(date +%Z)), disk check every ${DISK_CHECK_INTERVAL}s"
    while true; do
        disk_check || true
        if [ "$(date +%s)" -ge "$next" ]; then
            nightly || log "nightly job failed, retrying tomorrow"
            next=$(next_run)
        fi
        sleep "$DISK_CHECK_INTERVAL"
    done
}

command=${1:-schedule}
[ $# -gt 0 ] && shift
case "$command" in
    init) init ;;
    schedule) schedule ;;
    backup) backup ;;
    nightly) nightly ;;
    restore-test) restore_test ;;
    restore) restore "$@" ;;
    snapshots) require_repository; restic snapshots --host "$SNAPSHOT_HOST" --tag "$SNAPSHOT_TAG" ;;
    disk-check) disk_check ;;
    *) echo "usage: evmap-backup [init|schedule|backup|nightly|restore-test|restore [snapshot]|snapshots|disk-check]" >&2; exit 2 ;;
esac
