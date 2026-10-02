# EVMap web client

Angular workspace for the browser client (ADR 0018). The start page is the map (ADR 0023, phase 8a) with
place search, filters and a station panel; next to it the sign-in flow, the account area and the admin area.

```
src/app/
  core/api/       EvmapApi — the only way features reach the backend (REST implementation behind it)
  core/auth/      AuthService (session in an HttpOnly cookie), PKCE, interceptor, admin route guard
  core/map/       MapEngine — the seam to MapKit JS (MapKitEngine), loaded only by the map routes
  features/map    map page, place search, filter panel, /station/:id panel; domain/ holds the iOS logic as pure functions
  features/login  provider buttons and the /auth/callback/:provider route
  features/admin  admin-only area (sync runs, counts)
  src/locale/     XLIFF translations — German is the source language, English the translation
```

## Commands

Needs Node.js 24.15+ (Angular 22).

```sh
npm ci
npm start          # dev server on http://localhost:4200, German only, /api proxied to 127.0.0.1:8080
npm test           # unit tests (Vitest), once
npm run build      # production build, one bundle per locale: dist/evmap_web/browser/{de,en}
npm run extract-i18n   # after changing texts: refresh src/locale/messages.xlf, then update messages.en.xlf
```

For sign-in against a local API, set `WEB_BASE_URL=http://localhost:4200` on the API and register
`http://localhost:4200/auth/callback/<provider>` with the provider's test OAuth app
(see `docs/operations/sign-in-providers.md`). A missing English translation fails the production
build on purpose. The map needs the API's Maps key (`docs/operations/web-map.md`); without it the map page shows
a notice and `/station/:id` still works.

## Container

`Dockerfile` builds the app and serves it with nginx, which also proxies `/api/**` to
`API_UPSTREAM` (default `http://api:8080`) and writes `/.well-known/apple-app-site-association` from
`APPLE_TEAM_ID`. Paths without a locale are redirected by `Accept-Language` to `/de/` or `/en/`.
