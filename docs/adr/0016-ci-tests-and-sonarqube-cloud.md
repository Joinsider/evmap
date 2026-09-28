# 16. CI runs both test suites; SonarQube Cloud analyses both deployables

- Status: Accepted — CI active; SonarQube Cloud prepared, waiting for the organisation and `SONAR_TOKEN`
- Date: 2026-09-28
- Deciders: Johannes Popp

## Context

Until now the only thing that ran tests on a PR was `pr-snapshot-build.yml`, as a side effect of
building the snapshot jar (`./mvnw package`). Nothing ran the iOS tests at all. That was how a test
target that no longer compiled — a stub declared as an `actor` against a protocol the app's
`MainActor` default isolation had made main-actor-bound — reached a branch unnoticed. There was also no
static analysis of either side.

Two constraints shape the answer. The repository is **private**, so GitHub bills macOS runner minutes
at 10x Linux. And the repository holds two deployables in two languages, which SonarQube Cloud models
as two projects (a monorepo), not one.

## Decision

**`.github/workflows/ci.yml` runs the backend and the iOS unit tests on every PR and on master, each
only when its own code changed, and hands both results to SonarQube Cloud when a token is present.**

- **Backend** (`ubuntu-latest`, JDK 25): `./mvnw verify`. JaCoCo (added to `pom.xml`) attaches at
  `initialize` and writes `target/site/jacoco/jacoco.xml` at `verify`, which the SonarScanner for
  Maven reads by default. The analysis is a second invocation of `sonar-maven-plugin`, pinned by
  version rather than resolved from the plugin prefix.
- **iOS** (`macos-26`, Xcode 26.6 pinned, iPhone 17 / iOS 26.5): `xcodebuild test -only-testing:EVMapTests`
  with coverage. UI tests are excluded — they drive the real app against a backend CI does not have.
  The `EVMap` scheme is not committed; `xcodebuild` autocreates it, which was checked on a clean clone.
- **Path filtering** (`dorny/paths-filter`) runs each job only for its own directory or a change to the
  workflow itself. A skipped job reports as skipped, which branch protection accepts, so both can be
  required checks without a backend-only PR waiting forever for an iOS run.
- **SonarQube Cloud, Free plan.** Private projects are free up to 50k LOC per organisation; the two
  projects together are ~6k. Project keys are `Joinsider_evmap-service` (in `pom.xml`) and
  `Joinsider_evmap-ios` (in `evMap_ios/EVMap/sonar-project.properties`), organisation `joinsider`.
  Every Sonar step is guarded by `SONAR_TOKEN` being set, so CI works before the account exists.
- **Swift coverage** is converted by `evMap_ios/scripts/xccov-to-sonar.py` from the `.xcresult` into
  SonarQube's generic coverage XML. SonarSource documents a shell script from its examples repository
  for this; fetching it at CI time would execute unreviewed code in a job holding `SONAR_TOKEN`, so the
  ~40-line equivalent is kept in the repository. Only files under the app's source root are reported.
- **master is analysed as well**, because SonarQube Cloud computes a PR's "new code" against the main
  branch's last analysis; without it the quality gate has no baseline.

`pr-snapshot-build.yml` still runs the backend tests too. That duplication is left in place for now:
the snapshot jar should not be published from a commit whose tests fail, and making it depend on the CI
workflow would couple two workflows that are otherwise independent.

Measured when this was set up: backend 67,9 % line coverage, iOS app ~45 % (872 of 1.931 executable
lines).

## Consequences

- Both suites gate a PR; an iOS test target that stops compiling now fails a check instead of a
  developer's next local run.
- An iOS run costs roughly 5–10 macOS minutes, i.e. 50–100 billed minutes on a private repository. The
  path filter keeps that to PRs that touch the app.
- The Xcode pin has to be moved by hand when the project is upgraded (`LastUpgradeCheck`) or the
  deployment target is raised past what the runner's Xcode ships. A too-new project fails loudly in
  the "Select Xcode" or build step, not silently.
- SonarQube Cloud's Automatic Analysis must stay **off**: it and CI-based analysis cannot both run for
  a project, and Automatic Analysis would not import coverage.

## Setup still to be done (outside the repository)

1. Sign in to [SonarQube Cloud](https://sonarcloud.io) with GitHub and create the organisation
   `joinsider` bound to the GitHub account; choose the Free plan.
2. Import `Joinsider/evmap` as a **monorepo** and create two projects with exactly the keys above
   (or change the keys in `pom.xml` / `sonar-project.properties` to match).
3. In both projects: *Administration → Analysis Method* → turn **Automatic Analysis off**.
4. Create an analysis token and store it as the repository secret `SONAR_TOKEN`.
5. Optionally make *Backend tests*, *iOS tests* and the SonarQube quality gates required checks on
   `master`.

## Open points

1. **Quality gate strictness.** SonarQube Cloud's default gate requires 80 % coverage on new code;
   both sides are below that overall. Options: (a) keep the default and let it push coverage up on
   new code only, (b) lower the new-code coverage condition to e.g. 60 % in a custom gate, (c) make
   the gate informational (not a required check) until coverage has caught up.
2. **Snapshot build duplication** — whether `pr-snapshot-build.yml` should skip tests (`-DskipTests`)
   now that CI runs them, trading a slower snapshot for a snapshot that can exist for a red PR.
