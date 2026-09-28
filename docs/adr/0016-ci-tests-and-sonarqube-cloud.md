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
- **One analysis, one job.** A SonarQube Cloud project takes one analysis per commit, so the two test
  jobs cannot each report their own half — the second would replace the first. The `sonar` job
  (Linux) downloads both artifacts, resolves the Maven classpath for `sonar.java.libraries`, and runs
  `sonarqube-scan-action` once. Swift coverage is passed only when the iOS job ran; naming a missing
  report would fail every PR that does not touch the app.
- **Swift coverage** is converted by `evMap_ios/scripts/xccov-to-sonar.py` from the `.xcresult` into
  SonarQube's generic coverage XML, with paths relative to the repository root (the analysis runs on a
  different machine than the tests). SonarSource documents a shell script from its examples repository
  for this; fetching it at CI time would execute unreviewed code, so the ~60-line equivalent is kept
  here. Its arguments are allowlisted before they reach `xccov` (pythonsecurity:S8705).
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
initializer argument, and the converter's arguments validated. The two base URLs in `APIEnvironment`
(swift:S1075) were **accepted** in SonarQube Cloud: that type is the configuration point, and both are
overridden at runtime by `API_BASE_URL`.

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

1. **Quality gate strictness.** SonarQube Cloud's default gate requires 80 % coverage on new code;
   both sides are below that overall. Options: (a) keep the default and let it push coverage up on
   new code only, (b) lower the new-code coverage condition to e.g. 60 % in a custom gate, (c) make
   the gate informational (not a required check) until coverage has caught up.
2. **Snapshot build duplication** — whether `pr-snapshot-build.yml` should skip tests (`-DskipTests`)
   now that CI runs them, trading a faster snapshot for one that can exist for a red PR.
