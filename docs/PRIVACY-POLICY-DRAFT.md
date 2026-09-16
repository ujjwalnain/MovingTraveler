# Moving Traveler privacy policy — publisher draft

**Not ready to publish.** Draft updated 15 September 2026 for **1.4.0 / code 5**. Replace bracketed fields, verify the final binary and provider arrangements, and remove this notice before publication. This is not a submitted Google Play Data Safety declaration. Setup is described in [Routing setup](ROUTING-SETUP.md).

Effective date: **[EFFECTIVE DATE]**  
Publisher: **[PUBLISHER LEGAL/DISPLAY NAME]**  
Privacy contact: **[PRIVACY CONTACT EMAIL OR FORM]**  
Privacy policy: **[PUBLIC HTTPS PRIVACY URL]**  
Application terms: **[PUBLIC HTTPS TERMS URL]**  
Corresponding source: **[PUBLIC HTTPS SOURCE URL FOR VERSION 1.4.0]**

Moving Traveler holds a simulated location or plays a selected route through up to six ordered intermediate points at your chosen pace using Android's developer mock-location feature. Via points shape the path without adding timed pauses. Application code does not retrieve your actual device location or enable a map location-tracking layer. Optional approximate-location permission enables the Google Play Services mock-publication path; declining it leaves Android framework simulation available. The app does not access WhatsApp messages, contacts or accounts, send messages or start location sharing in another app.

## Information on your device

The app stores eligible manual coordinate drafts, ordered manual vias, pace, travel mode, preferred route type, playback options, permission-prompt choices and the online-planning preference locally. A personal Geoapify key entered in **Settings → Routing access** is encrypted in app-local storage with an Android Keystore-backed AES-GCM key. Saved access is reused automatically, with no plaintext key shown in the interface; replacement is optional. It is sent to Geoapify when needed to authenticate a search or route request. A provider access error does not erase it. Encryption at rest is not a guarantee against a compromised device, and does not protect a publisher key compiled into an APK from extraction.

Loaded provider places, their coordinates/labels and all returned route choices/geometry remain in process memory for the current plan rather than a route-history database. Provider-derived endpoints are omitted from saved drafts. If **any via is provider-derived**, all coordinates of that draft, including otherwise manual endpoints/vias, are omitted so a later launch cannot restore an incomplete trip that silently skips it. Accepting unchanged prefilled coordinates retains the point's existing provenance. Rotation can retain the in-memory plan; Stop does not necessarily erase it. MapLibre may maintain its own map cache in app storage. These caches are distinct from the app's coordinate draft. There is no Moving Traveler account, advertising feature or added analytics SDK. The personal Geoapify account is managed by Geoapify outside this app.

Android backup is disabled. Use **Android Settings → Apps → Moving Traveler → Storage → Clear storage**, or uninstall, to remove app-local preferences, routing credentials and caches. A publisher key compiled into the installed APK is not removed by clearing a personal key or preferences. Removing a personal key uses the publisher key when present. Revoke a Geoapify key through that provider when necessary. Clearing this app does not erase provider records or storage maintained independently by Google Play Services. Stop ends simulation and does not erase preferences.

## Optional online planning

The app asks whether to enable online planning before loading its online map or sending searches/routes. The choice covers **OpenFreeMap** for the basemap and **Geoapify** for search and routing. Coordinate-only planning supports explicit straight segments through ordered points and fixed locations without new planning requests. Upgrading from the retired Google planning setup requires the OpenFreeMap/Geoapify provider choice.

| Feature | Data sent and recipient | Purpose |
| --- | --- | --- |
| Map display | OpenFreeMap receives requested map areas and map-resource requests, along with network metadata. | Display the area you select. |
| Search | Geoapify receives entered search text and the routing API key, plus network metadata. | Find a place and return coordinates/labels. |
| Route planning | Geoapify receives start, ordered vias, destination, travel profile, route preference and API key, plus network metadata. | Return route choices and their geometry for local simulation. |

The app sends these requests directly over HTTPS; this version does not require a publisher proxy. **[PUBLISHER: CONFIRM WHETHER THE RELEASE USES PERSONAL KEYS, A SHARED KEY OR A MODIFIED SERVICE ARRANGEMENT; DISCLOSE ANY ADDITIONAL RECIPIENTS.]** Searches and coordinates can reveal real places of interest even if a journey is fictional. Each replan can send the selected points in two routing requests for Walk/Cycle or three for Drive to obtain different preferences. Selecting an already loaded choice, changing only pace and Pause/Resume do not request another route. A loaded simulation runs locally; a visible online map can still fetch tiles as its view changes.

OpenFreeMap states that ordinary logs omit IP addresses, while security incidents can enable temporary IP logging for up to 30 days; its anonymized logs can be retained indefinitely. It may use Cloudflare. See [OpenFreeMap privacy](https://openfreemap.org/privacy/).

Geoapify says API processing retains request body, headers, IP address and timestamp for service operation and usage statistics. Successful-request records are generally kept no longer than 24 hours; this is not a universal deletion deadline for every record. Its policy describes EU hosting and infrastructure providers. Geoapify account registration follows its separate account-data practices. See [Geoapify privacy](https://www.geoapify.com/privacy-policy/).

Turning online planning off cancels pending app planning work and prevents new map/search/route requests while allowing a loaded local simulation to continue. It cannot recall completed requests or erase provider logs; work already in progress may finish. The setting does not govern Android or Play Services' independent system activity. **[PUBLISHER: ADD APPLICABLE PROCESSING LOCATIONS, LEGAL BASIS, TRANSFER INFORMATION AND ANY ADDITIONAL RETENTION/LOGGING FOR YOUR FINAL ARRANGEMENT.]**

## Simulation and permissions

The app publishes selected synthetic coordinates, speed, bearing, accuracy and timestamps to Android test providers. When Play Services is available and optional approximate-location permission is granted, it also uses Google's Fused Location Provider mock APIs. It does not use that permission to request actual location. Simulated values remain marked as mock; other apps decide whether to accept them and may cache the last fix.

- **Developer options → Select mock location app** authorizes simulation.
- Approximate-location permission is optional for Google compatibility. Fine/background-location permission is not requested.
- Internet/network permissions support optional maps/search/routes.
- Notification permission on Android 13+ makes playback controls visible in the notification drawer. In-app Stop remains available if denied.
- A foreground service and partial wake lock keep user-started playback updating while another app is open or the display is off. The lock does not keep the display on and is released during normal cleanup.

Pause and arrival hold the current simulated point until Stop. Stop requests removal of owned test providers, disables the app's Play Services mock mode when active and ends the service. A killed process or reboot does not automatically resume a journey. Recovery and delivery can vary by device and receiving app.

You can stop playback, change the selected mock app, disable online planning, revoke optional permissions, clear storage or uninstall. If you manually share a location through another app, that app governs recipients and retention; tell recipients it is simulated.

## Terms, contact and changes

Provider information: [OpenFreeMap terms](https://openfreemap.org/tos/), [Geoapify terms](https://www.geoapify.com/terms-and-conditions/), [OpenStreetMap data license](https://www.openstreetmap.org/copyright) and [Google privacy](https://policies.google.com/privacy) for optional Play Services. Application terms are **[PUBLIC APPLICATION TERMS URL]**; corresponding source is **[PUBLIC SOURCE URL]**.

For questions or privacy requests, contact **[PRIVACY CONTACT]**. **[PUBLISHER: EXPLAIN REQUEST VERIFICATION, RESPONSE PROCESS, APPLICABLE RIGHTS AND RETENTION OBLIGATIONS.]** Provider records may require contacting the provider directly. Update this policy and effective date when data practices change.
