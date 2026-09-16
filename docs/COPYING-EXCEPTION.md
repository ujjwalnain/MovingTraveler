# Moving Traveler — additional linking permission

## Permission under GNU GPL version 3, section 7

The licensors of the independently authored Moving Traveler application files identified below grant an additional permission to the extent they control those files' rights. You may link or combine those files with Google's Google Play Services client libraries, including `com.google.android.gms:play-services-location` and the dependencies necessary to use the optional mock-publication path, and convey the resulting combination under the applicable terms of those libraries together with GNU GPL version 3 or any later version for the covered Moving Traveler code.

This permission accommodates Google Play Services components distributed under the Android Software Development Kit License Agreement or other applicable Google component terms. It does not relicense those components, grant rights belonging to Google or another third party, or waive compliance with their terms.

All GPL obligations for the covered Moving Traveler code continue except to the extent necessary for the permitted combination. In particular, its corresponding source, modifications and necessary build instructions must remain available as required by the GPL. This additional permission does not require disclosure of source code for proprietary Google components that you are not entitled to distribute.

This permission is optional. It may be removed as allowed by GPL section 7; contributors need not extend it to modifications for which they control the rights. A distributor must confirm that every part of a combination has compatible permissions before relying on this exception.

## Covered files

This permission applies only to the newly and independently authored Moving Traveler application files carrying a notice referring to this document:

- Java application sources under `app/src/main/java/cl/coders/movingtraveler/`.
- New application tests under `app/src/test/java/cl/coders/movingtraveler/` and any new application resources explicitly carrying the same notice.

The project's `LICENSE` remains the unmodified GNU GPL text. This additional permission is a separate supplement, not a replacement license for the repository or its dependencies.

MapLibre map rendering does not require this additional permission. Its own license and the map-data/service terms continue to apply. Version 1.4.0 does not bundle Google Maps or Firebase App Check.

## Provenance and exclusions

Moving Traveler's application code was independently authored for this project using the standard Android mock-location mechanism demonstrated by [FakeTraveler](https://github.com/mcastillof/faketraveler). No upstream FakeTraveler Java application source is included in this application. A reference to an adapted mechanism acknowledges conceptual inspiration; it is not a claim that FakeTraveler's copyright holder authorized this exception.

The following retain their original terms and notices and receive no permission purportedly granted on their authors' behalf:

- FakeTraveler's preserved README (`UPSTREAM.md`), copyright attribution to Matías Castillo Felmer and upstream license notices.
- Any historical Leaflet JavaScript/CSS retained in older version archives and their BSD-2-Clause notices. Leaflet is not included in version 1.4.0.
- The Gradle wrapper and its Apache-2.0 notices.
- MapLibre Native (BSD-2-Clause), OpenFreeMap styles/services, OpenStreetMap/OpenMapTiles data, Google Play Services and all other third-party code and materials.

This permission must not be used to cover subsequently copied or adapted upstream GPL implementation code without the necessary rights holders' consent. Merely rewriting lines or omitting attribution does not establish independent authorship. Keep the provenance record and notices with each distributed source release.

The approach follows the Free Software Foundation's [GPL-incompatible library guidance](https://www.gnu.org/licenses/gpl-faq.en.html#GPLIncompatibleLibs), which permits additional linking permission from the relevant rights holders and distinguishes their own code from other authors' GPL code. Google components remain subject to their [applicable SDK terms](https://developer.android.com/studio/terms).
