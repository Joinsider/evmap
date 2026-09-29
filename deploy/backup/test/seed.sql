-- A miniature of the production schema: both schemas, a PostGIS column, a cascade between user
-- tables, and a Liquibase history — the things a restore can get wrong.
-- kartoza/postgis (the Apple Silicon stand-in) preinstalls pg_cron, which can only be created in the
-- database named in cron.database_name and so cannot be restored into the scratch database. The
-- production image postgis/postgis has no pg_cron; dropping it makes the stand-in look like production.
DROP EXTENSION IF EXISTS pg_cron;
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE SCHEMA master;
CREATE SCHEMA user_data;

CREATE TABLE databasechangelog (id VARCHAR(255) NOT NULL, author VARCHAR(255) NOT NULL, filename VARCHAR(255) NOT NULL);
INSERT INTO databasechangelog VALUES ('001', 'evmap', 'a.sql'), ('002', 'evmap', 'b.sql'), ('003', 'evmap', 'c.sql');

CREATE TABLE master.charging_station (
    id SERIAL PRIMARY KEY,
    display_name TEXT NOT NULL,
    location GEOGRAPHY(Point, 4326) NOT NULL
);
INSERT INTO master.charging_station (display_name, location)
SELECT 'Station ' || n, ST_MakePoint(9.0 + n / 1000.0, 48.7)::geography FROM generate_series(1, 2000) n;

CREATE TABLE user_data.user_identity (id UUID PRIMARY KEY);
CREATE TABLE user_data.station_comment (
    id SERIAL PRIMARY KEY,
    user_identity_id UUID NOT NULL REFERENCES user_data.user_identity (id) ON DELETE CASCADE,
    body TEXT NOT NULL
);
INSERT INTO user_data.user_identity VALUES ('00000000-0000-0000-0000-000000000001');
INSERT INTO user_data.station_comment (user_identity_id, body)
VALUES ('00000000-0000-0000-0000-000000000001', 'rehearsal comment');
