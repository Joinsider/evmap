--liquibase formatted sql

--changeset evmap:001 dbms:postgresql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE SCHEMA IF NOT EXISTS master;
CREATE SCHEMA IF NOT EXISTS user_data;

CREATE TABLE master.charging_station
(
    id                  UUID PRIMARY KEY,
    display_name        VARCHAR(500),
    street              VARCHAR(500),
    city                VARCHAR(200),
    postal_code         VARCHAR(32),
    country_code        CHAR(2)          NOT NULL,
    operator_name       VARCHAR(500),
    latitude            DOUBLE PRECISION NOT NULL,
    longitude           DOUBLE PRECISION NOT NULL,
    availability_status VARCHAR(32),
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    location            GEOGRAPHY(POINT, 4326) GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography) STORED
);
CREATE INDEX charging_station_location_idx ON master.charging_station USING GIST (location);
CREATE INDEX charging_station_filter_idx ON master.charging_station (country_code, operator_name);

CREATE TABLE master.charging_connector
(
    id             UUID PRIMARY KEY,
    station_id     UUID        NOT NULL REFERENCES master.charging_station (id) ON DELETE CASCADE,
    connector_type VARCHAR(64) NOT NULL,
    power_kw       NUMERIC(7, 2),
    quantity       INTEGER     NOT NULL DEFAULT 1
);
CREATE INDEX charging_connector_station_idx ON master.charging_connector (station_id);

CREATE TABLE master.station_source
(
    station_id        UUID         NOT NULL REFERENCES master.charging_station (id) ON DELETE CASCADE,
    source            VARCHAR(32)  NOT NULL,
    source_station_id VARCHAR(255) NOT NULL,
    last_updated_at   TIMESTAMPTZ  NOT NULL,
    field_provenance  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (source, source_station_id)
);
CREATE INDEX station_source_station_idx ON master.station_source (station_id);

CREATE TABLE user_data.user_identity
(
    id               UUID PRIMARY KEY,
    provider         VARCHAR(32)  NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_subject)
);

CREATE TABLE user_data.station_comment
(
    id               UUID PRIMARY KEY,
    station_id       UUID          NOT NULL REFERENCES master.charging_station (id) ON DELETE CASCADE,
    user_identity_id UUID          NOT NULL REFERENCES user_data.user_identity (id) ON DELETE CASCADE,
    body             VARCHAR(2000) NOT NULL,
    paid_price_cents INTEGER,
    experience       VARCHAR(32),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (paid_price_cents IS NULL OR paid_price_cents >= 0)
);
CREATE INDEX station_comment_station_idx ON user_data.station_comment (station_id, created_at DESC);
CREATE INDEX station_comment_user_idx ON user_data.station_comment (user_identity_id);

--rollback DROP SCHEMA user_data CASCADE; DROP SCHEMA master CASCADE;
