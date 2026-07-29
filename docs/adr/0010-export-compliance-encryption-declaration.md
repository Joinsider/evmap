# 10. Declaring export-compliance encryption status in the build

- Status: Accepted
- Date: 2026-07-29
- Deciders: Johannes Popp

## Context

Uploading to TestFlight or the App Store is an export from the United States, so every
submission is subject to U.S. export law regardless of where the legal entity sits.
App Store Connect asks the encryption-compliance questions interactively on **each new
version** unless the answer is already declared in the app's Info.plist. Answering by
hand every release is both friction and a place to give an inconsistent answer.

The declaration is a legal statement, not a build flag, so the value has to follow from
what the app actually does.

## Decision

Declare `ITSAppUsesNonExemptEncryption = NO` in the app target's generated Info.plist,
via `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption` in both the Debug and Release
configurations of the `EVMap` target. The project uses `GENERATE_INFOPLIST_FILE = YES`
with no checked-in plist (see ADR 0001), so a build setting is the only place this can
live without introducing a plist file.

Xcode writes the value as a real plist boolean (`<false/>`), not the string `"NO"`;
this was verified against the generated `EVMap.app/Info.plist` of a Release build.

### Why `NO` is the accurate answer today

At the time of writing the client contains no cryptography of its own. An audit of the
sources found:

- **No cryptographic APIs.** No `CryptoKit`, no `CommonCrypto`, no direct `Security`
  framework or Keychain use.
- **No third-party code.** The project has no Swift Package or other external
  dependencies, so nothing is linked that could carry its own implementation.
- **Transport encryption is Apple's.** `APIClient` uses `URLSession` against an HTTPS
  endpoint; TLS is provided by the operating system.
- **Authentication is Apple's.** Sign in with Apple goes through
  `AuthenticationServices`; the app receives an identity token and forwards it. It
  neither signs nor verifies anything itself — JWT verification happens server-side in
  `AppleIdentityTokenVerifier`.
- **The access token is stored in plain `UserDefaults`**, so not even storage
  encryption is invoked by app code.

All of that falls under the exemptions for encryption provided by the operating system
and for authentication, which is what `NO` asserts.

## Consequences

### Positive

- Submissions skip the compliance questionnaire, and every version answers identically.
- The reasoning is recorded, so the answer does not have to be re-derived — or guessed —
  at the next release.

### Negative / accepted risks

- **The declaration can silently go stale.** It is a build setting that no test asserts
  and no reviewer is prompted by. Anything that introduces non-exempt cryptography makes
  a shipped legal statement false. Concretely, revisit this record when:
  - a Swift Package is added (its transitive dependencies count as "third-party
    libraries it links against");
  - the app encrypts, signs, or hashes anything itself rather than handing it to the
    system — proprietary or non-standard algorithms in particular;
  - end-to-end encryption of user content is introduced.

  Moving the access token to the Keychain would *not* change the answer; Keychain and
  HTTPS are both exempt.
- **`NO` is only a self-assessment.** It does not exempt the app from the import rules
  of the countries it is distributed into, which this record does not address.

### Neutral

- The setting is duplicated across Debug and Release rather than lifted into an
  `.xcconfig`. ADR 0001 already declined to introduce config files for a single value;
  the same reasoning applies here, and an `.xcconfig` remains the natural home if a
  third build configuration ever appears.
- Test targets do not carry the key. Only the app target is submitted.

## Alternatives considered

**Answering the questionnaire per submission.** The status quo. Rejected: it repeats a
decision whose inputs rarely change, and the reasoning lives nowhere.

**A checked-in `Info.plist`.** Would make the key visible in a file rather than buried in
`project.pbxproj`, which is a real readability gain for something with legal weight.
Rejected because it means abandoning `GENERATE_INFOPLIST_FILE` and hand-maintaining the
~12 keys Xcode currently generates, for one entry.

**Omitting the key and setting it in App Store Connect.** Equivalent for a single
release, but the value then lives outside version control and outside review.

## References

- `evMap_ios/EVMap/EVMap.xcodeproj/project.pbxproj` — `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption`
- [Complying with Encryption Export Regulations](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations)
- ADR 0001 — why the project has no `.xcconfig` files
