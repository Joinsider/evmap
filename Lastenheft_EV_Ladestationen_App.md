# Lastenheft: EV-Ladestationen App (iOS)

## 1. Zielbestimmung

Die App soll reisenden und privaten E-Auto-Fahrern in Europa eine kartenbasierte Übersicht öffentlicher Ladestationen bieten, die Daten aus mehreren Open-Data-Quellen zusammenführt und um Community-Feedback (Bewertungen, Preise, Ladeerfahrungen) ergänzt.

## 2. Produkteinsatz

- **Zielgruppe:** Private und reisende EV-Fahrer in Europa, primär DACH-Raum (BNetzA-Daten), sekundär EU-weit (Open Charge Map)
- **Einsatzumgebung:** Native iOS-App (Swift), Backend selbst gehostet auf privatem VPS via Docker
- **Nutzungskontext:** Online-Nutzung vorausgesetzt, keine Offline-Fähigkeit erforderlich

## 3. Produktfunktionen (v1 - funktionale Anforderungen)

### Kartenansicht
- Apple Maps als Kartenhintergrund (via MapKit), Ladestationen als Annotations/Overlay

### Ladestationsdaten
- Anzeige gemergter Daten aus Bundesnetzagentur-Ladesäulenregister und Open Charge Map
- Filter nach Steckertyp, Ladeleistung (kW), Anbieter/Betreiber
- Verfügbarkeitsfilter vorbereiten (Datenfeld einplanen, auch wenn keine Datenquelle es aktuell liefert)
- Ladepunkt-Inventar je Station (einzelne Ladepunkte mit eigenen Steckern und EVSE-ID), soweit die
  Quelle sie einzeln ausweist
- Live-Verfügbarkeit je Ladepunkt, wo eine AFIR-Datenquelle sie liefert und die EVSE-ID exakt
  zugeordnet werden kann (siehe ADR 0015)

### Community-Funktionen
- Kommentarfunktion pro Ladestation
- Optionale Angabe von gezahltem Preis und Ladeerfahrung durch Nutzer
- Hierfür ist ein Account erforderlich (Login mit Apple)
- Nutzer sollen ihre Bewertungen auch wieder löschen und bearbeiten können.

### Authentifizierung
- Login mit Apple ("Sign in with Apple"), App-Grundfunktionen auch ohne Account nutzbar, aktuell nur für Kommentarfunktion
- Kein externer Identity Provider (z. B. Zitadel) in v1 - direkte Verifizierung des Apple-Identity-Tokens im Backend
- Baue eine Tabelle für Nutzeridentifikation ein, die darauf vorbereitet ist später ggf. weitere Anbieter zu unterstützen. Intern werden dann nur die IDs aus dieser Tabelle für die Nutzeridentifikation genutzt

### Internationalisierung
- App-Texte vorbereitet für i18n (String-Ressourcen statt Hardcoding)
- Sprachunterstützung: Deutsch (Basis) und Englisch
- Backend-Daten müssen nicht mehrsprachig sein; falls Quelle mehrsprachige Daten liefert, werden diese optional mitgespeichert

## 4. Produktfunktionen (später - vorzubereitende Erweiterungen)

- Routenplanung mit Ladehalt-Vorschlägen (Reichweitenmanagement)
- Kombination weiterer Kartenanbieter-Datenquellen (Merge über mehrere Kartenanbieter hinweg)
- Migration der App-Netzwerkschicht von REST auf GraphQL (v2)

## 5. Datenquellen & Merge-Logik

### Quellen
| Quelle | Abdeckung | Lizenz | Format |
|---|---|---|---|
| Bundesnetzagentur Ladesäulenregister | Deutschland | CC BY 4.0 | JSON/CSV, tägliches Bulk-Update |
| Open Charge Map | International/EU | CC BY 4.0 (Community-Daten), API-Key Pflicht | JSON REST API |

### Merge-Strategie
- **Deduplizierung:** Geo-Distanz-Matching (Radius ca. 15-30m) kombiniert mit Adress-Fuzzy-Match, da keine gemeinsame ID über Quellen existiert
- **Feldpriorität:** BNetzA als "amtliche" Quelle für Adresse/Betreiber/Leistung bei deutschen Standorten; Open Charge Map für internationale Standorte und community-gepflegte Zusatzinfos
- **Konfliktauflösung:** Pro Feld wird `source` und `lastUpdated` gespeichert; bei Widerspruch gewinnt die aktuellere Quelle, BNetzA als Tie-Breaker für DE-Standorte
- **Nutzerdaten getrennt:** Kommentare/Preisangaben werden als eigene Entität verknüpft, nicht in Stammdaten geschrieben (Nutzen Nutzer-ID aus interner DB-Tabelle als Identifikation) Aber keine Namensnennung in der App für andere Nutzer
- **Provenienz-Transparenz:** Datenquelle pro Ladepunkt in der App sichtbar (Pflicht zur Namensnennung), ggf. beide / mehrere wenn mehrere Daten kombiniert werden dann aber auch mit Label, „Kombination mehrerer Datenquellen: "

## 6. Backend-Architektur

### Startarchitektur: Variante B (Monolith + separater Sync-Container)
- **API-Service:** Spring Boot 4 Anwendung, stateless, beliebig horizontal skalierbar, beantwortet alle Anfragen der iOS-App (REST in v1)
- **Sync-Service:** Separater Docker-Container, fragt periodisch BNetzA/OCM ab, führt Merge-Logik aus, schreibt in gemeinsame PostgreSQL/PostGIS-Datenbank
- **Datenbank:** PostgreSQL mit PostGIS-Extension für performante Geo-Queries (Umkreissuche, räumliche Indizierung)

### Vorbereitung auf Variante C (getrennte Services)
Um den späteren Übergang zu einer vollständig getrennten Ingestion-/API-Service-Architektur ohne größeren Umbau zu ermöglichen, gelten für v1 folgende Architekturvorgaben:

- Sync-Logik von Beginn an in einem eigenständigen Java-Package/Modul kapseln, strikt getrennt von REST-Controller- und Business-Logik der API
- Sync-Job erhält eigenes Dockerfile und eigenen Build-Prozess, auch wenn er anfangs im selben Repository liegt - Ziel: Auslagerung in einen komplett separaten Service ist rein ein Deployment-Schritt, kein Code-Umbau
- Datenbankschema von Anfang an trennen: Stammdaten-Tabellen (nur vom Sync-Service beschrieben) und Nutzerdaten-Tabellen (Kommentare, Accounts - nur vom API-Service beschrieben) liegen in getrennten Schemas
- Kommunikation zwischen den Modulen ausschließlich über klar definierte Interfaces/Repository-Abstraktionen, keine direkten Querverweise zwischen Sync- und API-Code

### Spätere Zielarchitektur: Variante C
- **Ingestion-Service:** Eigenständiger Container ohne öffentliche Schnittstelle, nur Schreibrechte auf Stammdaten-Tabellen
- **API-Service:** Stateless, nur Lesezugriff auf Stammdaten, Schreibzugriff auf Nutzerdaten, horizontal beliebig skalierbar
- **Optionale Erweiterung:** Kommunikation über Message Queue (z. B. Kafka) statt direktem DB-Schreiben, falls mehrere Datenkonsumenten oder komplexere Verarbeitungsschritte entstehen

## 7. Schnittstellen-Architektur (Backend zu App)

- **v1:** REST-API zwischen Backend und iOS-App
- **Vorbereitung v2 (GraphQL):** iOS-App-Netzwerkschicht wird von Beginn an hinter einer Abstraktionsschicht (z. B. Protocol/Interface `ChargingStationRepository`) implementiert, sodass ViewModels und UI-Code ausschließlich mit dieser Abstraktion arbeiten
- Der konkrete REST-Client (v1) kann dadurch in v2 durch einen GraphQL-Client ersetzt werden, ohne dass darüberliegende App-Schichten angepasst werden müssen
- Backend-seitig werden DTOs und Service-Layer so entworfen, dass eine spätere GraphQL-Schicht (z. B. via Netflix DGS oder Spring for GraphQL) über die bestehende Business-Logik gelegt werden kann

## 8. Authentifizierung & Nutzerkonzept

- Sign in with Apple als einziger Login-Weg in v1, kein externer Identity Provider
- Ablauf: iOS-App erhält signiertes Identity-Token von Apple, sendet es an Backend, Backend verifiziert gegen Apples JWKS-Endpoint (appleid.apple.com/auth/keys), stellt danach eigene Session/Access-Token aus
- App-Grundfunktionen (Kartenansicht, Filter, Ladestations-Infos) ohne Account nutzbar
- Account erforderlich nur für: Kommentare/Bewertungen schreiben, zukünftige nutzerbezogene Erweiterungen
- Baue eine Tabelle für Nutzeridentifikation ein, die darauf vorbereitet ist später ggf. weitere Anbieter zu unterstützen. Intern werden dann nur die IDs aus dieser Tabelle für die Nutzeridentifikation genutzt

## 9. Nicht-funktionale Anforderungen

- **Containerisierung:** Alle Backend-Komponenten laufen als Docker-Container
- **Skalierbarkeit:** API-Service muss horizontal skalierbar sein (nicht zwingend ab v1 aktiv genutzt, aber architektonisch vorbereitet)
- **Datenschutz:** DSGVO-Konformität - Datenminimierung bei Kommentaren, Speicherort EU, Löschkonzept für Nutzerdaten
- **Datenbank:** PostgreSQL mit PostGIS-Extension
- **Hosting:** Deployment auf bestehendem privatem VPS, kein zusätzliches Cloud-Budget vorgesehen
- **Internationalisierung:** App-seitig vorbereitet für Deutsch/Englisch (i18n-Grundgerüst, keine direkten Texte innerhalb der App alles dynamisch über die i18n Dateien)

## 10. Abgrenzungskriterien (Out of Scope für v1)

- Kein eigenes Meldeverfahren für Ladepunktbetreiber (nur Konsument bestehender Register)
- Keine Zahlungsabwicklung oder Ladevorgangs-Steuerung
- Kein Android-Client
- Keine Routenplanung/Reichweitenmanagement
- Keine flächendeckende Echtzeit-Verfügbarkeit — seit ADR 0015 wird sie dort angezeigt, wo eine
  nationale AFIR-Datenquelle sie liefert und die EVSE-ID exakt zuzuordnen ist; ohne exakten
  Treffer bleibt der Status bewusst unbekannt. Kein Anspruch auf vollständige Abdeckung.
- Kein externer Identity Provider
- Keine GraphQL-Schnittstelle (nur Vorbereitung der Abstraktion)
