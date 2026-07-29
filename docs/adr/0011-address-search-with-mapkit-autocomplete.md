# 11. Address search with MapKit autocomplete, and an idle map with nothing on it

- Status: Accepted
- Date: 2026-07-29
- Deciders: Johannes Popp

## Context

Two problems on the map screen, one cosmetic and one a missing feature.

**A blank badge floated over every idle map.** `MapStatusBadge` (ADR 0009 §2) wrapped its
two states in a `Group` and applied padding, `.thinMaterial` and a shadow to that
`Group`. A `Group` whose `if`/`else if` chain matches nothing is still a view — it just
has no content — so an empty capsule stayed pinned to the top-centre of the screen
whenever the map was neither loading nor truncated, which is most of the time. The badge
was correct about *what* to say and wrong about *whether* to exist.

**The map could only be moved by hand.** ADR 0009 made panning and zooming load
stations, which closed the "how do I see other places" gap at the level of gestures. It
did not close it at the level of intent: getting to a city the user is not currently
looking at meant dragging there. Every mapping app answers this with a search field, and
the app already depends on MapKit for the map itself, so the geocoding is available
without adding a dependency or a backend endpoint.

Three sub-requirements came with it: the search must autocomplete, the resolved place
must be visible as a pin, and there must be a way to undo the search — but the undo
control must not be a permanent fixture of the screen, since most of the time there is
nothing to undo.

## Decision

### 1. A view only exists when it has something to say

`MapStatusBadge` builds its capsule per state instead of decorating a `Group`. When
neither branch matches, the body produces nothing and the overlay is empty. The chrome
(padding, material, shadow, transition) moved into a `capsule(_:)` helper so the three
states still look identical.

The `.animation` moved from inside the badge to the overlay in `MapScreen`, because a
view that no longer exists cannot animate its own disappearance.

### 2. Address search is a feature module, not a repository method

A new `Features/Search/` module in the established `Domain` / `Data` / `Presentation`
shape. It does **not** go through `ChargingStationRepository`: nothing here touches the
backend. Geocoding is a device capability, and routing it through the station repository
would put a MapKit dependency behind an abstraction that exists to be swapped for
GraphQL.

It does get its own protocol for the same reason the repository has one.
`AddressSearchProviding` has three methods — suggest, resolve, cancel — and
`MapKitAddressSearchProvider` is the only implementation. The tests use a stub, which is
what makes the debounce, the echo suppression and the failure path testable at all: none
of them could be exercised against a live MapKit daemon.

`AddressSuggestion` and `SearchedPlace` carry no MapKit types. The
`MKLocalSearchCompletion` handle needed to resolve a row *precisely* is kept in a
dictionary inside the provider, keyed by suggestion id. Resolution uses the handle when
it has one and falls back to a natural-language `MKLocalSearch` when it does not — which
is exactly the case for a suggestion restored from disk, where no handle can exist.

### 3. Suggestions are debounced; the field's own echo is not a search

`MKLocalSearchCompleter` is delegate-driven and rate-limited. It is wrapped so callers
see `async` — each call parks a continuation that the delegate resumes, and a newer query
resumes the older one with an empty list rather than leaving it waiting on a callback
that now belongs to a different query. A cancelled task is drained through
`withTaskCancellationHandler`, so closing the search field cannot strand a continuation.

A 250 ms pause sits in front of the provider, so a typed word is one request rather than
one per character.

Selecting a suggestion writes the place's name back into the search field. SwiftUI
reports that write as an `onChange`, which naively reads as "the user typed the full
name" and would reopen the suggestion list on top of a search that is already finished.
`AddressSearchViewModel` records the string it echoed and ignores exactly that one
change. It records the *string*, not a boolean flag: if the echo never provokes a change
(the text was already identical), the next genuinely different keystroke clears the
record and searches normally, where a flag would have swallowed it.

### 4. Recents are stored locally, with coordinates

Tapping into an empty search field shows the last 8 searched places. They are stored in
`UserDefaults` as JSON **including the coordinate**, so replaying one moves the map
immediately — no lookup, and it works offline. Re-searching a known place moves it to the
front and refreshes its coordinate rather than adding a second row.

Searched addresses are somebody's home, workplace or destination, so they are treated
like a location fix under ADR 0002's rules: never sent to the backend, never logged
(only counts and the *fact* of a search reach the log; the coordinate is `.debug`-only),
and removed with the app. Individual entries can be swiped away and the whole list
cleared from the section header. `UserDefaults` rather than the Keychain because losing
the list is harmless, and rather than a database because eight rows do not warrant one.

Unreadable stored data is discarded and the key removed, so a shape change between app
versions cannot take the search field down with it.

### 5. The field goes in the bottom bar, using the system's own components

`DefaultToolbarItem(kind: .search, placement: .bottomBar)` — the iOS 26 position for
search, within thumb reach, rather than a `navigationBarDrawer` under the title at the
far end of the screen. `.searchable` keeps its default placement and the toolbar item
decides where it lands.

An earlier iteration hand-built the bar to control its material, because a `.searchable`
field over a map is close to invisible: iOS 26 renders it as free-floating Liquid Glass,
which needs a surface to read against, and a map has none — only bright fill and
saturated pins. That was reverted in favour of the stock component. The translucency is
accepted as the platform's look; **it is not adjustable**, which is worth recording so it
is not re-litigated: `toolbarBackground(_:for:)` and `toolbarBackgroundVisibility(_:for:)`
have no effect on a `.bottomBar` search item (both verified against the 26.5 SDK — the
bar is drawn as a floating capsule, not as a bar with a background), and SwiftUI exposes
no search-field style API. Owning the field is the only way to own its material, and that
costs the system cancel button, the suggestion presentation and search-specific VoiceOver
semantics.

After a hit the app drops **focus**, not the search presentation: `.searchFocused` closes
the keyboard while the field keeps showing the place that was found. Dismissing the
presentation instead (`.searchable(text:isPresented:)` set to `false`) makes UIKit clear
the field as it tears the search controller down, which left the map sitting on a place
its own search bar no longer named.

### 6. The field's own clear button is the only clear

No separate control. When the search field's `X` empties the text, the pin goes with it:
`queryChanged(to:)` treats an empty query as "there is no search", dropping the
suggestions, cancelling any in-flight resolution and clearing `result`. That satisfies
"only visible when there is something to clear" for free — the system shows the `X` only
when the field has text — and it avoids a second control that has to be explained.

Deliberately **not** cleared: the history. Emptying the field is not the same statement
as forgetting where the user has been; that is what the section header's own button is
for.

### 7. One alert, two sources

A failed station load and a failed address lookup are the same class of interruption, and
SwiftUI presents one alert per view. The existing alert's binding now reads both
`MapViewModel.errorMessage` and `AddressSearchViewModel.errorMessage`.

Autocomplete failures are the exception: they are logged and produce an empty list, never
an alert. A user typing offline would otherwise get one alert per character.

## Consequences

### Positive

- The idle map is actually idle — no artefact hovering over it.
- Reaching a place is one search instead of a drag across the country; the resulting
  camera change loads stations through the existing viewport path, so no new fetch
  trigger was needed.
- Returning somewhere is one tap and no typing, with no network involved.
- The search pin is visually distinct (indigo `Marker` balloon) from the power-coded
  station pins, and is drawn first so it can never hide one.
- The search layer is unit-testable without MapKit: 23 tests cover debounce, echo
  suppression, dedup, capping, corrupt-data recovery and the failure path.

### Negative / accepted risks

- **The suggestion list is one request behind fast typists.** The 250 ms pause is a fixed
  guess, not a measured one. Typing continuously produces no suggestions until the user
  pauses.
- **`MKLocalSearchCompleter` is shared and stateful.** One completer serves all queries,
  so two overlapping searches cannot both be in flight; the older one is resolved empty.
  This is invisible in practice — the older result would be discarded anyway — but it
  means the provider is not safely reusable from more than one screen at a time.
- **Resolution can disagree with the suggestion.** The fallback text search
  (`title, subtitle`) is what MapKit thinks that string means, which for a suggestion
  restored from disk may be a different branch of the same chain. The stored coordinate
  makes this rare: only a recents entry whose place moved would ever re-resolve.
- **Recents survive sign-out.** They are device-local and not tied to a `UserIdentity`,
  so a shared device shows one person's searches to the next. The clear-history button is
  the only mitigation.
- **Query completions are excluded** (`resultTypes` is `.address` + `.pointOfInterest`).
  Searching "Schnellladen" as a category does nothing. This field moves the map to a
  place; category search is what `StationFilterScreen` is for.
- **The search field is translucent over the map**, sometimes to the point of being hard
  to spot against bright fill. This is the platform's Liquid Glass treatment and there is
  no supported way to change it (§5). Revisit if Apple adds a search-field style API.

### Neutral

- No backend change, no new endpoint, no new dependency.
- `MapScreen` grows a second view model. `MapViewModel` is untouched; the two communicate
  only through the camera — the search moves it, `onMapCameraChange` loads what it lands
  on, and the map keeps the search's region for bias.
- `MKMapItem.location` is used rather than the `placemark` property, which iOS 26
  deprecated.

## Alternatives considered

**A backend geocoding endpoint.** Would keep the client thin and let German addresses be
matched against the BNetzA data the app already has. Rejected: it is a new service
dependency (Nominatim or a paid geocoder), a new REST contract, and a caching and
rate-limiting problem — for a capability every iOS device already has offline-ish and for
free. MapKit is already a hard dependency of the map itself.

**`CLGeocoder.geocodeAddressString`.** Simpler, and no delegate wrapping. Rejected: it
has no autocomplete at all, which was half the requirement, and Apple documents a hard
per-app request throttle that a search-as-you-type field would hit immediately.

**Storing recents as plain query strings and re-resolving on tap.** Less to persist and
no stale coordinates. Rejected: it makes the fast path — going back somewhere — depend on
the network, and a recents list that fails offline defeats its purpose.

**Keeping the `MKLocalSearchCompletion` in `AddressSuggestion`.** Would make every
resolution exact. Rejected: it puts a MapKit type in the domain layer, and it cannot be
persisted, so recents would need a second model anyway. The provider-side dictionary gets
the precision for live rows without the coupling.

**A permanently visible clear button, disabled when idle.** Rejected on the user's
explicit requirement, and it is the better call regardless: a disabled control is a
question the user has to answer ("why can't I press that?"), an absent one is not.

**Showing recents in a sheet or a separate screen.** Rejected: `.searchSuggestions`
already owns the space under a focused search field, and putting history anywhere else
means the user has to know it exists.

## References

- `evMap_ios/EVMap/EVMap/Features/Search/Domain/AddressSuggestion.swift`
- `evMap_ios/EVMap/EVMap/Features/Search/Data/AddressSearchProvider.swift`
- `evMap_ios/EVMap/EVMap/Features/Search/Data/RecentSearchStore.swift`
- `evMap_ios/EVMap/EVMap/Features/Search/Presentation/AddressSearchViewModel.swift`
- `evMap_ios/EVMap/EVMap/Features/Search/Presentation/AddressSearchSuggestions.swift`
- `evMap_ios/EVMap/EVMap/Features/Map/Presentation/MapScreen.swift`
- `evMap_ios/EVMap/EVMapTests/AddressSearchTests.swift`
- ADR 0002 (iOS logging) — the rules the recents history is handled under
- ADR 0009 §2 — the status badge this record fixes
