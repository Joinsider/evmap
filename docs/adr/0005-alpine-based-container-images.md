# 5. Alpine-based, layer-sharing container images

- Status: Accepted
- Date: 2026-07-28
- Deciders: Joinsider

## Context

Both backend deployables ran on the glibc `eclipse-temurin:25-jre` base. Measured locally (podman,
linux/arm64, uncompressed):

| Image | Before | After |
| --- | ---: | ---: |
| `evmap_api` | 453 MB | 298 MB |
| `evmap_sync` | 440 MB | 298 MB |
| both on one host | ~893 MB | ~298 MB (layers shared) |

Two things drove the size. The base image itself is 369 MB, of which the app contributes only ~72 MB;
and the two images had nothing in common below the application layer, so a host or registry holding
both stored two full copies of the same JRE and the same Spring dependencies. The API image
additionally ran `apt-get install curl` purely to give the Compose healthcheck an HTTP client
(ADR 0003).

## Decision

**Both images build on `eclipse-temurin:25-jre-alpine`, with byte-identical runtime stages.**

- **Alpine/musl base.** `25-jre-alpine` is 226 MB versus 369 MB for the glibc variant — the single
  largest available win, and the only Temurin variant that is smaller (`ubi10-minimal` and `noble`
  are both larger). It is published for `linux/amd64` and `linux/arm64`, which covers the
  `ubuntu-latest` release runner and local Apple-silicon development.
- **No HTTP client is installed.** The Compose healthcheck now uses the busybox `wget` that is part
  of the Alpine base: `wget -q -O /dev/null http://localhost:8080/actuator/health/container`. This
  removes the `apt-get` layer and the extra package surface that ADR 0003 accepted as a cost.
- **The sync image uses the same layered extraction as the API** (`-Djarmode=tools … extract
  --layers`) instead of copying the fat jar, and its runtime stage is kept textually identical to the
  API's — same base, same user creation, same `COPY` steps. Only metadata differs (`EXPOSE`,
  `SPRING_PROFILES_ACTIVE`). Both images therefore resolve to the *same* 10 layer digests, so a host
  or registry that holds both stores one copy, not two.
- **The sync profile moved from an entrypoint argument to `ENV SPRING_PROFILES_ACTIVE=sync,docker`**,
  which is what makes the two entrypoints identical. Nothing else sets that variable for the sync
  container, and the startup log line confirms `profiles=sync,docker`.

## Consequences

### Positive

- 34% smaller per image, ~67% less disk when both are present; correspondingly faster pulls.
- Layered extraction in the sync image also means a code-only change re-pushes the small application
  layer rather than a whole fat jar.
- Fewer installed packages in the API image than before (no `curl`, no apt lists).

### Negative / accepted risks

- **musl instead of glibc.** For a pure-JVM workload this is well-trodden ground, but any future
  native dependency (a JNI library, a native-image build, glibc-only tooling in a debug session) has
  to be musl-compatible. Verified working: full Compose cold start, Liquibase migration, healthcheck,
  `GET /api/v1/stations` → 200, sync container boot on the `sync,docker` profile.
- **The two runtime stages must be kept in sync by hand.** They are duplicated on purpose (the two
  Dockerfiles exist to keep the deployables separable per Lastenheft §6), and layer sharing silently
  stops the moment one drifts. It is a lost optimisation, not a failure — but it is invisible.
- **busybox `wget` is less expressive than `curl`** (no `--fail`, terser diagnostics). It returns a
  non-zero exit code on a non-2xx response, which is all the healthcheck needs.

## Alternatives considered

**A `jlink` custom runtime** (`alpine:3.22` + a stripped, app-specific JVM) would have been the
larger win — roughly another 100 MB. **Not possible with Temurin:** the published images contain no
`jmods` directory and are not built for JEP 493 run-time-image linking, so `jlink` fails outright.
Getting there would mean leaving Temurin (Zulu/Corretto) or provisioning a JDK by hand in the build
stage. Revisit only if image size becomes a real constraint.

**`eclipse-temurin:25-jre-ubi10-minimal`** — larger than both the Alpine and the current Ubuntu
variant. No.

**Sharing one Dockerfile with a build argument for the two deployables.** Fewer places to drift, but
it re-couples the API and sync images into one build definition, which is exactly what ADR-adjacent
prep for the §6 service split is trying to avoid.

## References

- `evmap_service/Dockerfile`, `evmap_service/Dockerfile.sync`
- `docker-compose.yml` — the `wget` healthcheck
- [ADR 0003](0003-single-owner-for-schema-migrations.md) — why the healthcheck exists at all, and the
  `curl` install this ADR removes
- [ADR 0004](0004-ingestion-run-history-and-health-reporting.md) — the `container` health group the
  check asks
