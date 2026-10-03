# 2. Central iOS logging via `os.Logger`

- Status: Accepted
- Date: 2026-07-28
- Deciders: Joinsider

## Context

The iOS client had no logging at all — not a single `print`, `NSLog`, or `os_log`
call in the 26 Swift files. Every failure path funnelled into the same shape:

```swift
} catch { errorMessage = error.localizedDescription }
```

That string is what the user sees, and it was also the *only* record that anything
went wrong. For the errors that actually occur during development this is close to
worthless:

- `DecodingError.localizedDescription` is "The data couldn't be read because it
  isn't in the correct format" — it names neither the field, the type, nor the
  coding path, which are the only three things needed to fix a client/server
  contract mismatch.
- A non-2xx response and a transport failure (offline, DNS, TLS) collapse into
  indistinguishable user-facing strings, even though they have completely
  different causes.
- Nothing recorded *which backend* a build was talking to, despite
  `APIEnvironment` resolving that at runtime from three different sources
  (ADR 0001). A console full of failed requests gave no way to tell a broken
  server from a build pointed at an unreachable loopback address.

Debugging therefore meant adding temporary `print` statements and deleting them
before committing.

## Decision

### 1. One `AppLogger` type over `os.Logger`, not `print`

`Core/Logging/AppLogger.swift` introduces a `Sendable` struct wrapping `os.Logger`,
exposed as seven category statics: `app`, `network`, `map`, `location`, `stations`,
`auth`, `comments`. Call sites use `AppLogger.network.info(…)` and never construct
a logger or touch `os` directly.

`os.Logger` over `print` because it gives, for free, what a print-based logger
would have to reimplement: severity levels, per-subsystem/category filtering in
both the Xcode console and Console.app, log persistence that survives a detached
run, and no I/O cost when a level is disabled. The subsystem is the bundle
identifier, so `subsystem:de.joinside.EVMap category:network` is a working filter.

Levels map to `os` levels with one deliberate deviation: `warning` emits as
`OSLogType.default` because `os` has no distinct warning level, while keeping a
visually distinct `⚠️` in the composed message.

### 2. Every line carries severity symbol, category, and call site

The message is composed in `AppLogger`, not at the call site, so all output has one
shape:

```
❌ [net] APIClient.send:48 › ← GET /api/v1/stations?latitude=…&longitude=…&radiusKm=20 500 in 812 ms, 231 B — …
✅ [loc] MapViewModel.locationManager:53 › Fix #1 received (accuracy 12 m)
ℹ️ [stations] MapViewModel.loadStations:40 › Nearby stations (connectors=CCS|Type 2, minPower=50kW) finished in 143 ms
```

Call site comes from `#fileID`/`#function`/`#line` defaults, trimmed to
`File.function:line` — the argument list is stripped from `#function` and the
module/path prefix from `#fileID`, because the full forms are long enough to push
the actual message off-screen.

Messages take `@autoclosure () -> String` so interpolation is not evaluated for
disabled levels.

### 3. Log level doubles as the data-retention control

The unified log persists levels differently — `.debug` is not written at all
unless something is streaming, `.info` lives in a memory buffer, and `.notice`
and above go to disk for days and are always included in a `sysdiagnose`. Since a
`sysdiagnose` is something a user hands to Apple or to support, that table decides
what may be logged where:

- **Anything personal** — coordinates, token fingerprints, request/response
  bodies — goes on `.debug` and nowhere else.
- **Operational facts** — endpoint, status, duration, size, error class, counts —
  may use `.info` and above.

Concretely: `MapViewModel` persists that a location fix arrived and its accuracy,
never the position; `APIClient` strips `latitude`/`longitude` values from the
request label before it reaches a persisted level; `AuthSession` persists that a
session exists, never the token fingerprint.

Response body previews get one extra step — `#if DEBUG`, so they are absent from
the release binary rather than merely lowered. A response body can contain *other*
users' comment text, and third-party data does not belong on this user's device
regardless of persistence.

Full inventory and rationale: `docs/privacy/data-processing.md`.

### 4. Logged values are redacted at the call site; the composed line is `.public`

`os.Logger` defaults dynamic strings to `<private>`, which would make the console
useless. Rather than fight that per interpolation, `AppLogger` composes the whole
message itself and logs it as `.public`, and pushes the redaction obligation to
the call site via three helpers:

- `AppLogger.redact(token:)` → `eyJh…f9Qw (412 chars)`. Access tokens and Apple
  identity tokens are **never** logged in full — enough survives to answer "is
  this the same token as before?" and nothing that can be replayed.
- `AppLogger.coordinate(latitude:longitude:)` rounds to two decimals (~1 km).
  Rounding alone does not anonymize — a sequence of rounded fixes still yields a
  movement profile — so it only ever appears on `.debug` per decision 3.
- `StationFilter.logDescription` renders only the active criteria.

### 5. `APIClient` is the primary instrumentation point

Every request logs a `→` line before dispatch and a `←` line after, with method,
path, query, status, duration, and response size. The three failure modes are
separated:

- **transport failure** (`session.data` threw) — logged as an error with the
  `URLError` code, distinct from any HTTP status;
- **non-2xx** — logged with the server's error message;
- **decode failure** — logged with the unpacked `DecodingError`.

Both failure paths additionally emit a body preview on the debug channel, capped
at 512 bytes and compiled out of release builds (decision 3). Successful responses
log their size, never their content — a success log has no diagnostic need for it.

`AppLogger.describe(_:)` unpacks `DecodingError` into
`decoding: missing key 'powerKw' at station.connectors.0`, and `URLError` into
description plus numeric code. This is the single highest-value part of the change.

### 6. `measure(_:_:)` wraps repository calls

`AppLogger.measure` runs an async block, logging start, duration on success, and
duration plus unpacked error on failure. It is applied at the view-model boundary
(`MapViewModel.loadStations`, `StationDetailViewModel.load`,
`AuthSession.completeAppleSignIn`) so each user-visible operation has a timed
start/finish pair without repeating do/catch bookkeeping.

### 7. A launch banner

`EVMapApp.init` logs version, build, and resolved `APIEnvironment.baseURL`. Given
that the base URL is resolved at runtime from a `UserDefaults` override, the
simulator check, or the production default, this line is what makes every
subsequent network log interpretable.

## Consequences

### Positive

- A decoding mismatch now names the field and path instead of "couldn't be read".
- Transport failures, HTTP errors, and decode errors are three distinguishable
  events in the console.
- Every run states which backend it is talking to.
- Categories make `category:network` or `category:auth` a one-click filter, so
  adding logging in one area does not degrade readability in another.
- No temporary `print` statements to add and remember to remove.

### Negative / accepted risks

- **`.public` on the composed message is a standing obligation.** Nothing in the
  type system enforces that a call site redacted what it passed in. Any new
  `AppLogger` call that interpolates a token, an email, or a precise coordinate
  leaks it into the device log. The redaction helpers and the level policy exist
  to make the correct thing convenient, but they are a convention, not a
  guarantee — every new `AppLogger` call needs review for personal data.
- **Release and TestFlight builds are less diagnosable.** No coordinates, no
  bodies, no token fingerprints. A tester reporting "it didn't load" yields
  endpoint, status, duration, and error class, but not the position or payload
  that produced it. This is the accepted cost of decision 3.
- **`.debug` is not "off".** With a debugger attached or `log stream` running,
  the personal values are visible. Acceptable because it requires a human at the
  device.
- **Call-site metadata costs three implicit arguments per call.** Negligible at
  this scale, but it does mean `AppLogger` methods cannot be trivially wrapped
  without threading `file`/`function`/`line` through the wrapper.
- **Category is a closed enum.** New feature modules need a case added.
- **`os` has no warning level.** Filtering by `OSLogType` in Console.app cannot
  separate warnings from notices; the `⚠️` prefix is the only discriminator, and
  it is only greppable, not filterable.

### Neutral

- No log ever leaves the device — there is no remote sink, no Crashlytics, no
  Sentry. That is what keeps client logging largely outside our processing as a
  controller, and it is a property to preserve deliberately rather than lose to a
  future SDK integration.
- `Bundle.appVersion` is added as a small extension in the logging file; it is a
  logging concern today and should move if another caller appears.
- The privacy inventory in `docs/privacy/data-processing.md` surfaced two issues
  outside this change's scope: reverse-proxy access logs would record coordinates
  from the query string, and there is no account-deletion endpoint.

## Alternatives considered

**A `print`-based logger behind `#if DEBUG`.** Simplest, and gives colored
line-oriented output with no `os` ceremony. Rejected: it produces nothing on
TestFlight or device builds run detached from Xcode, which is precisely when a log
would be most valuable, and it would require reimplementing level filtering.

**SwiftLog (`swift-log`) or CocoaLumberjack.** Both give backend pluggability and
would allow shipping logs to a remote collector later. Rejected for now: a package
dependency for an app that currently has zero, to solve a problem (remote log
aggregation) that does not exist yet. `AppLogger` is a thin enough facade that
swapping its implementation for a SwiftLog backend touches one file.

**Logging inside `RESTChargingStationRepository` instead of `APIClient`.**
Would keep `APIClient` a pure transport type. Rejected: the repository does not
see status codes, durations, or raw bodies, so the most valuable information is
only available at the `APIClient` level.

**Interpolating errors directly (`\(error)`) rather than `describe(_:)`.** Ships
less code, but `DecodingError`'s default rendering is a multi-line dump of an
internal `Context` struct — technically complete and practically unreadable in a
console.

**Logging inside the generic `catch` blocks in view models only.** Rejected as
insufficient: by the time an error reaches the view model it has already lost the
response body and status code that explain it.

## References

- `evMap_ios/EVMap/EVMap/Core/Logging/AppLogger.swift`
- `evMap_ios/EVMap/EVMap/Core/Networking/APIClient.swift`
- `evMap_ios/EVMap/EVMap/Features/Stations/Domain/StationFilter.swift` — `logDescription`
- ADR 0001 — runtime base-URL resolution, which the launch banner exists to disambiguate
