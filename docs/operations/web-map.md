# Web map (MapKit JS)

Operator runbook for phase 8a ([ADR 0023](../adr/0023-user-web-app.md)). The web app's start page is a MapKit JS
map. Its token is signed by the API (`GET /api/v1/map/token`, 30 minutes, bound to the web domain), so the API needs
a **Maps key** from the Apple developer portal. Without one the endpoint answers 404 and the web app shows
"Die Karte ist gerade nicht verfügbar" — stations opened by link still work.

Secrets go into `deploy/.env` on the VPS and nowhere else.

## 1. Create the Maps ID and key (👤)

1. [Apple Developer → Certificates, Identifiers & Profiles → Identifiers](https://developer.apple.com/account/resources/identifiers/list),
   filter **Maps IDs**, add one (e.g. `maps.de.joinside.evmap`).
2. **Keys** → add a key, enable **MapKit JS**, configure it with the Maps ID from step 1, and download the `.p8`
   file. Apple shows the download once. Note the **Key ID**.
3. The **Team ID** is the one already set as `APPLE_TEAM_ID`.

A separate key for the map is recommended over adding MapKit JS to the Sign in with Apple key: the two can then be
revoked independently.

## 2. Configure the API

In `deploy/.env`:

```sh
MAPKIT_KEY_ID=ABC123DEFG
# The .p8 file's content; newlines may be written as \n.
MAPKIT_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n…\n-----END PRIVATE KEY-----"
# Optional: defaults to APPLE_TEAM_ID and to evmap.joinside.de.
# MAPKIT_TEAM_ID=
# MAPKIT_ORIGIN=evmap.joinside.de
```

Then `docker compose -f deploy/docker-compose.yml up -d api`. A malformed key stops the API at startup with
"MAPKIT_PRIVATE_KEY is not a PKCS#8 EC key" — the key itself is never logged.

## 3. Roll out the web container

The web container's Content-Security-Policy now allows `https://cdn.apple-mapkit.com` (script) and
`https://*.apple-mapkit.com` (tiles, search, workers). Pull and restart `web` with the release that carries it.

## 4. Check

1. `curl -s https://evmap.joinside.de/api/v1/map/token` answers `{"token":"…","expiresAt":"…"}` with
   `Cache-Control: no-store`.
2. `https://evmap.joinside.de/de/` shows the map with pins; panning loads stations, a pin opens the station panel,
   the search finds "Stuttgart Hauptbahnhof".
3. In the [Maps usage dashboard](https://developer.apple.com/account/resources/services/maps-tokens) map views and
   service calls appear. The free quota is 250.000 map views and 25.000 service calls a day.
4. Before announcing the web app publicly: check API response times and load in Uptime Kuma (ADR 0023, "Scaling is
   checked before the public launch").

## Local development

`npm start` serves the web app on `http://localhost:4200`. To see the real map there, run the API with the key and
`MAPKIT_ORIGIN=localhost`; without a key the map page shows its "not available" notice and everything else works.
