# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- **GPX export & import for rides** — *My rides* now has **Import** and **Export** actions. Tapping *Export* turns the list into a **multi-select** (checkboxes + a Cancel/Export bar); confirming with several rides asks whether to **combine them into one GPX file** or write **one file per ride**, then asks for the **destination**: *Share* (system share sheet → save to *Files*, Drive, e-mail, …) or **Save to file** (a Storage Access Framework picker — *Create document* for a single file, or *pick a folder* for separate files). A single selection skips the layout question, the file named after the ride. *Import* opens a document picker and reads each `<trk>` of the chosen GPX file(s) back into a ride (keeping its `<name>`), deriving distance, elevation and — when the GPX is time-stamped — duration/speeds. Built on a dependency-free `GpxWriter`/`GpxParser`/`GpxRideFactory` (`core/gpx`) and a cache + `FileProvider` share path / SAF save path (`GpxExporter`). Fully localised across all eight supported languages.
- **Named rides** — recorded rides now carry a **name** instead of just a date. An auto-recorded navigation ride is named after its **destination** (the reverse-geocoded place of the destination coordinate, e.g. *"Trier"*, falling back to the destination's own label); a generated **round trip** becomes *"Round trip - {current place}"* (localised, `ride_round_trip_name`). Finishing a **manual** recording now opens a **name prompt** pre-filled with the reverse-geocoded current place (cancel keeps the ride recording, an empty field saves it unnamed). The name is shown in the *My rides* list and the ride-detail sheet, and can be **edited** anytime via the pencil in the detail sheet. Resolved in `MapViewModel.resolveAndSetAutoRideName` / `requestStopRideTracking` (reverse-geocoded via `NominatimGeocoder.reverseGeocodePlace`) and persisted with the ride (`RecordedRide.name`, Room migration). Fully localised across all eight supported languages.
- **Pedalling cyclist avatar during navigation** — the live-location cyclist now visibly **pedals** while you ride. The rider's legs, shoes and pedals are no longer baked into the static `ic_cyclist_avatar` vector; they're drawn programmatically (`MarkerIconFactory.drawCyclistLegs`) as two knees-bent legs whose feet sweep forward/back 180° out of phase, mimicking a turning crank seen from above. The navigation controller pre-renders a strip of pedal frames (`NAV_PEDAL_FRAME_COUNT`, `navPedalFrameImageId`) and its per-frame `Choreographer` loop swaps the avatar image each tick, with the crank phase tied to the rider's along-route distance (`PEDAL_METERS_PER_REV`) so the cadence matches the real ground speed — and naturally **freezes when you stop**. Whenever the rider has (nearly) **stopped — whether idle on the map or waiting at a traffic light mid-navigation** — the avatar now drops into a believable **standstill pose**: the rider plants one foot flat on the ground beside the bike (a wider, flattened shoe sells the ground contact) while the other foot stays on the raised pedal, instead of hovering frozen mid-stroke. Off-route the resting marker uses it by default; during navigation a dedicated foot-down frame (`navIdleFrameImageId`) is shown once the eased ground speed drops below `PEDAL_STANDSTILL_SPEED_MPS` (`idle` flag in `createLocationMarkerIcon` / `drawCyclistLegs`).
- **"Keep screen on while riding" toggle** — the display is now kept awake during **both** active navigation **and** a live ride recording (previously only navigation prevented the screen from dimming/locking). A new switch in the *Settings → Appearance & map* sheet lets you turn this off (e.g. to save battery and rely on voice guidance). It's **on by default** and persisted (`KeepScreenOnPreferences`, `MapViewModel.keepScreenOnEnabled`). Fully localised across all eight supported languages.
- **Legal notice (Impressum)** — the project now carries a proper imprint with the responsible party's name and postal address (§ 5 DDG). It's reachable everywhere: a new **Imprint** section in the in-app *About* sheet (shown inline, so it stays offline-accessible and localised across all eight languages), a dedicated **`imprint.html`** page on the website linked from the navigation and footers of the home and privacy pages, a root **`IMPRINT.md`**, and the contact/data-controller blocks of both `PRIVACY.md` files and the privacy page now name the full address.
- **`CODEOWNERS`** — a `.github/CODEOWNERS` file (`* @drzeeb`) so GitHub automatically requests the maintainer's review on every pull request (including Renovate bot PRs) and branch protection can require code-owner reviews.
- **"Ridden tracks" map layer** — a new *Layers* overlay draws **every recorded ride as its own thin, translucent line**, so you can see everywhere you've been at a glance. Because the lines are semi-transparent, overlapping passes build up colour and frequently used streets read stronger — a lightweight, route-preserving complement to the existing *Ride heatmap*. Each track is reduced with Ramer–Douglas–Peucker simplification (`RideTrackLines`, ~8 m tolerance, typically −80–95 % points) off the main thread before drawing, the hairline width scales with zoom, and the layer sits beneath the map pins (which stay tappable). It's **off by default** and persisted (`MapLayerCategory.TRACKS`, `updateTracksHistoryLayer`); the pure aggregation is unit-tested (`RideTrackLinesTest`). Fully localised across all eight supported languages.

### Changed
- **Closing a ride returns to the rides list** — closing a recorded ride's detail sheet (the **✕** or the back gesture) now reopens the *My rides* list it was opened from, instead of dropping back to the bare map, so browsing several rides in a row no longer means reopening the list each time (`MainMapScreen`).
- **Saved-ride detail no longer blocks the map** — opening a recorded ride from *My rides* used to show its statistics in a modal bottom sheet, whose scrim swallowed all touches so the drawn ride track couldn't be panned, pinched or zoomed. The detail view is now a **non-modal, draggable sheet**: it overlays the map without a scrim, only its own surface consumes touches, and it can be dragged down to a small peek (or closed with the **✕** button) to free up the map while the ride polyline stays drawn. It starts fully expanded so all stats are visible immediately (`RideDetailSheet`, rendered inside the map layout). Fully localised across all eight supported languages (`ride_detail_close`, `ride_detail_drag_hint`).

### Fixed
- **Debug route simulation can now be paused & resumed (and no longer makes the rider run away)** — the (debug-only) GPS route simulator's play/stop button now works as a proper **play / pause**: pressing it again **pauses** the run (keeping the position) and pressing play **resumes from where it left off** instead of restarting at the route start (`RouteSimulator.travelledMeters` + `startOffsetMeters`; a reroute/new route still restarts fresh). Separately, stopping the simulator used to leave the navigation avatar **coasting on to the end of the route**: the simulator stops sending fixes, but the navigation puck advances by *dead-reckoning* (`NavigationManager` predicts position from the last speed between sparse GPS fixes) and never saw a "standing still" fix to slow it down. Pausing/stopping now feeds one final **stationary fix** (speed 0) at the current position so the puck eases to a stop. Covered by `RouteSimulatorTest` (resume/reset) and `NavigationControllerTest` (stationary fix).
- **Flaky `MapViewModelTest` on CI** — the test built fresh `MapViewModel`s but never tore them down, so two kinds of coroutine outlived each test and kept running on background threads: the view-models' own `viewModelScope` collectors (location, favorites, route-simulation flows) and — because navigation auto-starts a ride recording that the tests never stop — the process-level `RideRecordingManager`'s endless 1 s stats ticker and GPS collector. An exception from such a leaked coroutine (often after `resetMain()`) then surfaced against the *next* test as `UncaughtExceptionsBeforeTest` (e.g. `toggleFavorite…`, `startInAppNavigation…`), failing the suite intermittently. `RideRecordingManager`'s background scope is now **injectable** (Hilt still supplies the real `Dispatchers.Default` one); the test hands each manager a real, **cancellable** scope and, in `tearDown`, cancels every manager scope and clears every view-model — so nothing leaks across tests. (A test scheduler is deliberately *not* used for the manager, as `advanceUntilIdle()` would spin forever on the ticker's endless `delay` loop.)
- **Bogus ride "max speed" from GPS Doppler spikes** — a recorded ride could report a wildly wrong top speed (e.g. **70 km/h** / **52 km/h** on rides where nothing close was ridden). The cause: `RideTracker` took the **GPS-reported instantaneous speed** (`speedMps`, the receiver's Doppler velocity) directly as the peak, gated only by an absolute 90 km/h ceiling. That sensor value can briefly glitch to 2–5× the real speed — typically on a low-accuracy fix — while the position barely moved; the existing "teleport" filter only validates the *position-derived* speed and so never caught these. A peak-speed sample is now accepted **only when corroborated by the track geometry**: there must be a reliable position-derived baseline (fixes ≥ 1 s apart) and the reported speed may not exceed it by more than 1.5× (`SPEED_CORROBORATION_FACTOR`), which discards the Doppler spikes while still honouring genuine fast (e.g. downhill) stretches. Covered by a new `RideTrackerTest` case.
- **Accessibility (a11y) — TalkBack/screen-reader support** across the map UI: sheet titles are now exposed as **headings** (`SheetHeader` + every sheet title) so screen-reader users can navigate by heading; the **record-ride FAB** (idle red-dot state) now has an accessible name (`ride_start`); the **2D/3D navigation tiles** and the **offline routing-profile rows** expose `Role.RadioButton` + selected state so the active choice is announced; **layer toggle cards** and **address-search result rows** merge their contents into a single focusable element. New strings localised across all eight languages.

## [v1.0.22] - 2026-06-21

### Added
- **Ride heatmap overlay** — a new *Ride heatmap* map layer turns your recorded rides into a colour heatmap that reveals where you cycle most. All recorded GPS tracks are aggregated into a compact grid (≈11 m cells) weighted by how often you've ridden through each spot, so frequently used streets glow hotter (cool blue → red). Toggle it in *Layers*; it's **off by default** and persisted, sits beneath the map pins (which stay tappable), and is built/aggregated off the main thread. The pure aggregation logic (`RideHeatmap`) is unit-tested and the overlay reuses the existing recorded-ride data, so no extra storage is needed. Fully localised across all eight supported languages (`MapLayerCategory.HEATMAP`, `updateHeatmapLayer`).
- **Spoken turn-by-turn voice guidance (TTS)** — navigation can now read the upcoming-turn instructions aloud via Android `TextToSpeech`, building on the existing on-screen turn banner. It speaks an early *prepare* cue when a turn comes within ~150 m ("In 150 m, turn left"), a final *imminent* cue within ~30 m ("Now turn left") and an *arrival* cue at the destination; each cue fires once per turn (re-arming after you pass it), and off-route situations are suppressed until a reroute lands. A new **"Voice guidance"** switch in the Settings sheet (*Appearance & map*) toggles it — **disabled by default** (opt-in) and persisted across sessions. The decision logic (`NavigationVoiceCues`) is pure and unit-tested, the engine wrapper (`NavigationVoiceGuide`) matches the app locale and uses navigation-guidance audio attributes, and the flag lives in `VoiceGuidancePreferences`. Fully localised across all eight supported languages.
- **"Route hilliness" slider — flatter offline routes on demand** — the offline *Routing Profile* sheet now carries a discrete **Route hilliness** slider with five steps (*Any → Gentle → Flatter → Low climb → Flattest*) that lets you trade a bit of distance for less climbing. Each step maps to an extra uphill penalty (`ElevationPreference.uphillExtraCost`) handed to BRouter as a uniform **`uphill_extra`** profile parameter, which is added on top of every bundled profile's own uphill cost — so *Any* leaves routing unchanged and flatter levels progressively avoid ascents. The choice is persisted (`OfflineRoutingPreferences.getElevationPreference`) and applied to **point-to-point, on-demand and round-trip** routing; changing it while navigating immediately recomputes the active route. Offline (BRouter) only — the online OSRM fallback has no elevation model and ignores it. The `uphill_extra` parameter was added to all five profiles (`trekking`, `fastbike`, `mtb`, `gravel`, `shortest`, `PROFILES_VERSION` 4 → 5) and is parse-checked by `BRouterProfileIntegrityTest`; the level→cost mapping is covered by `ElevationPreferenceTest`. Fully localised across all eight supported languages.

### Fixed
- **No profile starts the route on the sidewalk anymore** — opening navigation on the pavement was possible in two ways, both now closed for **every** bundled profile (`PROFILES_VERSION` 4 → 8): (1) the start-snapping guard (`check_start_way` + `noStartWay=footway,sidewalk`) was only in *trekking*/*gravel*, so *fastbike*/*mtb*/*shortest* could snap the start onto a `footway=sidewalk` — it's now declared in all five; and (2) cycling a `footway=sidewalk` was cheaper than the parallel carriageway in several profiles, so the route hugged the pavement: *trekking*/*shortest* rode a bicycle-allowed sidewalk almost for free, and the **MTB** profile (which heavily penalises paved roads) made even a plain sidewalk cheaper than a residential/tertiary road. A `footway=sidewalk` surcharge now keeps every profile on the carriageway (the MTB surcharge is large enough to beat its high road cost). A regression test (`BRouterNoStartWayProbeTest`) asserts every profile both populates `noStartWays` and prices a sidewalk **above** a tertiary road.
- **Round trips now work with every cycling profile** — generating a *Round trip* with a profile that enables `consider_elevation` / `consider_forest` / `consider_river` (trekking, fastbike, mtb) silently produced no route ("route data incomplete"). With no explicit start heading, BRouter derived one via `getRandomDirectionFromData`, which for those profiles reads area-info data and parses a `dummy.brf` that VeloSpot doesn't bundle. The round-trip generator now always hands BRouter a concrete start direction (a random heading when the rider doesn't pick one), bypassing that path entirely so loops build for every profile (`BRouterEngine.calculateRoundTrip`, regression-guarded by `BRouterRoundTripDirectionTest`).
- **Offline routing no longer fails when the start-U-turn correction can't reroute** — the standstill *second pass* (which re-runs BRouter with a forced forward direction to drop a spurious start hairpin) used to overwrite the valid first-pass route, so if BRouter couldn't satisfy the forced direction (one-way nets, dead-ends) the whole request failed with "route data incomplete". The second pass is now best-effort: its result is only adopted when it actually produces a route, otherwise the good first-pass route is kept (`BRouterEngine.calculateRoute`).
- **Offline routing with "Any" hilliness no longer breaks on some profiles** — selecting *Any* used to send routing down a different BRouter code path (no key-value injection, a different profile-cache key) than the other levels, which left some profiles (e.g. gravel) unable to produce a route on *Any* while every other setting worked. The `uphill_extra` parameter is now always passed as a key-value map — *Any* simply carries a zero penalty — so every hilliness level uses the identical, working path (`BRouterEngine.elevationKeyValues`, guarded by `BRouterProfileIntegrityTest`).

## [v1.0.21] - 2026-06-21

### Added
- **Share a location with other apps** — the detail sheets for a custom map pin, an address search result, a bike parking space and a saved favourite now carry a **Share** action that opens the system share sheet with a universal **OpenStreetMap web link** (plus the resolved name/address as the first line), so you can send a spot to WhatsApp, Telegram, e-mail, etc. Built on a small `LocationSharer` (in `core/share`, symmetric to `ImageSharer`). Fully localised across all eight supported languages.
- **Leaner, cleaner map UI + new ride features** — a focused pass that declutters the map and adds practical navigation tools, all localised across the eight supported languages:
  - **Unified top bar + Settings sheet** — the cramped top-bar dropdown menu (which mixed quick actions and settings) is gone. The map now carries just a search field and a single round **menu button** that opens a tidy **Settings bottom sheet**, grouping everything into clear sections: *Quick actions* (favourites, parked bike, rides, round trip), *Appearance & map* (dark mode, language, map view, layers), *Routing* (offline routing, about) and a *Developer* section (the GPS simulator in debug builds). The menu button is tinted when offline routing is active (`SettingsSheet`, `MapMenuCard`, `MapScreenUiState`).
  - **Slimmer navigation card** — the bulky active-navigation card is now a compact, glanceable **pill** showing distance · ETA and live speed with a round stop button; tapping it expands to reveal the destination name and the route's elevation profile (`MapNavigationOverlay`).
  - **Minimal navigation mode** — while navigating, all map clutter that isn't part of the trip (other parking spots, saved places, search pins) is hidden, leaving just the route, the destination and the live position for a clean, focused view (`MapMarkerRenderer.minimalNavMode`).
  - **Turn-by-turn banner** — a top banner now announces the next turn ("In 120 m — Turn left") with an arrow that rotates to point the way. It's derived purely from the route geometry, so it works for both BRouter offline and OSRM online routes, and animates in/out as a turn approaches (`RouteMatcher.nextTurn`, `NavigationProgress`, `MapTurnBanner`).
  - **Round-trip generator** — a new *Round trip* action generates a circular route that starts and ends at your current position; pick a target distance (5–50 km) and BRouter builds a loop back home (offline routing required). Uses BRouter's native round-trip support (`BRouterEngine.calculateRoundTrip`, `RoutingRepository.getRoundTrip`, `RoundTripSheet`, `NavigationController.startRoundTrip`).
  - **Route elevation profile** — the expanded navigation pill now shows a compact elevation graph of the route (distance vs. terrain height) with total ascent ↑ / descent ↓, drawn from BRouter's per-node elevation data with a plain Canvas (`RouteElevationProfile`).
  - **Cancellable route calculation with progress** — while a route is being computed the loading card now shows a progress bar and a running **elapsed-seconds** counter, plus a **Cancel** button. Cancelling propagates into the BRouter engine, which aborts its search at the next loop check (`RoutingEngine.terminate`) instead of running to completion — handy for long trips you didn't mean to start (`BRouterEngine.runEngine`, `NavigationController.cancelRouteCalculation`, `MapNavigationOverlay`).

- **Offline routing: full Germany/France/Luxembourg download + automatic on-demand tiles** — the offline-routing setup now offers two choices instead of silently grabbing one tile: **"Download my region"** (the single 5°×5° BRouter tile around your current position, ~250 MB) and **"Download all of Germany, France & Luxembourg"** (the curated set of 12 land tiles covering the three supported countries, ~2–2.5 GB), with a resumable per-file progress indicator (`BRouterSegmentManager.COUNTRY_SEGMENTS` / `downloadCountrySegments`, `OfflineRoutingController`, two-button `OfflineRoutingSetupSheet`). On top of that, offline navigation is no longer limited to the pre-downloaded tile: when you route to a destination whose tile is missing, the router now **downloads the needed tile(s) for that route on demand** (`RoutingRepositoryImpl` → `BRouterSegmentManager.ensureSegments`) and routes offline, only falling back to the online OSRM router when the download can't happen (no connectivity). Governed by a new `OfflineRoutingPreferences.isOnDemandDownloadEnabled` flag (default on). Fully localised across all eight supported languages.

### Changed
- **Faster offline route calculation on long trips**: the in-memory segment **node cache** (`RoutingContext.memoryclass`) is no longer left at BRouter's conservative 64 MB default but sized to the device (≈ half the app heap budget, clamped 96–256 MB), so long routes stop thrashing the cache (evict → re-read → re-decode segments from disk); and the standstill **start-U-turn correction** — which recomputes the *entire* route a second time — is now skipped beyond ~30 km, where the start spur is negligible relative to the trip and a full recompute is the dominant cost (`BRouterEngine`).
- **"Download my region" is bound to your actual position** — the single-tile download no longer falls back to a hard-coded default region when there's no GPS fix; it now requires a real location and surfaces `LocationUnavailable` otherwise, so it always fetches the tile you're actually in (`OfflineRoutingController`).
- **BRouter cycling profiles de-prefer pavements and favour quiet streets in town** (`gravel.brf`, `trekking.brf`, `PROFILES_VERSION` 2 → 4) — footways/sidewalks with bike permission are made more expensive and quiet carriageways (residential / living-street / unclassified / service) cheaper, so urban routes stop hugging the pavement next to the road; dedicated cycleways stay preferred for safety.

### Fixed
- **Saved-place pins now actually show their star** — the green saved-place pin (custom pins stored as named favourites) was documented and intended to carry a white star to set it apart from the transient blue custom pin and the red address-search pin, but the star was never drawn. The pin now renders a crisp white five-pointed star on its green body, so saved favourites are instantly recognisable on the map again (`MarkerIconFactory.createSavedPlaceIcon`).
- **Smoother live navigation — no more "kangaroo" hopping** — GPS fixes arrive only every few seconds, so easing the puck/camera straight to each fix made it lurch forward then freeze until the next fix. While on-route, the puck is now **dead-reckoned**: it advances continuously along the route at the rider's measured speed (derived from along-route progress, so it works even when a fix carries no speed) and is gently corrected to the snapped GPS position on every fix (a large drift / reroute hard-resyncs). The result is continuous, fluid motion between fixes (`NavigationManager`). While dead-reckoning, the camera/puck also hugs the route more tightly (a shorter position smoothing constant) so curves aren't visibly cut.
- **Offline routes no longer open with a spurious "make a U-turn"** — BRouter used to connect the start waypoint to the nearest network node, which can sit *behind* the rider, opening the route with an out-and-back hairpin the online router never shows. The rider's live GPS heading is now passed to BRouter as a `startDirection` hint; for a start from a complete standstill (no heading) a lightweight **second pass** detects a start that heads away from the destination and re-runs BRouter with the route's own forward direction — BRouter then drops the spur itself, so the geometry stays real on-road (no path surgery) (`BRouterEngine`).
- **Offline routes start on the carriageway, not the sidewalk** — BRouter was snapping the start/end onto the nearest way, which in town is often a `footway=sidewalk`, opening the route with a pavement + crossing detour before reaching the road. The cycling profiles now declare `noStartWay=footway,sidewalk`, so the start/end snaps to the carriageway like the online router does (and cycling on sidewalks isn't suggested) (`gravel.brf`, `trekking.brf`).
- **Navigation marker/camera heading no longer skewed by sub-metre route stubs** — BRouter occasionally emits a tiny (~0.1 m) first segment, and the camera/marker took its heading from that single segment, pointing slightly off the route at the start. The route heading is now sampled ~15 m **ahead** along the polyline, so a degenerate stub can't skew it; the marker also seeds to the route's forward direction at the very start instead of a stale bearing (`RouteMatcher`, `NavigationManager`).

## [v1.0.20] - 2026-06-21

### Added
- **GitHub community health files** — added the standard community documents to meet the GitHub Community Standards and make the project easier and safer to contribute to: a **Code of Conduct** (`CODE_OF_CONDUCT.md`, Contributor Covenant v2.1), a **Contributing guide** (`CONTRIBUTING.md`, tailored to VeloSpot's `googlePlay`/`fdroid` flavours, JDK 17, BRouter submodule and the real Gradle build/test commands), a **Security policy** (`SECURITY.md`, private vulnerability reporting via GitHub Security Advisories), structured **issue templates** (`.github/ISSUE_TEMPLATE/` — bug report, feature request and a chooser config), a **pull request template** (`.github/PULL_REQUEST_TEMPLATE.md`) and a **funding link** (`.github/FUNDING.yml`, Buy Me a Coffee).
- **Comprehensive ride statistics dashboard in "My rides"** — the *My rides* sheet now leads with a rich, collapsible **Statistics** card that crunches your whole ride history into every metric a data nerd could want, all derived purely from the already-stored rides (no extra storage). It's **collapsed by default** (tap the header to expand) so the ride list stays uncluttered, and groups the numbers into five sections:
  - **Totals** — ride count, total distance, total & moving time, cumulative elevation gain ↑ and loss ↓.
  - **Averages** — Ø distance, Ø duration, Ø speed (distance-weighted across moving time) and Ø climb per ride.
  - **Personal records** (highlighted chips) — top speed, longest ride, longest duration, best Ø speed and biggest single climb.
  - **Activity** — first-ride date, distinct active days, current & longest day streak (consecutive calendar days), plus rides + distance this week and this month.
  - **Fun facts** — CO₂ saved vs. an average car (~120 g/km), calories burned (~30 kcal/km) and your share of a full lap around the Earth (40,075 km).
  - Pure, side-effect-free computation (`computeRideStatistics`) feeding a chip-based, wrapping flow layout (`RideStatisticsSection`). Fully localised across all eight supported languages.
- **Share a recorded ride as a "VeloSpot Wrapped" card** — a new **Share ride** button in the ride detail sheet ("My rides" → tap a ride) opens a preview dialog that renders the ride as a bold, vertical **1080×1350 (4:5) social-media tile**, ready for WhatsApp, Telegram, Instagram and the like. The card shows a **real 2D map cutout** of the route behind a glowing GPS track, the headline distance, the date and the key ride statistics (time, Ø speed, elevation gain, max speed).
  - **Off-screen, deterministic rendering** — the card is drawn directly onto a `Bitmap` with the platform `Canvas` (no Compose lifecycle, no charting dependency) on a background thread (`RideShareCardRenderer`), so it is fully reproducible. The tile has slightly rounded corners.
  - **Map cutout that lines up with the route** — `RideRouteMapSnapshotter` uses MapLibre's `MapSnapshotter` to render the ride's bounding box off-screen (no on-screen `MapView`); track points are projected with the snapshot's own `pixelForLatLng`, so the polyline sits exactly on the streets. OSM attribution is drawn into the panel. If the snapshot can't be produced (offline, error or 8 s timeout), the card falls back to a clean themed-gradient panel, so sharing always works.
  - **Colour theme picker with live preview** — six hand-picked themes (Aurora, Sunset, Forest, Ocean, Berry, Midnight) restyle the gradient, accent and route/marker colours; tapping a swatch re-renders the preview live. The (expensive) map snapshot is fetched once and reused across theme changes. The route uses a contrast halo + a theme-coloured glow over a tinted scrim so it stays legible on any basemap.
  - **Privacy-friendly sharing** — the image is written to the app's private cache and handed to the system share sheet via a `FileProvider` (`ImageSharer`); nothing is uploaded by VeloSpot itself — the image only leaves the device once *you* pick a target app. Fully localised across all eight supported languages.
- **Ride recording keeps running in the background — with a notification, a Quick Settings tile and a home-screen widget** — recording a ride no longer freezes the moment you leave the map screen. The recording lifecycle (the GPS feed, the live stats and the persistence) moved out of the `viewModelScope` into a process-level `@Singleton` **`RideRecordingManager`**, paired with a `location`-typed **foreground service** (`RideRecordingService`) that keeps the process + GPS alive while the app is backgrounded or closed.
  - **Persistent notification** — while recording, an ongoing notification shows the live **time • distance • speed** and offers **Stop & save** and **Discard** actions, so you can finish the ride straight from the shade without reopening the app. Tapping it returns to the map. A dedicated low-importance notification channel is created on Android 8+, and `POST_NOTIFICATIONS` is requested on Android 13+ when a recording starts (the recording still runs if denied — only the notification is hidden).
  - **Quick Settings tile** — a `RideRecordingTileService` tile starts/stops a recording with a single tap from the notification shade, reflecting the active/inactive state with label + subtitle; it bounces you into the app to grant location permission if it's missing.
  - **Home-screen widget** — a `RideRecordingWidget` (`AppWidgetProvider`) with a single start/stop control that shows the live time + distance while recording. Both the tile and the widget share the very same singleton manager as the in-app FAB, the notification and the service, so the recording state stays consistent across every entry point; the manager pushes state changes to them (`AppWidgetManager` refresh broadcast + `TileService.requestListeningState`) even while the app's UI is closed.
  - **GPS stays alive only while needed** — `MapViewModel` no longer tears down location updates on background/`onCleared` while a recording is active (the manager owns the GPS radio then, at high accuracy); when nothing is recording the existing battery-friendly teardown is unchanged. New permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`. Works in **both** the Google-Play (Fused) and F-Droid (`LocationManager`) flavours via the shared `LocationRepository`. Fully localised across all eight supported languages.

### Changed
- **Privacy policy: disclose the ride-share card** — the new share-card feature is now documented across `PRIVACY.md`, `docs/PRIVACY.md` and `docs/privacy.html`: a new section "3.4 Ride Sharing (Share Card)" explains that the card image is generated and cached locally and only leaves the device when *you* pick a target app in the Android share sheet (after which that app's policy applies), and that drawing the map cutout loads OpenFreeMap tiles for the ride's area (the OpenFreeMap row and notes were updated accordingly).
- **Privacy policy: disclose the one-time BRouter offline-routing download** — the policy previously implied BRouter is *entirely* offline, but the **one-time download** of the offline routing data fetches map-segment tiles from `brouter.de` (the requested 5°×5° tile name reveals the rider's approximate region + IP). This connection is now listed in the third-party services table and the BRouter note is clarified across `PRIVACY.md`, `docs/PRIVACY.md` and `docs/privacy.html`; the `INTERNET` permission description was updated accordingly.

### Removed
- **Dead "parking photos" feature** — the parking-photo UI was never functional: the bundled OpenStreetMap dataset always stores `imageUrl = NULL` (the extraction script never populates it), so the `AsyncImage` block in `SelectedSpaceSheet` could never render and **no image request was ever made**. Removed the dead photo UI and the now-unused **Coil** image-loading dependency (`coil-compose`, plus its version-catalog and attribution entries), and stripped the misleading "parking photos" mentions from the README, the store descriptions and the privacy policy. The dormant `imageUrl` column is intentionally kept in the Room schema to avoid a destructive migration / regenerating the bundled databases.

### Fixed
- **Navigation now reliably detects arrival and ends itself — for every destination** — turn-by-turn navigation previously only auto-finished when riding to a *genuine bike parking spot* (via the auto-park path), so navigating to an address-search result, a saved place, a custom map pin or the parked bike would keep running indefinitely even after you'd arrived; you had to stop it by hand. On top of that, arrival detection relied **solely** on the along-route remaining distance and was **suppressed entirely while off-route**, so pulling onto the pavement at the door (a couple of metres of GPS noise) or a BRouter route that stops just short of the destination could leave navigation stuck. Arrival is now handled centrally for **all** destinations (`maybeHandleArrival` in `NavigationController`):
  - **Works off-route** — when the rider is off the route line near the destination, a straight-line (crow-flies) distance from the raw GPS fix to the actual destination coordinate is used as a fallback, independent of the route, so arriving a few metres beside the line still registers; while on-route the precise along-route remaining distance is still used.
  - **Debounced** — two consecutive fixes inside the 25 m arrival radius are required before navigation ends, rejecting a single stray GPS sample that briefly snaps onto the destination.
  - **Ends every navigation** — reaching a real parking spot still auto-parks the bike with the *"arrived — bike parked here"* confirmation; reaching any other destination now ends navigation with a new generic *"you've arrived at your destination"* confirmation. Covered by updated and new unit tests; the new string is localised (English + German).
- **Cyclist marker no longer disappears behind 3D buildings** — in the tilted 3D view the live-location cyclist (and the parking pins / route line) could vanish wherever a building footprint overlapped them on screen. The `fill-extrusion` 3D building layer was being added with `style.addLayer` on **top** of every other layer, and because MapLibre draws symbol/line layers without a depth test against the extrusion, visibility is purely a matter of paint order — so the buildings simply painted over the markers wherever they overlapped (hence "sometimes hidden", only near tall buildings). `ensureBuildingExtrusionLayer` now slots the extrusion **beneath** the lowest of our own overlays (route → track → parking → location → pins) via `addLayerBelow`, so the cyclist avatar and all other overlays stay reliably in the foreground while the volumes still rise above the flat base building fill (`MapStyleLayers.kt`).
- **Routing could put a bike onto trunk roads / motorway feeders (e.g. the A1)** — the bundled BRouter profiles forbade motorways but treated `trunk`/`trunk_link` ways (*Kraftfahrstraßen* — the high-speed feeders and on/off ramps around a motorway like the A1) too cheaply, so on a longer route BRouter could route a bike onto one. All bundled profiles are now hardened (`motorroad=yes` trunks were already forbidden everywhere via the access rules; the motorway itself was already cost 10000 / forbidden):
  - **gravel** & **mtb** — a trunk is only ridden when it explicitly carries bike infrastructure (a cycleway, a cycle route or `bicycle=yes|designated`, cost 20–60); otherwise it is strongly avoided (cost 200) and forbidden outright where cycling is signed off (`bicycle=no|private|dismount`).
  - **trekking** (the default), **fastbike** and **shortest** — trunks where cycling is signed off (`bicycle=no|private|use_sidepath`) are now forbidden, while legally-cyclable trunks keep their existing tuned cost (`shortest` previously treated a trunk like any other road at cost 1).
  - All five edited profiles were validated with BRouter's own `IntegrityCheckProfile` (way + node contexts).
  - Because `BRouterEngine.ensureProfiles()` previously copied a profile only when it was missing, the bundled profiles are now **versioned** (`PROFILES_VERSION`) and re-extracted to internal storage whenever the version changes, so existing installs actually pick up profile fixes.
- **Startup crash on upgrade: `no such table: room_table_modification_log`** — when upgrading over an install that already had the bundled parking database on disk, the app could crash on launch the first time any favourites/rides/places `Flow` was observed. The one-time "isolate favourites" migration opened the new `FavoritesDatabase` through its low-level `openHelper.writableDatabase` and ran a raw `ATTACH DATABASE … INSERT … SELECT`. That bypasses Room's (2.7+) connection-pool initialisation, so Room's internal `room_table_modification_log` invalidation table was never created — and the first `InvalidationTracker` observer then crashed with `SQLiteException: no such table: room_table_modification_log`. The legacy favourites are now read through a **separate read-only `SQLiteDatabase` connection** and re-inserted via the Room **DAO**, so the database is only ever opened on Room's normal path (no `openHelper`/`ATTACH`), which correctly initialises the invalidation tracker. Clean installs were unaffected.
- **Private data no longer backed up to Google Drive (`allowBackup` hardening)** — `android:allowBackup` was `true` with empty backup-rule templates, so Android Auto Backup could upload **recorded GPS ride tracks**, favourites, saved places and settings to the user's Google Drive — undisclosed and at odds with VeloSpot's "all data stays exclusively on your device" promise. Backup is now disabled (`android:allowBackup="false"`), and the `data_extraction_rules.xml` / `backup_rules.xml` are filled with explicit `exclude` rules (covering the `database`, `sharedpref` and `file` domains — including Room's `-wal`/`-shm` sidecar files) as defense-in-depth should backup ever be re-enabled. No third party receives any user data. (#86)
- **Ride-recording timer now ticks continuously** — the elapsed-time counter on the live recording card was derived from the *last GPS fix's* timestamp, so it only advanced when a new fix arrived (it would freeze at a red light or during a brief GPS dropout). The elapsed time is now wall-clock based (`RideTracker.currentStats(now)`) and a 1 Hz ticker in `RideTrackingController` republishes the live stats every second, so the timer counts up smoothly and honestly regardless of the GPS cadence.
- **No accidental pins while navigating or recording** — tapping an empty spot on the map during turn-by-turn navigation or an active ride recording no longer drops a custom pin (which also triggered reverse-geocoding and a camera jump mid-trip). `MapViewModel.onMapTapped` now ignores empty-map taps while a follow session is active; tapping existing parking spots / saved places still works.

## [v1.0.19] - 2026-06-20

### Added
- **Cyclist avatar as the live-location marker** — the plain blue location dot (and the green navigation heading-arrow puck) is replaced by a full-colour **2D cyclist sprite** shown both on the idle map and during turn-by-turn navigation. It's a clean 3rd-person / top-down rider (helmet, jersey shoulders, arms reaching to the handlebar, frame and two wheels with rims, legs on the pedals) drawn with a soft contact shadow and a thin white keyline so it pops on any basemap (new `R.drawable.ic_cyclist_avatar`, reworked `createLocationMarkerIcon` with a stamped outline). The sprite is rendered as an **upright billboard** (`iconRotationAlignment` / `iconPitchAlignment = viewport`) so the tilted 3D navigation map never flattens or squishes it; since the navigation camera keeps the heading pointing "up", the rider naturally appears from behind for a true 3rd-person feel. The now-unused `createNavigationArrowIcon` arrow puck was removed.
- **Ride tracking — record your ride as a timeline ("My rides")** — riders can now record a ride and review it afterwards with full statistics:
  - **One-tap recording** from a dedicated red record/stop FAB stacked above the *My location* button (keeps the already-busy menu uncluttered). While recording, a compact live-stats card at the top of the map shows the running **time, distance and current speed**, with **Stop** and **Discard** actions, and the travelled track is drawn live on the map as a coloured polyline (new `velospot-track` source/layer).
  - **Automatic recording during navigation** — starting turn-by-turn navigation auto-starts a recording for the whole trip and saves it when navigation ends (or on auto-park arrival); a manually-started recording is never interrupted by navigation.
  - **Persistent history** in a dedicated, isolated Room database (`RidesDatabase` / `recorded_rides`, independent of the parking and saved-places stores) — a new *"My rides"* menu entry opens a timeline list of past rides (date, distance, duration, average speed).
  - **Ride detail with statistics + speed timeline** — tapping a ride redraws its track on the map and opens a sheet with moving time, max speed, elevation gain/loss and a Canvas-drawn **speed-over-time chart**, plus a delete action.
  - **Elevation support** — elevation gain/loss now comes from **BRouter's accurate terrain data** (the SRTM elevation baked into its `.rd5` segment files, read per route node — `RoutePoint.elevationMeters`) whenever a ride is recorded while navigating offline; the rider's live position is snapped to the route and its terrain elevation is fed to the tracker instead of the very noisy raw GPS altitude. For manual rides (or the online OSRM fallback, which carries no elevation) it falls back to GPS altitude, now low-pass filtered with a 3 m dead-band so a parked bike no longer racks up phantom metres (`GeoCoordinate.altitudeMeters`, pure unit-tested `RideTracker`). GPS altitude is requested whenever navigation **or** a recording is active. Fully localised across all eight supported languages and covered by new `RideTracker` unit tests.
- **Follow camera you can break and re-lock — for navigation *and* recording** — the map now stays centred on the live position not only during turn-by-turn navigation but also while **recording a ride**. In both modes you can freely **pan/zoom the map by hand** mid-trip: a touch gesture unlocks the follow camera (the heading arrow / location keeps tracking and route progress keeps updating), and a dedicated **re-centre button appears** on the map (an extended FAB stacked clear of the location/record buttons) that snaps the camera back onto you and resumes following until you pan again. The button disappears and the lock is dropped automatically once neither navigation nor a recording is active. Navigation's per-frame 3D camera glides smoothly back from wherever you left the map (`MapViewModel.isFollowingLocation`, `NavigationManager.setFollowing`, gesture detection via `REASON_API_GESTURE`). Fully localised across all eight supported languages.

### Changed
- **Faster, interruptible navigation start-up tilt-in** — when navigation begins, the camera's "tilt-in" into the 3D view eases in noticeably **faster** (zoom/pitch time constants scaled by 0.28) and **snaps the last imperceptible bit** so it finishes crisply instead of lingering on the exponential tail. Crucially the intro is now **interruptible**: a pan/zoom touch is detected directly on the map and **instantly** cancels the intro and breaks follow, so the map reacts to your gesture without any wait (no more hard input lock). Follow resumes via the re-centre button (`NavigationManager` intro phase + direct gesture handling).
- **Ride tracking — GPS drift filtering + moving-average smoothing** — recorded tracks no longer show the long "spike" lines and implausible peak speeds (e.g. 95 km/h on a leisurely ride) that GPS multipath produces in urban canyons / under 3D building shadow. Each fix now carries its horizontal accuracy (`GeoCoordinate.accuracyMeters` / `TrackPoint.accuracyMeters`, read from `Location.accuracy` in both the Google-Play and F-Droid location sources) and the pure `RideTracker` **rejects a fix outright** when its accuracy is worse than 30 m or when the implied segment speed exceeds a plausible bike bound (lowered from ~108 to ~90 km/h). Rejected fixes are no longer appended to the track, counted towards distance/max-speed, or drawn on the live polyline (the `RideTrackingController` now mirrors only accepted points onto the map). Accepted positions are additionally passed through a small **moving-average window** (3 fixes) so the residual side-to-side jitter is smoothed out, while distance/speed/moving-time are still measured on the **raw** fixes so the totals stay accurate. Covered by new `RideTracker` unit tests.
- **Favourites isolated in their own database (no more accidental wipes)** — user favourites used to share the asset-seeded `BikeParkingDatabase`, which is configured with `fallbackToDestructiveMigration`; any future parking-schema bump without an explicit migration would have silently dropped the favourites along with the parking data. Favourites now live in a dedicated, isolated `FavoritesDatabase` (mirroring `SavedPlacesDatabase` / `RidesDatabase`) that a parking-data migration can never touch. Existing favourites are copied over once from the legacy table on first launch (`ATTACH … INSERT OR IGNORE … SELECT`, guarded by a one-time flag); the legacy parking-database schema is left byte-for-byte unchanged so the bundled SQLite asset still validates.
- **Per-API HTTP clients with appropriate timeouts** — the single shared `OkHttpClient` was split into dedicated, `@Named` clients in `NetworkModule`: a **Nominatim** client (carrying the new rate limiter), a **segments** client with a 5-minute read timeout for the 100 MB+ BRouter offline-routing downloads (so a slow-but-progressing download is no longer aborted at 30 s), and the default client for OSRM/everything else.
- **OSRM DTOs use Moshi code-gen adapters** — `OsrmRouteResponseDto` / `OsrmRouteDto` / `OsrmGeometryDto` now carry `@JsonClass(generateAdapter = true)`, matching the Nominatim DTOs (faster parsing, ProGuard-safe, no reflection fallback).
- **Single source of truth for the OSRM host** — the OSRM bicycle URL is no longer duplicated; `OsrmApi.getBikeRoute` takes a relative path resolved against the one base URL configured in `NetworkModule`, removing the constant-drift risk between `NetworkModule` and `RoutingRepositoryImpl`.
- **Faster ride-track drawing during recording** — the live ride polyline used to be rebuilt from scratch on every GPS fix (re-mapping the entire, ever-growing track — O(n²) work and N fresh `RoutePoint` allocations per ride), which churned the GC and triggered redundant recompositions on long rides. Since each fix appends exactly one point, the track now grows by a single incremental append. The route-elevation lookup was likewise changed from a full route rescan per fix to a cursor that resumes from the last match (amortised ~O(1)), so accurate BRouter terrain elevation no longer costs an O(route) scan on every fix.
- **Smoother navigation on long routes — skip redundant route-line rebuilds** — during live navigation the travelled/remaining route split (`NavigationManager.renderRouteSplit`) was rebuilt from scratch on **every** GPS fix (~1 Hz), reconstructing the full polyline (O(route)) and re-uploading it to the GPU even when the rider had barely moved within the same segment — a source of frame drops on long routes and lower-end devices. The rebuild is now skipped while the rider stays on the same route segment and the snapped position has moved less than 12 m (the imperceptible shift of the single split-boundary point); the smoothly-eased location puck, redrawn every frame, still carries the live motion. Forced renders on route start and after a style reload keep the line correct.
- **`MapViewModel` split into focused controllers (Single Responsibility)** — the ~1170-line map view-model that mixed address search, offline routing, ride tracking, saved places, the parked bike and live navigation was decomposed into six small, independently testable controllers (`AddressSearchController`, `OfflineRoutingController`, `RideTrackingController`, `SavedPlacesController`, `ParkedBikeController`, `NavigationController`), shrinking the view-model to ~650 lines. Each owns its own state; the view-model now orchestrates and re-exposes their flows, and cross-feature effects (camera, selection reset, location-accuracy, toasts, geocoding, route calculation, auto-park-on-arrival, off-route reroute and the debug GPS simulator) are passed as callbacks. Pure internal refactor — the public view-model API and all exposed UI state are unchanged.

### Removed
- **Dead Overpass live-fetch code** — the unused `OverpassApi`, `OverpassDto` and `OverpassMapper` (leftovers from the pre-bundled-database era), plus the never-called `writeSpaces`/`readSpaces`/`lastSyncEpochMs` cache methods and the now-orphaned `toEntity`/`toEntities` mappers. Parking data is served read-only from the bundled SQLite asset.

### Fixed
- **Cyclist avatar no longer disappears when toggling dark mode mid-navigation** — switching the theme reloads the map style, which wipes all custom sources/layers/images. During navigation the normal renderer deliberately skips re-creating `SOURCE_LOCATION` (`suppressLocationDot`, since `NavigationManager` owns the puck), and `writePuck` only updated an **already-existing** source — so after a reload the rider vanished until navigation ended. `NavigationManager.writePuck` now **upserts** (create-or-update) the location source, so the avatar is redrawn immediately after a style reload.
- **Nominatim rate limiting (OSM usage policy)** — a new `NominatimRateLimitInterceptor` on the dedicated Nominatim client enforces the required ≤ 1 request/second, so rapid user actions (e.g. tapping several parking pins to resolve addresses) can no longer burst past the limit and risk an IP ban.
- **OSRM HTTP errors handled gracefully** — `OsrmApi.getBikeRoute` now returns `Response<…>`; the fallback routing surfaces 4xx/5xx as a `RoutingFailedException` instead of throwing a raw exception.

## [v1.0.18] - 2026-06-19

### Added
- **More countries — France 🇫🇷 and Luxembourg 🇱🇺 bike-parking coverage** — the bundled OSM parking dataset is no longer Germany-only. Two additional pre-built SQLite assets (`app/src/main/assets/bike_parking_france.db`, `bike_parking_luxembourg.db`, generated by the same `scripts/extract_osm_parking.py`) are now merged into the single on-device database, so the map's bounding-box queries transparently return parking spots in these countries too. Implemented as a data-only Room migration (`BikeParkingDatabase` v3 → v4) that bulk-imports the extra datasets into the Germany-seeded `bike_parking_spaces` table on first open / app upgrade — preserving existing favorites and Germany data (`INSERT OR IGNORE`, OSM element IDs are globally unique).
- **Multi-country address search with home-country bias** — the Nominatim forward-geocoding search now covers all bundled countries (`countrycodes=de,fr,lu`) instead of Germany only. When the user's location is known, results are biased toward their surroundings via a `viewbox` (≈ 110 km half-span) with `bounded=0`, so matches in the country the user is currently in rank first without excluding the other countries (`NominatimApi.search`, `NominatimGeocoder.searchAddress`, `MapViewModel.onSearchQueryChanged`).
- **About screen** — a new *"About"* entry in the menu opens a sheet showing the app name, a link to the website (https://velospot.app), the per-country dataset status (Germany 08.08.2026, France & Luxembourg 18.06.2026), a link to the privacy policy (https://velospot.app/privacy) and a *"Buy me a coffee"* support link (https://buymeacoffee.com/velospot, opened externally in the browser — no in-app billing, compliant with both Play Store and F-Droid). Fully localised across all eight supported languages (`AboutSheet`).
- **Keep the screen awake during navigation** — while a route is being navigated the display no longer dims or locks; the `keepScreenOn` flag is set on the map view for the duration of active navigation and cleared automatically when navigation ends (`MainMapScreen`).
- **"Where did I park my bike?" — remember your parked-bike location** — a new feature lets riders save exactly where they left their bike and find it again later:
  - **Park your bike** from the map menu (*"Park bike here"* drops the marker at your current GPS position) or from any tapped custom pin (a *"Park bike here"* action in the pin sheet), with a confirmation toast.
  - **Persistent amber bike marker** — a distinctive amber pin carrying a white bike glyph (`createParkedBikeIcon()`, new `velospot-parked-bike` source/layer/image) stays on the map across app restarts until you collect the bike, clearly distinct from the green saved-place star, blue custom pin and red search pin.
  - **Find-my-bike sheet** — tapping the marker (or the menu entry, which then reads *"My parked bike"*) opens a sheet showing how long ago you parked, the reverse-geocoded address, and the live distance from your current position, with **Navigate to my bike** (full in-app routing) and **I picked up my bike** (clears the marker) actions.
  - **Lightweight, isolated persistence** — exactly one parked bike is stored at a time in its own `SharedPreferences`-backed store, exposed reactively (`ParkedBikeRepository` + impl, new domain model `ParkedBike`, Hilt provider and `MapViewModel` wiring: `parkBikeAtCurrentLocation`, `parkBikeAt`, `showParkedBike`, `navigateToParkedBike`, `pickUpBike`). Fully localised across all eight supported languages and covered by new `MapViewModel` unit tests.
  - **Auto-park on arrival** — when you navigate to a genuine bike parking spot, reaching it is detected automatically (the live route progress drops below a 25 m arrival radius) and the bike is parked at the destination without any extra tap, ending navigation and dropping the persistent marker with an *"arrived — bike parked here"* confirmation (`maybeAutoParkOnArrival` in `MapViewModel`, reusing the existing `NavigationManager` route-progress tracking; only real map parking spots auto-park — synthetic destinations like custom pins, address-search results, saved places and the parked bike itself never do). Covered by two new unit tests.
  - **Bigger, more legible parked-bike pin** — the amber marker icon was scaled up (~18 %) so it reads clearly at a glance, and the parked-bike detail sheet's action buttons now match the standard button height used across the saved-place and custom-pin sheets (the bike-rack detail sheet's previously taller buttons were aligned to the same default).
  - **No more pin overlap at the parked spot** — when the bike is parked on a real parking spot, that spot's marker is now hidden so only the single amber parked-bike pin shows (the spot reappears once the bike is picked up), and the parked-bike pin takes click priority during hit-testing — so tapping it always opens the *"my parked bike"* sheet (with parked-ago time, distance, navigate and *"I picked up my bike"*) instead of falling through to the underlying parking-spot sheet. Implemented via a location match in `buildParkingFeatures` (`isParkedAt`, ~12 m radius) and a reordered `queryRenderedFeatures` chain in `MapInitializer`.
  - **Consistent dismiss action on the parking-spot sheet** — the bike parking-spot detail sheet (`SelectedSpaceSheet`) now offers a bottom *"Remove pin"* text button that closes the sheet, mirroring the custom-pin and address-search sheets so dismissing a marker's detail view is consistent across the whole map.
- **Marker clustering for the parking layer — major map performance boost** — at city-level zoom the ~100 000 bike-parking markers are now aggregated into native MapLibre clusters instead of being drawn as thousands of overlapping individual symbols. The parking `GeoJsonSource` is created with clustering enabled (`clusterMaxZoom = 13`, `clusterRadius = 60`); the existing icon layer is filtered to non-clustered points (`!has("point_count")`), and two new layers render the cluster bubble (`CircleLayer` with a step-scaled radius) and its count label (`SymbolLayer`, `point_count_abbreviated`, "Noto Sans Bold"). Tapping a cluster animates the camera to the source's `getClusterExpansionZoom`, so it smoothly breaks apart. The currently selected spot and the active navigation destination are rendered on a dedicated **non-clustered highlight layer** (`velospot-parking-highlight-*`) so they always stay visible on top, never disappearing into a cluster. This drastically reduces the number of rendered symbols when panning/zooming dense areas. Implemented in `MapStyleLayers.kt`, `MapMarkerRenderer.kt` (new `ClusterRenderStyle`, split bulk/highlight feature building) and the click handling in `MapInitializer.kt`.
- **Live 3D turn-by-turn navigation** — navigation is now a real, mitlaufende 3D experience similar to Google Maps, driven by a new self-contained `NavigationManager` and a pure, unit-tested `core/navigation` package:
  - **3D follow camera** — once navigation starts the camera centres on the live GPS position with a fixed 60° pitch, a speed/turn-dependent zoom (closer at standstill / before turns, further out while cruising) and a bearing that smoothly follows the direction of travel. A `Choreographer` frame loop interpolates position, bearing, zoom and tilt every frame (frame-rate-independent exponential smoothing) so the motion stays ruckelfrei despite the ~3 s GPS cadence.
  - **Map matching (snap-to-route)** — each raw GPS fix is snapped onto the active BRouter polyline (`RouteMatcher`) so the heading arrow rides the road instead of jittering beside it; the location puck is a rotating navigation arrow (`IMG_LOCATION_NAV`) aligned to the live heading.
  - **Live route progress + ETA** — the navigation card now shows the dynamically shrinking remaining distance and a remaining-time estimate (ETA), recomputed on every fix; the already-travelled part of the route is greyed out while the remaining part stays in the theme colour (split `velospot-route` / `velospot-route-traveled` line layers).
  - **Off-route detection & auto-reroute** — straying more than ~30 m from the route for several consecutive fixes triggers a silent BRouter recalculation from the current position to the destination (throttled, self re-arming), with an "off route – recalculating…" hint in the overlay.
  - **3D buildings** — a `fill-extrusion` layer pulls the OpenMapTiles building footprints into 3D (using `render_height` / `render_min_height`); enabled during navigation and for the 3D resting view.
  - **GPS heading + speed** — `GeoCoordinate` now carries optional `bearing` + `speedMetersPerSecond`, populated by both flavor `LocationRepositoryImpl`s, feeding the heading arrow and the speed-dependent zoom.
- **2D / 3D map view switch** — a new "Map view" entry in the menu opens a sheet with a segmented 2D/3D selector (animated preview tiles). The choice is persisted (`NavigationModePreferences`) and applied live to the **resting** map (flat north-up vs. 45° tilt + 3D buildings); after navigation ends the map returns to the saved perspective. Active navigation always uses the full 3D camera regardless of the setting.
- **GPS route simulator (debug)** — a debug-only "Simulate route" menu entry drives a synthetic GPS track along the active BRouter route (with bearing + speed, snap/off-route compatible), so the whole live-navigation pipeline can be tested from the couch without moving. Backed by a unit-tested `RouteSimulator`; real GPS updates are ignored while simulating.

### Fixed
- **Crash on first open with the multi-country database** — bumping the database to v4 made Room run its full post-migration `TableInfo` validation, which then failed because the `idx_parking_lat_lon` index present in the bundled assets was not declared on `BikeParkingSpaceEntity` (`Migration didn't properly handle: bike_parking_spaces`). The index is now declared on the entity so the expected schema matches the seeded database (and it speeds up the bounding-box queries).
- **Parking pins no longer get stuck at the wrong size** — the zoom-bucket-scaled parking marker icons (`IMG_NORMAL` … `IMG_MUTED_SELECTED`) were only registered on the style **once** (`registerIcons` guarded each `addImage` with `getImage(id) == null`), so zooming never updated their on-map pixel size — they stayed frozen at the size of the zoom level they were first registered at and only refreshed on a full style reload (e.g. a dark-mode toggle). That made the pins occasionally appear too large/small for the current zoom. The zoom-dependent icons are now re-registered in place on every icon-set change (matching the location dot), so they always match the current zoom level.
- **Address-search result now shows the full custom-pin sheet** — selecting an address from the search dropdown previously opened a reduced sheet that only offered "Navigate here". It now reuses the exact same card as a tapped custom pin, so a searched location also offers **Save as favourite** (with the naming dialog → persistent saved place) and **Remove pin** alongside navigation. The `CustomMapPinSheet` header/subtitle are now parameterised (the search variant shows the "Address" title without the tap-to-move hint); `MapViewModel` gains `saveSearchPinAsFavorite()`, and the now-redundant `SearchPinSheet` was removed.

---

## [v1.0.17] — 2026-06-16

### Fixed
- **F-Droid scanner: remove the bundled BRouter `brouter-routing-app` module** — the pinned `brouter-upstream` submodule now points to a VeloSpot fork of `abrensch/brouter` (BRouter `v1.7.9`) with the entire `brouter-routing-app` Android module deleted. That module bundled binary asset blobs (the `segments4` / `modes` ZIP archives), which F-Droid's repository scanner flagged as non-free/unexpected binaries. VeloSpot only compiles the on-device routing modules (`btools.router`, `.mapaccess`, `.util`, `.codec`, `.expressions`) from source and never builds the routing app, so the module — together with its now-dead references in `settings.gradle` and `brouter-server/build.gradle` — was removed at the source. This complements the earlier removal of the GitHub Packages publishing block, so the checked-out submodule tree no longer contains the GitHub Packages Maven URL or the routing-app binary archives. The `.gitmodules` branch/URL and the submodule pointer are updated accordingly.

---

## [v1.0.16] — 2026-06-16

### Changed
- **Remove unused GitHub Packages publishing from bundled BRouter** — the pinned `brouter-upstream` submodule now points to a VeloSpot fork of `abrensch/brouter` (BRouter `v1.7.9`) with the `publishing { ... }` block deleted from `buildSrc/src/main/groovy/brouter.library-conventions.gradle`. That block declared a GitHub Packages Maven repository via an unresolved `System.env.REPO` URL, which F-Droid's repository scanner flagged as an *unknown maven repo*. It is only used to publish the library to GitHub Packages and is not needed to build the app, so it (and the now-unused `maven-publish` plugin) was removed at the source instead of being hidden behind a `scanignore`. The fork is source-identical to upstream `v1.7.9` apart from this buildSrc-only change; the `.gitmodules` URL/branch and the submodule pointer are updated accordingly.
- **Battery: power-aware location strategy** — the app no longer keeps the GPS running at full `HIGH_ACCURACY` every 5 s for the whole session. While the user is just browsing the map it now uses a battery-friendly **balanced-power mode** (15 s interval, 20 m minimum displacement, `PRIORITY_BALANCED_POWER_ACCURACY` on Google Play / relaxed `LocationManager` cadence on F-Droid); precise, frequent GPS fixes (3 s / 5 m, `PRIORITY_HIGH_ACCURACY`) are requested **only during active turn-by-turn navigation** and dropped again when navigation ends. In addition, location updates are now fully **stopped when the app goes to the background** and re-armed on return, so the GPS radio no longer drains the battery while the app is not visible. Implemented across the shared `LocationRepository` (new `startLocationUpdates(highAccuracy)`), both flavor `LocationRepositoryImpl`s, and `MapViewModel`/`MainMapScreen` lifecycle wiring.
- **Release workflow: validate the new changelog-promotion prompt** — verifies that the release pipeline correctly promotes the `## [Unreleased]` section to a dated `## [v1.0.15]` heading and re-creates an empty `## [Unreleased]` section above it.

### Fixed
- **Reproducible build: disable embedded VCS info** — the Android Gradle Plugin embeds the current Git commit hash into `META-INF/version-control-info.textproto` at build time. This was the only file that still differed between F-Droid's rebuild and the signed release APK, breaking byte-for-byte reproducibility. Added `vcsInfo { include = false }` to the `release` build type in `app/build.gradle.kts`, so the file is no longer written into the APK and the F-Droid build is now fully reproducible.

---

## [v1.0.14] — 2026-06-16

### Changed
- **BRouter is now built from source — fully reproducible, no binary blob, no prebuild** — the bundled `app/libs/brouter-1.7.9-all.jar` is removed. BRouter is now compiled from source by a new `:brouter` Gradle module that builds the on-device routing modules (`btools.router`, `.mapaccess`, `.util`, `.codec`, `.expressions`) from the pinned `brouter-upstream` git submodule (BRouter `v1.7.9`). A plain `./gradlew :app:assembleFdroidRelease` now resolves BRouter without any preparation scripts. This eliminates the F-Droid custom `prebuild` step and `BRouter` `srclib`; the `fdroiddata` recipe simplifies to `submodules: true` + `gradle: [fdroid]`. The ProGuard rules `-dontwarn java.awt.**` / `-dontwarn javax.imageio.**` (previously appended by the prebuild) are now committed in `app/proguard-rules.pro`.
- **Reproducible release setup** — release signing now reads from a gitignored `keystore.properties` or CI env vars (`KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`); secrets are never committed. The release workflow checks out submodules and publishes the F-Droid APK under the stable name `VeloSpot-v<versionName>.apk` (matching the fdroiddata `Binaries` URL). See [`docs/RELEASING.md`](docs/RELEASING.md).

---

## [v1.0.13] — 2026-06-16

### Fixed
- **F-Droid: strip the AGP "Dependency metadata" signing block from the APK** — the Android Gradle Plugin embeds a Google-signed *Dependency metadata* block into the release APK's signing section. F-Droid's `scanner` / *Check App* job rejects this opaque, non-reproducible block (`CRITICAL: Found extra signing block 'Dependency metadata'`), failing the pipeline. Added a `dependenciesInfo { includeInApk = false; includeInBundle = true }` block to `app/build.gradle.kts` so the block is no longer written into the APK that F-Droid scans, while it is kept in the AAB for Google Play's upload-time processing.

---

## [v1.0.12] — 2026-06-16

### Changed
- **BRouter upgraded 1.6.3 → 1.7.9** — the bundled offline-routing engine (`app/libs/brouter-1.7.9-all.jar`) is updated to BRouter 1.7.9. The JAR is a **slimmed archive** rebuilt from the official `brouter-1.7.9.zip` release that contains only the on-device routing modules (`btools.router`, `.mapaccess`, `.util`, `.codec`, `.expressions`); the server/map-creation modules and their protobuf/osmosis dependencies (~2 MB of the upstream `-all.jar`) are intentionally stripped, since they are only used to build map data, not to route on-device. `BRouterEngine.kt` needed **no code change**: the used API (`RoutingEngine(String, String, File, List, RoutingContext)`, `doRun`, `getFoundTrack`, `RoutingContext.localFunction`, `OsmNodeNamed`, and the reflection-read private `OsmPathElement.ilat/ilon` fields) is fully present in 1.7.9. The bundled routing profiles and `lookups.dat` in `app/src/main/assets/brouter/profiles/` (`trekking`, `fastbike`, `gravel`, `mtb`, `shortest`) are refreshed to the 1.7.9 versions from the same release. F-Droid's `srclib` (`BRouter@1.6.3` → `BRouter@1.7.9`) and the `prebuild` step in the `fdroiddata` recipe (`metadata/de.velospot.yml` + `srclibs/BRouter.yml`) must be bumped to 1.7.9 accordingly.
- **Release workflow auto-promotes the changelog** — the release workflow now turns the `## [Unreleased]` section into a dated `## [vX.Y.Z] — YYYY-MM-DD` heading and inserts a fresh, empty `## [Unreleased]` section above it. The change is committed and a sync PR is opened back to `main`. The step is idempotent: if a heading for the released version already exists (e.g. promoted manually), it does nothing.

---

## [v1.0.11] — 2026-06-16

### Added
- **Map layer toggles** — a new "Layers" entry in the menu opens a sheet where the user can show/hide three pin categories independently: **parking spots**, **favourites** and **saved places**. Each layer is a tappable card with a coloured pin badge and a switch (active cards tinted in the layer's accent colour). All layers are visible by default; changes are persisted across restarts via `LayerVisibilityPreferences`. The selected spot and the active navigation destination always stay visible regardless of the toggles. Filtering happens in `buildParkingFeatures` (parking vs. favourites by favourite-state) and on the saved-places source.
- **Dark map tiles** — the map now switches to a dark vector-tile style when dark mode is enabled. The dark style (`app/src/main/assets/map_style_dark.json`) reuses the very same OpenFreeMap vector tiles (OpenMapTiles schema) as the light `liberty` style, so no extra tile provider, API key or tracking dependency is introduced. Toggling dark mode in the menu re-loads the style live without resetting the camera.
- **Theme-aware map markers** — `defaultMarkerStyleConfig(isDarkTheme)` now returns a brighter, higher-contrast marker/route palette for the dark style (normal pin `#3B82F6` instead of the dark navy `#0A2A66`, which previously blended into the dark water colour; favourite `#F44336`, selected `#FFB300`, route `#42A5F5`). The light style keeps its original colours unchanged. Marker icons are regenerated on theme switch.
- **One-time startup centering** — on launch the map now centres on the user's current position once, as soon as the first GPS fix arrives (`hasCenteredOnStartup` guard in `MapViewModel.observeUserLocation()`). Subsequent location updates no longer move the camera, so the user stays in control after the initial centering.
- **Save custom pins as named favourites** — a manually placed map pin can now be saved as a named favourite via a new "Save as favourite" action in the custom-pin sheet (with a naming dialog). Saved places:
  - persist in a **dedicated, isolated Room database** (`SavedPlacesDatabase` / `saved_places` table) so they are completely independent of the asset-seeded parking database and its destructive migrations — a schema change to one can never wipe the other
  - render as **persistent green star markers** on the map (`createSavedPlaceIcon()`, new `velospot-saved-pin` source/layer); tapping one opens a sheet to navigate there or remove it
  - appear in a **"Saved places" section** in the favourites sheet with navigate and delete actions
  - new domain model `SavedPlace`, `SavedPlacesRepository` (+ Room-backed impl), DAO, Hilt providers, and `MapViewModel` wiring (`saveCustomPinAsFavorite`, `selectSavedPlace`, `navigateToSavedPlace`, `removeSavedPlace`)
  - fully localised in all 8 supported languages

### Changed
- **`MainMapScreen` map initialisation refactored** — one-time map setup (compass, initial camera, viewport/zoom/click listeners) is now separated from style loading. Style loading runs in its own `LaunchedEffect(maplibreMap, isDarkTheme)` and re-applies the correct light/dark style whenever the theme changes; a `styleVersion` counter re-runs the marker rendering effect so all custom sources/layers/images are rebuilt after a style reload
- **Favourites counter includes saved places** — the menu favourites count now sums bike-parking favourites **and** saved custom places (`favorites.size + savedPlaces.size`)
- **Explicit "Save as favourite" button on parking spots** — the parking detail sheet (`SelectedSpaceSheet`) now shows a full-width "Save as favourite" / "Remove from favourites" button (matching the custom-pin sheet) instead of the small corner heart toggle
- **Favourites list uses a delete icon** — the remove action in the favourites list now uses a trash/`Delete` icon (consistent with the saved-places cards) instead of the filled heart
- **"Show spot" for saved places** — saved-place cards in the favourites list now offer a "Show spot" action (alongside "Start navigation") that centres the map on the place and opens its detail sheet
- **Modernised adaptive app icon** — redesigned launcher icon for a fresh 2026 look: a vibrant green gradient background with a bold white location pin and brand-green bicycle; foreground, background and Android-13 themed (monochrome) layers all updated

### Refactored
- **`feature/map/presentation` split into sub-packages** for clarity: rendering infrastructure moved to `presentation/markers/` (`MapMarkerRenderer`, `MarkerIconFactory`, `MapStyleLayers`, `MarkerStyleConfig`) and all modal sheets/dialogs moved to `presentation/sheets/` (`MapBottomSheets`, `FavoritesSheet`, `LayersSheet`, `SavedPlaceSheet`, `SelectedSpaceSheet`, `CustomMapPinSheet`, `SearchPinSheet`, `LanguageSheet`, `OfflineRoutingSheet`, `SavePlaceDialog`). The root package keeps the core screen, view model, UI state, overlays, shared components and lifecycle helpers. The shared `BikeParkingType.label` helper moved to `MapUiActionComponents`.
- **`MapMarkerRenderer.kt` split by concern** into three files: `MarkerIconFactory.kt` (pure pin/icon drawing), `MapStyleLayers.kt` (MapLibre source/layer/image IDs + idempotent registration helpers) and `MapMarkerRenderer.kt` (state→GeoJSON orchestration only)
- **`MainMapScreen.kt` slimmed down** by extracting `MapBottomSheets.kt` (all modal sheets/dialogs, collecting their own state from the ViewModel) and `MapInitializer.kt` (the imperative one-time MapLibre setup — compass, initial camera, viewport/zoom/click listeners — plus the map style URLs); behaviour is unchanged

### Docs
- **README, GitHub Pages site and `docs/` updated** with the new features (dark map tiles, map layer toggles, saved places) and two new screenshots (`dark-mode.jpeg`, `layers.jpeg`); the README gains a screenshots gallery

### Fixed
- **Bike marker glyph washed out on lighter pins** — the bike symbol inside the parking markers was drawn as a colour **emoji** (`🚲`), whose tint could not be controlled, so it rendered greyish/blurry on the brighter dark-mode pins. Replaced with a crisp white vector drawable (`ic_bike_marker.xml`, Material `directions_bike`) tinted via `DrawableCompat`, giving a sharp, high-contrast glyph on every pin colour in both light and dark mode (including the dimmed navigation markers)

---

## [v1.0.10] — 2026-06-15

### Added
- **CI: Gradle Wrapper Validation workflow** — verifies the `gradle-wrapper.jar` checksum against official Gradle releases on every push/PR (supply-chain hardening)
- **CI: CodeQL security scanning** — workflow added but currently **disabled for automatic runs** (manual dispatch only): CodeQL's Kotlin extractor does not yet support Kotlin 2.4.x (rejects ≥ 2.3.30 with a build, extracts no Kotlin sources with `build-mode: none`). The build-based config is kept ready to re-enable once CodeQL supports Kotlin 2.4
- **CI: Android Lint workflow** — runs `lintFdroidDebug` on every push/PR and uploads the HTML/XML report as a build artifact
- **CI: Dependency Review workflow** — fails PRs that introduce dependencies with known high-severity vulnerabilities or disallowed licenses
- **README: live pipeline status badges** — CI, Release workflow and Android Lint status badges

### Changed
- **F-Droid: BRouter is now built from source** — the pre-built `app/libs/brouter-1.6.3-all.jar` is no longer shipped as a binary blob in the F-Droid build; instead it is rebuilt from the official BRouter source code via an srclib (`BRouter@1.6.3`) and a `prebuild` step configured in the `fdroiddata` recipe (`metadata/de.velospot.yml` + `srclibs/BRouter.yml`). Local and `googlePlay` builds keep using the committed JAR.

### Fixed
- **Missing translations completed** — added the `custom_pin_*` strings to all 6 non-default locales (es, fr, it, lb, nl, pt) and the `error_*` strings to Italian, resolving the 10 `MissingTranslation` lint errors that broke the Android Lint CI job
- **MapView leak** — `rememberMapViewWithLifecycle` now calls `onDestroy()` in `onDispose`, releasing the native renderer, GL context and map listeners even when the composable leaves composition before the Activity is destroyed
- **Location callback** — the `googlePlay` `LocationRepositoryImpl` resets `locationCallback` to `null` after `removeLocationUpdates` (no double removal, reference is released)
- **F-Droid build hardening** — removed the `foojay-resolver-convention` plugin from `settings.gradle.kts` **and** the `gradle/gradle-daemon-jvm.properties` daemon-JVM pinning (which forced a JetBrains JDK 21 toolchain download via Foojay). The Gradle daemon now runs on the invoking JDK (CI: 17, local: developer's JDK); the app still targets Java 11. This also fixes CI builds failing with `Unable to download toolchain ... vendor=JetBrains`

---

## [v1.0.9] — 2026-06-12

### Added
- **Tap-to-place custom map pin** — tap any empty location on the map to drop a blue pin marker at that exact point; the map camera animates to the tapped position automatically
- **`CustomMapPinSheet`** — bottom sheet that opens immediately when a custom pin is placed; shows the reverse-geocoded address from Nominatim (with a loading spinner and raw coordinates as fallback while the request is in-flight); provides a "Navigate here" primary action and a "Remove pin" secondary action
- **Reverse geocoding on tap** — `MapViewModel.onMapTapped()` fires a `NominatimGeocoder.reverseGeocode()` coroutine in the background; the resolved address replaces the coordinate placeholder as soon as the response arrives
- **Custom pin navigation** — "Navigate here" starts BRouter in-app bike routing directly to the tapped coordinates; the pin stays visible on the map as a route end-point marker for the entire duration of navigation and is automatically removed when navigation is stopped
- **Blue custom pin icon** — new `createCustomPinIcon()` function in `MapMarkerRenderer` renders a distinct blue dropped-pin (Material Blue 700 `#1565C0`) to visually separate the tap pin from the red address-search pin and the bike-rack parking markers
- **Dedicated GeoJSON source and layer** (`velospot-custom-pin-source` / `velospot-custom-pin-layer`) for the custom pin, following the same MapLibre `SymbolLayer` pattern as existing markers
- **`customMapPin` and `customMapPinAddress` StateFlows** in `MapViewModel` to expose pin position and its resolved address to the UI layer independently
- **Zoom-out parking marker visibility** — bike parking markers are hidden below zoom level 11 via MapLibre's native `minZoom` on the parking layer (GPU-side, no state update needed); a Toast notification informs the user when they zoom out too far
- **`MIN_ZOOM_PARKING_VISIBLE` constant** (`= 11f`) in `MapMarkerRenderer` controls the visibility threshold and is shared with `MainMapScreen` for the Toast trigger
- **Profile change recalculates active route** — switching the offline routing profile while navigation is active immediately triggers a new route calculation to the same destination with the new profile
- **Navigation job cancellation** — starting a new navigation while a route is still being computed cancels the previous calculation immediately, preventing race conditions when the user taps quickly between spots
- **F-Droid build flavor** — new `fdroid` product flavor that replaces Google's `FusedLocationProviderClient` with Android's standard `LocationManager`; the `googlePlay` flavor retains the existing GMS-based implementation; `play-services-location` dependency is scoped to `googlePlayImplementation` only
- **F-Droid store listing** — full fastlane metadata for `de-DE` and `en-US` locales: `title.txt`, `short_description.txt`, `full_description.txt`, `changelogs/10000.txt`, phone screenshots
- **F-Droid build metadata** — `metadata/de.velospot.yml` with `scanignore` for the BRouter JAR, `AutoUpdateMode: Version`, `UpdateCheckMode: Tags`, and versionCode formula (`X * 10000 + Y * 100 + Z`)
- **BRouter LICENSE file** — `app/libs/brouter-LICENSE.txt` documents the MIT license and source URL of the bundled JAR for F-Droid reviewers
- **Release signing via environment variables** — `signingConfigs.release` in `app/build.gradle.kts` reads `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` from the environment; local builds fall back to the debug key automatically
- **Version injection from Git tag** — `versionCode` and `versionName` are now Gradle-property-injectable (`-PversionCode`, `-PversionName`); the release workflow computes `versionCode = X * 10000 + Y * 100 + Z` from the tag
- **GitHub Pages screenshots** — added `searchbar.jpeg` and `found-location.jpeg` to the App Preview section; all screenshot `alt` texts updated to English

### Changed
- `MainMapScreen` map-click listener now **always consumes the tap event** (`return true`) — clicking an empty area places a custom pin instead of being silently ignored
- Selecting a parking space or a Nominatim search result **dismisses any active custom pin** and vice versa, ensuring only one pin type is visible at a time
- `stopInAppNavigation()` only clears the custom pin when the destination was the custom pin itself; navigating to a parking space or address does not affect the custom pin state
- **`release.yml` workflow rewritten** — builds both `fdroid` and `googlePlay` release APKs from a single job; version is extracted from the Git tag; release body includes a bilingual APK variant table and sideload instructions; F-Droid fastlane changelog files are auto-committed after each release
- **`security-release.yml` workflow updated** — same two-flavor APK build and version-code formula as `release.yml`; section headings translated to English
- **`ci.yml` workflow updated** — `ci-build` now assembles `assembleFdroidDebug` (the canonical open-source build); APK artifact upload path updated for the new flavor directory structure; unit tests run against `testFdroidDebugUnitTest`
- **`NetworkModule`** — `provideFusedLocationClient` and `provideLocationRepository` extracted from the shared module into flavor-specific `LocationModule` Hilt modules (`src/googlePlay/…` and `src/fdroid/…`)
- **`ATTRIBUTIONS.md`** fully rewritten in English; Trier Geoportal and OSMDroid entries removed; BRouter, MapLibre, Coil, OSRM, OpenFreeMap, and all current libraries added with correct licenses and source links
- All German code comments translated to English across `build.gradle.kts`, workflow YAML files, `MapViewModel.kt`, `MainMapScreen.kt`, `MapMarkerRenderer.kt`, and all `strings.xml` files
- `zoom_in_for_parking` string resource added in all 8 supported languages (DE, EN, FR, IT, PT, LB, NL, ES)
- **`MapViewModel`** — `ID_CUSTOM_MAP_PIN` and `ID_ADDRESS_SEARCH_PIN` extracted as `companion object` constants; all usages in `MapViewModel` and `MainMapScreen` updated
- **`RoutingRepositoryImpl`** — OSRM base URL extracted to `OSRM_BICYCLE_BASE_URL` private constant
- **`MapMarkerRenderer.registerIcons()`** — location icons now updated via direct `style.addImage()` (MapLibre replaces in-place) instead of a `removeImage` + null-checked add on every call, eliminating unnecessary GPU work on each GPS position or zoom update

### Fixed
- **F-Droid `LocationRepositoryImpl` listener leak** — `startLocationUpdates()` now calls `stopLocationUpdates()` first, matching the Google Play implementation; previously calling start twice (once at init, once after permission grant) left the old `LocationListener` orphaned in `LocationManager`
- **Viewport reload errors silently dropped** — when a parking-data refresh fails after the initial load, a short Toast is now shown (`error_loading_parking` string in all 8 languages) instead of discarding the error; the existing map data remains visible

### Removed
- **`osmdroid`** version and library entries removed from `libs.versions.toml` — OSMDroid was replaced by MapLibre in v1.0.8; the catalog entries were never cleaned up
- **`accompanistPermissions`** version and library entries removed from `libs.versions.toml` — the library was superseded by manual permission handling and was never referenced in any build file

---

## [v1.0.8] — 2026-06-10

### Added
- **Address search bar** — floating search field at the top of the map; type any German address and receive up to 5 Nominatim suggestions with 400 ms debounce (minimum 3 characters) while you type
- **Search result pin** — tapping a suggestion drops a location pin on the map and animates the camera to that position; a `SearchPinSheet` bottom sheet displays the full resolved address and a "Navigate here" action that starts in-app BRouter routing directly to the address coordinates
- **`AddressSearchResult`** domain model and `NominatimSearchResultDto` Moshi DTO backing the Nominatim `/search` JSON response
- **Nominatim `/search` forward geocoding endpoint** added to `NominatimApi`; results are restricted to Germany (`countrycodes=de`); existing reverse geocoding endpoint unchanged
- **Search string resources** in all 8 supported languages: `search_placeholder`, `search_no_results`, `search_clear`, `search_result_pin_title`, `search_navigate_to`

### Changed
- **Map rendering migrated from OSMDroid to MapLibre** (`org.maplibre.gl:android-sdk:11.5.2`)
  - Map tiles now rendered as **vector tiles** (OpenFreeMap Liberty style, no API key required) — smooth scaling at every zoom level, no more pixelated raster tiles
  - `MapView` lifecycle now follows the full MapLibre lifecycle chain (`onCreate` / `onStart` / `onResume` / `onPause` / `onStop` / `onDestroy`)
  - `MapCameraAnimator` rewritten to use `MapLibreMap.animateCamera()` with `CameraPosition.Builder` — replaces manual coroutine-based easing loop; vertical offset for bottom-sheet compensation preserved via `calculateAdjustedCenter()`
  - `MapMarkerRenderer` rewritten: parking spots, route polyline, and location dot are now managed as **GeoJSON sources** (`GeoJsonSource`) with **`SymbolLayer`** / **`LineLayer`** instead of per-marker osmdroid overlays — only the data payload is updated on each state change, no overlay teardown/rebuild
  - `MainMapScreen` updated: `getMapAsync` callback sets up `CameraIdleListener` (viewport changes), `CameraMoveListener` (zoom-bucket tracking), and map-click listener (feature hit-testing via `queryRenderedFeatures`) once; marker updates driven by `LaunchedEffect` reacting to Compose state
  - `BaseApplication` cleaned up: osmdroid `Configuration.getInstance()` initialisation removed
  - `MapCameraAnimatorTest` updated: `org.osmdroid.util.GeoPoint` replaced with `org.maplibre.android.geometry.LatLng`
- **Address search bar and menu button** are now placed in a shared `Row` with `verticalAlignment = Alignment.CenterVertically` so both UI elements sit on the exact same horizontal baseline regardless of their individual card heights
- **MapLibre compass** repositioned to top-left with system-bar-aware margins to avoid overlap with the search bar and menu card

### Security
- **R8 minification and obfuscation** enabled for release builds (`isMinifyEnabled = true`, `isShrinkResources = true` in `app/build.gradle.kts`)
- **Comprehensive ProGuard rules** added for Room, Moshi, Retrofit, OkHttp, BRouter JAR (including private `ilat`/`ilon` reflection fields), Hilt/Dagger, Kotlin coroutines, and all domain/model classes
- **`HttpLoggingInterceptor`** guarded behind `BuildConfig.DEBUG` — network URLs and headers are never written to Logcat in release builds
- **All `Log.d` / `Log.w` calls** in `BRouterEngine` and `NominatimGeocoder` guarded behind `BuildConfig.DEBUG` to prevent GPS coordinates leaking to Logcat in production

### Dependencies
- `mockito-kotlin` updated to v6 (Renovate #35)
- `mockito` monorepo updated to v5.23.0 (Renovate #34)
- OkHttp / Retrofit (Networking) updated to v5.4.0 (Renovate #15)

---

## [v1.0.7] — 2026-06-08

### Added
- **BRouter offline routing engine** embedded as a local JAR (`app/libs/brouter.jar`) — routes are calculated entirely on-device, no internet required after the one-time segment download
- **`BRouterEngine`** — Kotlin wrapper around the BRouter Java API; reads `.brf` profile files from app assets and `.rd5` segment files from external storage; accesses private coordinate fields via reflection for JAR-version compatibility
- **`BRouterSegmentManager`** — manages download and local caching of BRouter `.rd5` map segment files; downloads the single 5°×5° tile covering the user's position (~200–250 MB, one-time); throws `NoInternetConnectionException` on network failure
- **`BRouterProfile` enum** — five routing profiles with localised name and description string resources: Trekking *(recommended default)*, Schnell, Kürzeste Strecke, Mountainbike, Gravel
- **`OfflineRoutingPreferences`** — SharedPreferences-backed storage for the opt-in flag and selected profile
- **Offline routing activation flow**: "Offline Navigation aktivieren" menu item → info sheet with benefits + download size hint (~200–250 MB) → Wi-Fi check → optional warning dialog → segment download with live progress overlay → 2.5 s success card
- **`OfflineRoutingUiState`** sealed class — `Disabled`, `Downloading` (with file index, total files, per-file byte counter), `DownloadComplete`, `Enabled`
- **Download progress overlay** — shows "Datei X von Y", per-file MB / total MB counter, filename, and `LinearProgressIndicator`
- **Success overlay** — brief green card after download completes ("Offline Navigation bereit!")
- **Wi-Fi warning dialog** — `AlertDialog` shown before download starts when device is not on Wi-Fi; user can proceed on mobile data or cancel
- **`WifiWarningDialog`** composable + `isWifiConnected()` utility function
- **`RoutingProfileSheet`** — bottom sheet to switch between the five profiles (with localised names and descriptions) or deactivate offline routing; wipes all `.rd5` segment files on deactivation
- **`NoInternetConnectionException`** domain exception mapped to `MapError.NoInternetConnection`
- **`MapError.BRouterProfilesMissing`** — shown when `.brf`/`lookups.dat` assets are absent
- **Localised profile names and descriptions** in all 8 languages (`profile_*_name`, `profile_*_desc`)
- **Duration formatting** — `MapNavigationOverlay` now shows "X Std. Y Min." / "X h Y min" instead of raw minutes; `duration_minutes`, `duration_hours`, `duration_hours_minutes` string resources added in all 8 languages

### Changed
- **`RoutingRepositoryImpl`** — respects `OfflineRoutingPreferences.isOfflineRoutingEnabled`; routes via BRouter when enabled and segments present, falls back to OSRM online otherwise
- **`RoutingRepositoryImpl.osrmFallbackRoute()`** — OSRM duration replaced with distance ÷ 15 km/h for realistic cycling travel time
- **`BRouterSegmentManager.downloadSegmentsForLocation()`** — reduced from 3×3 tile grid (~1.1 GB) to single primary tile (~200–250 MB)
- **`app/build.gradle.kts`** — `fileTree("libs/*.jar")` dependency added for BRouter JAR; `buildConfig = true` enabled

---

## [1.0.6] — 2026-06-08

### Added
- **Germany-wide coverage** via pre-bundled OSM SQLite asset — full PR #32 consolidating all Germany-wide parking data features into the release branch

### Fixed / Refactored
- Remove redundant `GeoCoordinate` wrapping in `MapViewModel.startInAppNavigation()` (location is already a `GeoCoordinate`)
- Replace `Math.pow()` with idiomatic Kotlin `pow()` in `MapCameraAnimator`; use `kotlin.math.abs` import instead of fully qualified form
- Remove duplicate `activeNavigationMarkerIcon` in `MainMapScreen` (identical to `selectedMarkerIcon`); pass `selectedMarkerIcon` directly for the active navigation slot
- Remove stale KDoc comment in `SelectedSpaceSheet` describing the old geo-intent redirect
- Fix import ordering in `NetworkModule` (android.* before third-party); narrow `LenientJsonAdapterFactory.create()` return type to non-null `JsonAdapter<*>`
- Remove unused `getFavoritesCount()` from `FavoritesRepository` interface

---

## [1.0.5] — 2026-06-08

### Added
- **In-app bike navigation** with OSRM route calculation and route polyline overlay on the map
- Navigation status card with route loading, distance/time summary, and stop action
- Favorites now provide two explicit actions: start navigation and show spot details
- Localized navigation/favorites labels for all supported app languages
- **Navigation focus mode**: non-destination parking markers are shown smaller, lighter gray, and more transparent during active navigation
- **`GeoCoordinate`** domain model replacing raw `Double` pairs across the routing layer
- **Typed `MapError`** sealed class replacing raw string error messages in `MapUiState` and `NavigationUiState`
- **Shared UI action components** (`PrimaryActionButton`, `SecondaryActionButton`, `MetaInfoChip`, `SheetHeader`, `SpotInfoCard`, `DetailRow`) extracted for reuse across overlays and sheets
- **`MapScreenUiState`** extracted to manage bottom sheet visibility independently of data state
- Unit tests for `MapViewModel` in-app navigation states (loading / active / error)
- Complete Priority 4 test coverage: ViewModel, camera animator, permission flow
- Dark mode preference persisted across restarts via `DarkModePreferences`

### Changed
- `MapViewModel` refactored from direct `MapView` manipulation to a `MapCameraTarget` state approach
- `MainMapScreen` split into smaller composables for status overlay, menu card, and location FAB
- Active destination marker color aligned with selected marker highlight (orange)
- Favorites action layout adjusted to avoid compressed button text on smaller widths
- External Google Maps intent flow removed; routing stays in-app
- Build toolchain modernised: Kotlin 2.4.0, AGP 9.2.1 (Gradle 9.5.1), Hilt 2.59.2, KSP 2.3.9
- Migrated annotation processing from KAPT to KSP for Hilt and Room
- Renovate configuration tightened: global automerge disabled; only security updates automerge
- Renovate dependency updates: `accompanist-permissions` 0.13.0→0.37.3, AndroidX libraries, Compose BOM 2024→2026.05.01, Retrofit 2→3, OkHttp 4→5, Room 2.8.1→2.8.4, Gradle Wrapper 9.4.1→9.5.1, GitHub Actions updated
- Release automation hardened to avoid duplicate release creation

### Fixed
- Sonar maintainability issues resolved across map, data, and domain layers

---

## [1.0.4] — 2026-06-07

### Added
- **Parking space image display** in bottom sheet details with photo preview from available sources
- **Automatic image caching** with Coil for fast loading and offline access

---

## [1.0.3] — 2026-06-07

### Fixed
- CI: upgraded `action-gh-release` to v3 for GitHub Actions compatibility

---

## [1.0.2] — 2026-06-07

### Fixed
- CI: migrated all workflows to Node 24-ready action runtime

---

## [1.0.1] — 2026-06-07

### Added
- **Smooth map camera animations** with easing functions for natural zoom and pan transitions (~120–180 ms)
- **Selected marker highlighting** with orange pin color to distinguish the currently selected parking space
- **Vertical offset support** for better map positioning with bottom sheets open
- Real app screenshots added to GitHub Pages preview section
- GitHub Actions CI workflow with dedicated `ci-build` and `ci-test` checks for pull requests and main branch pushes
- Repository branch protection ruleset for `main` with required PR approval, review-thread resolution, linear history, and required status checks
- Security dependency update automation via Renovate (security-only automerge)

### Fixed
- Removed temporary AGP 9 compatibility flags (`android.newDsl=false`, `android.disallowKotlinSourceSets=false`) now superseded by Hilt 2.59.2

---

## [1.0.0] — 2026-06-06

### Added
- **Germany-wide OSM dataset** bundled as `app/src/main/assets/bike_parking_germany.db` (~20 MB, ~100 000 nodes) — replaces the previous Trier-only WFS/WMS data source
- **`scripts/extract_osm_parking.py`** — Python pipeline (pyosmium + sqlite3) to regenerate the database from a Geofabrik PBF; uses a C++-level `TagFilter` for fast extraction
- **`scripts/README.md`** — full documentation for the extraction pipeline
- **Viewport-based marker loading** — `BikeParkingRepository.getSpacesInBoundingBox()` queries only the visible map area; ViewModel debounces scroll/zoom events (300 ms)
- **Lazy Nominatim reverse geocoding** — address resolved automatically on first marker tap, written back to local DB and cached permanently
- **`NominatimApi`** (Retrofit) and **`NominatimReverseDto`** (Moshi) — typed REST client for `nominatim.openstreetmap.org/reverse`
- **Favorites system** backed by Room / SQLite
- **Favorites sheet** with direct navigation shortcuts
- **Current-location support** with map recenter action and location marker
- **Dark mode toggle** integrated into the in-app menu
- **Favorite-aware map markers** with a red visual state
- **8 languages** with persistent in-app language picker: German 🇩🇪 English 🇬🇧 French 🇫🇷 Italian 🇮🇹 Portuguese 🇵🇹 Luxembourgish 🇱🇺 Dutch 🇳🇱 Spanish 🇪🇸
- **`MapViewModel.favoriteSpaces`** — full space objects for all favorited locations, resolved independently of the current viewport
- Room schema exported to `app/schemas/` (`exportSchema = true`)
- `ATTRIBUTIONS.md` — ODbL / OpenStreetMap attribution documentation
- GitHub Pages website in `docs/`
- Initial public repository setup on GitHub

### Changed
- Data source replaced: Trier Geoportal WFS/WMS → pre-bundled OpenStreetMap SQLite asset
- `BikeParkingDatabase` uses `Room.databaseBuilder().createFromAsset(...)` for first-launch seeding
- `NetworkModule` split Retrofit into two named instances (`@Named("osrm")` and `@Named("nominatim")`)
- `MainActivity` migrated to `AppCompatActivity` for full AppCompat locale support
- Local bike parking storage moved to SQLite / Room; temporary XML project-root files removed
- App launcher bike icon positioning refined

### Removed
- `TrierGeoportalApi` — WFS endpoint no longer used
- `BikeParkingGmlParser` — GML parsing no longer needed
- Sync-interval logic (`syncIntervalMs`, `lastSyncEpochMs`) — data is now static/bundled
