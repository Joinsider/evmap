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
and stays out (E-Werk Mittelbaden). One whose page shows all prices unrounded is entered and shown rounded (eins
energie: 58,31 ct → 0,58 €; product owner, 2026-10-02).

**The entry settles the VAT basis, not the feed's content** (product owner, 2026-10-02). Once an operator is entered,
every one of its tariffs is shown as the feed delivers it, even where the feed differs from the price page in an
amount, a start fee or the minute a blocking fee starts (Weinheim DC 0,65 € in the feed, 0,69 € on the page; Kiel
0,46 € at 27 charge points). The feed is the operator's own AFIR publication and may be station-specific; the app
shows it as "Ad-hoc-Preis laut Betreiber" with its date. Such differences are noted per operator below, so a
re-check can look at them first.

A tariff that contradicts *itself* still shows no price: two different time fees from the same minute — a zero
included, which is how OCPDB writes a lost time of day (0,00 and 0,05 €/min, both unrestricted) — are two prices for
one moment.

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

## Second round, operators 51–100 (2026-10-02)

Decided with the product owner (ADR 0022, open point 2). The ranking was rebuilt from the whole OCPDB of 2026-10-02
(same 63.356 unpriced charge points, 551 operators); the first round had covered ranks 1–51, so this round took ranks
52–101 (50 operators, 7.315 charge points, ~12 % of the gap). Ten agents researched five operators each with the same
brief; every candidate was then fetched again and compared by hand **and** checked against the feed's structure
(power class per tariff, contradictions), which refuted one agent verdict (Mer Germany, below).

**Result:** 16 operators entered, 10 platforms skipped, 24 unclear. Charge points priced via the table rose from
15.576 to 17.351; 46,0 % of the German charge points with a tariff now show a price (before: 44,2 %). On the live data
no entry is contradicted. The time-fee fix of the same change removed the price of 297 charge points of Allgäuer
Überlandwerk (unrestricted 0,05 €/min next to 0,00 €/min), which had been shown with a fee from the first minute.

### Entered

| Operator (as in OCPDB) | Basis | Charge points | Evidence | Feed differs from page |
|---|---|---:|---|---|
| Stadtwerke Münster GmbH | gross | 216 | [elektroauto-laden](https://www.stadtwerke-muenster.de/unterwegs/e-mobilitaet/elektroauto-laden): "Kreditkarte/ Ad-Hoc-Ladung" 47,00 / 52,00 ct/kWh, "Endpreise, einschließlich der geltenden Umsatzsteuer" | — |
| Electra | net | 210 | [go-electra.com/de/price](https://www.go-electra.com/de/price/): "EC-Karte oder Ladekarte ab 0.69€ / kWh inkl. MwSt" = 0,5798 × 1,19; Germany has a single tariff | — |
| Stadtwerke Ingolstadt Beteiligungen GmbH | gross | 210 | [sw-i.de](https://sw-i.de/e-mobilitaet/oeffentliches-laden/): "Alle Preise brutto inkl. MwSt. Gültig ab 01.09.2026", Ad-hoc 49 / 59 ct, Fair Use 10 ct from min 46 | fee from min 121 at 4 points |
| JOLT Energy | net | 200 | [jolt.energy](https://jolt.energy/de-de/preise/): Express "Ohne Anmeldung" 0,79 € = 0,66 × 1,19 | 0,70 net (0,83 €) at 12 points |
| Stadtwerke Kiel Aktiengesellschaft | gross | 167 | [laden-in-der-kiel-region](https://www.stadtwerke-kiel.de/e-mobilitaet/laden-in-der-kiel-region): "Arbeitspreis Spontanladen (brutto) Direct Payment per QR-Code" 48 / 58 ct, FairUse 5 / 10 ct (brutto), Preetz 70 ct start | 0,46 (the app price) at 27, 0,3557 + 0,80 at 16, 0,39 at 2 points |
| eins energie in sachsen GmbH & Co. KG | gross | 165 | [eins.de](https://www.eins.de/privatkunden/elektromobilitaet/unterwegs-laden): "Ad-Hoc Preise … Preis (brutto)" 58,31 / 66,64 ct, "beinhalten die gesetzliche Umsatzsteuer von 19 %" | shown rounded, 0,58 / 0,67 € |
| Stadtwerke Bamberg Verkehrs- und Park GmbH | gross | 150 | [e-laden](https://www.stadtwerke-bamberg.de/e-laden): "Ad hoc-Laden ohne Registrierung über den QR-Code kostet aktuell 62 Cent/kWh" (equal to the feed, no VAT wording) | — |
| Bocholter Energie- und Wasserversorgung GmbH | gross | 113 | [bew-bocholt.de](https://www.bew-bocholt.de/fuer-bocholt/emobilitaet/emobilitaet-fuer-unterwegs): "Adhoc-Laden 0,69 €/kWh", "inkl. 19 % Mehrwertsteuer" (Preisstand 01.07.2023) | 0,35 at 1 point |
| öPA Verkehrsgesellschaft mbH | gross | 113 | [Stadtwerke Troisdorf](https://www.stadtwerke-troisdorf.de/zusatzleistungen/elektromobilitaet/e-ladestationen-in-troisdorf), who run the network with öPA: Ad-Hoc AC 0,59 / DC 0,69 €, 10 ct/min (equal to the feed, no VAT wording) | — |
| Stadtwerke Dachau | net | 105 | [ladekarte-und-preise](https://www.stadtwerke-dachau.de/tarife-angebote/e-mobilitaet/ladekarte-und-preise): "Direktbezahlung via Smartphone" 0,65 / 0,94 €, blocking 0,18 / 0,36 €/min = 0,55 / 0,79 / 0,15 / 0,30 × 1,19 | 0,5125 net (0,61 €) at 2 points |
| Energie- und Wasserversorgung Bruchsal GmbH | gross | 103 | [Preisblatt](https://www.stadtwerke-bruchsal.de/wp-content/uploads/2025/03/SWB_Preisblatt_Ladesaeule.pdf) (image PDF, gültig seit 01.05.2026): "Ad-hoc (Kreditkarte)" 60 / 80 Cent (netto 50,42 / 67,23), 10 Cent from min 121 | — |
| Parkgaragengesellschaft Baden-Baden mbH | gross | 95 | [Preisblatt PGG](https://www.stadtwerke-baden-baden.de/media/docs/mobilitaet-freizeit/elektromobilitaet/Preisblatt_Stand_Mai_2026_PGG.pdf) (gültig ab 01.07.2026): "Roaming Ad-hoc" 63,00 / 77,00 Cent brutto (52,94 / 64,71 netto), start 1,50 € | fees from min 61 / 31 (page 241 / 121); start 0,015 at 1 point |
| Energieversorgung Landsberg GmbH & Co. KG | gross | 95 | [e-mobilitaet](https://energieversorgung-landsberg.de/e-mobilitaet): "Ad-hoc-Tarif (QR-Code, Direktpayment) 0,62 €/kWh" AC and DC, ab 01.09.2026 (equal to the feed, no VAT wording) | — |
| Stadtwerke Weinheim GmbH | gross | 87 | [Tarife](https://sww.de/de/Produkte/Mobilitaet/Tarife.php): "Ad-hoc Ladung" AC 0,59 € (netto 0,50), DC 0,69 € (netto 0,58) | DC 0,65 at 14 points (the 2025 sheet) |
| Wirtschaftsbetriebe Lingen GmbH | gross | 41 | [laden-unterwegs](https://www.stadtwerke-lingen.de/e-mobilitaet/laden-unterwegs): "Ad hoc Ladetarif" 59,00 / 69,00 ct/kWh, start 2 € (equal to the feed, no VAT wording) | DC start 5 €; 125 AC points have a self-contradicting time fee (no price) |
| MWEnergy GmbH | gross | 2 | [mw-autostrom.de/tarife](https://mw-autostrom.de/tarife/): "Laden ohne Vertrag (Ad-hoc)" 52 / 70 ct, Standzeitgebühr 6 ct (brutto) | start 0,25 € ("Grundgebühr: keine"); 114 AC points have a self-contradicting time fee (no price) |

### Platforms, skipped (no official statement on gross or net)

Chargemaker GmbH (180), Regioladen+ GmbH & Co. KG (165, prices per municipal shareholder), LAN1 Hotspots GmbH (135),
SCHARR WÄRME GmbH & Co. KG (133), BIDIREX GmbH (125), Würth Elektrogroßhandel GmbH & Co.KG (124), Hymes Energy GmbH
(121), Greenflash Charge (208), go2zero charging solutions gmbh (91), Charge Construct GmbH (101).

### Unclear — no entry

| Operator | Unpriced | Why |
|---|---:|---|
| SWP Stadtwerke Pforzheim GmbH & Co. KG | 230 | prices only in the app; feed 0,476 / 0,595 look net (0,40 / 0,50 × 1,19) |
| mblty charging | 218 | FAQ 0,39 / 0,59 € without basis; feed 0,69 at 214 points on no page |
| Energiedienst Holding AG | 209 | only the customer tariff is official; 0,61 = 0,5125 × 1,19 on an undated aggregator |
| NEW Energie | 202 | page ad hoc 65 / 65 / 79 ct without basis; feed mixes net (0,55 → 0,65) and gross values |
| Contipark Ladestation | 200 | page 0,55 € incl. MwSt; feed 0,4545 = 0,55 / 1,21 → 0,54 €: near miss |
| Stadtwerke Witten Energielösungen GmbH | 197 | only Ladekarte prices (April 2024); feed 0,55 on no page |
| e-regio GmbH & Co. KG | 191 | only the registered tariff 48 / 52 ct brutto; ad hoc "liegt höher", not published |
| Mer Germany | 188 | agent read 0,58 as gross, but its other tariffs are proven net (0,4874 → 0,58 AC, 0,5798 → 0,69 DC, 0,6639 → 0,79 HPC) and the 0,58 one sits on 400 kW points: gross would be suspended at once, net (0,69) is on no page |
| ServiceSTADTwerke GmbH & Co. KG | 172 | no official prices; 0,59 + 1,00 € on an undated aggregator |
| AVIA VOLT | 168 | dynamic app prices only |
| CutPower | 163 | official site unreachable (503, TLS) |
| Stadtwerke Castrop-Rauxel GmbH | 162 | no official price; press from Dec 2025 (65 ct = 0,55 × 1,19) too old |
| EA EnergieArchitektur GmbH | 149 | installer, no prices; feed mixes bases |
| Energie und Wasser Potsdam GmbH | 123 | only EchtMobil app tariffs (2024); ad hoc not published |
| Stadtwerke Essen AG | 118 | no official price; feed mixes bases |
| Barnimer Energiebeteiligungsgesellschaft mbH (BEBG) | 113 | 0,49 € is the Ladekarte price; the ad-hoc price is not printed |
| Glinicke Energie + Service GmbH & Co. KG | 113 | official site behind a Cloudflare challenge; feed mixes bases |
| Erlanger Stadtwerke Energiedienst GmbH | 110 | no own ad-hoc price; LadeVerbundPlus partner pages only |
| AggerEnergie GmbH | 103 | only the "Regulärer Tarif für Nichtkunden" 0,49 / 0,59 brutto; feed adds 0,50 € start |
| Stadtwerke Neumarkt i. d. OPf. Freizeit & Leben KU | 102 | page app/contract prices only, ad hoc "andere Konditionen"; feed 0,62 / 0,82 on no page |
| Elektro Keßler GmbH | 99 | no official price |
| KfW Bankengruppe | 98 | no ad-hoc tariff published |
| ib company GmbH | 97 | no ad-hoc price page |
| Greenman Energy Operations GmbH & Co. KG | 96 | no official prices |

Candidates for the next check: e-regio and AggerEnergie (prices nearly established), Stadtwerke Castrop-Rauxel and
Energiedienst (net looks likely, but no current source), and the LadeVerbundPlus members, whose shared ad-hoc price
(62 / 82 ct) a statement from LadeVerbundPlus itself could settle for several operators at once.
