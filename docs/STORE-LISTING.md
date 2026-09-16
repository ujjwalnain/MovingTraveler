# Moving Traveler — store listing draft

Draft dated 15 September 2026 for **1.4.0 / code 5**. Finalize publisher details, public policies/source and routing access before publication. Capture actual signed-release screens after live service and device testing. See [Routing setup](ROUTING-SETUP.md) and [Play release preparation](PLAY-RELEASE.md).

## App name

Moving Traveler

## Short description

Simulate Android locations along a route at your chosen pace.

## Full description

Plan a simulated journey and control how it moves. Moving Traveler uses Android's developer mock-location feature for location testing and demonstrations.

Choose a start and destination on a native map, search for a place or enter coordinates. Add up to six ordered via points. Select Walk, Cycle or Drive, review the returned route choices, choose your pace and start.

- Walking, cycling and driving routes use Geoapify where the underlying map data supports them.
- Compare distinct Balanced and Shortest routes, plus Fewer turns for Drive. Selecting an already loaded choice makes no new route request.
- Edit, reorder or remove vias. Swapping start and destination reverses their order. Vias shape the path without adding timed pauses.
- Walk, Cycle and Drive select a routing profile and a starting pace of 5, 15 or 50 km/h. Adjust custom pace from 0.5 to 200 km/h, including during playback.
- Pause holds the current point; Resume continues from there.
- Back and forth reverses along the selected route at each endpoint.
- Stay at start holds one simulated location.
- A one-way journey holds its destination after arrival until Stop.
- A foreground service supports playback while you switch apps, with notification controls when allowed.
- Explicit Direct line follows straight segments through your ordered points; fixed-location mode holds one point. Both support coordinate-only planning.

The map uses MapLibre and OpenFreeMap, with internet and online consent but no map key. Search and road routes use Geoapify; this build accepts a key in Settings → Routing access. Saved access is reused automatically, with optional Replace key. Each replan can make two billable route requests for Walk/Cycle or three for Drive; credit cost also depends on points and distance. **[PUBLISHER: CONFIRM THE RELEASE ACCESS MODEL, REQUIRED ACCOUNT AND PLAN.]**

Your chosen pace determines simulated movement and duration independently of provider estimates. Coverage varies by place and travel mode. Direct lines can cross buildings or water. This simulator does not reproduce live traffic or physical footsteps and is not a navigation or road-safety guide.

Setup requires Android 6.0+ and choosing Moving Traveler in Developer options → Select mock location app. Optional approximate-location permission enables Google Play Services mock publication; declining it preserves Android framework simulation. Google Play Services is not required to display the map. The app does not retrieve your actual location. Simulated fixes remain marked as mock; receiving apps can accept, ignore or reject them.

Online planning is optional. OpenFreeMap receives map requests; Geoapify receives searches, ordered points, route preferences and the routing key. Providers also process network metadata. Manual drafts stay locally; provider places and route choices remain in memory. A draft containing a provider-derived via is not saved as an incomplete trip. Map caches are separate. There are no Moving Traveler accounts, ads or added analytics.

The independently developed application is open source under GPL-3.0-or-later with an additional permission for optional Google Play Services linking. It takes inspiration from FakeTraveler and preserves its attribution. Use it for your own tests and demonstrations, and tell anyone viewing a shared location that it is simulated.

## Publisher fields

| Field | To complete |
| --- | --- |
| Category | Tools; confirm the final purpose. |
| Developer | [PUBLISHER DISPLAY NAME] |
| Support | [EMAIL / WEBSITE] |
| Privacy | [PUBLIC HTTPS URL] → `PUBLIC_PRIVACY_URL` |
| Application terms | [PUBLIC HTTPS URL] → `PUBLIC_TERMS_URL` |
| Corresponding source | [PUBLIC HTTPS RELEASE SOURCE URL] → `PUBLIC_SOURCE_URL` |
| Routing access | [USER-ENTERED KEY OR PUBLISHER SERVICE; REQUIRED ACCOUNT/PLAN DISCLOSURE] |
| Ads | No for this implementation; reassess changes. |
| Content rating | Complete the questionnaire for the final binary. |

## Screenshots and claims

Capture real loaded route choices, numbered vias, selected pace, running progress, Pause, Back and forth and Stay at start. Keep OpenStreetMap/OpenMapTiles and Geoapify attribution legible. Include portrait, landscape and large-text cases in the test record. Widget-test captures with a blank map are review aids, not storefront evidence of live map rendering. No completed live-key or phone verification is claimed by this draft. State relevant provider limits from [Routing setup](ROUTING-SETUP.md) in release help; do not imply vias guarantee coverage or remove provider distance limits.

Do not claim undetectable locations, universal app compatibility, verified WhatsApp support, zero lag or Play approval. Do not fabricate third-party screens or imply affiliation with WhatsApp/Meta. Make the routing account requirement and tested limits clear in the actual listing. [Play listing guidance](https://support.google.com/googleplay/android-developer/answer/13393723?hl=en)
