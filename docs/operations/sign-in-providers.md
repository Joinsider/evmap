# Sign-in providers, web client and admin access

Operator runbook for phase 1 (ADR 0018) and the account settings of phase 2 (ADR 0020). The why is in
[ADR 0018](../adr/0018-additional-identity-providers-and-web-client.md). Everything below is a
one-time setup; secrets go into `deploy/.env` on the VPS and nowhere else.

The web client runs at **`https://evmap.joinside.de`**, the same domain the iOS app already uses for
the API: the `web` container serves the Angular app and proxies `/api/**` to the `api` container.
All redirect URIs derive from `WEB_BASE_URL`:

| Provider | Redirect URI to register |
| --- | --- |
| Google | `https://evmap.joinside.de/auth/callback/google` |
| GitHub | `https://evmap.joinside.de/auth/callback/github` |
| Apple (web) | `https://evmap.joinside.de/api/v1/auth/apple/callback` |

A provider whose variables are empty is switched off: the login screens simply do not offer it.
So each provider can be set up on its own, in any order.

## 1. Reverse proxy and the web container

1. Point the reverse proxy for `evmap.joinside.de` at the **`web`** container
   (`WEB_PORT`, default `8081`) instead of the `api` container. The iOS app keeps working unchanged,
   because `/api/**` reaches the API through the web container.
2. In `deploy/.env` set `WEB_BASE_URL=https://evmap.joinside.de`.
3. Pull and restart: `docker compose -f deploy/docker-compose.yml pull && … up -d`.
4. Check: `https://evmap.joinside.de/` redirects to `/de/` or `/en/`, and
   `https://evmap.joinside.de/api/v1/auth/providers` answers `[]` until a provider is configured.

## 2. Google

1. Google Cloud Console → *APIs & Services* → *OAuth consent screen*: app name EVMap, scopes
   `openid` and `email` only (no sensitive scopes, so no verification is needed).
2. *Credentials* → *Create credentials* → *OAuth client ID* → type **Web application**.
   Authorized redirect URI: `https://evmap.joinside.de/auth/callback/google`. The same web client
   serves the iOS app, which receives that URL through its associated domain.
3. `deploy/.env`: `GOOGLE_CLIENT_ID=…`, `GOOGLE_CLIENT_SECRET=…`.

## 3. GitHub

1. GitHub → *Settings* → *Developer settings* → **OAuth Apps** (not a GitHub App) → *New*.
   Homepage `https://evmap.joinside.de`, callback URL
   `https://evmap.joinside.de/auth/callback/github`.
2. Generate a client secret.
3. `deploy/.env`: `GITHUB_CLIENT_ID=…`, `GITHUB_CLIENT_SECRET=…`.

## 4. Apple (web) and the iOS associated domain

Sign in with Apple in the iOS app needs nothing new. For the web, and for the iOS app to receive
Google/GitHub callbacks:

1. Apple Developer → *Identifiers* → App ID `de.joinside.EVMap`: enable **Associated Domains**
   (automatic signing in Xcode usually does this when the entitlement is first built).
2. *Identifiers* → **Services IDs** → new, e.g. `de.joinside.evmap.web`, enable Sign in with Apple,
   primary App ID `de.joinside.EVMap`, domain `evmap.joinside.de`, return URL
   `https://evmap.joinside.de/api/v1/auth/apple/callback`.
3. *Keys* → new key with Sign in with Apple, primary App ID `de.joinside.EVMap`. Download the `.p8`
   (only possible once) and note the key ID.
4. `deploy/.env`:
   - `APPLE_TEAM_ID=56T6W6Z755` — also makes the `web` container serve
     `/.well-known/apple-app-site-association`, which the iOS callback needs
   - `APPLE_SERVICES_ID=de.joinside.evmap.web`
   - `APPLE_KEY_ID=…`
   - `APPLE_PRIVATE_KEY=` the `.p8` content, newlines written as `\n`
5. Check `https://evmap.joinside.de/.well-known/apple-app-site-association` returns
   `{"webcredentials":{"apps":["56T6W6Z755.de.joinside.EVMap"]},"applinks":{"details":[{"appIDs":["56T6W6Z755.de.joinside.EVMap"],"components":[{"/":"/route"}]}]}}`.
   `applinks` is the share link of a planned route (ADR 0017): `https://evmap.joinside.de/route?…`
   opens the app, and a browser without the app gets a plain notice from the web container. Apple's CDN
   caches the file, so a device may need up to a day to see a change, and the app has to be reinstalled
   once after the entitlement `applinks:evmap.joinside.de` was added to it.

## 5. Account deletion, Apple token revocation and the privacy link (phase 2, ADR 0020)

Deleting an account works without any of this. Two settings make it complete:

1. **Apple token revocation.** Apple requires that a deleted account's Sign in with Apple token is
   revoked. The backend keeps Apple's refresh token, encrypted, and needs a key for that:
   `openssl rand -base64 32` → `deploy/.env`: `TOKEN_ENCRYPTION_KEY=…`. Keep it with the other
   secrets; if it is lost, stored tokens cannot be read any more and their accounts are deleted
   without a revocation (a warning names the account). Without the variable no token is stored.
   Revocation and the native app's code exchange also need `APPLE_TEAM_ID`, `APPLE_KEY_ID`,
   `APPLE_PRIVATE_KEY` (section 4) and `APPLE_CLIENT_ID=de.joinside.EVMap`. The one key must belong to
   the primary App ID `de.joinside.EVMap` *and* be enabled for the Services ID (section 4, step 2),
   because it signs for both the bundle id (app) and the Services ID (web).
   Apple users who signed in before this phase have no stored token until their next Apple sign-in.
2. **Privacy policy link.** Publish the policy wherever you like and set
   `PRIVACY_POLICY_URL=https://…` in `deploy/.env`. The iOS settings, the account screen and the web
   footer then link to it; without it they show no link. App Store Connect needs the same URL.

## 6. Making an account admin

There is deliberately no API or UI for this. Sign in once on the web, open `/api/v1/me` in the same
tab's developer tools (or look for the account uuid in the API log line `Issued access token for
account …`), then:

```sh
docker compose -f deploy/docker-compose.yml exec database \
  psql -U evmap -d evmap -c "UPDATE user_data.account SET is_admin = true WHERE id = '<uuid>'"
```

The flag is read on every admin request, so revoking it (`false`) takes effect immediately.

The moderation queue (*Admin* → *Meldungen*) shows comments users reported. An admin can delete the
comment or dismiss the reports; there is no account ban. Blocking is a per-user preference.

## 7. Local development

- API: `WEB_BASE_URL=http://localhost:4200`, and a *separate* test OAuth app per provider whose
  redirect URI is `http://localhost:4200/auth/callback/<provider>` (Google accepts `http://localhost`;
  Apple does not accept non-HTTPS return URLs, so Apple web sign-in cannot be tested locally).
- Web: `cd evmap_web && npm start` — serves the German build at `http://localhost:4200` and proxies
  `/api` to `127.0.0.1:8080`.
- Never put real client secrets into the repository or the example files.
