# Moving Traveler — Play release preparation

Prepared 15 September 2026 for **1.4.0 / code 5**. This is a checklist, not evidence of Play approval or completed device testing. [Verification](VERIFICATION.md) records checks actually run; [Device test plan](DEVICE-TEST-PLAN.md) lists remaining cases.

## Identity and signing

- Release application ID is `cl.coders.movingtraveler`; debug adds `.debug`. Choose a publisher-controlled ID before first publication. Changing it creates a different app, and updates require signing continuity. [Application IDs](https://developer.android.com/build/configure-app-module)
- Minimum API 23; compile/target API 36. Play requires API 36 for new phone apps and updates from 31 August 2026; recheck the submission requirements. [Target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
- The project has no release signing configuration. Its release AAB is unsigned and cannot be uploaded as-is. Use the publisher's upload key and configure Play App Signing. Keep private keys/passwords out of source and never use the debug identity for release. [Signing guidance](https://developer.android.com/studio/publish/app-signing)
- Run `./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug bundleRelease`. Inspect the final manifest and native libraries, sign the bundle and test that exact build through Play internal testing. Verify native ABI and 16 KB page-size compatibility in the final artifact/device environment. Increment the version code for later uploads and complete account verification/testing requirements in Play Console.

## Routing access and provider readiness

Complete [Routing setup](ROUTING-SETUP.md). Maps use MapLibre Native OpenGL 13.6.1 and OpenFreeMap's Liberty style. Search/routes use Geoapify directly over HTTPS. The map works without a routing key after online consent. A personal key can be entered in **Settings → Routing access** in the installed APK; personal testing needs no Google billing, Firebase or publisher backend.

For public distribution, record the actual access model. User-supplied keys need clear onboarding and provider-account requirements. An optional `GEOAPIFY_API_KEY` build property is an extractable shared credential, not a secret. Before shipping it, choose appropriate service protections and the provider plan for the intended audience. Browser origin/referrer restrictions are not Android package authentication. A publisher-operated proxy is an optional future architecture decision; this source does not include or claim one for 1.4.

Budget for up to two billable preference requests per Walk/Cycle replan and three per Drive replan. Matching paths may yield fewer visible choices without reducing calls already made. Each call's credits also depend on route distance and waypoint pairs; selecting a loaded route or changing only pace does not fetch again. Include six-via usage and search volume when choosing service quotas and protections. Review the documented per-leg limits in [Routing setup](ROUTING-SETUP.md); adding vias may shorten legs but does not guarantee provider coverage.

No completed live-key/provider or physical-phone verification is claimed for 1.4. Test representative regions and all three profiles (`walk`, `bicycle`, `drive`), distinct preferences, ordered via legs, selected service geometry, every-point snap guards, no-route cases, auth/quota/network failures and recovery. Vias must preserve order, with `through_stop` and stop optimization disabled, and must not add unintended dwell. Worldwide data coverage does not imply universal local path availability. The chosen pace controls playback time. Do not advertise real-time traffic simulation or navigation accuracy.

On a real phone, verify that Routing access shows the saved/reused state without exposing plaintext, Replace key is optional, and changing points does not prompt for the key again. Access rejection and invalid/over-limit walking requests must retain saved credentials. A temporary secure-storage failure must be distinguished from absent access. Automated AES-GCM tests substitute key acquisition; they do not establish Android Keystore behavior on actual devices.

Review [Geoapify pricing](https://www.geoapify.com/pricing/) and [terms](https://www.geoapify.com/terms-and-conditions/) for the public use case, including commercial use, quota and attribution. OpenFreeMap permits commercial use, but its public service has no SLA; review its [service terms](https://openfreemap.org/tos/). Free basemap access is not an uptime or unlimited-routing guarantee.

Preserve on-map OpenStreetMap/OpenMapTiles attribution and Geoapify attribution for route/search content, including when the map is hidden. Verify credits and links in portrait, landscape, large text and screenshots. App credits must not be obscured by the journey panel. The SDK/code licenses and map-data/provider terms are different obligations. [OSM license and attribution](https://www.openstreetmap.org/copyright)

## Foreground service declaration

The manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` and `foregroundServiceType="specialUse"`. The service publishes user-selected synthetic locations; it does not retrieve the real device location. This is an implementation classification requiring review, not a mock-app exemption or preapproval. [Android service types](https://developer.android.com/develop/background-work/services/fgs/service-types)

Use a description matching the shipped manifest:

> A user starts a developer location-simulation session from the visible app. The foreground service publishes a route or fixed mock location through Android test providers. Optional Google Play Services mock publication is used when available and the user grants approximate-location permission. Playback continues while another app is under test. The ongoing notification shows session state and Stop controls. Stop removes the app's test providers and disables its Play Services mock mode. The app does not read actual device location.

Explain that interruption breaks the selected pace and elapsed-time progression. Pause and arrival intentionally keep publishing a fixed point until Stop. A partial wake lock supports screen-off updates and is released during normal cleanup. The app does not automatically resume after process death or reboot.

Record an actual review video: launch and provider consent → select the mock app in Developer options → complete routing access if required → choose points/mode/pace → Start → test receiver showing mock fixes → Pause/Resume → Stop and provider recovery. Include fixed/arrival holds and notification controls. Use a receiver that displays the mock flag; do not fabricate compatibility evidence. [Play FGS declarations](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)

## Permissions and data disclosures

| Permission | Purpose |
| --- | --- |
| `ACCESS_MOCK_LOCATION` | Developer mock-app selection and injection. |
| `ACCESS_COARSE_LOCATION` | Optional Google Play Services 21.3.0 mock compatibility. Denial skips that path; framework mocking remains available. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Optional OpenFreeMap map display and Geoapify search/routes. |
| `POST_NOTIFICATIONS` | Optional on API 33+: playback controls in the notification drawer. |
| `WAKE_LOCK` | CPU availability during user-started playback, without keeping the screen on. |

The app does not request fine/background location or retrieve actual location. Optional coarse permission follows the bundled Play Services mock-method annotations; it does not turn simulation into real-location tracking. MapLibre's actual-location component is not enabled. [FLP reference](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)

Before rollout:

1. Verify fresh and upgraded installs obtain the new OpenFreeMap/Geoapify online-planning consent before online requests. A previous Google consent is not the new provider choice. Disable online planning during map loading/search/routing and confirm cancellation and stale-response handling; loaded local playback should continue.
2. Inspect storage and traffic in the final binary. Check ordered manual drafts, in-memory search/route choices, encrypted personal key, map cache and disabled backup. Any provider-derived via must cause all plan coordinates to be omitted from saved drafts, so a relaunch cannot silently restore a trip without it. Unchanged prefilled coordinates must retain their existing provenance. Never log keys, full credential-bearing request URLs or private coordinates in test reports.
3. Finalize [Privacy policy draft](PRIVACY-POLICY-DRAFT.md). Publish HTTPS privacy, application terms and complete corresponding-source pages; set `PUBLIC_PRIVACY_URL`, `PUBLIC_TERMS_URL`, `PUBLIC_SOURCE_URL`. Fill publisher/contact, access model, retention and request-handling details. The in-app summary is not a final publisher policy.
4. Complete Data Safety from the final data flows and provider contracts. Include map areas, entered search text/coordinates, ordered vias, route preferences, routing credentials and request metadata as applicable. Geoapify and OpenFreeMap have their own logging/retention; consent and absence of app accounts do not establish “no data collected.” Review optional Play Services processing too. [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en), [Data Safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469)
5. Run physical-device tests, including denied optional permissions, screen-off playback, Stop cleanup, receiving-app behavior and failure recovery. Fixture tests cannot establish these outcomes.

## Source, notices and listing

Application code is GPL-3.0-or-later with the scoped Google linking permission in [COPYING-EXCEPTION.md](COPYING-EXCEPTION.md). Keep `LICENSE`, source-header references, Matías Castillo Felmer/FakeTraveler attribution and `UPSTREAM.md`. The exception does not grant upstream authors' or Google's rights. Publish complete corresponding source for each distributed application version, modifications and necessary build instructions; include an accessible source link with the app. An upstream-only link is insufficient.

MapLibre uses BSD-2-Clause; map data and styles retain their own terms. Optional Play Services dependencies retain applicable Google terms. Google's OSS licenses Gradle plugin generates dependency notices; inspect the final packaged metadata and in-app viewer, including native library credits. Preserve the Gradle wrapper's Apache-2.0 notices and all bundled notices.

Use [Store listing draft](STORE-LISTING.md), replacing its routing-access and publisher placeholders. Keep developer simulation as the stated use case. Do not claim “undetectable,” universal third-party support, WhatsApp verification, guaranteed smoothness or Play approval. Mock fixes may be ignored or rejected. [Deceptive Behavior policy](https://support.google.com/googleplay/android-developer/answer/17006354?hl=en)

Attach actual signed-release screenshots and device evidence, complete content rating/ads/data/FGS declarations, and review the staged release. Builds and documentation are preparation; provider activation, device compatibility and store approval remain separate checks.
