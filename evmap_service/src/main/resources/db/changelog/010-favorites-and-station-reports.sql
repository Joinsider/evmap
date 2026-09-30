--liquibase formatted sql

--changeset evmap:010 dbms:postgresql
-- Favorites and station error reports (roadmap phase 3, ADR 0021). Both are user_data owned by the
-- account that acts, both reference a master station by its uuid, and both go with the account.
-- Neither ever writes to master.*: the sync is its only writer.

-- A favorite is the pair (account, station); the station going away takes the favorite with it.
CREATE TABLE user_data.favorite_station
(
    account_id UUID        NOT NULL REFERENCES user_data.account (id) ON DELETE CASCADE,
    station_id UUID        NOT NULL REFERENCES master.charging_station (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, station_id)
);
CREATE INDEX favorite_station_station_idx ON user_data.favorite_station (station_id);

-- What is wrong with a station, as seen by a user. The free text is optional and is the person's own
-- data (export, deletion, never logged). One open report per account, station and reason; a resolved or
-- dismissed one does not block a new report about the same thing.
CREATE TABLE user_data.station_report
(
    id          UUID PRIMARY KEY,
    station_id  UUID        NOT NULL REFERENCES master.charging_station (id) ON DELETE CASCADE,
    reporter_id UUID        NOT NULL REFERENCES user_data.account (id) ON DELETE CASCADE,
    reason      VARCHAR(16) NOT NULL CHECK (reason IN ('gone', 'wrong_power', 'wrong_connector', 'defective', 'other')),
    note        VARCHAR(500),
    status      VARCHAR(16) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'dismissed')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolved_by UUID REFERENCES user_data.account (id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX station_report_open_uq ON user_data.station_report (reporter_id, station_id, reason) WHERE status = 'open';
CREATE INDEX station_report_queue_idx ON user_data.station_report (created_at) WHERE status = 'open';
CREATE INDEX station_report_reporter_idx ON user_data.station_report (reporter_id);

--rollback DROP TABLE user_data.station_report;
--rollback DROP TABLE user_data.favorite_station;
