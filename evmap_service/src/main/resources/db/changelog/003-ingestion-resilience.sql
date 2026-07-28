--liquibase formatted sql

--changeset evmap:003 dbms:postgresql
-- Ingestion no longer runs as one all-or-nothing transaction, so a run can now finish having
-- committed most of its work while losing individual records. That outcome needs somewhere to be
-- recorded, and a status that is neither "everything worked" nor "nothing did".
ALTER TABLE master.sync_run
    ADD COLUMN failed INTEGER NOT NULL DEFAULT 0;

ALTER TABLE master.sync_run
    DROP CONSTRAINT IF EXISTS sync_run_status_check;
ALTER TABLE master.sync_run
    ADD CONSTRAINT sync_run_status_check
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED'));

-- Per-source, per-scope high-water marks for incremental ingestion. Sync-owned bookkeeping like
-- master.sync_run: it describes the ingestion, not the stations, but lives in `master` because the
-- sync deployable is the only writer and the API reads it read-only.
--
-- `scope` is whatever subdivision an adapter crawls independently — a country code for Open Charge
-- Map. Kept deliberately generic so a future source can key on something else without a migration.
CREATE TABLE master.source_sync_state
(
    source     VARCHAR(32) NOT NULL,
    scope      VARCHAR(64) NOT NULL,
    watermark  TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source, scope)
);

--rollback DROP TABLE master.source_sync_state;
--rollback ALTER TABLE master.sync_run DROP CONSTRAINT IF EXISTS sync_run_status_check;
--rollback ALTER TABLE master.sync_run ADD CONSTRAINT sync_run_status_check CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','SKIPPED'));
--rollback ALTER TABLE master.sync_run DROP COLUMN failed;
