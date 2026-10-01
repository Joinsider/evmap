--liquibase formatted sql

--changeset evmap:011 dbms:postgresql
-- Master data, written only by sync through StationIngestionPort (ADR 0022).
--
-- operator_name: the operator of this charge point where a source knows it per charge point. A station
-- bundled from several operators' sites (Spain, Switzerland) carries the majority operator on
-- master.charging_station; the others were lost until now (ADR 0012, "Spain (L4)"). NULL means "the
-- station's operator": the ingestion writes the column only where it differs, so it stays sparse and the
-- operator directory and filters (ADR 0014) can afford to look at it.
ALTER TABLE master.charge_point
    ADD COLUMN operator_name VARCHAR(500);
CREATE INDEX charge_point_operator_idx ON master.charge_point (station_id)
    WHERE operator_name IS NOT NULL;

-- The ad-hoc price a register publishes for a charge point, already normalized to a gross amount.
-- Only prices the recognizer is certain about get a row; everything else has none, which the client
-- renders as "no price known". Live tariffs (Germany, OCPI) are never stored here: they are read on
-- demand like live status (ADR 0015) and are not master data.
--
-- Replaced together with its charge point: the ingestion deletes and re-inserts a station's charge
-- points, and the cascade takes the price with it.
CREATE TABLE master.charge_point_price
(
    charge_point_id     UUID PRIMARY KEY REFERENCES master.charge_point (id) ON DELETE CASCADE,
    currency            CHAR(3)       NOT NULL,
    -- Gross amounts. NULL means the component is absent, not that it is free.
    energy_per_kwh      NUMERIC(8, 4),
    session_fee         NUMERIC(8, 2),
    time_fee_per_minute NUMERIC(8, 4),
    -- Charging is free of charge. Never set together with an amount.
    free                BOOLEAN       NOT NULL DEFAULT FALSE,
    -- The source lists further fees (idle fees, time windows) that are deliberately not shown.
    further_fees        BOOLEAN       NOT NULL DEFAULT FALSE,
    observed_at         TIMESTAMPTZ
);

--rollback DROP TABLE master.charge_point_price;
--rollback DROP INDEX master.charge_point_operator_idx;
--rollback ALTER TABLE master.charge_point DROP COLUMN operator_name;
