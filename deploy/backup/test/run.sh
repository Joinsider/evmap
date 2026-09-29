#!/bin/sh
# Rehearses backup, restore test and a real restore against throwaway containers (ADR 0019).
#
#   deploy/backup/test/run.sh                                        # Linux / CI
#   TEST_DB_IMAGE=kartoza/postgis:17-3.5 deploy/backup/test/run.sh    # Apple Silicon
set -eu
cd "$(dirname "$0")"
export COMPOSE_PROJECT_NAME=evmap-backup-rehearsal
compose() { docker compose -f compose.yml "$@"; }
sql() { compose exec -T -e PGPASSWORD=evmap database psql -h localhost -U evmap -d evmap -v ON_ERROR_STOP=1 -Atq "$@"; }
fail() { echo "REHEARSAL FAILED: $*" >&2; exit 1; }

trap 'compose logs --no-color backup s3 >&2 || true; compose down -v >/dev/null 2>&1 || true' EXIT

compose build backup
compose up -d database s3

echo "waiting for the database and the S3 endpoint"
i=0
until sql -c 'SELECT 1' >/dev/null 2>&1 && compose run --rm --no-deps --entrypoint sh backup -c 'curl -s -o /dev/null http://s3:8333/' >/dev/null 2>&1; do
    i=$((i + 1)); [ "$i" -lt 60 ] || fail "services did not come up"
    sleep 3
done

sql < seed.sql

echo "--- init"
compose run --rm backup init

echo "--- backup, twice"
compose run --rm backup backup
compose run --rm backup backup

# restic keeps the oldest snapshot as long as a policy is not filled, so with the production policy
# both same-day snapshots survive. A policy of exactly one daily snapshot must prune down to one.
echo "--- rotation"
compose run --rm -e BACKUP_KEEP_DAILY=1 -e BACKUP_KEEP_WEEKLY=0 -e BACKUP_KEEP_MONTHLY=0 backup backup
[ "$(compose run --rm backup snapshots | grep -c evmap-db)" = 1 ] || fail "rotation did not prune"

echo "--- restore test"
compose run --rm backup restore-test | tee restore-test.log
grep -q "restore test passed" restore-test.log || fail "restore test did not pass"
grep -q "restored  master.charging_station 2000" restore-test.log || fail "stations not restored"
rm -f restore-test.log
[ "$(sql -c "SELECT count(*) FROM pg_database WHERE datname = 'evmap_restore_test'")" = 0 ] \
    || fail "scratch database left behind"

echo "--- a restore without confirmation is refused"
if compose run --rm backup restore >/dev/null 2>&1; then fail "restore ran without RESTORE_CONFIRM"; fi

echo "--- real restore brings deleted user data back"
sql -c "DELETE FROM user_data.user_identity"
[ "$(sql -c 'SELECT count(*) FROM user_data.station_comment')" = 0 ] || fail "cascade did not delete"
compose run --rm -e RESTORE_CONFIRM=evmap backup restore
[ "$(sql -c 'SELECT count(*) FROM user_data.station_comment')" = 1 ] || fail "comment not restored"
[ "$(sql -c 'SELECT count(*) FROM master.charging_station WHERE ST_X(location::geometry) > 9')" = 2000 ] \
    || fail "geography column not restored"

echo "--- nightly with an unreachable heartbeat URL still succeeds"
compose run --rm -e BACKUP_PUSH_URL=http://s3:1/unreachable backup nightly

echo "--- disk check"
compose run --rm backup disk-check
compose run --rm -e DISK_ALERT_PERCENT=0 backup disk-check | grep -q "alert at 0%" \
    || fail "disk alert threshold not applied"

echo "REHEARSAL PASSED"
