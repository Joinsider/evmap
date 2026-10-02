# VAT basis per operator (German ad-hoc prices)

Runbook for the hand-kept table `evmap.pricing.mobidata.vat-basis` in `evmap_service/src/main/resources/application.yaml`
(ADR 0022, phase 5r). The why is in [ADR 0022](../adr/0022-prices-at-the-station.md): OCPDB drops the flag that says
whether a tariff includes VAT (binary-butterfly/ocpdb#278). A tariff whose arithmetic does not prove it net is
therefore shown only for an operator listed here, and **a price shown wrongly is worse than none**.

## The rule for an entry

An operator is entered when an **ad-hoc price** (direct payment, QR code, card terminal — not an app, card,
subscription or roaming tariff) on its **own official page** matches a value of its OCPDB tariffs exactly:

- page price = feed value → `GROSS`
- page price = feed value × 1,19, rounded to the cent → `NET`

A third-party source (comparison site, press, forum screenshot) counts only if it is **at most two months old**, and
such an entry is **spot-checked by the product owner** before it ships. Near misses are not entered. A platform
whose site hosts set their own prices (ChargePoint, smopi, Spirii …) is entered only if it states officially that all
its published prices are gross (or net).

Every amount of a listed operator is shown **rounded to whole cents**, as the price pages charge them (Mainova
publishes 0,6426 and charges 0,64 €). An operator whose page rounds some prices and not others cannot be represented
and stays out (E-Werk Mittelbaden).

Feeds without a VAT rate (`datex2_chargecloud`): a `NET` entry assumes Germany's 19 %.

## How the table keeps itself honest

- **Contradiction → suspension.** When the feed contradicts an entry — an operator listed `GROSS` with a tariff the
  arithmetic proves net, or one listed `NET` with a tariff that is a whole-cent net price with VAT already added
  (0,6426 = 0,54 × 1,19) — the API suspends the entry for the rest of the process's life and logs at WARN:
  `MobiData BW tariff … of … contradicts its VAT basis entry …; entry suspended`. That operator's prices are gone
  until someone checks the page again and fixes the entry. Look for the line in the API's logs.
- **Age.** At startup the API names every entry older than `recheck-after` (six months) at WARN:
  `… VAT basis entr(ies) checked more than P6M ago, due for a new check …`. The entries stay in force.

## Re-checking or adding an operator

1. Find the operator's tariffs: `GET https://api.mobidata-bw.de/ocpdb/api/ocpi/3.0/locations?…` gives the
   `tariff_ids` per connector and the operator name exactly as it must be written in the table;
   `GET …/tariffs?limit=1000` the price components.
2. Open the official price page, compare by the rule above, and write `operator`, `basis`, `checked-on` and
   `source` (the URL) into `vat-basis`. Keep a short comment with the matched prices.
3. `ShippedVatBasisTableTests` binds the real file: a missing date, a duplicate or an unknown basis fails the build.

## Check of 2026-10-02

The whole OCPDB was read (100.948 locations, 1.029 tariffs). 63.356 charge points had no price because their
operator was unchecked; the 50 operators with the most of them (~79 %) were researched by ten parallel agents, and
every accepted source was then fetched again and compared by hand. No third-party source met the two-month rule, so
no entry needs a spot check.

**Result:** 14 operators entered, plus Allego from phase 5a. Charge points priced via the table rose from 2.064 to
15.576; together with the arithmetic evidence 44 % of the German charge points with a tariff now show a price
(before: 30 %). On the live data no entry is contradicted.

### Entered

| Operator (as in OCPDB) | Basis | Charge points | Evidence |
|---|---|---:|---|
| Allego | net | 2.064 | 0,64 net → 0,76 €/kWh (product owner, 2026-10-01) |
| TankE GmbH | gross | 1.848 | [tanke.io](https://www.tanke.io/oeffentliche-ladestationen/): AC 0,49 / DC 0,59 €/kWh "inkl. MwSt"; blocking fees match |
| N-ERGIE Aktiengesellschaft | gross | 1.683 | [LadeVerbundPlus](https://produkte.n-ergie.de/ladeverbundplus/): ad hoc 52 / 62 ct/kWh, "Alle Preise in brutto"; blocking fees match |
| IONITY | net | 1.572 | [ionity.eu](https://www.ionity.eu/network/access-and-payments): Germany Direct 0,72 €/kWh incl. VAT = 0,6018 × 1,19 |
| Berliner Stadtwerke GmbH | gross | 1.499 | [FAQ](https://berlinerstadtwerke.de/faq-beitrag/was-kostet-das-laden-an-den-ladestationen-der-berliner-stadtwerke/): "Ad-hoc-Preise (brutto)" AC 0,55 / DC 0,65 € |
| deer GmbH | gross | 1.323 | [deer-mobility.de](https://www.deer-mobility.de/laden-unterwegs/): ad hoc AC + DC 0,49 €/kWh, 0,15 €/min from min 241 |
| Stadtwerke Stuttgart GmbH | gross | 1.047 | [stadtwerke-stuttgart.de](https://www.stadtwerke-stuttgart.de/privatkunden/e-mobilitaet/oeffentliches-laden/oeffentliche-ladestationen/): "Ad-hoc-Laden, Bruttopreis pro kWh" 49 / 59 ct |
| Energie und Wasserversorgung Bonn/Rhein-Sieg GmbH (EnW Bonn/Rhein-Sieg) | gross | 1.032 | [stadtwerke-bonn.de](https://www.stadtwerke-bonn.de/fuer-zuhause/produkte/e-mobility/elektrotankstellen/): direct payment 0,49 / 0,56 €/kWh |
| Hochtief Ladepartner GmbH | net | 790 | [hochtief-ladepartner.de](https://hochtief-ladepartner.de/de/privatkunden): AC "ohne Vertrag" 52 ct/kWh = 0,44 × 1,19 |
| Mainova AG | gross | 674 | [mainova.de](https://www.mainova.de/de/loesungen/elektromobilitaet/ladestationen): ad hoc AC 0,64 / DC 0,76 €/kWh (brutto); feed 0,6426 / 0,7616 |
| Braunschweiger Versorgungs-Aktiengesellschaft & Co. KG | gross | 642 | [bs-energy.de](https://www.bs-energy.de/e-mobilitaet/ladeinfrastruktur): Direct Payment AC 0,53 / DC 0,59 EUR/kWh |
| Allgäuer Überlandwerk GmbH | gross | 376 | [auew.de](https://auew.de/privatkunden/e-mobilitaet/allgaeustrom-stromtankstellen/): 1 € per session + 51 ct; DC 57 / 66 ct/kWh |
| SWLB Mobilität GmbH | gross | 375 | [Preisblatt](https://preise.swlb.de/) from 2025-07-01: ad hoc AC 0,59, car park 0,49, DC 0,79; "enthalten die … Mehrwertsteuer" |
| ecowerk e-charge GmbH | gross | 354 | [ecowerk-echarge.de](https://www.ecowerk-echarge.de/ladetarife.html): "Adhoc Tarif" 0,42 / 0,49 / 0,58 €, "inkl. 19% MwSt." |
| ovag Energie AG | gross | 297 | [ovag.de](https://www.ovag.de/privatkunden/produkte/elektromobilitaet.html): "adhoc-Tarif" 59 ct/kWh, column "Preis (brutto)" |

### Platforms, skipped (no official statement on gross or net)

ChargePoint (7.147), MENNEKES Digital Services GmbH (2.175, chargecloud "ativo"), Spirii (850), Ecotap (792),
energielösung GmbH (433), smopi® - Multi Chargepoint Solution (360), FIRMENLADEN (355), 50five (322),
Backcharge GmbH (316).

### Unclear — no entry

| Operator | Unpriced | Why |
|---|---:|---|
| Aral pulse | 3.937 | 0,805 matches no page price; aral.de price page 404 |
| Techem Energy Services GmbH | 1.879 | only a B2B billing offer is official; 0,35 × 1,19 = 0,42 seen only on an undated aggregator |
| AUDI Aktiengesellschaft | 1.352 | only charging-hub prices (34 points) found, no VAT statement; audi.de 403 |
| TEAG Mobil | 1.265 | official 49 ct flat; feed 0,66 / 0,5125 on no official page |
| Claus Heinemann Elektroanlagen GmbH (ChargeOne) | 1.231 | official site unreachable (TLS) |
| TotalEnergies | 1.229 | no official price; third-party sources contradict each other |
| Citywatt GmbH | 1.221 | ADAC 0,64 (2025) vs 0,55 × 1,19 = 0,65: near miss |
| Vattenfall InCharge | 1.105 | AC 0,47 fits both a net (0,3949) and a gross (0,47) tariff; no basis stated |
| MVV Energie AG | 1.098 | only the app price sheet is official |
| MOON POWER GmbH | 900 | no charging price published |
| naturenergie holding AG | 899 | only customer tariff official; net hint from an aggregator built on the same feed |
| EnBW Ostwürttemberg DonauRies AG | 763 | no ad-hoc price published |
| nonoxx pro GmbH | 640 | price shown only after scanning the QR code |
| BayWa Mobility Solutions GmbH | 521 | only card prices official; QR price 1,13 vs feed 1,11 (undated) |
| Stadtwerke Bochum | 449 | no ad-hoc price published; net hint on undated aggregator pages |
| Westfalen | 429 | only net B2B fleet prices |
| E-Werk Mittelbaden | 426 | net, but AC 47,60 ct unrounded and DC 65,00 ct rounded: cannot be shown exactly |
| badenova | 370 | official pages 404 |
| Porsche Sales & Marketplace | 365 | only subscription prices |
| infra fürth service gmbh | 338 | page 61,88 / 81,44 ct (brutto) vs feed 0,62 / 0,82: near miss |
| Plenitude On The Road | 306 | page 0,50 / 0,65 matches no feed value |
| team energie | 264 | page ad-hoc prices match no feed value |
| CUT! Energy GmbH | 249 | only app tariff, 2 ct below the feed |
| WISAG Elektrotechnik Holding GmbH & Co. KG | 247 | no official price page |
| Stadtwerke Göttingen Aktiengesellschaft | 234 | no ad-hoc price published |
| WEMAG | 232 | brutto 69 / 79 ct match only one tariff (0,58 × 1,19), which also carries a start fee the page lacks |
| Präg Strom & Gas GmbH & Co. KG | 230 | only card prices official |

Worth another look at the next check: Aral pulse and Techem (largest), Vattenfall (the net/gross mix may resolve
per tariff once OCPDB ships #278), and the "near misses", whose operators may publish a price that moved since.
