# Impressum, privacy policy and logs

What the server needs so that the Impressum is complete and the privacy policy is true (ADR 0024).

## 1. Operator details

In `deploy/.env`:

```sh
OPERATOR_NAME=Vorname Nachname
OPERATOR_STREET=Straße Hausnummer
OPERATOR_CITY=PLZ Ort
OPERATOR_COUNTRY=Deutschland        # optional, this is the default
OPERATOR_EMAIL=kontakt@example.org
OPERATOR_PHONE=                     # optional
```

The address must be one where post reaches you (a "ladungsfähige Anschrift"), not a P.O. box. Then
`docker compose -f deploy/docker-compose.yml up -d web` and check:

```sh
curl -s https://evmap.joinside.de/site-operator.json
```

Without `OPERATOR_NAME` the web container logs `WARNING: OPERATOR_NAME not set` and both pages show that the details
are missing.

## 2. Privacy policy URL for iOS and the App Store

```sh
PRIVACY_POLICY_URL=https://evmap.joinside.de/de/datenschutz
```

in `deploy/.env` (read by the API, `GET /api/v1/legal`, shown in the iOS settings), and the same URL in App Store
Connect under *App Privacy*.

## 3. Nginx Proxy Manager: no access log, error logs for 14 days

The privacy policy says that access logs hold no full IP address and error logs are deleted after 14 days at the
latest. The web container already logs shortened addresses; the proxy in front of it has to follow.

1. **No access log for EVMap.** NPM → *Hosts* → *Proxy Hosts* → the EVMap host → *Edit* → *Advanced* → *Custom
   Nginx Configuration*:

   ```nginx
   access_log off;
   ```

   Save. `off` switches off every access log of this server block, including NPM's own
   `/data/logs/proxy-host-<id>_access.log`. Old files with full addresses: delete them once
   (`/data/logs/proxy-host-<id>_access.log*` in NPM's data volume).

2. **Error logs at most 14 days.** NPM rotates error logs weekly and keeps ten of them. Mount your own logrotate file
   over `/etc/logrotate.d/nginx-proxy-manager` (NPM's documented way), for example as `./logrotate-npm` next to
   NPM's compose file:

   ```
   /data/logs/*_access.log /data/logs/*/access.log {
       su npm npm
       create 0644
       daily
       rotate 7
       missingok
       notifempty
       compress
       sharedscripts
       postrotate
       kill -USR1 `cat /run/nginx/nginx.pid 2>/dev/null` 2>/dev/null || true
       endscript
   }

   /data/logs/*_error.log /data/logs/*/error.log {
       su npm npm
       create 0644
       daily
       rotate 13
       missingok
       notifempty
       compress
       sharedscripts
       postrotate
       kill -USR1 `cat /run/nginx/nginx.pid 2>/dev/null` 2>/dev/null || true
       endscript
   }

   /data/logs/backend.log {
       su npm npm
       size 10M
       rotate 5
       missingok
       notifempty
       compress
       copytruncate
   }
   ```

   and in NPM's `docker-compose.yml` under `volumes:`
   `- ./logrotate-npm:/etc/logrotate.d/nginx-proxy-manager:ro`, then `docker compose up -d`. The current file plus
   13 rotated ones are 14 days. The access-log block only matters for other hosts on the same NPM; it is shortened
   to a week as well.

3. **Check.** Open a page of EVMap, then on the server
   `ls -la <npm-data>/logs/` — the EVMap host's access log must not grow.

## 4. When something changes

- **Hosting moves to a provider:** the policy's section 2 changes (provider, location, data processing agreement),
  and so does `docs/privacy/data-processing.md` §7.
- **New personal data, cookie or third party:** `docs/privacy/data-processing.md` and
  `evmap_web/src/app/features/legal/privacy.page.html` in the same change, plus the "Stand" date and the English
  translation in `messages.en.xlf`.
- **Address change:** only `deploy/.env` and a restart of `web`.
