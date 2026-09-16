# Routing setup — Moving Traveler 1.4.0

Updated 15 September 2026 for version **1.4.0 / code 5**.

## Enable search and routes in the installed app

Moving Traveler displays maps through MapLibre Native and OpenFreeMap. The basemap has **no account, billing setup or API key**. Geoapify supplies place search and route geometry. Personal testing needs one Geoapify account; it does not need a Google Cloud account, Firebase or a server.

1. Open [Geoapify MyProjects](https://myprojects.geoapify.com/), register or sign in, create a project and copy its API key. Keep the key private; do not put it in screenshots, issue reports or chat messages.
2. In the installed Moving Traveler APK, open **Settings → Routing access**, paste the key and save it. **There is no rebuild step.** The planning button **Set up routing** leads to the same setup.
3. Enable online planning when prompted. That choice permits map requests to OpenFreeMap and search/route requests to Geoapify.
4. Choose a start and destination, optionally add up to six via points, select **Walk**, **Cycle** or **Drive**, and wait for the route choices. Select a returned route, confirm its geometry, distance and chosen pace, then tap **Start journey**.

After saving, Routing access shows **Key saved · reused automatically**. New searches and changed points reuse it; the saved plaintext key is never shown. **Replace key** is optional and opens a blank entry field. The normal saved-key dialog offers **Done**, so opening Settings does not require pasting it again.

Saving a key does not itself prove that the account is active or quota is available. Actual search/route responses establish provider access. Saving again also retries planning, which could make re-pasting appear to fix an earlier error; **Retry route** retries directly with the saved key. Missing access, rejected access, a temporarily unreadable Android Keystore and an invalid route request are different states. An HTTP error does not erase the saved key. Without access, map point selection, coordinates and explicit **Direct line** or **Stay at start** remain available. No account was created or key supplied as part of this source package.

## Via points and route choices

Use **+ Add via point** to choose an intermediate place through search, map selection or coordinates. Tap its row to change the place, move it earlier/later, or remove it. The order is always start → numbered vias → destination. **Swap start and destination** reverses the vias too. Six intermediates is the app limit. Vias shape the journey; this version adds no dwell time or automatic pause at them. In explicit Direct line mode, each consecutive pair is joined by a straight segment.

Road planning asks Geoapify for **Balanced** and **Shortest** paths, plus **Fewer turns** in Drive. Matching geometry appears only once, so not every request produces two or three choices. The selected route is teal and other loaded routes are subdued. Tapping a loaded choice changes the preview and the geometry sent to playback without another request. A timeout or failure fetching extra choices can leave the already obtained valid route available, with an explanatory message.

Via requests use `intermediate_waypoint_mode=through_stop` and `optimize_stops=false`. This keeps the selected order and continuous path through each intermediate point. The app checks contiguous legs and the returned position for every selected point. These parameters concern route calculation; they do not add timed stops to playback. [Geoapify routing parameters](https://apidocs.geoapify.com/docs/routing/)

## Provider limits and coverage

As checked on 15 September 2026, Geoapify's Free plan includes **3,000 credits/day**, up to **5 requests/second**, with **no credit card required**. Search and routes share that budget; OpenFreeMap does not consume it. Review the [current pricing](https://www.geoapify.com/pricing/) for your release.

Each complete replan can make **two billable routing calls for Walk/Cycle or three for Drive**, even when duplicate paths become one visible choice. Each call's credit cost also depends on consecutive waypoint pairs, distance and options. Adding vias can increase cost. Moving/reordering/removing points, changing travel mode or pressing Retry route can replan; choosing a loaded route, changing only pace or pausing playback does not. See [Geoapify routing cost rules](https://apidocs.geoapify.com/docs/routing/); two or three calls is not a fixed two- or three-credit promise.

The app maps Walk → `walk`, Cycle → `bicycle`, Drive → `drive`. These are documented Geoapify profiles. Coverage is worldwide, based on available map data; it can be incomplete or differ locally by mode. A valid key cannot create a missing path. Test representative places in the countries you need. Playback follows the chosen constant pace and does not reproduce live traffic. [Geoapify routing documentation](https://apidocs.geoapify.com/docs/routing/)

Geoapify currently lists these limits for the synchronous routing service used by the app:

| App mode | Maximum distance between consecutive waypoints |
| --- | --- |
| Walk (`walk`) | 100 km |
| Cycle (`bicycle`) | 300 km |
| Drive (`drive`, default vehicle) | 10,000 km |

These are per-segment provider limits, not a guarantee of connected coverage. Adding closer vias can shorten individual legs, but cannot create missing roads, pedestrian access or a connection across water. This app uses synchronous requests, not the larger asynchronous Batch API limits. [Geoapify's synchronous/Batch limits table](https://www.geoapify.com/batch-api/)

The app accepts each response up to **2 MB (2,000,000 bytes)** and each route with at most **20,000 returned vertices**. It rejects a route if the returned start, destination or **any via** is more than **1 km** from the corresponding selected point. A snap beyond **50 m** shows a warning; playback follows the displayed route without adding a straight connector to the original pin. Move that pin nearer an accessible road/path or choose a shorter journey when these checks reject a result. These are separate application validation limits.

**Back and forth** replays the same loaded path backwards at each endpoint. It does not calculate a separate return route or validate return-direction restrictions; a driving route can have different restrictions in the opposite direction.

OpenFreeMap currently permits commercial use of its free public service without request quotas, but provides no availability SLA. It is separate infrastructure, not a guarantee that the entire application is free to operate at any scale. Geoapify's pricing FAQ allows free commercial use with attribution, while its formal terms describe limits for production use. Confirm the intended public release against the selected plan and [Geoapify terms](https://www.geoapify.com/terms-and-conditions/); see [OpenFreeMap terms](https://openfreemap.org/tos/) and [service information](https://openfreemap.org/).

## If a route does not load

| What you see | Next step |
| --- | --- |
| Set up routing / no saved access | Add a personal Geoapify key in Settings → Routing access. |
| Enable routes | Enable online planning; it is required for new network routes. |
| Choose start / Choose destination | Complete the missing point by map, search or coordinates. |
| Finding route | Wait for the bounded request; moving a point starts a newer request. |
| Key saved / reused automatically | Continue planning. Replace key is optional; the saved value is not displayed. |
| AUTH / HTTP 401 or 403 | The provider rejected access. Check the account and key restrictions, then retry with the saved key. Replace it in Settings only when necessary. |
| Saved key cannot be read | The saved encrypted value is retained. Retry after secure storage is available, or deliberately replace/remove it in Routing access. |
| INVALID_REQUEST / walking route rejected | Check coordinates, chosen mode and per-leg distance limits above. Try closer points or suitable vias; this is not proof of a missing key. |
| Quota / HTTP 402 or 429 | Check the provider plan/usage or wait for its reset; pasting the same key again does not restore quota. |
| Retry route / network or no-route error | Check connectivity and mapped access for every point, then retry. |
| Map visible, search/routes fail | The basemap and routing are separate services; check Geoapify access. |
| Map unavailable | Check connectivity and OpenFreeMap availability. Coordinates/direct simulation remains available. |

For no-route cases, move the point nearer a mapped road/path or try another suitable profile. The reported walking `INVALID_REQUEST` can indicate route inputs or a provider limit; the screen alone does not establish the exact cause. Choose **Direct line** explicitly only when straight simulated segments are intended. The app never silently presents those segments as a road route.

## Personal keys and public distribution

A key entered on the phone is stored using Android Keystore-backed AES-GCM and can be replaced or removed in Routing access. Removing a personal key falls back to a publisher key if the APK contains one; turning online planning off stops new routing requests. Clearing app storage removes app-local configuration, not a key compiled into the installed APK. Revoke a compromised key in Geoapify MyProjects. The app sends the key directly to Geoapify over HTTPS when searching/routing; a personal account's usage belongs to its owner.

Geoapify supports IP, origin and referrer restrictions. Browser origin/referrer restrictions are not an Android package/signing-certificate restriction, and a phone's IP can change. Do not assume a browser restriction will authenticate this APK. Test any account restrictions with the real Android client. [Geoapify key configuration](https://apidocs.geoapify.com/docs/places/)

A publisher may optionally set `GEOAPIFY_API_KEY` in ignored `secrets.properties`, or as a Gradle project property, before building. That embeds a reusable, **extractable** key in the client. Android Keystore does not make that compiled shared key secret. Before public release, choose and document either user-supplied keys with clear onboarding or an appropriately protected publisher service/key arrangement. A managed server/proxy is a possible later publisher decision, not a dependency of this personal-testing build. Do not publish an unrestricted shared test key as a production access strategy.

Finalize `PUBLIC_PRIVACY_URL`, `PUBLIC_TERMS_URL` and `PUBLIC_SOURCE_URL` for the publisher's HTTPS pages. These build-time publishing fields are separate from entering a routing key in the installed app.

## Data and verification

Map area requests go to OpenFreeMap; searches, all ordered points, profile, route preferences and key go to Geoapify. Each route-choice request sends the same selected points. Loaded choices and provider-derived places remain in memory. If any via is provider-derived, the entire coordinate draft is omitted from saved storage rather than restoring a trip with that via missing. Those providers also receive network metadata and apply their own retention policies. See [privacy](PRIVACY-POLICY-DRAFT.md), [OpenFreeMap privacy](https://openfreemap.org/privacy/) and [Geoapify privacy](https://www.geoapify.com/privacy-policy/).

Automated tests use controlled responses. Credential fixtures exercise real AES-GCM with a substituted key provider, not a phone's Android Keystore. Live routes/choices/vias still need an account-key test, followed by physical-device checks of saved-key reuse, map rendering, mock delivery, background playback and Stop cleanup. No completed live-provider or phone verification is claimed for this release. Record results in [Verification](VERIFICATION.md).
