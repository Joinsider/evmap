# OCPDB upstream issues (drafts for the product owner)

ADR 0022, open point 1, decided 2026-10-02: the two OCPDB defects EVMap works around are reported upstream. The texts
below are ready to post on [binary-butterfly/ocpdb](https://github.com/binary-butterfly/ocpdb); the **product owner
posts them** from their own GitHub account, because they appear publicly under that name. Nothing here is a secret.

Checked against OCPDB release 2.16.2 (2026-09-28) and the public MobiData BW API on 2026-10-02. When either issue is
fixed upstream, follow "When OCPDB fixes it" at the end.

## 1. Comment on #278 (taxIncluded is dropped)

Post as a comment on <https://github.com/binary-butterfly/ocpdb/issues/278>.

```markdown
Some numbers from the public MobiData BW API (OCPI 3.0), read in full on 2026-10-02, in case they help prioritise this:

- 1.029 tariffs, none of which carries `tax_included` (neither on the tariff nor on a price component).
- Of the 95.970 German charge points whose connectors reference exactly one tariff, the VAT basis can be derived
  from the arithmetic for only 26.774 (a net price with more than two decimals that lands on whole cents with its
  rate, e.g. 0.4622 × 1.19 = 0.55). For 63.356 charge points (551 operators) it cannot.
- The values do not follow OCPI's "price excludes VAT" consistently: some operators publish round gross amounts
  (TankE 0.49, Berliner Stadtwerke 0.55, both "brutto" on their own price pages), others net amounts (IONITY 0.6018 →
  0.72 incl. VAT), and some gross values look net (Mainova 0.6426 = 0.54 × 1.19, charged as 0.64 gross).
- `datex2_chargecloud` (355 tariffs) carries no tax rate at all, so not even the arithmetic works there.

A consumer app that shows a price to drivers therefore has to keep a hand-checked list of operators to know whether
to add VAT. Passing `energyPrice.taxIncluded` through (as `tax_included` on the price component, or on the tariff
when all components agree) and leaving it absent when the feed does not state it would remove that guesswork.
The 3.5 and 3.7 static mappers both read `taxRate` next to it already
(`datex2_v3_5_json_static_mapper.py` / `datex2_v3_7_json_static_mapper.py`, the `energy_price` loop).
```

## 2. New issue: `pricePerMinute` is imported as OCPI `TIME` without conversion

Title: **DATEX II `pricePerMinute` is imported as OCPI `TIME` without converting to per hour**

```markdown
### What happens

The DATEX II 3.5 and 3.7 static importers map `PriceTypeEnum.PRICEPERMINUTE` onto `TariffDimensionType.TIME`
(`_price_type_map` in `datex2_v3_5_json_static_mapper.py` and `datex2_v3_7_json_static_mapper.py`) and copy
`energy_price.value` unchanged. OCPI defines the `TIME` price component as a price **per hour** (OCPI 2.2.1 and
3.0, Tariffs module, `TariffDimensionType.TIME`: "price per hour"). The published values are therefore per-minute
amounts labelled as per-hour amounts.

The export side assumes the same thing the other way round: `v3_5_static_export_mapper.py` maps `TIME` (and
`PARKING_TIME`) back to `PRICEPERMINUTE` without converting.

### Evidence (public MobiData BW API, OCPDB 2.16.2, 2026-10-02)

Non-zero `TIME` prices per source:

| source | tariffs | TIME components | median | most common |
|---|---:|---:|---:|---|
| `datex2_ecomovement` | 672 | 628 | 0.083 | 0.10, 0.0833, 0.08 |
| `datex2_chargecloud` | 355 | 465 | 0.06 | 0.10, 0.05, 0.06 |
| `datex2_enbw` | 2 | 2 | 0.15 | 0.1008, 0.2017 |

Read as per hour, a blocking fee of 0.10 €/h after four hours would be pointless; operators publish these fees per
minute (EnBW's 0.10084034 is 0.12 €/min incl. 19 % VAT, from minute 120). No source mixes the two: every median sits
in the per-minute range.

### Expected

Either convert to per hour on import (`value × 60`) so `TIME` means what OCPI says, or document that OCPDB's `TIME`
is per minute. A conversion would change the meaning of existing values for every consumer, so a note in the
changelog would help those who compensate today.
```

## When OCPDB fixes it

- **`tax_included` arrives:** nothing to deploy — `OcpiTariffs` already reads `tax_included` on the tariff and on
  each price component, and an explicit flag wins over the evidence and the table. Then check whether the table
  `evmap.pricing.mobidata.vat-basis` can shrink (ADR 0022) and whether `ShippedVatBasisTableTests` still holds.
- **`TIME` becomes per hour:** nothing to deploy either — the unit is detected per feed from the median on every
  refresh, and the API logs the change at WARN (`Time unit of MobiData BW feed … changed from … to …`). Watch for that line after an OCPDB release.
