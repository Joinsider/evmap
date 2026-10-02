# EVMap Roadmap

Stand: 2026-10-02 · abgestimmt mit Johannes Popp · Umsetzung überwiegend durch Claude Code

Diese Roadmap legt fest, **was** als Nächstes kommt und **in welcher Reihenfolge**. Das *Warum* und
*Wie* eines Features steht im jeweiligen ADR. Jede Phase bekommt ihr ADR spätestens zu Beginn der
Umsetzung (CLAUDE.md). Eine Phase ist erst fertig, wenn sie getestet, dokumentiert und
veröffentlicht ist.

**Aufwand** ist in T-Shirt-Größen angegeben: S ≈ 1–3 Tage, M ≈ 1–2 Wochen, L ≈ 2–4 Wochen,
XL ≈ 1–2 Monate Entwicklungsumfang. Weil Claude Code implementiert, bestimmen den Takt vor allem die
mit 👤 markierten Schritte: Konten und Freigaben bei Apple, Google und GitHub, Server und Domain,
Tests auf dem Gerät, Reviews.

## Status

Wird am Ende jeder Phase im selben PR aktualisiert (Skill `/roadmap-phase`).
Werte: `offen` · `in Arbeit` · `fertig` · `übersprungen`.

| Phase | Inhalt | Status | Branch / PR | ADR |
|---|---|---|---|---|
| 0 | Fundament: Backups, Monitoring | fertig ¹ | [#10](https://github.com/Joinsider/evmap/pull/10) | [0019](adr/0019-backups-and-monitoring.md) |
| 1 | Login mit Google/GitHub, Web-Gerüst | fertig ² | [#11](https://github.com/Joinsider/evmap/pull/11) | [0018](adr/0018-additional-identity-providers-and-web-client.md) |
| 2 | Konto-Bereich und App-Store-Pflichten | fertig ³ | [#13](https://github.com/Joinsider/evmap/pull/13) | [0020](adr/0020-account-area-and-app-store-obligations.md) |
| 3 | Favoriten und Fehler melden | fertig ⁶ | [#20](https://github.com/Joinsider/evmap/pull/20) | [0021](adr/0021-favorites-and-station-reports.md) |
| 4 | Routenplaner Stufe 1 | fertig ⁹ | [#23](https://github.com/Joinsider/evmap/pull/23) | [0017](adr/0017-route-planning-with-charging-stops.md) |
| 5a | Preise an der Station: Betreiber pro Ladepunkt, Ad-hoc-Preise | fertig ¹⁰ | [#26](https://github.com/Joinsider/evmap/pull/26) | [0022](adr/0022-prices-at-the-station.md) |
| 5r | Netto/Brutto-Prüfung pro Betreiber (direkt nach 5a) | fertig ¹¹ | [#27](https://github.com/Joinsider/evmap/pull/27) | [0022](adr/0022-prices-at-the-station.md) |
| 5b | Ladekarten und Preis mit eigenen Karten | offen | | [0022](adr/0022-prices-at-the-station.md) |
| 6 | Routenplaner Stufe 2 | offen | | 0017 |
| 7 | Routenplaner Stufe 3 | offen | | 0017 |
| 8a | Nutzer-Web-App: Karte und Station (lesend), vor 5b gezogen | fertig ¹² | [#29](https://github.com/Joinsider/evmap/pull/29) | [0023](adr/0023-user-web-app.md) |
| 8b | Nutzer-Web-App: Mitmachen (Kommentare, Melden, Favoriten) | offen | | [0023](adr/0023-user-web-app.md) |
| 9 | CarPlay als Lade-App | offen | | neu |
| L1 | Lückenfüller: Österreich | übersprungen ⁴ | | [0012](adr/0012-additional-national-charging-registers.md) |
| L2 | Lückenfüller: Schweiz | fertig ⁵ | [#18](https://github.com/Joinsider/evmap/pull/18) | [0012](adr/0012-additional-national-charging-registers.md) |
| L3 | Lückenfüller: Italien | übersprungen ⁷ | | [0012](adr/0012-additional-national-charging-registers.md) |
| L4 | Lückenfüller: Spanien | fertig ⁸ | [#22](https://github.com/Joinsider/evmap/pull/22) | [0012](adr/0012-additional-national-charging-registers.md) |
| L5 | Lückenfüller: Mobilithek (Live-Daten Deutschland), vor 8b | in Arbeit | `feature/availability-mobilithek` | [0015](adr/0015-live-availability-national-access-points-with-tomtom-fallback.md) |

¹ Im Repository fertig; offen sind die 👤-Schritte auf dem VPS (Backup-Ziel, Monitore, erste
Restore-Probe, `docs/operations/backup-and-restore.md` §5).
² Im Repository fertig; offen sind die 👤-Schritte in `docs/operations/sign-in-providers.md`
(Reverse Proxy auf den Web-Container, OAuth-Apps bei Google und GitHub, Services ID und Schlüssel
bei Apple, Associated Domains, Gerätetest, Admin-Flag setzen).

³ Im Repository fertig; offen sind die 👤-Schritte in `docs/operations/sign-in-providers.md` §5
(`TOKEN_ENCRYPTION_KEY`, `APPLE_CLIENT_ID`, Apple-Schlüssel für Bundle-ID und Services ID,
Datenschutzerklärung veröffentlichen und `PRIVACY_POLICY_URL` setzen, Löschung auf dem Gerät testen).
Die Web-Oberfläche zum Melden und Blockieren von Kommentaren folgt mit der Nutzer-Web-App (Phase 8).
⁴ Die Nutzungsbedingungen der E-Control-API verbieten Speichern und Weitergabe als Webservice, jede
Veränderung der Werte und verlangen Besucherzahlen pro Quartal (ADR 0012, „Austria skipped“). Österreich
bleibt über OCM abgedeckt. Wieder aufnehmen, falls E-Control das Speichern und Zusammenführen schriftlich
erlaubt oder eine CC-BY-Fassung über die Mobilitätsdatenplattform erscheint.
⁵ Im Repository fertig (4.977 Stationen aus 14.394 EVSEs gegen die Live-Datei geprüft). Offen ist der erste
Lauf auf dem Server: zweiten Lauf prüfen, dass kaum noch Stationen neu angelegt werden (ADR 0012, offener
Punkt 8). Das Schweizer Register steht unter `terms_by_ask`: kommerzielle Nutzung braucht die Erlaubnis
des BFE.

⁶ Im Repository fertig, keine neuen 👤-Schritte. Offen ist der Gerätetest: Favorit ohne Konto setzen, anmelden
(Vereinigung prüfen), abmelden (Liste leer), Station melden und im Admin-Bereich schließen. Favoriten in
der Web-Oberfläche folgen mit der Nutzer-Web-App (Phase 8); die Endpunkte sind vorbereitet.

⁷ Die PUN (GSE/MASE) hat ihren CSV-Export im Juni 2026 abgeschaltet; geblieben ist nur die undokumentierte
Portal-API, die laut Product Owner Italienern mit italienischem Ausweis vorbehalten ist, und die Lizenz
(CC BY 4.0) ist nur eine Annahme der AgID (ADR 0012, „Italy skipped“). Italien bleibt über OCM abgedeckt.
Wieder aufnehmen, falls GSE/MASE einen offenen, dokumentierten Export oder eine API für ausländische Nutzer
mit klarer Lizenz anbietet oder der italienische AFIR-Zugangspunkt einen offenen Datensatz liefert.

⁸ Im Repository fertig (10.217 Stationen aus 35.546 Ladepunkten gegen die Live-Datei vom 2026-10-01 geprüft), keine
neuen 👤-Schritte. Offen ist der erste Lauf auf dem Server: einen zweiten Lauf prüfen, dass kaum noch Stationen neu
angelegt werden (ADR 0012, offener Punkt 9). Die Lizenz (CC-BY laut Datensatzseite) wurde nicht mit der DGT
geklärt; Restrisiko akzeptiert, Abschalten mit `MITERD_ENABLED=false`. Spanien war vorher nicht abgedeckt (nicht in
der OCM-Standardliste). Betreiber pro Ladepunkt kommt mit Phase 5.

⁹ Im Repository fertig (Backend 428 Tests, iOS-Unit-Tests grün, Ablauf im Simulator geprüft). Offen sind die 👤-Schritte
aus ADR 0017, „Steps for the product owner“: Web-Container neu ausrollen (AASA mit `applinks`, Hinweisseite unter
`/route`), App einmal neu installieren, Gerätetest unter iOS 26 (Apple Karten etappenweise, Google Maps ganze Route,
Teilen-Link, Offline-Stand, Stationen entlang einer echten Route). Die Recherche zu Fahrzeugdaten (Open EV Data,
CDLA-Permissive-2.0) und Tarifquellen steht als Notiz im ADR und fließt in Phase 6 und 5 ein.

¹⁰ Im Repository fertig (Backend 525 Tests, iOS-Unit-Tests grün, im Simulator gegen echte MobiData-Tarife geprüft),
keine neuen 👤-Schritte. Offen ist der Gerätetest nach dem Ausrollen (deutsche Station mit Live-Tarif, französische mit
Registerpreis, spanische Station mit zwei Betreibern). Deutsche Tarife erscheinen bisher nur, wo der Bruttopreis belegt
ist oder der Betreiber geprüft wurde (nur Allego); die übrigen Betreiber prüft Phase 5r.

¹¹ Im Repository fertig (Backend 535 Tests), keine neuen 👤-Schritte. Von den 50 Betreibern mit den meisten
ungeprüften Ladepunkten sind 14 eingetragen, 9 Plattformen übersprungen und 27 unklar (Nachweise in
`docs/operations/price-basis-operators.md`). Mit Preis über die Tabelle: 15.576 statt 2.064 Ladepunkte; insgesamt 44 %
statt 30 % der deutschen Ladepunkte mit Tarif. Offen ist der Gerätetest nach dem Ausrollen (z. B. Berliner Stadtwerke,
TankE, IONITY). Erneute Prüfung fällig am 2027-04-02; die API warnt beim Start.
Nachtrag 2026-10-02 (offene Punkte von 5r, [#28](https://github.com/Joinsider/evmap/pull/28)): zweite Runde für die Betreiber 51–100, 16 weitere eingetragen; mit Preis über
die Tabelle jetzt 17.351 Ladepunkte, 46 % der deutschen Ladepunkte mit Tarif. 👤 Die Texte für OCPDB (Kommentar zu #278,
neues Issue zur Minuten-Einheit) in `docs/operations/ocpdb-upstream-issues.md` selbst posten.

¹² Im Repository fertig (Backend 521 Tests, Web 100 Tests, Oberfläche im Browser gegen eine gemockte API geprüft; die
echte Karte ohne Maps-Schlüssel noch nicht gesehen). Offen sind die 👤-Schritte in `docs/operations/web-map.md`: Maps-ID
und MapKit-JS-Schlüssel anlegen, `MAPKIT_KEY_ID`/`MAPKIT_PRIVATE_KEY` setzen, die Karte einmal auf Desktop und Handy
ansehen, vor der öffentlichen Freigabe Last und Antwortzeiten prüfen, MapKit JS in der Datenschutzerklärung nennen.
Als Nächstes: 8b (Mitmachen im Web), danach 5b.

Separat angestoßen (Teil von Phase 0): `permitAll` für `/api/v1/stations/**` auf GET beschränken.
Erledigt mit PR #8 (Commit `3d40492`), vor Beginn von Phase 0 auf `master` geprüft.

## Überblick

```
Phase 0  Fundament ──► Phase 1  Login + Web-Gerüst ──► Phase 2  Konto & Store-Pflichten
                                                              │
         ┌────────────────────────────────────────────────────┘
         ▼
Phase 3  Favoriten + Fehler melden ──► Phase 4  Routenplaner 1 ──► Phase 5a/5r Preise
                                                                        │
         ┌──────────────────────────────────────────────────────────────┘
         ▼
Phase 8  Web-App (8a, 8b; vor 5b gezogen) ──► Phase 5b Ladekarten ──► Phase 6  Routenplaner 2
         ──► Phase 7  Routenplaner 3 ──► Phase 9  CarPlay

Lückenfüller zwischen den Phasen:  AT → CH → IT → ES   (je ein sync-Adapter)
Recherche parallel zu Phase 4:     Tarif-Quellen, Fahrzeugdatenbank
Bei Bedarf (Monitoring-Trigger):   Skalierung / Variante C
Optional am Ende:                  GraphQL
v3:                                Live-Fahrzeugdaten, Turn-by-Turn
```

## Phasen

### Phase 0 — Fundament · S · [ADR 0019](adr/0019-backups-and-monitoring.md)

Bevor mehr Nutzerdaten dazukommen, müssen sie gesichert sein und Ausfälle auffallen.

- Automatische PostgreSQL-Backups auf dem VPS (täglicher Dump, Kopie an einen zweiten Ort,
  Rotation), dazu ein **dokumentierter und einmal geprobter Restore**.
- Monitoring und Alarm: API-Health (`/actuator/health`), fehlgeschlagene oder `PARTIAL`
  Sync-Runs (`master.sync_run`), Speicherplatz. Umsetzung selbst gehostet, z. B. mit Uptime Kuma.
- Sicherheitskorrektur: `permitAll` für `/api/v1/stations/**` auf GET beschränken (als eigene
  Aufgabe angelegt).
- 👤 Backup-Ziel und Alarm-Kanal (Mail, Push, Matrix …) festlegen.

### Phase 1 — Weitere Login-Anbieter und Web-Gerüst · L · [ADR 0018](adr/0018-additional-identity-providers-and-web-client.md)

Voraussetzung für die Admin-Oberfläche in Phase 2 und die spätere Web-App.

- Login mit **Google und GitHub** zusätzlich zu Apple, direkt im Backend angebunden, in App und Web.
- **Automatische Kontoverknüpfung per bestätigter E-Mail-Adresse**.
- Schema: getrennte Tabellen für das interne Konto und für die Anbieter-Identitäten,
  Admin-Flag am Konto. Admin wird man nur per manuellem Datenbank-Update.
- **Angular-Workspace** mit App-Shell, Login, API-Client und i18n (Deutsch/Englisch). Der
  Admin-Bereich ist das erste Feature-Modul. Die Struktur ist so angelegt, dass die Nutzer-Web-App
  (Phase 8) als weiteres Modul dazukommt.
- Deployment als eigener Container hinter demselben Reverse Proxy wie die API.
- 👤 OAuth-Apps bei Google und GitHub anlegen, Services ID und Schlüssel für die
  Apple-Web-Anmeldung, Domain und TLS für das Web-Frontend.

### Phase 2 — Konto-Bereich und App-Store-Pflichten · L

Alles, was der App Store verlangt, plus die Konto-Funktionen, die denselben Bereich betreffen.

- **Kontolöschung** in App und Web über alle verknüpften Anbieter. Bei Apple gehört der
  **Token-Widerruf** über Apples REST-Schnittstelle dazu. Dafür muss das Backend künftig den
  Autorisierungscode gegen ein Refresh-Token tauschen; heute prüft es nur das Identity-Token.
- **Melden von Kommentaren und Blockieren von Nutzern** (App-Store-Richtlinie für Nutzerinhalte).
- **Moderations-Warteschlange** im Angular-Admin.
- **Meine Beiträge**: eigene Kommentare, Meldungen und später Fahrzeug-Einreichungen.
- **Datenexport** (DSGVO Art. 15/20) als JSON-Datei.
- Link zur Datenschutzerklärung in App und Store; Aktualisierung von
  `docs/privacy/data-processing.md` (E-Mail-Adresse, Meldungen, Blockierungen, Löschkonzept).
- 👤 Datenschutzerklärung veröffentlichen, Apple-Schlüssel für den Token-Widerruf.

### Phase 3 — Favoriten und Fehler melden · M

Kleine Features, im Alltag sofort nützlich.

- **Favoriten-Stationen**: merken, als Liste, auf der Karte hervorgehoben. Ohne Konto nur auf dem
  Gerät gespeichert, nach der Anmeldung mit dem Konto synchronisiert (für App, Web und CarPlay).
  Damit sind Favoriten Nutzerdaten und gehören in Datenexport und Kontolöschung.
- **Fehler melden** an einer Station (existiert nicht mehr, falsche Leistung oder Stecker,
  defekt). Die Meldung ist Nutzerdatum und landet in der Admin-Warteschlange; Stammdaten werden
  nicht direkt geändert.

### Phase 4 — Routenplaner Stufe 1: manuell · XL · [ADR 0017](adr/0017-route-planning-with-charging-stops.md)

- Start, Ziel und Wegpunkte (Adressen, Orte, Ladestationen, gespeicherte Orte), Aufenthaltsdauer,
  Reihenfolge per Drag & Drop.
- Route über MapKit hinter einer Routing-Schnittstelle; Maut und Autobahn meiden,
  Alternativrouten.
- Ladestationen entlang der Route nach Umweg-Minuten, über den neuen REST-Endpunkt
  `POST /api/v1/stations/along-route`.
- Pausenvorschläge und Ladestationen mit Umgebung (MapKit-Ortssuche entlang der Route).
- Übergabe an Apple Maps (Start → Ziel oder Etappe für Etappe) und Google Maps (ganze Route),
  Link teilen, Offline-Cache der geplanten Route.
- **Parallel dazu recherchieren:** Wie lassen sich Tarife automatisch beziehen (ADR 0017,
  offener Punkt 2)? Welche Fahrzeugparameter braucht es, und reicht Open EV Data (offener Punkt 1)?

### Phase 5 — Preise an der Station · L · [ADR 0022](adr/0022-prices-at-the-station.md)

Baut auf der Tarif-Recherche aus Phase 4 auf. Aufgeteilt in **5a** (Betreiber pro Ladepunkt, Ad-hoc-Preise),
**5r** (Netto/Brutto-Prüfung pro Betreiber anhand offizieller Preisseiten, direkt nach 5a) und **5b** (Ladekarten).

- **Voraussetzung: Betreiber pro Ladepunkt** (entschieden 2026-10-01, ADR 0012 „Spain (L4)“). Heute steht der
  Betreiber nur an der Station (`master.charging_station.operator_name`); Stationen mehrerer Betreiber am selben
  Ort (in Spanien 250 gebündelte Stationen, 647 Ladepunkte) tragen den Mehrheitsbetreiber. Der Preis hängt am
  Betreiber des Ladepunkts, deshalb kommt die Spalte in `master.charge_point` samt Migration, API-Feld und
  iOS-Anzeige hier hinzu.
- Ladekarten verwalten: eine gepflegte, möglichst automatisch aktualisierte Liste plus eigene
  Tarife.
- An jeder Station anzeigen, was sie mit den eigenen Karten kostet; Ad-hoc-Preise, sobald eine
  Quelle sie liefert.

### Phase 6 — Routenplaner Stufe 2: automatische Ladestopps · XL

- Fahrzeugprofile (mehrere); Fahrzeugdatenbank (bevorzugt offen, sonst eigene); Nutzer können
  Fahrzeuge **einreichen** (Freigabe im Admin) und **manuell anlegen**.
- Planung der Stopps im Backend: Mindest-Ladestand bei Ankunft, Ziel-Ladestand, Mindestleistung
  als Voreinstellung, pro Route änderbar; Planungsziel „schnell“ oder „nach Filtern“.
- Laden während eines Aufenthalts an Wegpunkten; Live-Status wird angezeigt, fließt aber nicht in
  die Planung ein.

### Phase 7 — Routenplaner Stufe 3: günstigste Route · M

- Planungsziel „günstig“, auf Basis der Ladekarten und Tarife aus Phase 5.

### Phase 8 — Nutzer-Web-App · XL · [ADR 0023](adr/0023-user-web-app.md)

Vor 5b gezogen und geteilt in **8a** (Karte, Ortssuche, Filter, Stationsseite mit Live-Status, Preisen und
Kommentaren, lesend) und **8b** (Kommentar schreiben, melden und blockieren, Favoriten, Fehler melden).


- Karte, Stationsdetails, Kommentare, Favoriten und Konto im Browser, auf dem Angular-Gerüst aus
  Phase 1. Den Routenplaner im Web danach.
- Karte: **MapKit JS**. Gleiche Optik und Ortssuche wie in iOS; kostenlos bis 250.000
  Kartenaufrufe und 25.000 Service-Aufrufe pro Tag. Das Token signiert das Backend. Die
  Autovervollständigung wird beim Tippen verzögert wie in iOS, weil sie das Service-Kontingent
  verbraucht.
- Vorher prüfen: Ist Skalierung schon nötig? Entschieden 2026-10-02: Prüfung anhand der Monitoring-Daten durch den
  Product Owner vor der öffentlichen Freigabe der Web-App, nicht vor dem Bau.

### Phase 9 — CarPlay als Lade-App · M

- Stationen, Favoriten und geplante Stopps im Auto; navigiert wird über Apple Maps.
- 👤 **Früh beantragen:** Apple vergibt die CarPlay-Freigabe für Lade-Apps auf Antrag. Den Antrag
  deshalb schon während Phase 6 stellen.

## Laufende und bedingte Themen

### Lückenfüller: weitere Länder · je S–M

Ein nationales Register pro Lücke zwischen zwei Phasen, jeweils als eigenes `sync.<land>`-Paket
nach dem Rezept in `sync/package-info.java`, und `SourceAdapterRegistrationTests` erweitert.
Reihenfolge: **Österreich → Schweiz → Italien → Spanien**. Vor jedem Adapter wird geprüft, ob die
Daten offen verfügbar sind und unter welcher Lizenz; ein Land ohne offene Daten wird übersprungen
und im ADR 0012 vermerkt. Österreich wurde so übersprungen (Stand 2026-09-30), Schweiz ist umgesetzt, Italien wurde
übersprungen (Stand 2026-10-01), Spanien ist umgesetzt (Stand 2026-10-01). Damit sind alle vier Lückenfüller
erledigt; danach folgte **Phase 4**, dann **Phase 5** (5a und 5r fertig); als Nächstes **Phase 8** (vor 5b gezogen,
entschieden 2026-10-02), danach 5b.

### Skalierung / Variante C · bei Bedarf

Kein fester Platz. Wird ausgelöst durch Messwerte aus dem Monitoring (Antwortzeiten, CPU/RAM,
Ausfälle) und spätestens **vor Phase 8 (Web-App)** neu bewertet. Umfang: Live-Cache aus dem Prozess
(ADR 0015), zweite API-Instanz, Sync als eigener Service.

### GraphQL · optional, am Ende

Vom Routenplaner abgekoppelt. Für einen einzelnen Client mit zehn Endpunkten ist der Nutzen gering,
die Abstraktion hinter `ChargingStationRepository` bleibt bestehen. Neu bewerten, wenn die Web-App
steht; dann gibt es zwei Clients, und das Lastenheft §7 wird entsprechend angepasst.

### v3-Kandidaten

- Live-Fahrzeugdaten (Ladestand aus dem Auto)
- Eigene Turn-by-Turn-Navigation (Valhalla + Ferrostar), dabei neu klären, wie die Live-Belegung
  einfließt. Zusammen damit prüfen, ob iOS und Web komplett auf MapLibre + OpenStreetMap umsteigen
  (Ferrostar baut darauf auf).
- Nicht gewählt, aber vermerkt: Fotos zu Stationen, Sternebewertungen, Widgets und Live Activity

## Entschieden am 2026-09-29

Phase 0 (Details in ADR 0019):

- Backup-Ziel: S3-kompatibler Speicher (SeaweedFS) auf einem zweiten Host; Sicherung mit restic.
- Gesichert wird die ganze Datenbank; Rotation 7 täglich / 4 wöchentlich / 3 monatlich.
- Der Backup-Job läuft als Container im Deploy-Stack, mit wöchentlichem automatischem Restore-Test.
- Uptime Kuma läuft auf einem anderen Host und wird vom Product Owner selbst betrieben; Alarme per ntfy.
- `PARTIAL`-Sync-Runs alarmieren über eine eigene Health-Gruppe `/actuator/health/sync`.

Phase 1 (Details in ADR 0018, Abschnitt „Phase 1 decisions“):

- Die iOS-App fragt bei Sign in with Apple künftig die E-Mail-Adresse an, damit Apple-Konten
  verknüpft werden können.
- iOS bekommt den Google/GitHub-Login über einen HTTPS-Callback auf der Web-Domain zurück
  (Associated Domain), nicht über ein eigenes URL-Schema.
- Der Web-Container (nginx) liefert die Angular-App aus und leitet `/api/**` an die API weiter.
- Der Admin-Bereich zeigt in Phase 1 eine Übersicht der Sync-Runs und Kennzahlen (nur lesend).
- Web-i18n mit `@angular/localize` (ein Build je Sprache).
- Web-Domain ist `evmap.joinside.de`, dieselbe wie die API heute: der Reverse Proxy zeigt auf den
  Web-Container, der `/api/**` weiterleitet. Die iOS-App behält ihre Basis-URL.

Phase 2 (Details in ADR 0020):

- Apple-Widerruf: Der Refresh-Token wird beim Apple-Login gespeichert, AES-GCM-verschlüsselt.
- Kontolöschung sofort und vollständig, ohne Bedenkzeit; Kommentare werden mitgelöscht.
- Gelöschte Konten bleiben bis zu ~3 Monate in Backups; kein Löschprotokoll (ADR 0019, offener Punkt a).
- Gemeldete Kommentare bleiben sichtbar (für den Melder ausgeblendet); Admin kann löschen oder abweisen, kein Nutzer-Bann.
- Blockierungen serverseitig am Konto, über einen Kommentar ausgelöst.
- Datenexport als direkter JSON-Download.
- Datenschutz-Link per Backend-Konfiguration (`PRIVACY_POLICY_URL`).

Lückenfüller Österreich (2026-09-30, Details in ADR 0012, Abschnitt „Austria skipped“):

- L1 wird übersprungen. Die E-Control-Bedingungen passen nicht zu EVMap: Speichern und Weitergabe über
  die eigene API sind verboten, Werte dürfen nicht verändert werden, Besucherzahlen müssen gemeldet werden.
- Ein Durchreich-Modul ohne Speicherung wurde geprüft und verworfen (keine Filter, Kommentare, Favoriten
  und Routenplanung für diese Stationen, offene Rechtsfrage).

Lückenfüller Schweiz (2026-09-30, Details in ADR 0012, Abschnitt „Switzerland (L2)“):

- Die autoritative Quelle je Land wird eine konfigurierbare Tabelle (`evmap.sync.authority`: DE=BNetzA,
  FR=IRVE, CH=DIEMO) statt einer festen BNetzA-Regel. Damit entfällt auch die Abhängigkeit von der
  Reihenfolge der Adapter. Umgesetzt und getestet; jede weitere nationale Quelle trägt sich dort ein.
- Der Status-Feed wird nicht gelesen (Live-Belegung gehört nicht in die Stammdaten, ADR 0015).

Phase 3 (2026-09-30, Details in ADR 0021):

- Favoriten werden bei der Anmeldung vereinigt (Gerät ∪ Konto); beim Abmelden wird die lokale Liste geleert.
- Fehlermeldung an einer Station: geschlossener Grund plus optionaler Text (max. 500 Zeichen), nur für
  angemeldete Nutzer. Admins schließen mit „erledigt“ oder „abgewiesen“; Stammdaten bleiben unberührt.
- iOS: Favoritenliste als Sheet über einen Stern-Button in der Karten-Toolbar. Web: nur die Admin-Warteschlange.

Lückenfüller Italien (2026-10-01, Details in ADR 0012, Abschnitt „Italy skipped“):

- L3 wird übersprungen. Die PUN hat keinen offenen Export mehr; die Portal-API ist laut Product Owner nur mit
  italienischem Ausweis erreichbar, ihre Lizenz ist nicht ausdrücklich erklärt. Ein Adapter gegen die Portal-API
  wurde verworfen. Italien bleibt über OCM abgedeckt.

Lückenfüller Mobilithek (2026-10-02, Details in ADR 0015, Abschnitt „Mobilithek (L5)“):

- Die Organisation ist genehmigt und der Zugang freigeschaltet. Die Mobilithek-Live-Daten werden deshalb aus den
  v3-Kandidaten vorgezogen und jetzt als Lückenfüller L5 umgesetzt, vor Phase 8b.

Lückenfüller Spanien (2026-10-01, Details in ADR 0012, Abschnitt „Spain (L4)“):

- Quelle ist das MITERD-Register über den DGT-NAP (DATEX II v3, CC-BY laut Datensatzseite, kein Schlüssel). Der
  allgemeine DGT-Rechtshinweis wurde nicht geklärt; die Datensatz-Lizenz gilt als maßgeblich, Restrisiko akzeptiert.
- Standorte (einer je Betreiber) werden nach Position über alle Betreiber gebündelt (35 m, 10.217 Stationen).
  Grund: Die Aufnahme behandelt 30 m als „derselbe Ort“; sonst überschreiben sich Standorte einer Quelle bei
  jedem Lauf. Nur gleiche Betreiber zu bündeln ließe 441 Stationen kollidieren.
- „Betreiber pro Ladepunkt“ wird Voraussetzung von Phase 5, kein Teil von L4.
- Spanien ist nicht in der OCM-Standardliste; vor diesem Adapter war es nicht abgedeckt.

Phase 5 (2026-10-01, Details in ADR 0022):

- Phase 5 wird geteilt: 5a (Betreiber pro Ladepunkt + Ad-hoc-Preise), 5r (Netto/Brutto-Prüfung pro Betreiber,
  eigene Phase direkt nach 5a), 5b (Ladekarten).
- Betreiber pro Ladepunkt als Spalte `operator_name` an `master.charge_point`; leer heißt: Betreiber der Station.
- Deutsche Ad-hoc-Preise (MobiData BW, OCPI-Tarife) werden wie der Live-Status bei Bedarf gelesen, nur bei exakt
  gleicher EVSE-ID, nie in die Stammdaten.
- Freitext-Preise (Frankreich) nur bei eindeutigen Mustern, sonst kein Preis; vorher Analyse aller Datensätze.
  Ohne MwSt-Angabe = brutto, „HT“ wird × 1,2 umgerechnet, das OCPI-artige Format von EASYCHARGE/Citeos nur dort,
  wo × 1,2 nachweislich auf ganze Cent aufgeht.
- Open Charge Map liefert keine Preise.
- Deutsche Tarife nur, wenn brutto belegt ist (ganze Cent nach MwSt) oder der Betreiber in einer gepflegten
  Tabelle steht (Start: Allego netto, vom Product Owner geprüft); die Tabelle zu füllen ist Phase 5r.
- Zeitgebühren als €/min anzeigen (OCPDB rechnet `pricePerMinute` nicht um); die Einheit wird je Quelle
  automatisch erkannt und ein Wechsel geloggt.
- Anzeige auf der Stationsseite je Ladepunkt-Gruppe und als „ab“-Preis oben auf der Stationsseite (nicht in der
  Infokarte: die öffnet sich für Stationen nicht, Frage korrigiert und neu beantwortet); keine Preise an den Pins.

Phase 5r (2026-10-02, Details in ADR 0022, Abschnitt „Phase 5r decisions“):

- Geprüft werden die 50 Betreiber mit den meisten ungeprüften Ladepunkten (~79 % der Lücke von 63.356 Ladepunkten);
  die Recherche läuft parallel über mehrere kleine Agenten.
- Eingetragen wird ein Betreiber, wenn ein Ad-hoc-Preis auf seiner offiziellen Seite exakt einem Feed-Wert entspricht
  (brutto oder netto × 1,19). Drittquellen nur, wenn höchstens zwei Monate alt, und dann mit Stichproben durch den
  Product Owner.
- Der chargecloud-Feed hat keinen MwSt.-Satz: für als netto geprüfte Betreiber gilt bei deutschen Ladepunkten 19 %.
- Plattformen (ChargePoint, smopi, Spirii …) nur, wenn sie offiziell eine einheitliche Netto- oder Bruttoangabe
  erklären; sonst übersprungen.
- Jeder Eintrag trägt Prüfdatum und Quelle; widerspricht der Feed einem Eintrag, wird er für den Betreiber ausgesetzt
  und als WARN geloggt. Erneute Prüfung nach sechs Monaten.
- Beträge von Tabellenbetreibern werden auf ganze Cent gerundet (Mainova 0,6426 → 0,64 €); E-Werk Mittelbaden rundet
  AC und DC unterschiedlich und bleibt ohne Eintrag.

Offene Punkte von Phase 5r (2026-10-02, Details in ADR 0022, Abschnitt „Phase 5r open points“):

- OCPDB-Fehler werden upstream gemeldet: Die Texte liegen in `docs/operations/ocpdb-upstream-issues.md`, der Product
  Owner postet sie selbst.
- Zweite Recherche-Runde für die Betreiber 51–100 (nach ungeprüften Ladepunkten), Verfahren wie in 5r.
- Ein Eintrag klärt nur die MwSt-Basis: Weicht der Feed eines eingetragenen Betreibers von dessen Preisseite ab (alter
  Preis, App-Preis, Startgebühr), wird die Zahl aus dem Feed gezeigt („laut Betreiber“ mit Datum). Frage einmal
  nicht verstanden, mit Beispielen (Weinheim, Kiel) neu gestellt.
- Betreiber mit ungerundeten Bruttopreisen (eins energie, 58,31 ct) werden eingetragen und auf ganze Cent gerundet
  gezeigt.

Phase 8 (2026-10-02, Details in ADR 0023):

- Phase 8 wird vor 5b gezogen: Die Sitzung lief in der Cloud ohne iOS-Simulator, und Phase 8 braucht keine
  iOS-Arbeit. Neue Reihenfolge: 8 → 5b → 6 → 7 → 9.
- Phase 8 wird geteilt: 8a lesend (Karte, Ortssuche, Filter, Stationsseite), 8b Mitmachen (Kommentare schreiben,
  melden/blockieren, Favoriten, Fehler melden).
- Skalierung: kein Umbau vorab; der Product Owner prüft Antwortzeiten und Last vor der öffentlichen Freigabe.
- MapKit-JS-Token: Das Backend signiert kurzlebige Tokens selbst (wie in ADR 0018 entschieden), statt ein
  Portal-Token durchzureichen; nochmals gefragt, nachdem Apple inzwischen domain-gebundene Portal-Tokens anbietet.

Phase 4 (2026-10-01, Details in ADR 0017, Abschnitt „Phase 4 decisions“):

- Gespeicherte Orte: freie Liste mit eigenen Namen, nur auf dem Gerät; Favoriten-Stationen sind zusätzlich als
  Wegpunkt wählbar.
- Teilen-Link: Universal Link auf `evmap.joinside.de/route?…` (Wegpunkte im Link, nichts auf dem Server).
- Lokal gespeichert werden die aktuelle Route (offline lesbar) und eine Liste benannter, gespeicherter Routen.
- Einstieg: kein Toolbar-Button. Man sucht einen Ort oder tippt auf einen beliebigen Ort (auch Ortschaft, POI) oder
  eine Ladestation; es erscheint eine Infokarte wie in Apple Maps mit „Route von hier“ / „Route hierhin“ /
  „Wegpunkt hinzufügen“. Damit zeigt die Suche nicht mehr nur die Kartenposition (ADR 0011, wird angepasst).

Roadmap allgemein:

- Favoriten: auf dem Gerät, bei Anmeldung mit dem Konto synchronisiert (Phase 3).
- Web-Karte: MapKit JS; ein kompletter Umstieg auf MapLibre wird in v3 mit Turn-by-Turn geprüft.
- Reihenfolge: erst die Web-App (Phase 8), dann CarPlay (Phase 9).
- Verknüpfte Anmeldungen lassen sich nicht lösen; der Admin-Bereich ist öffentlich und nur durch
  das Admin-Flag geschützt (ADR 0018).
- Das Lastenheft hat einen v2-Abschnitt (§11); ADR 0017 und ADR 0018 sind angenommen.
