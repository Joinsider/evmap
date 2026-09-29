# Backup sidecar for the deployment stack (ADR 0019): pg_dump into a restic repository on an
# S3-compatible store, rotation, a scheduled restore test's building blocks, and heartbeats to the
# external monitor. Published by the release workflow as ghcr.io/joinsider/evmap-backup.
#
# The major version must match the database image in deploy/docker-compose.yml (postgis/postgis:17-*):
# pg_dump refuses to dump a newer server, and pg_restore may not read a newer dump.
FROM postgres:17-alpine

RUN apk add --no-cache restic curl ca-certificates tzdata \
    && addgroup -S backup && adduser -S -G backup -h /home/backup backup \
    # Pre-created so a fresh named volume mounted here inherits the unprivileged owner.
    && mkdir -p /home/backup/.cache && chown backup:backup /home/backup/.cache

COPY --chmod=755 evmap-backup.sh /usr/local/bin/evmap-backup

# restic keeps its index cache here; losing it only costs a re-download on the next run.
ENV RESTIC_CACHE_DIR=/home/backup/.cache/restic

USER backup
WORKDIR /home/backup

ENTRYPOINT ["evmap-backup"]
CMD ["schedule"]
