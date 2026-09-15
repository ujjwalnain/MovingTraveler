# Moving Traveler

Android mock-location simulator with adjustable movement pace, route planning, pause/resume and foreground playback. The latest version adds ordered via points, route choices and a clear saved-key state. Other apps decide whether to accept mock locations.

## Downloads

Each release includes the original debug APK, unsigned AAB, exact source ZIP and available preview images:

| Version | Release and downloads | Source archive |
| --- | --- | --- |
| **1.4.0 — latest** | [v1.4.0](https://github.com/ujjwalnain/MovingTraveler/releases/tag/v1.4.0) | [Source ZIP](MovingTraveler-1.4.0-source.zip) |
| 1.3.0 | [v1.3.0](https://github.com/ujjwalnain/MovingTraveler/releases/tag/v1.3.0) | [Source ZIP](MovingTraveler-1.3.0-source.zip) |
| 1.2.0 | [v1.2.0](https://github.com/ujjwalnain/MovingTraveler/releases/tag/v1.2.0) | [Source ZIP](MovingTraveler-1.2.0-source.zip) |
| 1.1.0 | [v1.1.0](https://github.com/ujjwalnain/MovingTraveler/releases/tag/v1.1.0) | [Source ZIP](MovingTraveler-1.1.0-source.zip) |
| 1.0.0 | [v1.0.0](https://github.com/ujjwalnain/MovingTraveler/releases/tag/v1.0.0) | [Source ZIP](MovingTraveler-source.zip) |

This repository index keeps the original source archives and a portable source-history backup. Extract a version's ZIP to inspect or build its source; this index does not contain an extracted source tree. `MovingTraveler-source.zip` is v1.0.0.

Versions 1.2–1.4 use MapLibre/OpenFreeMap for the map and a personal Geoapify key for routing/search. No routing key is included. Version 1.1's Google integration is unconfigured; v1.0 uses driving geometry for all speed presets. Follow each archive's README and setup guide.

## Restore complete source history

Download [MovingTraveler-all-versions.bundle](MovingTraveler-all-versions.bundle), then run:

```sh
git clone MovingTraveler-all-versions.bundle MovingTraveler-history
cd MovingTraveler-history
git tag
git checkout v1.4.0
```

The bundle contains five chronological source-import commits and version tags, preserving each archive's file contents and executable modes. Import timestamps record when the archives were imported, not original development dates. This restored history is separate from the repository index's browser-upload history; use the bundle to obtain version-specific source commits.

[SHA256SUMS.txt](SHA256SUMS.txt) covers the five original ZIPs, bundle, license and upstream notice. The source archives are unchanged from their original distributions.

## Build and testing status

Extract the desired source ZIP, enter its `MovingTraveler` directory and use JDK 17/21, Android SDK Platform 36 and Build Tools 36.0.0:

```sh
sh ./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug bundleRelease
```

APKs are debug-signed test builds. AABs are unsigned and require publisher signing and release preparation. These are not Play-approved releases; live route quality, physical-device operation and WhatsApp live-location acceptance still require testing. See each archive's `docs/VERIFICATION.md` and `docs/PLAY-RELEASE.md` for version-specific checks and limitations.

Application source is GPL-3.0-or-later under [LICENSE](LICENSE), with the scoped linking permission and dependency notices included in each source archive. [UPSTREAM.md](UPSTREAM.md) preserves project attribution.
