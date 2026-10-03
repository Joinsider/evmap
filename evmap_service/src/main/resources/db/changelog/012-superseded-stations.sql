--liquibase formatted sql

--changeset evmap:012 dbms:postgresql
-- Master data, written only by sync through StationIngestionPort (ADR 0025).
--
-- superseded_by: the station that describes the same charge points better. Set after every sync run for the
-- stations an authority source does not maintain, when all their EVSE-IDs sit on the authority's stations, or when
-- they have no EVSE-ID and lie within 30 m of one: in Germany, register entries next to the Mobilithek station that
-- replaced them. Recomputed every run, so a station comes back when the reason goes away. Map, route corridor and
-- operator directory skip superseded stations; nothing is deleted, because favorites, comments and reports reference
-- stations and would cascade with them.
ALTER TABLE master.charging_station
    ADD COLUMN superseded_by UUID REFERENCES master.charging_station (id) ON DELETE SET NULL;
CREATE INDEX charging_station_superseded_idx ON master.charging_station (superseded_by)
    WHERE superseded_by IS NOT NULL;
