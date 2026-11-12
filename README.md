# RouteMock v0.1 — Working Prototype (code only)

A movement-based mock-location app for Android. Instead of teleporting to
one fake point, it plays a real road route over a duration you choose, so
a consumer app sees a believable trip.

**Status: v0.1 code is complete in this folder. It has NOT been
build-verified here — see "Unverified" below. There is no APK in this
folder yet; build one with Android Studio or GitHub Actions (below).**

This is a separate app from Nico. Do not mix the two projects.

---

## What v0.1 does

- **Map (free stack, no keys):** MapLibre Native + OpenStreetMap tiles
  (public demo style). Long-press the map: 1st press = Start, 2nd = End.
- **Road routing:** OSRM public demo server (`router.project-osrm.org`,
  driving profile, up to 2 alternatives). Tap a route to select it.
- **Address search:** Nominatim, debounced (600 ms) and cached.
- **Offline fallback:** if OSRM is unreachable after 2 retries, the app
  uses a straight line and shows a banner — "Offline — following
  straight line". It does not silently pretend the route is road-based.
- **Duration + vehicle:** free duration entry in minutes; vehicle
  recommendation (Car 70, Motorcycle 50, Bicycle 10 km/h) with the PRD
  formula `recommended_minutes = round(distance_km / speed_kph * 60)`.
  A non-blocking warning appears if the implied speed is over 130 km/h.
- **Mock engine (the core of v0.1):** `MockLocationEngine` precomputes
  cumulative distances, ticks every 1 s, advances by
  speed = totalDistance / duration, interpolates position and bearing,
  and injects into the GPS + Network + Passive test providers
  (`addTestProvider` + `setTestProviderEnabled`, `makeComplete()` on
  API 31+). State machine:
  IDLE → ARMING → MOVING → PAUSED → HOLDING → STOPPED → IDLE.
- **Anti snap-back:** while PAUSED or HOLDING, the frozen position is
  re-injected every 2 s, so consumer apps never see the real GPS.
- **Session controls:** Pause / Resume / Stop (with confirmation
  mid-route) in the app and in the persistent notification, edit
  remaining time (speed is recalculated from the current position),
  tap-to-jump (tap the route line on the map to jump there),
  progress bar + live ETA.
- **Setup check:** the app checks via AppOpsManager whether RouteMock
  is selected as the mock-location app, and deep-links to Developer
  Options if it is not.

## What is NOT in v0.1 (next steps)

- Freehand draw with road snapping (OSRM Match) — Phase 4
- Favorites and history (last 50 sessions, re-run) — Phase 5
- Quick Settings tile
- Play Billing / Pro, accuracy jitter, off-road toggle
- Cloud sync, accounts, iOS, GPX import, live traffic
- A self-hosted OSRM server — v0.1 uses the public demo server, which
  has no uptime guarantee and is not for production traffic.

## Project structure (single `:app` module)

The plan proposed six Gradle modules, but v0.1 is a single `:app`
module with the same package boundaries, so nothing blocks progress on
module wiring. The boundaries are the seam for splitting later:

- `com.routemock.app.core.engine` — `MockLocationEngine` (pure maths,
  no Android), `MockLocationService` (foreground service + test
  providers), `SessionStore`, `MockSetupChecker`
- `com.routemock.app.core.routes` — OSRM + Nominatim Retrofit clients,
  `RouteRepository` (cache, retry, straight-line fallback)
- `com.routemock.app.core.map` — `RouteMapHost`, the only file that
  imports MapLibre. Swap the map vendor here and nowhere else.
- `com.routemock.app.feature.planner` — `Vehicle` + recommendation
- `com.routemock.app` — `MainActivity` (Compose UI) + `RouteMockViewModel`

Stack: Kotlin 1.9.24, Jetpack Compose (Material 3), AGP 8.5.2,
Gradle 8.7, minSdk 26, targetSdk 34, package `com.routemock.app`
(provisional — confirm before any store listing).

Permissions: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`,
`POST_NOTIFICATIONS`, `INTERNET`. `SYSTEM_ALERT_WINDOW` is
deliberately NOT used.

---

## Option A — Build in Android Studio (laptop)

1. Copy this whole `routemock/` folder to the laptop (or unzip
   `routemock-v0.1.zip` there).
2. Open Android Studio → Open → select the `routemock/` folder.
   Let Gradle sync finish. If sync fails on downloading Gradle, see
   the Nico notes: retry, then point Android Studio at a locally
   downloaded Gradle 8.7 distribution.
3. Connect the phone (USB debugging on) and press Run, or
   Build → Build App Bundle(s) / APK(s) → Build APK(s).
4. Install the APK on the phone.

## Option B — Get an APK from GitHub Actions (no Android Studio)

The repo contains `.github/workflows/android.yml`. It builds a debug
APK on every push to `main` and uploads it as an artifact.

1. Create a new GitHub repo on the **gplayaaru** account and push this
   code to it (branch `main`). Do not commit `build/` or `.gradle/` —
   they are build output.
2. On GitHub, open the repo → **Actions** tab → the **Android CI** run
   for your push. Wait for it to go green.
3. Scroll to **Artifacts** at the bottom of the run page and download
   **`routemock-debug-apk`**.
4. Unzip it, copy `app-debug.apk` to the phone, tap it to install.
   Allow "install unknown apps" for the browser/files app if asked.
5. Then follow "First run on the phone" below.

**The workflow itself is untested here** (Gradle cannot run in this
VM — see "Unverified"). If the first Actions run fails, the failing
step's log is the thing to fix; do not assume the code is wrong until
that log has been read.

## First run on the phone

1. Open RouteMock. Grant location permission (and notifications on
   Android 13+).
2. If the red card says the mock-location app is not selected:
   tap **Open Developer Options** → scroll to **Select mock location
   app** → choose **RouteMock** → go back → tap **Check again**.
   (Developer Options itself must be enabled first: Settings → About
   phone → tap Build number 7 times.)
3. Long-press the map for Start, long-press again for End. Wait for
   the blue road route and the distance.
4. Pick a vehicle if you want a recommendation, set the duration in
   minutes, tap **Start mocking**.
5. Open Google Maps (or any consumer app) and watch the blue dot move
   along the route. RouteMock can stay in the background — the
   foreground service keeps the session alive.

## How to test (pass criteria for v0.1)

Run these with Google Maps open as the consumer app:

1. **Movement:** start a 10 km route in 12 minutes. The dot moves
   along roads, not in a straight line, and the speed shown is close
   to the expected average (distance / duration).
2. **Bearing:** on turns, the dot follows the road direction — no
   sudden jumps or backwards movement.
3. **Pause:** tap Pause (in-app or notification). The dot freezes.
   Wait 30 s — it must NOT jump back to your real location.
4. **Hold:** let a short route finish. The dot stays at the
   destination. Wait 30 s — still no snap-back to real GPS.
5. **Edit time:** mid-route, change the remaining time. The speed
   changes and the ETA updates; the dot does not jump.
6. **Tap-to-jump:** tap a point on the blue route line. The dot
   moves there and continues from there.
7. **Stop:** tap Stop. The dot returns to your real location.
8. **Screen off / background:** start a session, turn the screen off
   for 5 minutes, then check — the session should still be moving.

Record which of these pass or fail, with phone model + Android
version. A failure in test 3 or 4 is the most important thing to
report — anti snap-back is the whole point of this app.

## Known limits (read before judging v0.1)

- **Android 12+ can detect mock locations.** Consumer apps can read
  `Location.isMock` — RouteMock cannot hide that, and does not try
  to. "Indistinguishable" in the PRD means realistic movement, not
  undetectable mocking.
- **OSRM demo server** may be slow or down; that is why the
  straight-line fallback exists. It is not a production backend.
- **Map style** is MapLibre's public demo style — free, no SLA.
- **Vehicle speeds** (Car 70, Motorcycle 50, Bicycle 10) are the
  PRD's rough numbers. 70 km/h is unrealistic on most city routes;
  revisit them before any release.
- **Battery optimisation / OEMs:** some phones kill foreground
  services aggressively. If a session dies in the background, check
  battery-optimisation settings for RouteMock first.
- **Play Store:** this prototype is for testing only. A store listing
  must use testing/QA/privacy language only — never games, cheats,
  or "undetectable".

## Unverified (do not treat as done)

- **Not build-verified here.** Android builds cannot run in this VM:
  Gradle 8.7 + JDK 17 + Android SDK are present, but every build dies
  with `DaemonConnectionException` ("Could not receive a message from
  the daemon", "Unexpected type tag 109"), even for trivial projects.
  This is environmental, not a RouteMock code problem. The user builds
  in Android Studio on their laptop (Option A) or via GitHub Actions
  (Option B) and pastes errors back. (One build was attempted here on
  2026-11-10: it failed before Gradle even started — the wrapper could
  not download the Gradle 8.7 distribution, `SSLException: Unsupported
  or unrecognized SSL message` through the VM proxy. No further attempt
  was made, per the build brief.)
- **Not phone-tested.** No test in "How to test" above has been run.
  Speed, bearing, pause/hold snap-back, screen-off behaviour and the
  OSRM/Nominatim calls are all unverified until the laptop build +
  phone test.
- **MapLibre API surface** used here is the long-stable
  Mapbox-derived API (`MapView`, `getMapAsync`, `addMarker`,
  `addPolyline`, camera updates). If Android Studio reports a changed
  signature on the pinned SDK version (11.11.0), fix
  `core/map/RouteMapHost.kt` only — it is the only file that imports
  MapLibre.
- **GitHub Actions workflow untested** — see Option B.
