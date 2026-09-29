# 20. Account area and App Store obligations

- Status: In progress — roadmap phase 2 (`feature/phase-2-account-area`)
- Date: 2026-09-29
- Deciders: Johannes Popp

## Context

Apple's review guidelines require an app that lets people create an account to let them delete it
in the app (5.1.1(v)), to revoke the Sign in with Apple token when they do, and — for apps with
user-generated content — to offer reporting, blocking and a moderation process (1.2). The GDPR adds
access and portability (Art. 15/20) and erasure (Art. 17). Phase 1 (ADR 0018) already prepared the
ground: comments and provider identities reference `user_data.account` with `ON DELETE CASCADE`, and
the Angular admin area exists. Phase 2 builds what sits on top of it.

## Decisions

Agreed with the product owner when the phase started (2026-09-29). Points are added as they are
decided.

### Apple token revocation: keep the refresh token, encrypted

Apple only revokes a token it issued, and only the refresh token outlives the sign-in. Both Apple
flows (native `POST /api/v1/auth/apple` and web `AppleWebSignIn`) therefore exchange the
authorization code for a refresh token at sign-in and store it **AES-GCM encrypted** on the Apple
provider identity; the key comes from the environment (new variable). Account deletion decrypts it
and calls Apple's revoke endpoint. Accounts whose Apple identity has no stored token yet (everyone who
signed in before this phase) are deleted without a revocation, and that is logged as a warning by
account id — the token appears at their next Apple sign-in. Rejected: plaintext storage (a long-lived
credential in the clear) and re-authenticating with Apple at deletion time (couples erasure to an
Apple UI step, which the web cannot do gracefully and which fails if the Apple access is gone).

### Deletion is immediate and complete

After a confirmation the account is deleted at once: identities (with e-mail and the encrypted Apple
token), comments, reports and blocks go with it through the existing `ON DELETE CASCADE`, after the
Apple token has been revoked. No grace period and no anonymized leftovers — comment text can identify
its author, and a deletion nobody has to wait for is the simplest thing to explain in the privacy
notice. Other users lose the deleted person's comments too.

### Deleted accounts in backups: accept the three-month bound

Resolves ADR 0019's open point (a). A deleted account leaves the backups after at most ~3 months
(the monthly snapshot rotation). The privacy notice and `docs/privacy/data-processing.md` say so;
no deletion log is kept, so a restore can bring a deleted account back until it is deleted again.
Revisit if a restore ever happens in production.

### Moderation: reports stay visible until an admin decides

A reported comment stays visible to everyone else; for the reporter it is hidden at once. An admin can
**delete the comment** (report resolved) or **dismiss the report**. There is no account ban in this
phase: the public admin area keeps read-and-moderate powers only (ADR 0018). Blocking a user is a
per-account preference, not a moderation action.

### Blocking is stored server-side on the account

Comments show no author, so a user blocks *through a comment*: the request carries only the comment
id, the backend resolves the author and never returns an account id. The blocker's comment lists are
filtered in the query, for every client. A block belongs to the blocker's data (export, deletion) and
can be lifted in the account area, where blocks appear as anonymous entries.

### Data export is a direct JSON download

`GET /api/v1/me/export` answers with one JSON document (account, linked identities with e-mail,
comments, reports, blocks). A single account's data is small, so there is no job and no e-mail
delivery. The encrypted Apple refresh token is a credential, not the user's data, and is never part
of it. Every later phase that adds user data (favorites, vehicles) extends the export and the deletion.

### Privacy policy link comes from backend configuration

The policy is published wherever the product owner chooses. Its URL is the environment variable
`PRIVACY_POLICY_URL`, served by a small public endpoint (`GET /api/v1/legal`) to iOS and web. Without
a value the clients show no link, so the build never depends on the 👤 step, and the URL can change
without an app release. App Store Connect needs the same URL separately.

### Defaults chosen without a question

- Report reasons are a closed set: `spam`, `offensive`, `wrong`, `other`; one report per reporter and
  comment (a repeat is idempotent).
- "My contributions" lists the caller's own comments and reports; vehicle submissions join it in
  phase 6.

## Open points

None at the start of implementation.

## References

- ADR 0018 (accounts, providers, web session), ADR 0019 (backups)
- Lastenheft §8, §9, §11; `docs/privacy/data-processing.md`; `docs/roadmap.md`
