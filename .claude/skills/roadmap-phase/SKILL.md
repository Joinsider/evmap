---
name: roadmap-phase
description: Start, continue or finish a phase of the EVMap v2 roadmap (docs/roadmap.md) — picks the next phase from the status table, clarifies open points with the product owner one question at a time, writes the ADR first, implements on its own branch, and closes with the definition of done. Use when the user says "next phase", "continue the roadmap", "start phase N", "Lückenfüller", or asks what to build next.
---

# Roadmap phase

The procedure for every piece of roadmap work in this repository. `docs/roadmap.md` says *what* and
*in which order*; the ADRs say *why* and *how*; `CLAUDE.md` holds the architecture rules that apply
throughout. Talk to the product owner in German (the language of the roadmap and the Lastenheft);
write ADRs and code comments in English like the existing ones.

## 1. Find out where the project stands

1. Read `docs/roadmap.md` completely — the status table, the phase section, "Entschieden",
   the open points — and `git log --oneline -20` plus open branches, to see whether work is already
   underway (`in Arbeit` in the table, or a `feature/phase-*` branch).
2. Pick the phase:
   - a phase that is `in Arbeit` is continued before anything else;
   - otherwise the **lowest-numbered `offen` phase**;
   - a gap filler (`L1`…`L4`, one country adapter) is due *between* two phases: offer it right after
     a phase was finished, before the next one starts, in order Österreich → Schweiz → Italien →
     Spanien;
   - "Skalierung" has no slot: suggest it only if monitoring data shows it is needed, and always
     reassess it before phase 8.
3. Read every ADR the phase references, the Lastenheft §11, and the `CLAUDE.md` sections for the
   packages it touches. For a sync adapter, read `sync/package-info.java` first.

## 2. Agree the phase before writing code

1. Summarize the phase for the product owner in a few lines: scope, rough effort, which 👤 steps it
   needs from them (developer portals, OAuth apps, domains, secrets, device tests) — and ask them to
   confirm the phase before starting.
2. Collect every open point for this phase (the ADR's "Open points", the roadmap's open points, and
   anything discovered while reading the code). Ask them **one at a time with AskUserQuestion**, 2–4
   options each, the recommended one first and marked "(Recommended)". Honour free-text answers
   literally; if an answer contradicts an earlier decision, say so and ask again instead of guessing.
   If the owner says they answered wrong, repeat the question.
3. Record each answer immediately: in the phase's ADR, and in the roadmap if it changes order or
   scope ("Entschieden am …").

## 3. ADR first

- If the phase has no ADR yet (`neu` in the table), create `docs/adr/NNNN-<slug>.md` with the next
  free number, in the format of the existing ADRs: Status, Date, Deciders; Context; Decision;
  Consequences; Open points (with options); References. Short, but it must tell a later session
  *why* and *how*.
- If the phase extends an existing ADR, add a dated section there instead of a new file.

## 4. Implement

1. Branch from an up-to-date `master`: `feature/phase-<N>-<slug>` (gap fillers:
   `feature/sync-<country>`). Never commit to `master`. In an app-made worktree, update the base with
   the `sync_with_base_branch` tool instead of merging yourself.
2. Mark the phase `in Arbeit` in the status table with the branch name — first commit on the branch.
3. Build in vertical slices that each leave the build green. The rules in `CLAUDE.md` are not
   optional here, in particular:
   - `api` ↔ `sync` boundary, `StationIngestionPort` as the only write path into master data,
     master vs. user data separation in every migration, only the API deployable migrates;
   - iOS: UI talks to the backend only through `ChargingStationRepository` (or a dedicated seam
     like `AddressSearchProviding`); new modules follow `Features/<Name>/{Domain,Data,Presentation}`;
   - every user-facing string through i18n (German base, English) — iOS *and* the Angular client;
   - logging per ADR 0002: never tokens, secrets, e-mail addresses, comment bodies, provider
     subjects, addresses or unrounded coordinates; route polylines count as coordinates;
   - new personal data → a row in `docs/privacy/data-processing.md`, and inclusion in data export
     and account deletion once those exist (phase 2).
4. Tests with the code, not after it: backend `cd evmap_service && ./mvnw verify`; iOS
   `xcodebuild test -scheme EVMap -only-testing:EVMapTests` (see `CLAUDE.md` for the simulator).
   New sync adapters extend `SourceAdapterRegistrationTests`; new availability providers extend
   `AvailabilityProviderRegistrationTests`.
5. 👤 steps that block: prepare everything behind a seam or with test values, then name exactly what
   the owner has to do and where the value goes (`.env`, launch argument, Keychain …). Never ask for
   secrets in chat and never put real ones in the repository.

## 5. Definition of done — then stop

A phase is finished when all of this is true, in the phase's PR:

- [ ] all tests green locally (`./mvnw verify`, iOS unit tests); CI green on the PR
- [ ] ADR updated: status, what was actually built, deviations from the plan, remaining open points
- [ ] `docs/roadmap.md`: phase set to `fertig` with the PR link; decisions made during the phase
      recorded
- [ ] `CLAUDE.md` updated if the phase changed architecture, commands, packages or constraints
- [ ] `docs/privacy/data-processing.md` updated if personal data changed
- [ ] Lastenheft §11 adjusted if the scope agreed there changed
- [ ] PR opened (commit and PR attribution lines as the session's system instructions require),
      bound with the ccd_pr tools; CI status read, Auto-fix offered — no polling

Then **stop**. Report to the owner in German: what was built, what they still have to do (👤),
what the next phase or gap filler would be — and wait for them to start it.
