# EVMap Roadmap

Stand: 2026-09-29 · abgestimmt mit Johannes Popp · Umsetzung überwiegend durch Claude Code

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
| 0 | Fundament: Backups, Monitoring | offen | | neu |
| 1 | Login mit Google/GitHub, Web-Gerüst | offen | | 0018 |
| 2 | Konto-Bereich und App-Store-Pflichten | offen | | neu |
| 3 | Favoriten und Fehler melden | offen | | neu |
| 4 | Routenplaner Stufe 1 | offen | | 0017 |
| 5 | Preise an der Station | offen | | 0017 + neu |
| 6 | Routenplaner Stufe 2 | offen | | 0017 |
| 7 | Routenplaner Stufe 3 | offen | | 0017 |
| 8 | Nutzer-Web-App | offen | | 0018 + neu |
| 9 | CarPlay als Lade-App | offen | | neu |
| L1 | Lückenfüller: Österreich | offen | | 0012 |
| L2 | Lückenfüller: Schweiz | offen | | 0012 |
| L3 | Lückenfüller: Italien | offen | | 0012 |
| L4 | Lückenfüller: Spanien | offen | | 0012 |

Separat angestoßen (Teil von Phase 0): `permitAll` für `/api/v1/stations/**` auf GET beschränken.
Lief am 2026-09-29 als eigene Sitzung; ob der Fix auf `master` ist, vor Phase 0 prüfen.

## Überblick

```
Phase 0  Fundament ──► Phase 1  Login + Web-Gerüst ──► Phase 2  Konto & Store-Pflichten
                                                              │
         ┌────────────────────────────────────────────────────┘
         ▼
Phase 3  Favoriten + Fehler melden ──► Phase 4  Routenplaner 1 ──► Phase 5  Preise an der Station
                                                                        │
         ┌──────────────────────────────────────────────────────────────┘
         ▼
Phase 6  Routenplaner 2 ──► Phase 7  Routenplaner 3 ──► Phase 8  Web-App ──► Phase 9  CarPlay

Lückenfüller zwischen den Phasen:  AT → CH → IT → ES   (je ein sync-Adapter)
Recherche parallel zu Phase 4:     Tarif-Quellen, Fahrzeugdatenbank
Bei Bedarf (Monitoring-Trigger):   Skalierung / Variante C
Optional am Ende:                  GraphQL
v3:                                Live-Fahrzeugdaten, Turn-by-Turn
```

## Phasen

### Phase 0 — Fundament · S

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

### Phase 5 — Preise an der Station · L

Baut auf der Tarif-Recherche aus Phase 4 auf.

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

### Phase 8 — Nutzer-Web-App · XL

- Karte, Stationsdetails, Kommentare, Favoriten und Konto im Browser, auf dem Angular-Gerüst aus
  Phase 1. Den Routenplaner im Web danach.
- Karte: **MapKit JS**. Gleiche Optik und Ortssuche wie in iOS; kostenlos bis 250.000
  Kartenaufrufe und 25.000 Service-Aufrufe pro Tag. Das Token signiert das Backend. Die
  Autovervollständigung wird beim Tippen verzögert wie in iOS, weil sie das Service-Kontingent
  verbraucht.
- Vorher prüfen: Ist Skalierung schon nötig?

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
und im ADR 0012 vermerkt.

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
- Mobilithek-Live-Daten, sobald eine Organisation registriert ist (ADR 0015)
- Nicht gewählt, aber vermerkt: Fotos zu Stationen, Sternebewertungen, Widgets und Live Activity

## Entschieden am 2026-09-29

- Favoriten: auf dem Gerät, bei Anmeldung mit dem Konto synchronisiert (Phase 3).
- Web-Karte: MapKit JS; ein kompletter Umstieg auf MapLibre wird in v3 mit Turn-by-Turn geprüft.
- Reihenfolge: erst die Web-App (Phase 8), dann CarPlay (Phase 9).
- Verknüpfte Anmeldungen lassen sich nicht lösen; der Admin-Bereich ist öffentlich und nur durch
  das Admin-Flag geschützt (ADR 0018).
- Das Lastenheft hat einen v2-Abschnitt (§11); ADR 0017 und ADR 0018 sind angenommen.
