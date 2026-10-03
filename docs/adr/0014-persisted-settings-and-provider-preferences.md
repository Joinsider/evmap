# 14. Persisted settings and per-provider preferences

- Status: Accepted
- Date: 2026-07-29
- Deciders: Joinsider

## Context

Three things came together in one change, because they are one feature seen from three
angles.

**Filters did not survive the app.** `StationFilter` lived in `MapViewModel` as
`@Published var filter = StationFilter()`. A driver whose car takes CCS and who never
wants to see a Schuko socket had to say so again on every launch. That is not a filter,
it is a setting, and it was being stored in the one place guaranteed to be thrown away.

**Choosing a provider was a text field.** The filter sheet's only operator control was
`TextField("filter.operator")`, sent as `?operator=<exact string>` and matched with
`lower(s.operator_name) = lower(:operator)`. It required the user to already know the
exact name a source adapter happened to write; a typo matched nothing, and there was no
way to find out what the valid values were. It also expressed the wrong relation: the
request is "don't show me this network", not "show me only this one".

**Provider choice is the first half of route planning.** Range and route planning (a
later milestone) needs to know which networks a driver prefers and which they route
around — the same list, with more values. Building a boolean "hidden" switch now would
mean rebuilding both the storage format and the UI when that lands, and would strand
whatever users had configured in the meantime.

A fourth requirement fell out of the first: once settings persist, there has to be a way
to get back to the shipped state without deleting the app.

## Decision

### 1. `AppSettings` is the persisted value; `StationFilter` is what goes on the wire

Two types, because they answer two questions. `AppSettings` is what the user decided and
what is stored. `StationFilter` is the criteria of one query, and is now built only by
`AppSettings.stationFilter` (plus `MapViewport.effectiveFilter`, which raises the power
floor at overview scale). Nothing else constructs one.

That split is what keeps the provider vocabulary out of the query layer: the map never
reads `providerPreferences`, it reads a filter that already has `excludedProviders` in
it. When `preferred` starts meaning something, routing reads the preferences and the
station query is untouched.

`AppSettings` is a single JSON value under one `UserDefaults` key (`settings.v1`) rather
than a key per field, so the whole thing is written, read and cleared atomically, and a
field added later cannot be missed by a partial reset.

### 2. The vocabulary is declared in full, the UI offers the implemented part

`ProviderPreference` has four cases — `shown`, `hidden`, `preferred`, `avoided` — and a
`selectableCases` of `[.shown, .hidden]` that the picker iterates. The two reserved cases
round-trip through storage and are covered by tests, but cannot be chosen, because they
would do nothing until routing exists and a control that does nothing is worse than an
absent one.

Declaring them now is not speculation, it is format stability: the day routing writes
`preferred` into these settings, an older installed build has to be able to read that
file. Two lines of enum today; a build that silently discards its own settings otherwise.

`hidesStations` is a property of the case rather than `preference == .hidden` at each
call site, because `avoided` is deliberately *not* a visibility switch — an avoided
network's stations must still be drawn, they are just weighted down when routing.

`ProviderPreference.default` is `shown`, and the default is stored by *not* being stored:
`setPreference` removes the entry when it matches the default in force (§2c generalizes
"the default" from the constant `shown` to whatever the global switch says). Switching a
network back to it is then indistinguishable from never having touched it, which is what
keeps `isDefault` — and therefore the reset button's enabled state — honest.

The row uses a `Picker` rather than a `Toggle` for the same reason: two choices grow into
four without the row being redesigned, where a switch would have to be replaced and its
meaning ("on" = which of four?) renegotiated.

The picker is segmented, icon-only (`eye` / `eye.slash`), and sits on its own line under
the network name in *every* row. Icons because the choice is "visible / not visible",
which the pair says without text and therefore without segments that change width per
language; each carries the localized name as its accessibility label, since an image
alone announces nothing. Its own line because names range from "EnBW" to "Autobahn Tank &
Rast Anlagen Betreibergesellschaft mbH": a control placed beside the name wraps for the
long ones and not for the short ones, so the controls land at a different position in
each row — which is the one thing the eye tracks when scanning down this list. A uniform
extra line of height buys a single column to aim at. When `preferred` and `avoided`
become selectable, they extend the same segmented control with `star` and
`exclamationmark.triangle`; the layout does not have to change again.

### 2b. The provider screen is a personal list, not a directory

`ProviderPreferencesScreen` shows only networks the user picked. There is no browsable
list of everything the backend knows: `operator_name` has roughly two thousand distinct
values, most of them a single station, and a screen that scrolls through them asks the
user to *find* an answer where they already know the name they want. So the entry point
is search, in the bottom bar (`DefaultToolbarItem(kind: .search, placement: .bottomBar)`)
where the thumb is on a settings sheet held in one hand, seeded with the largest networks
until something is typed. Tapping a result adds the row and closes the search — the close
is what makes the new row visible, so it is part of the same gesture.

The listed set is **view state** (`listed`), merged from `configuredProviders` on appear,
not derived from the settings. Deriving it cannot work: `shown` is stored by not being
stored, so a row switched back to "show" would vanish under the finger at exactly the
moment the user is most likely to switch it off again. Keeping it in view state gives the
two removal paths the user asked for and both are honest about the model:

- **swipe** — explicit: drops the row *and* calls `setPreference(.shown,…)`, because a
  stored `hidden` left behind would keep filtering the map from a screen that no longer
  lists the network doing the filtering;
- **closing the screen** — implicit: a row left at the default was never written, so it
  is simply not there next time. Nothing has to clean up, which is the point.

Search results keep networks that are already listed, marked with a checkmark, rather
than filtering them out. Removing them would make a search for a name you know return
nothing, which reads as "this network does not exist" — and "did I already set this one?"
is a common reason to search in the first place. The station count lives on the result
row only: listed rows come from stored settings, and a name that no longer exists in the
master data has no count to show.

### 2c. One switch decides what "not listed" means, and it inverts the query

`AppSettings.unlistedProviders` is the global default for every network the user has not
decided about individually — the "show all other providers" switch at the top of the
provider screen. It is a `ProviderPreference`, not a `Bool`, so that a reserved case can
become a global default later without the stored field changing type.

It changes two things beyond its own value:

**What counts as an opinion.** `setPreference` drops an entry that equals
`unlistedProviders`, not one that equals `shown`. In allowlist mode "shown" is the
exception worth storing and "hidden" is what goes without saying, so the rule has to be
relative or the screen would store rows that mean nothing and drop rows that mean
everything. Flipping the switch does *not* rewrite what is already stored: those entries
may become redundant, but deleting them would throw away opinions the user would get back
by flipping the switch again.

**What the query says.** With the switch on, the preferences are a blocklist and the map
sends `excludeOperator` as before. With it off they are an allowlist, and the map sends
`includeOperator` — a new repeated parameter applied in the SQL for exactly the reason
`excludeOperator` is (see §5): the row limit is server-side and ranked by power, so
filtering after the fact would let unwanted stations eat slots. A station with no operator
name is *kept* by the blocklist and *dropped* by the allowlist, which is the same rule
seen from both ends: "unknown" is not a network anyone chose to hide, and it is not one
anyone chose to keep either.

The edge case that dictates the design: **hide everything, pick nothing**. That is a
legal state and it means an empty map. But an allowlist of nothing has no spelling in a
query string — sending zero `includeOperator` parameters is indistinguishable from not
restricting at all, which would show the user every station they just hid. So
`StationFilter` keeps `includedProviders` as an optional set where `nil` and `[]` differ,
exposes `matchesNothing`, and `RESTChargingStationRepository` answers such a query with an
empty result **without a request**. The server correspondingly reads an empty
`includeOperator` list as "no allowlist", because that is the only thing it can mean on
the wire.

### 3. Unknown stored values degrade; they do not throw

`AppSettings.init(from:)` is written by hand instead of synthesized. A missing key falls
back to its default (settings written before a field existed), an unknown connector raw
value is dropped and the rest kept, and `ProviderPreference.init(from:)` maps an
unrecognized raw value to `.shown`.

The synthesized decoder would throw in all three cases, and a throw here is not a local
failure: it sends `UserDefaultsAppSettingsStore.load()` down its corrupt-data path, which
discards the key. One value from a newer app version would silently reset every other
setting the user made. Genuinely unreadable data still takes that path — and is *removed*
rather than merely ignored, since a payload that fails to parse once fails every launch.

### 4. Settings persist on change; the map refetches on dismiss

There is no save button. `SettingsViewModel.settings` writes through to the store in
`didSet`, skipping the write when the new value equals the old one (a slider reports the
step it is already on for the length of a drag). A screen that can be abandoned without
applying is a promise the app would have to keep across a swipe-to-dismiss, a
backgrounding and a crash.

The *refetch* is not immediate. `MapScreen` calls `viewModel.apply(_:)` from the sheet's
`onDismiss`, and `apply` compares before reloading, so opening the settings and closing
them again costs nothing. Persisting per keystroke and querying per keystroke are
different things, and only the first is free.

`MapViewModel.filter` became `private(set)` and is passed into the initializer, so the
very first viewport query already carries the stored settings instead of loading the
unfiltered world once and visibly correcting itself.

### 5. Exclusion happens server-side

The hidden networks are sent as repeated `?excludeOperator=` parameters and applied in
`StationSpatialRepository` as
`AND (s.operator_name IS NULL OR lower(s.operator_name) NOT IN (…))`.

Filtering client-side would have been less work and is wrong: the backend caps results at
`limit` rows *ranked by power*, so hidden stations would consume slots and the map would
quietly show fewer stations than the user asked for — worst exactly where the limit
bites, at wide zoom. A station with no operator name is never excluded; "unknown" is not
a network anyone chose to hide.

`StationService` caps the list at 200 names. A plausible user hides a handful; a list
that long is a malformed request, and each entry costs a comparison per candidate row.

The allowlist (`?includeOperator=`, §2c) is applied the same way and capped the same way,
as `AND lower(s.operator_name) IN (…)`. It is a separate parameter rather than a reuse of
`?operator=`: that one means "exactly this one network" to installed builds, and widening
its meaning in place would change what an old client's request returns.

The old `?operator=` parameter stays on the backend although no current client sends it.
Installed builds still do, and it is one branch in a query builder.

### 6. `GET /api/v1/operators` is its own resource

`OperatorController` answers "which charging networks exist", searchable by substring and
ranked by station count, with a default limit of 50 and a ceiling of 200. It is not a
sub-resource of `/stations`, because it is a question about master data as a whole rather
than about one station or one viewport. It is permitted without authentication like the
station endpoints — the settings screen has to work before anybody signs in.

**There is no operator table and no operator id.** `operator_name` is a string the source
adapters normalize onto each station, and neither BNetzA nor OCM publishes a stable
operator identifier to key one on. The directory is therefore a `GROUP BY operator_name`
over the stations, and the *name is the identity* — which is why preferences are keyed by
name, and why two spellings of one company are two providers (see Consequences).

Migration `005` adds `charging_station_operator_idx (operator_name)` so that aggregate is
an index-only scan with pre-sorted input rather than a sequential scan over 113k+ rows;
`charging_station_filter_idx` cannot serve it, since `operator_name` is its second column.

`OperatorSearch` is a separate record from the service so the two things that are easy to
get wrong — bounding the limit and escaping user input into a `LIKE` pattern — are unit
tested without a database. Everything the user typed is a literal: unescaped, "50%" would
match every operator starting with "50", and an underscore would silently become "any
character".

### 7. Reset restores settings, and nothing else

One destructive button behind a confirmation dialog, disabled while the settings are
already at their defaults. It resets filters and provider preferences — not the search
history, which is another feature's data with its own "clear history" control, and not
the Apple sign-in. A button labelled "reset settings" must not log somebody out.

In `SettingsViewModel.reset()` the assignment comes *before* `store.reset()`: `didSet`
persists the new value, so clearing first would write the defaults straight back into the
key that was just removed. (This was caught by a test, not by inspection.)

## Consequences

### Positive

- Connector, power, availability and provider choices survive a relaunch, and the first
  map query of a session already carries them.
- Providers are discoverable: the picker opens on the largest networks by station count,
  and search finds the rest without anyone having to guess how a source spells a name.
- The map's row limit now means what it says — hidden stations never consume slots.
- Both directions are one gesture: "show everything except these" and "show nothing except
  these" are the same list read against a switch, so nobody has to hide 2000 networks by
  hand to get an allowlist.
- The storage format is ready for `preferred`/`avoided`; adding them is an enum entry, two
  strings, and whatever routing does with them. No migration, no UI redesign.
- Corrupt, partial and future settings payloads all have a defined outcome, each covered
  by a test (31 new iOS tests, 12 new backend tests).
- One screen instead of a filter sheet plus a settings screen that would have meant
  almost the same thing.

### Negative / accepted risks

- **The operator name is the identity.** "IONITY" and "IONITY GmbH" are two providers,
  and hiding one does not hide the other. The station count in each row is the only hint
  that this is happening. Fixing it properly means an operator registry with aliases in
  the sync layer, which is a much larger change; the ranked list at least puts the
  dominant spelling first.
- **A preference outlives the provider.** A network that disappears from the data — a
  rename, an acquisition — keeps its stored preference forever and stays in the "your
  selection" section with no station count. That section exists partly so it can be
  cleared; nothing prunes it automatically, because "absent from the current result page"
  is not evidence a provider is gone.
- **Hiding many providers makes long URLs.** 200 exclusions is a query string of a few
  kilobytes on every viewport change. Under the cap this is well inside what servers
  accept, but it is a per-request cost that scales with the setting.
- **The operator search cannot use an index.** `lower(operator_name) LIKE '%x%'` is a
  filter applied during the scan; only the grouping is index-assisted. Fine at 113k
  stations and a few thousand distinct names, and the ceiling to watch as more national
  registers land (ADR 0012). `pg_trgm` is the upgrade path.
- **Settings are device-local and survive sign-out.** Like the search history (ADR 0011),
  a shared device shows one person's provider choices to the next. The reset button is
  the mitigation.
- **An empty allowlist is a blank map with no in-map explanation.** Hiding everything and
  picking nothing is a legal state that draws zero pins, and the map says only what it
  always says when a region has no stations. The provider row in settings reads "nothing
  visible", which is where the user just was; a dedicated map-level notice was left out
  rather than guessed at.
- **`preferred` and `avoided` are dead code until routing exists.** They are covered by
  round-trip tests only; nothing exercises them end to end.

### Neutral

- `StationFilterScreen` is deleted; `SettingsScreen` replaces it. The `filter.*` string
  keys stay — they name sections that did not change meaning — while `filter.operator`
  and `action.apply` are gone and `action.done` is new.
- A `settings` category was added to `LogCategory`. Nothing stored here is personal data
  under ADR 0002's rules, but the excluded providers are logged **by count**, in both the
  client's `logDescription` and the backend's query trace: which networks somebody
  switched off is a preference of theirs, and forty names would drown the line they are
  meant to explain.
- `EVMapApp` now owns `SettingsViewModel` and passes it to `MapScreen`, which is where
  `AuthSession` already lives, for the same reason: the map needs it before its first
  query.

## Alternatives considered

**Keeping the free-text operator field alongside the picker.** Two mechanisms writing the
same column, one of them a positive filter and the other a negative preference, with no
defined behaviour when both are set. Rejected in favour of one mechanism that grows into
the routing case.

**Deriving the provider list from the stations already loaded.** No backend change at all.
Rejected: the viewport is capped at 600 rows ranked by power, so the list would show
whichever networks happen to be on screen, would change as the user pans, and would never
surface a regional network the user is not currently looking at — which is exactly the one
worth configuring.

**A `boolean isHidden` per provider now, richer preferences later.** Smaller today.
Rejected: it changes the stored format and the control when routing lands, and users who
configured providers in between would have their settings migrated or dropped. The
four-case enum costs two unused lines and buys format stability.

**Filtering hidden providers on the client after the response.** No API change, no
migration. Rejected — see §5: the server-side row limit makes it silently lossy.

**A `preferences` table on the backend, keyed by `UserIdentity`.** Would sync across a
user's devices and is where this has to go if settings ever need to follow an account.
Rejected for now: it makes a screen that must work signed-out depend on being signed in,
adds a write path to the API for data that is not worth a round trip, and the app has one
client platform and no multi-device story yet.

**Applying the filter live as the user drags the slider.** Rejected: one network request
per slider step, against an endpoint that answers a spatial aggregate. The dismiss
boundary is a natural debounce and matches what the old "Apply" button did.

## References

- `evMap_ios/EVMap/EVMap/Features/Settings/Domain/AppSettings.swift`
- `evMap_ios/EVMap/EVMap/Features/Settings/Domain/ProviderPreference.swift`
- `evMap_ios/EVMap/EVMap/Features/Settings/Data/AppSettingsStore.swift`
- `evMap_ios/EVMap/EVMap/Features/Settings/Presentation/SettingsScreen.swift`
- `evMap_ios/EVMap/EVMap/Features/Settings/Presentation/ProviderPreferencesScreen.swift`
- `evMap_ios/EVMap/EVMapTests/SettingsTests.swift`, `ProviderSearchTests.swift`
- `evmap_service/src/main/java/de/joinside/evmap_service/api/station/OperatorController.java`
- `evmap_service/src/main/java/de/joinside/evmap_service/api/station/OperatorSearch.java`
- `evmap_service/src/main/resources/db/changelog/005-operator-directory.sql`
- ADR 0001 §2 — the free-text operator filter this replaces
- ADR 0002 (iOS logging) — the rules the provider counts are logged under
- ADR 0011 §4 — the same device-local storage decision, for search history
