# Verarbeitung personenbezogener Daten in EVMap

- Stand: 2026-09-29
- Gilt für: iOS-Client (`evMap_ios/`) und API-Service (`evmap_service/`)

Dieses Dokument ist eine **technische Bestandsaufnahme** für Entwicklung und
Architekturentscheidungen. Es ist als Grundlage für ein Verzeichnis von
Verarbeitungstätigkeiten (Art. 30 DSGVO) und eine Datenschutzerklärung gedacht,
ersetzt aber weder das eine noch das andere und ist keine Rechtsberatung. Die
Spalte "Rechtsgrundlage" enthält die naheliegende Einordnung aus technischer
Sicht und ist juristisch zu prüfen.

Wenn sich der Code ändert, ändert sich dieses Dokument mit. Insbesondere jede
neue `AppLogger`-Aufrufstelle und jede neue Tabelle in `user_data` gehören hier
hinein.

## 1. Übersicht

| Datum | Zweck | Wo gespeichert | Aufbewahrung | Rechtsgrundlage (Einordnung) |
| --- | --- | --- | --- | --- |
| Gerätestandort | Umkreissuche nach Ladestationen | nur flüchtig im Client; als Query-Parameter an die API übertragen | nicht gespeichert | Art. 6 Abs. 1 lit. a (iOS-Standortfreigabe) |
| Apple `sub` (Provider-Subject) | Wiedererkennung des Kontos | `user_data.user_identity.provider_subject` | bis Kontolöschung | Art. 6 Abs. 1 lit. b |
| Access Token | Authentifizierung der Session | Client: `UserDefaults`; Server: **nicht** gespeichert (HMAC-signiert, zustandslos) | Client bis Logout, Token-TTL 12 h | Art. 6 Abs. 1 lit. b |
| Kommentartext, Preisangabe, Erfahrung | Nutzerbeiträge zu Ladestationen | `user_data.station_comment` | bis Löschung durch Nutzer | Art. 6 Abs. 1 lit. b |
| Zeitstempel (`created_at`, `last_login_at`) | Sortierung, Betrieb | `user_data.*` | wie zugehöriger Datensatz | Art. 6 Abs. 1 lit. f |
| Client-Logs | Fehlerdiagnose | ausschließlich Unified Log des Nutzergeräts | siehe §3 | keine Verarbeitung durch den Verantwortlichen (§3) |
| Server-Logs | Betrieb, Fehlerdiagnose | stdout des Containers | abhängig vom Log-Collector | Art. 6 Abs. 1 lit. f |
| Datenbank-Backups (alle obigen `user_data`-Inhalte) | Wiederherstellung nach Datenverlust | restic-Repository auf S3-kompatiblem Speicher (SeaweedFS) auf einem **zweiten, selbst betriebenen Host**, clientseitig verschlüsselt | 7 tägliche, 4 wöchentliche, 3 monatliche Stände — höchstens ~3 Monate (ADR 0019) | Art. 6 Abs. 1 lit. f, Art. 32 |

Ladestationsdaten (`master.*`) stammen aus BNetzA und Open Charge Map und sind
keine personenbezogenen Daten.

## 2. Was bewusst *nicht* erhoben wird

Diese Punkte sind Ergebnis von Designentscheidungen und sollten bei Änderungen
nicht stillschweigend aufgegeben:

- **Kein Name, keine E-Mail-Adresse.** Sign in with Apple liefert beides optional
  mit; `AuthController` verwirft es. Gespeichert wird nur das pseudonyme
  `provider_subject` aus dem Apple-Identity-Token.
- **Keine Standorthistorie.** Die Koordinate wird pro Suche als Query-Parameter
  gesendet und weder im Client noch in der Datenbank persistiert.
- **Kein Tracking, keine Analytics, keine Werbe-IDs.** Es gibt keine
  Drittanbieter-SDKs im Client.
- **Kein Remote-Log-Sink.** Weder Crashlytics noch Sentry noch Vergleichbares.
- **Keine serverseitige Session.** `SecurityConfiguration` ist zustandslos; das
  Access Token wird bei jedem Request neu über HMAC verifiziert, statt in einer
  Tabelle nachgeschlagen zu werden.

## 3. Client-Logs (`AppLogger`)

**Grundeinordnung:** Die Logs verlassen das Gerät nicht. Es gibt keinen
Remote-Sink; sie landen ausschließlich im Unified Log von iOS, das Apple und dem
Nutzer gehört. Damit findet insoweit keine Verarbeitung durch uns als
Verantwortlichen statt.

**Aber:** Der Unified-Log-Store wird in einen `sysdiagnose` eingeschlossen. Den
erzeugt ein Nutzer für einen Apple-Bugreport oder auf Aufforderung im Support —
und in diesem Moment fließen die Inhalte doch an Apple oder an uns. Deshalb gilt
Datenminimierung auch für Logzeilen, die das Gerät nie verlassen sollen.

### Log-Level als Aufbewahrungssteuerung

Das Unified Log behandelt Level unterschiedlich, und genau das nutzen wir:

| Level | Persistenz |
| --- | --- |
| `.debug` | wird nicht geschrieben, außer jemand streamt aktiv mit (Xcode angehängt) |
| `.info` | nur Memory-Ringpuffer; erreicht die Platte nur über `sysdiagnose` |
| `.notice` / `.error` / `.fault` | auf die Platte, überlebt Tage, immer im `sysdiagnose` |

Daraus folgt die verbindliche Regel in `LogLevel` (`AppLogger.swift`):

- **Personenbezogenes** — Koordinaten, Token-Fingerprints, Request-/Response-Bodies
  — ausschließlich auf `.debug`.
- **Betriebsdaten** — Endpoint, Statuscode, Dauer, Größe, Fehlerklasse, Anzahlen —
  dürfen `.info` und höher verwenden.

### Konkrete Maßnahmen

| Datum | Maßnahme | Ort |
| --- | --- | --- |
| Koordinaten | auf ~1 km gerundet **und** nur `.debug`; persistiert wird nur die Tatsache eines Fixes samt Genauigkeit | `MapViewModel`, `AppLogger.coordinate` |
| Koordinaten im Query-String | Werte von `latitude`/`longitude` werden aus dem Request-Label entfernt, bevor es auf `.info`/`.error` geht | `APIClient.redactedQuery` |
| Access-/Identity-Token | nie vollständig; nur Fingerprint `eyJh…f9Qw (412 chars)`, und dieser nur auf `.debug` | `AppLogger.redact(token:)`, `AuthSession` |
| Response-Bodies | nur auf Fehlerpfaden, auf 512 Byte begrenzt, `.debug`, **und per `#if DEBUG` vollständig aus dem Release-Binary entfernt** | `APIClient.logBodyPreview` |

Die Response-Body-Ausgabe ist der einzige Punkt, der zusätzlich zur
Level-Absenkung auskompiliert wird. Grund: Ein Response-Body kann
Kommentartexte **anderer** Nutzer enthalten. Diese Daten Dritter haben auf dem
Gerät dieses Nutzers auch dann nichts zu suchen, wenn sie nicht persistiert
würden.

### Bekannte Restrisiken

- **Redaction ist Konvention, keine Garantie.** `AppLogger` setzt die
  zusammengesetzte Nachricht auf `.public`, weil eine Konsole voller `<private>`
  wertlos ist. Nichts im Typsystem erzwingt, dass die Aufrufstelle vorher
  redigiert hat. Jede neue `AppLogger`-Zeile ist daher reviewpflichtig auf
  personenbezogene Inhalte.
- **`.debug` ist nicht "aus".** Bei angehängtem Debugger oder aktivem
  `log stream` sind die Werte sichtbar. Das ist beabsichtigt und akzeptabel, weil
  es einen Menschen am Gerät voraussetzt.

## 4. Server-Logs

Loglevel ist `INFO` (`application.yaml`), im Container strukturiert als ECS-JSON.
Spring Boot loggt Request-URLs auf diesem Level nicht, Koordinaten aus dem
Query-String erscheinen also nicht in den Anwendungslogs. `UserIdentityService`
loggt bewusst die interne `UUID` statt des Apple-`sub`.

**Offener Punkt:** Ein vorgelagerter Reverse Proxy (nginx, Traefik, Load
Balancer) protokolliert standardmäßig vollständige Request-URLs — inklusive
`?latitude=…&longitude=…`. Damit entstünde an einer Stelle, die dieses Repository
nicht kontrolliert, doch eine Standort-Historie pro IP-Adresse. Vor
Produktivbetrieb ist entweder das Access-Log des Proxys auf den Pfad ohne Query
zu beschränken oder eine Aufbewahrungsfrist zu setzen.

## 5. Backups

Seit Phase 0 (ADR 0019) sichert der `backup`-Container die gesamte Datenbank
nächtlich. Für den Datenschutz relevant:

- **Verschlüsselung:** restic verschlüsselt vor dem Upload; der Speicher-Host
  sieht nur Chiffrat. Das Passwort liegt außerhalb des VPS.
- **Aufbewahrung:** Kein Stand ist älter als ca. 3 Monate. Daten, die im
  Live-System gelöscht werden — einzelne Kommentare heute, ganze Konten ab
  Phase 2 —, bleiben so lange in älteren Ständen erhalten. Das gehört in die
  Datenschutzerklärung. Ob nach einer Wiederherstellung zwischenzeitliche
  Löschungen erneut angewendet werden, ist in ADR 0019 als offener Punkt für
  Phase 2 vermerkt.
- **Logs:** Das Backup-Skript protokolliert nur Snapshot-IDs, Tabellennamen und
  Zeilenanzahlen, keine Inhalte.
- **Wiederherstellungstest:** läuft wöchentlich in eine temporäre Datenbank auf
  demselben Server, die direkt danach gelöscht wird.

## 6. Betroffenenrechte — Umsetzungsstand

| Recht | Stand |
| --- | --- |
| Auskunft (Art. 15) | **nicht implementiert** — kein Export-Endpoint |
| Berichtigung (Art. 16) | teilweise — Kommentare via `PATCH /api/v1/comments/{id}` |
| Löschung (Art. 17) | **lückenhaft** — einzelne Kommentare löschbar, Konto nicht |
| Datenübertragbarkeit (Art. 20) | **nicht implementiert** |
| Widerruf Standortfreigabe | über iOS-Systemeinstellungen jederzeit möglich |

**Die Kontolöschung ist die relevanteste Lücke.** Es existiert kein Endpoint, der
eine `user_identity` samt Kommentaren entfernt. Das Schema ist darauf schon
vorbereitet — `station_comment.user_identity_id` hat `ON DELETE CASCADE`, ein
Löschen der Identity räumt die Kommentare also mit ab. Zusätzlich verlangt Apple
für Apps mit Kontoerstellung eine In-App-Kontolöschung als
Review-Voraussetzung; das ist somit auch ein Release-Blocker, nicht nur ein
DSGVO-Thema.

## 7. Auftragsverarbeiter / Dritte

- **Apple** — Sign in with Apple. Das Identity-Token wird gegen Apples
  JWKS-Endpoint geprüft (`AppleIdentityTokenVerifier`), wodurch der Server bei
  jedem Login eine Verbindung zu Apple aufbaut.
- **Hosting des API-Service** — abhängig vom Deployment (aktuell
  `evmap.joinside.de`); AV-Vertrag erforderlich.
- **Backup-Speicher und Monitoring** — SeaweedFS und Uptime Kuma laufen auf einem
  zweiten, selbst betriebenen Host. Wird dieser Host angemietet, braucht es
  auch dafür einen AV-Vertrag. Uptime Kuma erhält nur Statuscodes und
  Heartbeats, keine Nutzerdaten; die Push-Benachrichtigung über ntfy enthält
  nur den Monitornamen und eine kurze Statusmeldung.
- **Bundesnetzagentur / Open Charge Map / Etalab (IRVE)** — Datenquellen der
  Stammdaten-Ingestion, ausschließlich eingehend und ohne Personenbezug.
- **MobiData BW (OCPDB)** — Live-Verfügbarkeit (ADR 0015). Anders als die
  Stammdatenquellen wird dieser Dienst *anlassbezogen* abgefragt: beim Öffnen
  einer Station und beim Verschieben der Karte. Übertragen wird ausschließlich
  ein Koordinatenrechteck — keine Nutzerkennung, kein Token, kein Gerätebezug,
  und der Aufruf erfolgt vom Server, nicht vom Client, sodass die IP-Adresse
  des Geräts MobiData BW nicht erreicht. Das Rechteck lässt dennoch Rückschlüsse
  darauf zu, welche Gegend gerade betrachtet wird; da die Anfrage serverseitig
  gebündelt und ohne Kennung gestellt wird, ist sie keinem Nutzer zuordenbar.
  Die serverseitige Zwischenspeicherung (60 s) reduziert die Zahl der Aufrufe
  zusätzlich. **Restrisiko:** ein Deployment mit sehr wenigen aktiven Nutzern
  macht einzelne Anfragen theoretisch zuordenbar — dieselbe Einschränkung, die
  für jede serverseitige Weiterleitung gilt.
- **transport.data.gouv.fr (IRVE dynamique)** — Live-Verfügbarkeit für
  Frankreich (ADR 0015). Abgerufen wird immer die komplette landesweite Datei,
  höchstens einmal pro Minute und nur, wenn jemand eine französische Station
  oder einen Kartenausschnitt in Frankreich ansieht. Die Anfrage enthält
  **keine Koordinaten**, keine Nutzerkennung und keinen Gerätebezug; sie
  verrät lediglich, dass in dieser Minute irgendein Nutzer Frankreich
  betrachtet hat.

## 8. Referenzen

- `evMap_ios/EVMap/EVMap/Core/Logging/AppLogger.swift` — Level-Policy und Redaction-Helper
- `evMap_ios/EVMap/EVMap/Core/Networking/APIClient.swift` — Query-Redaction, Body-Preview
- `evmap_service/src/main/resources/db/changelog/001-initial-schema.sql` — `user_data`-Schema
- `docs/adr/0002-central-ios-logging-via-oslog.md` — Begründung des Logging-Designs
- `docs/adr/0019-backups-and-monitoring.md` — Backups und Monitoring
