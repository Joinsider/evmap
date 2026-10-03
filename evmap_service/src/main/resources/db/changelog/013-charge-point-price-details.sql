--liquibase formatted sql

--changeset evmap:013 dbms:postgresql
-- Master data, written only by sync through StationIngestionPort (ADR 0022, gap filler L6p).
--
-- The Mobilithek's static AFIR feeds publish more than one energy price and one fee per minute: fees from a minute
-- of the session on, capped, by time of day and weekday, energy prices by time of day, and several ad-hoc rates
-- per charge point that differ by payment means. Shown as delivered, so stored as delivered.
--
-- ordinal: a charge point may have several prices now, one per payment means; 0 is the first.
ALTER TABLE master.charge_point_price DROP CONSTRAINT charge_point_price_pkey;
ALTER TABLE master.charge_point_price
    ADD COLUMN ordinal SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE master.charge_point_price
    ADD PRIMARY KEY (charge_point_id, ordinal);

-- time_fees: [{"fromMinute": 240, "toMinute": 390, "perMinute": 0.10, "cap": 12.00, "window": {"from": "08:00", "to": "20:00",
-- "days": ["monday", …]}}], gross; perMinute, cap and window may be null. Replaces the single fee from minute 0.
-- energy_windows: [{"perKwh": 0.49, "window": {…}}] where the energy price differs by time of day (energy_per_kwh
-- is then NULL). Read-only display data the API never filters on, hence JSON rather than child tables.
ALTER TABLE master.charge_point_price
    ADD COLUMN time_fees      JSONB,
    ADD COLUMN energy_windows JSONB;
UPDATE master.charge_point_price
SET time_fees = jsonb_build_array(jsonb_build_object('fromMinute', 0, 'perMinute', time_fee_per_minute))
WHERE time_fee_per_minute IS NOT NULL;
ALTER TABLE master.charge_point_price
    DROP COLUMN time_fee_per_minute;

-- payment_means: DATEX II tokens (qrCode, emv, nfc, …) of the rate, for labelling several prices apart.
-- vat_basis_stated: the source said per amount whether VAT is included; such a price wins over a live tariff whose
-- basis was inferred (OCPDB drops the flag).
-- stated_by: who published the price, credited next to it ("EnBW mobility+ AG und Co.KG via Mobilithek").
ALTER TABLE master.charge_point_price
    ADD COLUMN payment_means    VARCHAR(32)[],
    ADD COLUMN vat_basis_stated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN stated_by        VARCHAR(300);

--rollback ALTER TABLE master.charge_point_price DROP COLUMN stated_by, DROP COLUMN vat_basis_stated, DROP COLUMN payment_means;
--rollback ALTER TABLE master.charge_point_price ADD COLUMN time_fee_per_minute NUMERIC(8, 4);
--rollback UPDATE master.charge_point_price SET time_fee_per_minute = (time_fees -> 0 ->> 'perMinute')::numeric WHERE jsonb_array_length(time_fees) = 1 AND (time_fees -> 0 ->> 'fromMinute')::int = 0 AND time_fees -> 0 -> 'window' IS NULL;
--rollback ALTER TABLE master.charge_point_price DROP COLUMN energy_windows, DROP COLUMN time_fees;
--rollback DELETE FROM master.charge_point_price WHERE ordinal > 0;
--rollback ALTER TABLE master.charge_point_price DROP CONSTRAINT charge_point_price_pkey;
--rollback ALTER TABLE master.charge_point_price DROP COLUMN ordinal;
--rollback ALTER TABLE master.charge_point_price ADD PRIMARY KEY (charge_point_id);
