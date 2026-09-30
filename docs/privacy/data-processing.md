# Verarbeitung personenbezogener Daten in EVMap

- Stand: 2026-09-30 (Phase 3: Favoriten, Stationsmeldungen)
- Gilt für: iOS-Client (`evMap_ios/`), Web-Client (`evmap_web/`) und API-Service (`evmap_service/`)

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
| Provider-Subject (Apple `sub`, Google `sub`, GitHub-Nutzer-ID) | Wiedererkennung der Anmeldung | `user_data.provider_identity.provider_subject` | bis Kontolöschung | Art. 6 Abs. 1 lit. b |
| E-Mail-Adresse und ob der Anbieter sie bestätigt hat (seit Phase 1, ADR 0018) | automatische Kontoverknüpfung über eine bestätigte Adresse; ab Phase 2 Datenexport | `user_data.provider_identity.email`, `.email_verified` | bis Kontolöschung; bei jeder Anmeldung auf den Stand des Anbieters gebracht | Art. 6 Abs. 1 lit. b |
| Konto (interne UUID, Admin-Flag) | Zuordnung aller Nutzerdaten; Zugang zum Admin-Bereich | `user_data.account` | bis Kontolöschung | Art. 6 Abs. 1 lit. b |
| Apple-Refresh-Token (seit Phase 2, ADR 0020) | Widerruf des Sign-in-with-Apple-Tokens bei Kontolöschung (Pflicht von Apple) | `user_data.provider_identity.refresh_token`, AES-256-GCM-verschlüsselt; Schlüssel nur in der Umgebung des API-Containers | bis Kontolöschung; ein Zugangsdatum, kein Nutzerinhalt, deshalb **nicht** im Datenexport | Art. 6 Abs. 1 lit. b |
| Access Token | Authentifizierung der Session | iOS: `UserDefaults`; Web: `HttpOnly`-Cookie `evmap_session` (für Skripte der Seite nicht lesbar); Server: **nicht** gespeichert (HMAC-signiert, zustandslos), gelöschte Konten werden bei jeder Anfrage abgewiesen | iOS bis Logout, Web bis Logout oder Token-TTL 12 h | Art. 6 Abs. 1 lit. b |
| PKCE-Verifier und `state` einer laufenden Web-Anmeldung | Schutz des Anmeldeablaufs | Web: `sessionStorage` des Tabs | nur zwischen Absprung zum Anbieter und Rückkehr; wird beim Einlesen gelöscht | Art. 6 Abs. 1 lit. b |
| Kommentartext, Preisangabe, Erfahrung | Nutzerbeiträge zu Ladestationen | `user_data.station_comment` | bis Löschung durch Nutzer | Art. 6 Abs. 1 lit. b |
| Meldung eines Kommentars (Grund: Spam, unangemessen, falsch, sonstiges) samt Melder-Konto (seit Phase 2) | Moderation; Ausblenden für den Melder | `user_data.comment_report` | bis Kontolöschung des Melders oder Löschung des Kommentars; Admins sehen den Grund, **nicht** den Melder | Art. 6 Abs. 1 lit. f (Nutzerinhalte moderieren), App-Store-Richtlinie 1.2 |
| Blockierung eines Autors (seit Phase 2) | Kommentare eines Autors für den Blockierenden ausblenden | `user_data.account_block` (Blockierender, Blockierter, Zeitpunkt) | bis Aufhebung oder Kontolöschung einer der beiden Seiten; der Blockierte erfährt nichts davon | Art. 6 Abs. 1 lit. b |
| Favorisierte Stationen, synchronisiert (seit Phase 3, ADR 0021) | Favoriten über Geräte und Clients hinweg | `user_data.favorite_station` (Konto, Station, Zeitpunkt) | bis Entfernen, Löschung der Station oder Kontolöschung; im Datenexport | Art. 6 Abs. 1 lit. b |
| Favorisierte Stationen, lokal (iOS, seit Phase 3) | Favoriten ohne Konto; Liste und Kartenmarkierung ohne Netzwerkzugriff | `UserDefaults` der App (Schlüssel `favorites.v1`, ganze Stationsdatensätze); nie geloggt | bis Entfernen, **Abmelden leert die Liste**, oder Deinstallation der App | Art. 6 Abs. 1 lit. b |
| Fehlermeldung zu einer Station (Grund, optionaler Freitext bis 500 Zeichen) samt Melder-Konto (seit Phase 3, ADR 0021) | Datenqualität; Admin-Warteschlange | `user_data.station_report` | bis Kontolöschung des Melders oder Löschung der Station; Admins sehen Grund, Anzahl und Freitext, **nicht** den Melder; der Freitext wird nie geloggt | Art. 6 Abs. 1 lit. f (Datenqualität der Stationsdaten); der Freitext ist freiwillig, die Einordnung ist juristisch zu prüfen |
| Datenexport-Datei (iOS, seit Phase 2) | Weitergabe des Exports über das Teilen-Menü | temporäres Verzeichnis der App, mit vollständigem Dateischutz | bis der Konto-Bildschirm verlassen wird (dann gelöscht) oder das System das temporäre Verzeichnis leert | Art. 6 Abs. 1 lit. b (Art. 15/20) |
| Zeitstempel (`created_at`, `last_login_at`) | Sortierung, Betrieb | `user_data.*` | wie zugehöriger Datensatz | Art. 6 Abs. 1 lit. f |
| Client-Logs | Fehlerdiagnose | ausschließlich Unified Log des Nutzergeräts | siehe §3 | keine Verarbeitung durch den Verantwortlichen (§3) |
| Server-Logs | Betrieb, Fehlerdiagnose | stdout des Containers | abhängig vom Log-Collector | Art. 6 Abs. 1 lit. f |
| Datenbank-Backups (alle obigen `user_data`-Inhalte) | Wiederherstellung nach Datenverlust | restic-Repository auf S3-kompatiblem Speicher (SeaweedFS) auf einem **zweiten, selbst betriebenen Host**, clientseitig verschlüsselt | 7 tägliche, 4 wöchentliche, 3 monatliche Stände — höchstens ~3 Monate (ADR 0019) | Art. 6 Abs. 1 lit. f, Art. 32 |

Ladestationsdaten (`master.*`) stammen aus BNetzA und Open Charge Map und sind
keine personenbezogenen Daten.

## 2. Was bewusst *nicht* erhoben wird

Diese Punkte sind Ergebnis von Designentscheidungen und sollten bei Änderungen
nicht stillschweigend aufgegeben:

- **Kein Name.** Keiner der Anbieter wird nach dem Namen gefragt; was Apple beim
  ersten Web-Login dennoch mitschickt (`user`), verwirft `AuthController`.
- **E-Mail-Adresse nur zur Kontoverknüpfung** (seit Phase 1, ADR 0018). Sie wird
  gespeichert, weil sich nur so Konten verschiedener Anbieter automatisch
  zusammenführen lassen, aber **nie geloggt** (ADR 0002) und nicht an andere
  Nutzer ausgegeben — `GET /api/v1/me` zeigt sie nur dem Konto selbst. Wer bei
  Apple „E-Mail verbergen“ wählt, liefert eine Relay-Adresse, die mit nichts
  verknüpft wird.
- **Keine Standorthistorie.** Die Koordinate wird pro Suche als Query-Parameter
  gesendet und weder im Client noch in der Datenbank persistiert.
- **Kein Tracking, keine Analytics, keine Werbe-IDs.** Es gibt keine
  Drittanbieter-SDKs im Client.
- **Kein Remote-Log-Sink.** Weder Crashlytics noch Sentry noch Vergleichbares.
- **Admins sehen keine Melder.** Die Moderations-Warteschlange zeigt den gemeldeten
  Kommentar, Gründe und Anzahl, nie das Konto, das gemeldet hat. Dasselbe gilt für die
  Warteschlange der Stationsmeldungen (Phase 3): Grund, Anzahl und die neuesten Freitexte,
  nie das meldende Konto. Auch Blockierungen nennen
  nicht, wen sie betreffen: der Client bekommt nur eine eigene Blockier-ID, die Konto-ID
  eines anderen verlässt die Datenbankschicht nie.
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
Query-String erscheinen also nicht in den Anwendungslogs. `AccountService` und
`AuthController` loggen bewusst die interne Konto-`UUID` und den Anbieternamen,
nie Subject, E-Mail-Adresse, Autorisierungscode oder Token.

Der Web-Container (nginx, `evmap_web`) schreibt ein Access-Log mit der
Request-Zeile. Auf der Rückkehr-Route `/auth/callback/<anbieter>` enthält sie
einen **einmal verwendbaren, kurzlebigen Autorisierungscode** — für sich allein
nutzlos, weil der Tausch das Client-Secret (und bei Google/GitHub den
PKCE-Verifier) braucht. Access Tokens erscheinen dort nie; sie reisen nur im
`Authorization`-Header.

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
  Live-System gelöscht werden — einzelne Kommentare, ganze Konten —, bleiben so
  lange in älteren Ständen erhalten. Das gehört in die Datenschutzerklärung.
  Entscheidung (ADR 0020): Diese Frist wird akzeptiert und dokumentiert; es gibt
  kein Löschprotokoll. Nach einer Wiederherstellung können gelöschte Konten
  deshalb wieder auftauchen, bis sie erneut gelöscht werden.
- **Logs:** Das Backup-Skript protokolliert nur Snapshot-IDs, Tabellennamen und
  Zeilenanzahlen, keine Inhalte.
- **Wiederherstellungstest:** läuft wöchentlich in eine temporäre Datenbank auf
  demselben Server, die direkt danach gelöscht wird.

## 6. Betroffenenrechte — Umsetzungsstand

| Recht | Stand |
| --- | --- |
| Auskunft (Art. 15) | umgesetzt (Phase 2) — `GET /api/v1/me/export`, in App und Web |
| Berichtigung (Art. 16) | teilweise — Kommentare via `PATCH /api/v1/comments/{id}` |
| Löschung (Art. 17) | umgesetzt (Phase 2) — `DELETE /api/v1/me` löscht Konto, Anmeldungen, Kommentare, Meldungen und Blockierungen sofort; Backups siehe §5 |
| Datenübertragbarkeit (Art. 20) | umgesetzt (Phase 2) — derselbe Export als JSON |
| Widerruf Standortfreigabe | über iOS-Systemeinstellungen jederzeit möglich |

**Kontolöschung** (Phase 2, ADR 0020): Alles Nutzereigene hängt über
`ON DELETE CASCADE` am Konto (`provider_identity`, `station_comment`,
`comment_report`, `account_block`), das Löschen ist ein einziges Statement. Vorher
widerruft das Backend den gespeicherten Apple-Refresh-Token bei Apple; ist Apple
nicht erreichbar oder gibt es noch keinen Token, wird trotzdem gelöscht und das
protokolliert. Der Access Token verliert mit dem Konto sofort seine Gültigkeit.
**Wer neue Nutzerdaten in `user_data` ablegt, muss sie in `MyDataRepository`
(Export) aufnehmen;** `MyDataTests.exportKnowsEveryUserDataTable` schlägt sonst fehl.

## 7. Auftragsverarbeiter / Dritte

- **Apple** — Sign in with Apple. Das Identity-Token wird gegen Apples
  JWKS-Endpoint geprüft (`AppleIdentityTokenVerifier`), wodurch der Server bei
  jedem Login eine Verbindung zu Apple aufbaut. Der Server tauscht den
  Autorisierungscode (Web, seit Phase 2 auch die App) bei Apple gegen einen
  Refresh-Token und widerruft ihn bei der Kontolöschung.
- **Google, GitHub** (seit Phase 1) — Anmeldung über den Browser bzw.
  `ASWebAuthenticationSession`. Der Server tauscht den Code beim Anbieter ein
  und liest Subject, E-Mail-Adresse und Bestätigungsstatus (GitHub: `/user`
  und `/user/emails`). Der Anbieter erfährt dabei, dass sich jemand bei EVMap
  anmeldet — das ist jeder Anmeldung über einen Dritten eigen.
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
- `evmap_service/src/main/resources/db/changelog/001-initial-schema.sql`, `007-accounts-and-provider-identities.sql` — `user_data`-Schema
- `docs/adr/0002-central-ios-logging-via-oslog.md` — Begründung des Logging-Designs
- `docs/adr/0019-backups-and-monitoring.md` — Backups und Monitoring
- `docs/adr/0020-account-area-and-app-store-obligations.md` — Kontolöschung, Export, Melden und Blockieren
