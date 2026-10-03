# Mobilithek live data (Germany)

Operator runbook for gap filler L5 ([ADR 0015, "Mobilithek (L5)"](../adr/0015-live-availability-national-access-points-with-tomtom-fallback.md#mobilithek-l5-2026-10-02)).
The API container pulls the operators' AFIR dynamic feeds from the Mobilithek broker once a minute and shows their
live status wherever the EVSE-ID matches a charge point exactly. Without a machine certificate, or without
subscription ids, the provider is off and Germany is answered by MobiData BW alone — nothing breaks.

Secrets go into `deploy/.env` on the VPS and nowhere else. Never paste the certificate or its password into a chat,
an issue or a commit.

## 1. Machine account and certificate (👤)

1. Sign in at [mobilithek.info](https://mobilithek.info) → **Meine Organisation** → **Maschinenkonten** →
   **Maschinenkonto hinzufügen** (e.g. `evmap-api`).
2. The Mobilithek sends the certificate as a **PKCS#12 file** (`.p12`) by e-mail to the organisation admin and the
   password **by SMS**. The certificate is the only credential: whoever holds file and password reads every
   subscription of the organisation.
3. Note the expiry date. The API logs it at every start and warns 30 days before.

## 2. Subscribe to the feeds (👤)

Subscribe — with the *Bestell-Manager* role — to every offering listed under `evmap.availability.mobilithek.feeds`
in `evmap_service/src/main/resources/application.yaml` (the `url` of each entry is its catalogue page), delivery
mode **Pull**. Five need the operator's approval and may take days: ENERANDO, TC-Backend (Taubert Consulting),
GLS Mobility, msu m8mit, Eco-Movement.

Then copy each **Subskriptions-ID** from **Meine Abonnements** into the `subscription-id` of its entry and release
the change like any other. An entry without an id is skipped. The ids are not secret — the certificate is.

Some operators name their charge points by internal id in the live feed (the API log's snapshot line shows "other
ids e.g. …" with UUIDs or hashes). For those, also subscribe the operator's static offering
(`AFIR-recharging-stat-…`) and put its Abonnement-ID into `static-subscription-id` of the same entry; the API then
translates the ids into EVSE-IDs once a day and logs `… internal id(s) translated to EVSE-IDs from its static feed`.

New operators appear in the catalogue over time: search for `AFIR-recharging-dyn`, add an entry (publisher,
licence from the offering's *Nutzungsbedingungen*, URL), subscribe, release. `ShippedMobilithekFeedsTests` checks
the table.

## 3. Configure the API

In `deploy/.env`:

```sh
# The .p12 file, Base64-encoded on one line: base64 -i evmap-api.p12 | tr -d '\n'
MOBILITHEK_KEYSTORE=MIIK…
# The password from the SMS.
MOBILITHEK_KEYSTORE_PASSWORD=…
```

Then `docker compose -f deploy/docker-compose.yml up -d api`. For local runs `MOBILITHEK_KEYSTORE_PATH` may point at
the file instead.

## 4. Check it works

The API logs, per start and per feed (no secrets, no ids of users):

- `Mobilithek machine certificate loaded, valid until …` — certificate and password are right. An unreadable
  certificate logs an ERROR and switches only the Mobilithek off; the API keeps running.
- `Mobilithek feeds: N subscribed of 28 configured`
- `Mobilithek feed EnBW AG: snapshot with … charge point(s), … of them shaped like an EVSE-ID, … ignored` — one
  line per feed after its first full package. If "shaped like an EVSE-ID" is far below the total for a large
  operator, that operator uses internal ids and its static feed would be needed (ADR 0015, L5 open point b).
- `Mobilithek coverage EWE: … of … live charge point(s) match a stored EVSE-ID, … untranslated internal id(s);
  operator prefixes [DEEWE] carry … stored EVSE-ID(s); unmatched e.g. […]` — hourly, first 5 minutes after start, one
  line per feed that serves anything, then a total `Mobilithek coverage: … of … stored EVSE-ID(s) have a live
  status`. Few stored EVSE-IDs under a feed's prefixes: our master data lacks that operator's EVSE-IDs. Many stored
  but few matched: the feed lacks them or spells them differently — compare the samples (ADR 0015, "Coverage report").
- `answered HTTP 404 … leaving it alone until …` — the subscription is missing or not approved yet, or the
  operator's access quota is used up. The feed is retried after an hour.

Then open a German station of a subscribed operator in the app: the live section should credit
"<operator> via Mobilithek".

## 5. Renewal

Before the certificate expires, request a new one (step 1), replace `MOBILITHEK_KEYSTORE` and
`MOBILITHEK_KEYSTORE_PASSWORD`, restart `api`. The subscriptions stay.
