# Moving Traveler 1.4.0

An Android mock-location simulator with a full-screen native map, route choices, up to six ordered via points and adjustable pace. A rounded white journey sheet holds the planning controls; collapse it to see more of the map while keeping the primary action and active-session Stop available. Version **1.4.0 / code 5**, documented 15 September 2026. Independently authored from the [FakeTraveler](https://github.com/mcastillof/faketraveler) concept; upstream attribution is preserved in `UPSTREAM.md`.

## Downloads and earlier versions

This repository contains the extracted **v1.4.0** Android source and documentation. Download the test APK and unsigned AAB from [GitHub Releases](https://github.com/ujjwalnain/MovingTraveler/releases).

| Version | Release downloads | Original source archive |
| --- | --- | --- |
| **1.4.0 — latest** | [v1.4.0](https://github.com/ujjwalnain/MovingTraveler/releases/tag/v1.4.0) | [Source ZIP](MovingTraveler-1.4.0-source.zip) |
| 1.3.0 | [v1.3.0](https://github.com/ujjwalnain/MovingTraveler/releases/tag/v1.3.0) | [Source ZIP](MovingTraveler-1.3.0-source.zip) |
| 1.2.0 | [v1.2.0](https://github.com/ujjwalnain/MovingTraveler/releases/tag/v1.2.0) | [Source ZIP](MovingTraveler-1.2.0-source.zip) |
| 1.1.0 | [v1.1.0](https://github.com/ujjwalnain/MovingTraveler/releases/tag/v1.1.0) | [Source ZIP](MovingTraveler-1.1.0-source.zip) |
| 1.0.0 | [v1.0.0](https://github.com/ujjwalnain/MovingTraveler/releases/tag/v1.0.0) | [Source ZIP](MovingTraveler-source.zip) |

The named source ZIPs preserve the exact original version archives. For historical source, use these files or the [complete Git history bundle](MovingTraveler-all-versions.bundle), rather than GitHub's automatically generated repository snapshots. The bundle contains five chronological archive-import commits and version tags; its history is separate from this repository's browser-upload commits. Restore it with `git clone MovingTraveler-all-versions.bundle MovingTraveler-history`. Import timestamps record archive-import times, not original development dates.

## Use the installed APK

**The map needs no account or key.** Enable online planning to display the OpenFreeMap map. For worldwide place search and walking/cycling/driving routes, create one Geoapify account and enter your key in **Settings → Routing access**. You can do this in the installed APK; **no rebuild, Google billing, Firebase project or backend deployment is required**. The exact steps and current plan limits are in [Routing setup](docs/ROUTING-SETUP.md).

1. Install on an Android 6.0+ test phone and choose Moving Traveler in **Developer options → Select mock location app**.
2. Enable online planning. Choose a start and destination by tapping the map, searching for places or entering coordinates. **Add via point** adds an intermediate point using the same methods; tap an existing via for Change place, Move earlier/later or Remove.
3. Choose Walk, Cycle or Drive. **Set up routing** opens key setup when needed; the map remains available without a routing key.
4. Review the distinct returned choices: **Balanced**, **Shortest**, and **Fewer turns** for Drive. Selecting an already loaded choice changes the preview without another API request. Review its distance, adjust pace, then tap **Start journey**. Route failure offers **Retry route** and never silently substitutes a straight line.
5. **Pause** holds the current point; **Resume** continues. Arrival holds the destination until **Stop**.

Tap or vertically drag the journey sheet's heading to expand or collapse it. The form inside the sheet scrolls independently. Landscape uses an open side card, and returning to portrait restores your sheet choice. Place search includes a clear control, a close control and keyboard Search; coordinate entry and map selection remain available.

Routing access shows **Key saved · reused automatically** after a key is available. The key is reused for new points and routes and is not displayed in plaintext. **Replace key** is optional; a provider error is not evidence that the key was forgotten. See the error-specific steps in [Routing setup](docs/ROUTING-SETUP.md).

Vias are visited as start → via 1 → via 2 → destination, up to six intermediates. Swap start and destination also reverses the via order. Vias shape the path; no automatic pause or dwell time is added. For offline testing, explicitly choose **Direct line**: it draws straight segments through the ordered points and can cross buildings or water. **Stay at start** holds a single location and does not use vias.

## Features

- Native MapLibre map with OpenFreeMap's Liberty style, map point selection and Geoapify search.
- Geoapify walking, cycling and driving route geometry where the underlying data supports it; worldwide coverage does not guarantee every local road or path.
- Distinct route choices with a selected teal path and subdued alternatives. Planning requests up to two provider routes for Walk/Cycle and three for Drive; each is billable, even if matching paths collapse to one choice. Choosing an existing route or changing only pace does not refetch it.
- Numbered intermediate points, ordered editing/removal and full selected geometry for playback. Every selected point is checked against its returned route position; excessive snapping rejects that route.
- Explicit direct routes, fixed location, one-way arrival hold and back-and-forth playback.
- Pace from 0.5–200 km/h, adjustable during playback. Duration follows that pace rather than provider travel-time estimates.
- Progressive native controls: choose points, review the route, adjust pace, start. Walk/Cycle/Drive share one segmented selector. A fixed action row keeps the primary action and active-session Stop reachable when the sheet is collapsed, on small screens and in landscape.
- A floating app header, rounded sliding sheet and native place-search field inspired by the approved 21st references. The Android UI is independently authored; no React components are bundled. Visual credits are in `app/src/main/assets/THIRD-PARTY-NOTICES.txt`.
- System-controlled haptic feedback on important actions, plus accessible drawer expand/collapse actions. Rendering and route animation do not trigger repeated feedback.
- A foreground service with notification Pause/Resume/Stop controls and local playback after route calculation.
- Android GPS/network/framework fused mocking, plus optional Google Play Services fused mocking. Map display does not require Google Play Services.
- Cancelable, bounded route/search requests; stale responses cannot replace newer plans.
- No app accounts, ads or added analytics. Application code does not retrieve real device location. Optional approximate-location permission enables Google mock compatibility.

Other apps decide whether to accept mock locations. WhatsApp **live location** has not been verified on a physical phone; a static location message will not animate. The app does not access WhatsApp data or start sharing for you.

## Build and test

Use JDK 17/21, Android SDK Platform 36 and Build Tools 36.0.0:

```sh
bash ./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug bundleRelease
```

The browser uploader may store scripts without their executable bit; invoking the wrapper with `bash` works after a direct clone. The archived Git bundle preserves the original executable modes. Source files and documentation are uploaded unchanged from v1.4.0 except for this README’s repository links and build guidance.

Gradle 8.13 is pinned with a checksum; AGP 8.13.2, Java 17, minimum API 23 and target API 36. Map rendering uses MapLibre Native OpenGL 13.6.1; Play Services location 21.3.0 supports only the optional mock-publication path.

The normal personal-testing build can leave `GEOAPIFY_API_KEY` blank: enter a personal key in the app. Publishers can copy `secrets.properties.example` to ignored `secrets.properties` to set a shared build key and public policy/source URLs. **Any compiled key can be extracted from an APK**; public distribution needs a provider plan and a deliberate access-protection design. See [Routing setup](docs/ROUTING-SETUP.md). `local.properties` can set `sdk.dir`; exclude local configuration and signing keys from source archives. `-PdebugKeystore=/path/to/debug.keystore` optionally redirects debug signing.

Build outputs: `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/bundle/release/app-release.aab`. The release bundle is unsigned; use your own upload key for Play. Choose the final package before first publication.

## Architecture and data

`MainActivity` coordinates `MapSurface`, `JourneyUi`, planning and service commands. `JourneySheet` owns only handle gestures and the two sheet positions; the native form keeps its own scrolling behavior. `NetworkGateway` sends HTTPS requests directly to Geoapify; no publisher server is required for personal use. Route preferences are separate bounded requests, not fabricated variations of one line. Via requests use `intermediate_waypoint_mode=through_stop` and `optimize_stops=false`; the parser verifies contiguous legs and every via in order. `RouteEngine` advances along the full selected geometry against monotonic elapsed time; `SimulationService` publishes mock samples without calling routing APIs.

Online planning is opt-in. Map area requests go to OpenFreeMap; searches, chosen route points (including vias), route preferences and the routing key go to Geoapify. Playback remains local after a route is loaded, although a visible online map can request further tiles. A key entered in Settings is encrypted with Android Keystore-backed AES-GCM in app-local storage; encryption at rest does not hide a shared key used by a distributed client. Preferences and manual coordinate drafts stay locally; loaded search results and route choices/geometry remain in memory. If any via came from provider search, all coordinates of that draft are omitted from saved storage so reopening cannot silently drop that via and restore a different journey. Provider and map-library caches/logs are separate. See the [privacy draft](docs/PRIVACY-POLICY-DRAFT.md).

## Verification and release

[Verification](docs/VERIFICATION.md) records actual checks and limitations. This release has no completed live-key/provider or physical-phone verification. Physical mock delivery and third-party compatibility still require the [device plan](docs/DEVICE-TEST-PLAN.md). Credential fixtures exercise real AES-GCM with a substituted key provider; they do not establish Android Keystore reliability on a phone. An APK/AAB build or fixture test is not a production-service or device test.

Use [Play release preparation](docs/PLAY-RELEASE.md) before publishing. Code is GPL-3.0-or-later with the scoped [Google linking permission](docs/COPYING-EXCEPTION.md) for optional Play Services. MapLibre, map data and dependencies retain their own licenses. Keep OpenStreetMap/OpenMapTiles map credits and Geoapify route/search attribution visible. Publish complete corresponding source for every distributed version.
