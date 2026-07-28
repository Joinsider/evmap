--liquibase formatted sql

--changeset evmap:002 dbms:postgresql
-- Operational history of the ingestion job. Sync-owned like the rest of `master`; the API reads it
-- read-only to report ingestion health, since the sync deployable runs without a web server and can
-- expose nothing itself.
CREATE TABLE master.sync_run
(
    id            UUID PRIMARY KEY,
    started_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ,
    status        VARCHAR(16) NOT NULL,
    processed     INTEGER     NOT NULL DEFAULT 0,
    created       INTEGER     NOT NULL DEFAULT 0,
    updated       INTEGER     NOT NULL DEFAULT 0,
    unchanged     INTEGER     NOT NULL DEFAULT 0,
    error_message VARCHAR(2000),
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'))
);
CREATE INDEX sync_run_started_idx ON master.sync_run (started_at DESC);

--rollback DROP TABLE master.sync_run;
