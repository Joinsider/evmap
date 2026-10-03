# 8. The release pipeline builds before it pushes anything

- Status: Accepted
- Date: 2026-07-28
- Deciders: Joinsider

## Context

`.github/workflows/release.yml` releases on every push to `master` that touches `evmap_service/**`.
Its original step order was:

1. Read `project.version`, strip `-SNAPSHOT` → `RELEASE_VERSION`.
2. `versions:set` the release version in `pom.xml`.
3. **Commit and push** `chore(release): X.Y.Z`.
4. `./mvnw clean package`.
5. Tag `vX.Y.Z`, push the image, create the GitHub release.
6. Bump to the next `-SNAPSHOT`, commit and push.

Step 3 pushed before step 4 proved anything. A run against a `master` that did not compile therefore
mutated the repository on its way to failing, and left it in a state where **no subsequent run could
succeed either**:

- `pom.xml` on `master` was `0.0.1` — a release version — while step 1 assumes a `-SNAPSHOT` version.
  `RELEASE_VERSION` resolved to `0.0.1` again, `versions:set` became a no-op, and `git commit` exited
  non-zero with "nothing to commit". Under `bash -e` that failed the run at step 3, before the build.
- Had it got past that, `git tag v0.0.1` would have collided with the tag an earlier run created.
- Step 6, which restores the `-SNAPSHOT` invariant, is unreachable once anything before it fails. Every
  failed release is therefore self-perpetuating rather than self-correcting.

The build that exposed this failed for an unrelated reason (source files that committed code imported
had never been committed). That is the point: the pipeline should not turn an ordinary broken build
into a stuck release process.

Two smaller defects surfaced alongside it. The loop guard tested for `[no-CI]` while the workflow's own
commits are marked `[skip CI]`, so the guard never matched — the workflow only avoided re-triggering
itself because GitHub natively honours `[skip ci]`. And a version-state inconsistency reported itself
as "nothing to commit", which says nothing about the actual cause.

## Decision

**Nothing is pushed until the build has passed, and the preconditions a release depends on are
asserted up front with errors that name the problem.**

### The build gates every push

`./mvnw clean package` moves ahead of the version commit. Everything before it — reading the version,
`versions:set` — is local to the runner, so a failing build now leaves the repository exactly as it
found it. The release commit and the tag are pushed together in one step immediately after, since
there is no longer a reason to separate them.

### Preconditions are checked, not assumed

Step 1 now fails loudly if `project.version` is not a `-SNAPSHOT` version, or if `vX.Y.Z` is already
tagged, and says what to do about it. Both conditions mean the version state is inconsistent; failing
at the first step with an explanatory `::error::` beats failing three steps later with a git message
that describes a symptom.

### The loop guard matches the commits it guards against — on the subject line only

`[no-CI]` → `[skip CI]`, matching what the workflow actually writes.

**Amended 2026-07-28, after this ADR's own merge was wrongly skipped.** The corrected guard was
`!contains(github.event.head_commit.message, '[skip CI]')` — a substring test over the *entire*
message. The pull request implementing this ADR was squash-merged, and a squash concatenates every
commit message into the merge commit's body; one of those bodies contained the sentence describing
the `[no-CI]` → `[skip CI]` change. The marker matched its own documentation, and the release was
skipped.

The lesson is that a commit *body* must be free to discuss the marker without disabling the release.
Only the subject line carries the intent. GitHub Actions expressions cannot split a string, so the
check moves into a tiny `guard` job that reads the subject in bash and exposes a boolean output the
`release` job gates on. The head commit message is passed through `env:` rather than interpolated
into the script, since a commit message is untrusted input.

That the run was created at all — rather than suppressed before any run existed — is evidence that
GitHub's native skip handling already has these subject-only semantics. The `guard` job makes the
project's own guard agree with it instead of being stricter in an unpredictable way.

### Releases can be dispatched manually

`workflow_dispatch` is added. Recovering from a wrongly skipped run otherwise requires an artificial
commit touching `evmap_service/**`, because re-running a skipped run re-evaluates the same condition
and skips again. On dispatch there is no `head_commit`, the subject reads empty, and the guard allows
the release.

## Consequences

### Positive

- A broken build costs a red run and nothing else. `master`'s version and tags are untouched, and the
  next push retries cleanly once the build is fixed.
- Failures are no longer self-perpetuating: there is no state for a failed run to leave behind.
- An inconsistent version state — however it arises — reports itself as such.
- The `-SNAPSHOT` assertion also catches the case of someone hand-editing the version on `master`.

### Negative / accepted risks

- **The window is narrowed, not closed.** The Docker build, GHCR push, and GitHub release still run
  after the tag is pushed, so a failure there leaves a tagged commit without a published image.
  Accepted: those steps fail far less often than a compile or a test, and re-running the workflow after
  the tag exists is a manual cleanup rather than a deadlock.
- **Recovering from the state this ADR was written about is still manual.** The workflow refuses to
  guess. The version was set to `0.0.2-SNAPSHOT` by hand and `v0.0.1` left in place — the tag exists, so
  that release is treated as cut.
- **The guard costs an extra job** — a runner spin-up per push to run one `grep`. Cheap, and the
  alternative was a GitHub Actions expression that cannot express the condition correctly.
- `versions:set` runs before the build, so the jar is built at the release version. Intended — it is
  the artifact that gets published — but it means the build is never exercised at the `-SNAPSHOT`
  version on `master`. `pr-snapshot-build.yml` covers that on pull requests.

## Alternatives considered

**Make the version-bump steps idempotent** (skip the commit when `pom.xml` is unchanged, reuse an
existing tag). Would have unstuck this particular repository without reordering anything, but it treats
"the repository is in a state a failed release left behind" as normal and silently releases on top of
it. The ordering fix removes the state instead of tolerating it.

**Tag and release from a separate workflow triggered on tag push.** Cleanly separates "prove it builds"
from "publish it", and is where this should go if releases grow more steps. Rejected for now as more
moving parts than a single-module service needs.

**Drop the automatic version bump; release from manually pushed tags.** Removes the class of problem
entirely by removing the workflow's writes to `master`. Rejected because releasing on every push to
`master` is the property that makes this pipeline useful.

## References

- `.github/workflows/release.yml`
- `.github/workflows/pr-snapshot-build.yml` — the pull-request build this complements
