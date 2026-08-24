--liquibase formatted sql

--changeset evmap:005 dbms:postgresql
-- The operator directory (GET /api/v1/operators) groups every station by operator_name so the
-- client can offer a searchable list of charging networks. A dedicated index over that one column
-- lets the aggregate run as an index-only scan with pre-sorted input instead of a sequential scan
-- over 113k+ station rows followed by a hash aggregate. charging_station_filter_idx cannot serve
-- it: operator_name is its second column, behind country_code.
CREATE INDEX charging_station_operator_idx ON master.charging_station (operator_name);

--rollback DROP INDEX IF EXISTS master.charging_station_operator_idx;
