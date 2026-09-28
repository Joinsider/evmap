--liquibase formatted sql

--changeset evmap:006 dbms:postgresql
-- One row per physically countable charge point (Ladepunkt / EVSE), for the sources that describe
-- them individually. BNetzA publishes up to six per Ladeeinrichtung in repeated column groups and
-- IRVE one row per id_pdc_itinerance; Open Charge Map reports only totals and writes none of these,
-- which is why master.charging_connector.charge_point_id stays nullable.
--
-- evse_id is the eMI3/ISO-15118 identifier AFIR obliges operators to publish. It is what live
-- availability joins on (ADR 0015) — the only identifier both the register and the national access
-- points carry. evse_id_normalized holds the comparison form: uppercased with every character
-- outside [A-Z0-9] removed, because the same id appears as DE*EBW*E912316*1, DEAEWE002501 and
-- "DE CSA 24D 006" in the same column. It is written by the ingestion rather than generated here,
-- so that exactly one implementation (sync.EvseIds) normalizes both sides of the join.
CREATE TABLE master.charge_point
(
    id                     UUID PRIMARY KEY,
    station_id             UUID         NOT NULL REFERENCES master.charging_station (id) ON DELETE CASCADE,
    source                 VARCHAR(32)  NOT NULL,
    source_charge_point_id VARCHAR(255) NOT NULL,
    evse_id                VARCHAR(64),
    evse_id_normalized     VARCHAR(64),
    UNIQUE (source, source_charge_point_id)
);
CREATE INDEX charge_point_station_idx ON master.charge_point (station_id);
-- Partial: only the rows carrying an EVSE-ID can ever be joined, and they are a minority
-- (30,3 % of declared Ladepunkte in the 2026-07-28 BNetzA edition).
CREATE INDEX charge_point_evse_idx ON master.charge_point (evse_id_normalized)
    WHERE evse_id_normalized IS NOT NULL;

-- Nullable on purpose: connectors of a source that reports only station-level totals keep hanging
-- off the station directly, exactly as before this migration. ON DELETE CASCADE matches the
-- station_id column so that re-ingesting a station cannot strand connector rows.
ALTER TABLE master.charging_connector
    ADD COLUMN charge_point_id UUID REFERENCES master.charge_point (id) ON DELETE CASCADE;
CREATE INDEX charging_connector_charge_point_idx ON master.charging_connector (charge_point_id);

--rollback ALTER TABLE master.charging_connector DROP COLUMN charge_point_id;
--rollback DROP TABLE master.charge_point;
