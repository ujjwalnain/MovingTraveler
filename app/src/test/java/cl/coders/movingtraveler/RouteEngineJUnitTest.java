// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import org.junit.Test;

/** Runs the same portable regression suite through Gradle's JUnit runner. */
public final class RouteEngineJUnitTest {
    @Test
    public void routePlaybackRegressionSuite() {
        RouteEngineTest.main(new String[0]);
    }
}
