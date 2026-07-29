--liquibase formatted sql

--changeset evmap:004 dbms:postgresql
-- The nearby query aggregates MAX(power_kw) per station to colour map pins and to
-- rank results when a wide viewport hits the row limit. Carrying power_kw in the
-- station_id index keeps that aggregation index-only instead of a heap lookup per
-- connector.
DROP INDEX IF EXISTS master.charging_connector_station_idx;
CREATE INDEX charging_connector_station_idx ON master.charging_connector (station_id, power_kw);

--rollback DROP INDEX IF EXISTS master.charging_connector_station_idx; CREATE INDEX charging_connector_station_idx ON master.charging_connector (station_id);
