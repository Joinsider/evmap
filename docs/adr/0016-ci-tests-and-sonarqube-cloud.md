# 16. CI runs both test suites; SonarQube Cloud analyses the repository as one project

- Status: Accepted — CI and CI-based SonarQube Cloud analysis active
- Date: 2026-09-28
- Deciders: Johannes Popp

## Context

Until now the only thing that ran tests on a PR was `pr-snapshot-build.yml`, as a side effect of
building the snapshot jar (`./mvnw package`). Nothing ran the iOS tests at all. That was how a test
target that no longer compiled — a stub declared as an `actor` against a protocol the app's
`MainActor` default isolation had made main-actor-bound — reached a branch unnoticed. There was also no
static analysis of either side.

Two constraints shape the answer. The repository is **private**, so GitHub bills macOS runner minutes
at 10x Linux. And SonarQube Cloud already had the repository as **one** project, `Joinsider_evmap`,
first under Automatic Analysis — which cannot import coverage.

## Decision

**`.github/workflows/ci.yml` runs the backend and the iOS unit tests, and a final `sonar` job analyses
Java and Swift together into `Joinsider_evmap`, configured by the root `sonar-project.properties`.**

- **Backend** (`ubuntu-latest`, JDK 25): `./mvnw verify`, on every PR and push. JaCoCo (added to
  `pom.xml`) writes `target/site/jacoco/jacoco.xml` at `verify`. The compiled classes and the report
  are uploaded as an artifact, because the Java analyzer needs bytecode.
- **iOS** (`macos-26`, Xcode 26.6 pinned, iPhone 17 / iOS 26.5): `xcodebuild test -only-testing:EVMapTests`
  with coverage. On a PR it runs only when `evMap_ios/**` or the workflow changed (`dorny/paths-filter`);
  a skipped job reports as skipped, which branch protection accepts. On master it always runs, so the
  main-branch analysis never loses Swift coverage because the last push only touched the backend. UI
  tests are excluded — they drive the real app against a backend CI does not have. The `EVMap` scheme
  is not committed; `xcodebuild` autocreates it, which was checked on a clean clone.
- **The iOS job is the critical path** (~6 min against the backend's ~1 min; both already start in
  parallel). It boots its simulator before compiling so the boot overlaps the build, runs the tests on
  that one simulator (`-parallel-testing-enabled NO` — parallel testing cloned and booted extra
  simulators after the build, which cost more than 84 unit tests running in ~5 s could save), and
  skips the index store only the Xcode editor reads. The `changes` job runs only for PRs and needs no
  checkout, so the iOS job waits seconds for it, not a full job.
- **One analysis, one job.** A SonarQube Cloud project takes one analysis per commit, so the two test
  jobs cannot each report their own half — the second would replace the first. The `sonar` job
  (Linux) downloads both artifacts, resolves the Maven classpath for `sonar.java.libraries`, and runs
  `sonarqube-scan-action` once. Swift coverage is passed only when the iOS job ran; naming a missing
  report would fail every PR that does not touch the app.
- **Swift coverage** is converted by `evMap_ios/scripts/xccov-to-sonar.py` from the `.xcresult` into
  SonarQube's generic coverage XML, with paths relative to the repository root (the analysis runs on a
  different machine than the tests). SonarSource documents a shell script from its examples repository
  for this; fetching it at CI time would execute unreviewed code, so the ~60-line equivalent is kept
  here. It takes **no arguments**: its paths are fixed and derived from its own location, so nothing
  from a caller reaches the `xccov` command line (pythonsecurity:S8705 — an allowlist check was not
  recognised as sanitisation, and an argument-free script has nothing to sanitise).
- **Liquibase changelogs are excluded.** They are PostgreSQL; SonarQube Cloud has no PostgreSQL
  analyzer and applied Oracle PL/SQL rules (`VARCHAR2`) to them.
- **master is analysed as well**, because SonarQube Cloud computes a PR's "new code" against the main
  branch's last analysis; without it the quality gate has no baseline.
- **Free plan.** Private projects are free up to 50k LOC per organisation; the repository is ~6k.

`pr-snapshot-build.yml` still runs the backend tests too. That duplication is left in place for now:
the snapshot jar should not be published from a commit whose tests fail, and making it depend on the CI
workflow would couple two workflows that are otherwise independent.

Measured when this was set up: backend 67,9 % line coverage, iOS app ~45 % (872 of 1.931 executable
lines).

## First findings

Automatic Analysis reported 28 issues on master and 41 on PR #6 before the switch. They were fixed
rather than suppressed: third-party actions pinned to commit SHAs and permissions granted per job, API
URLs built from path segments instead of `/api/v1/…` literals, unused protocol parameters named `_`,
intentionally empty closures commented, backticked `default` statics renamed
(`AppSettings.factoryDefaults`, `ProviderPreference.fallback`), live counts grouped into one
initializer argument, and the converter made argument-free. The two base URLs in `APIEnvironment`
(swift:S1075) were **accepted** in SonarQube Cloud: that type is the configuration point, and both are
overridden at runtime by `API_BASE_URL`.

## Meeting the coverage gate

The first CI analysis put PR #6 at 57,2 % on new code. It was raised with tests, not exclusions:

- **Backend SQL against a real PostGIS.** `support.PostgisDatabase` starts `kartoza/postgis:17-3.5`
  — the image Compose deploys; `postgis/postgis` has no arm64 build — once per test JVM via
  Testcontainers and migrates it with the real Liquibase changelog. The ingestion repository, the
  spatial and operator queries and `ChargePointDirectory` are tested through it, because H2 has no
  `geography` and a mocked `JdbcClient` would test the mock. Locally the tests skip without a
  container runtime; on CI (`CI` set) a missing runtime fails them instead, so the coverage cannot
  silently disappear. Writing them surfaced one latent edge: an IRVE station whose `date_maj` and
  `last_modified` are both unreadable reaches ingestion with a null `last_updated_at`, which the
  `NOT NULL` column rejects — it costs that one station, and is left as is.
- **Backend orchestration as unit tests:** `SyncJob` run status, containment and watermark
  decisions against a recording port; `StationService` validation and aggregation.
- **iOS logic:** the REST repository and `APIClient` through a `URLProtocol` stub (URLs, headers,
  bodies, 401/4xx/5xx/decoding failures), `StationDetailViewModel`, and the domain vocabularies.
- **iOS views are rendered, not excluded.** `ViewRenderingTests` lays every screen out in a window in
  the states that change it. SwiftUI only evaluates `body` during layout, so a trap in a rarely-seen
  state otherwise fails on a user's device, not in CI. Appearance is not asserted. A map needs a
  phone-sized window: MapKit renders into a Metal texture of the view's size, and a 3 000 pt one
  exceeds the simulator's limit and aborts the test host.
- **One exclusion:** `evMap_ios/scripts/**` is excluded from the coverage *measure* (still analysed).
  It shells out to `xcrun` on the macOS runner, and its test is the coverage report existing.

Result locally: backend 88,6 % lines (was 67,9 %), iOS app 79,5 % (was ~45 %).

## Consequences

- Both suites gate a PR; an iOS test target that stops compiling now fails a check.
- An iOS run costs roughly 5–10 macOS minutes, i.e. 50–100 billed minutes on a private repository; every
  push to master pays that too, in exchange for a complete baseline.
- The Xcode pin has to be moved by hand when the project is upgraded (`LastUpgradeCheck`) or the
  deployment target is raised past what the runner's Xcode ships. It fails loudly when it is stale.
- Automatic Analysis must stay **off** for `Joinsider_evmap`: it and CI-based analysis cannot both run.
- The analysis depends on artifacts from two jobs; changing an output path in one job without the
  `sonar` job and `sonar-project.properties` silently drops that half's bytecode or coverage.

## Open points

1. ~~Quality gate strictness~~ — decided 2026-09-28: **(a), keep SonarQube Cloud's default gate of
   80 % coverage on new code** and meet it with tests rather than a lower threshold. See
   [Meeting the coverage gate](#meeting-the-coverage-gate).

2. **Snapshot build duplication** — whether `pr-snapshot-build.yml` should skip tests (`-DskipTests`)
   now that CI runs them, trading a faster snapshot for one that can exist for a red PR.
