// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import org.junit.Test;
import static org.junit.Assert.*;

/** The visual marker must visit bends between service updates, never cut across them. */
public class RouteSamplingTest {
    private static final double[][] CORNER = {{0,0},{0,0.001},{0.001,0.001}};
    private static final double EPS = 1e-9;

    @Test public void sampleFollowsNinetyDegreeCornerInsteadOfEndpointChord() {
        RouteEngine engine = new RouteEngine(CORNER, 5, "once");
        double segmentLength = engine.current().totalMeters / 2;
        RouteEngine.Sample before = engine.sampleAtDistance(segmentLength * 0.5);
        RouteEngine.Sample atCorner = engine.sampleAtDistance(segmentLength);
        RouteEngine.Sample after = engine.sampleAtDistance(segmentLength * 1.5);
        assertPosition(before, 0, 0.0005);
        assertPosition(atCorner, 0, 0.001);
        assertPosition(after, 0.0005, 0.001);
        assertEquals(90, before.bearingDegrees, 1e-6);
        assertEquals(0, atCorner.bearingDegrees, 1e-6);
        assertEquals(0, after.bearingDegrees, 1e-6);
        assertTrue(atCorner.longitude > 0.0009); // A start/end chord would be at longitude 0.0005.
    }

    @Test public void onceClampsAtArrivalAndCanStillSampleEarlierPositions() {
        RouteEngine engine = new RouteEngine(CORNER, 5, "once");
        double length = engine.current().totalMeters;
        RouteEngine.Sample end = engine.sampleAtDistance(length + 1000);
        assertPosition(end, 0.001, 0.001);
        assertTrue(end.arrived);
        assertEquals(0, end.speedMps, 0);
        assertEquals(1, end.progress, 0);
        engine.advance(length / (5 / 3.6) + 1);
        RouteEngine.Sample halfway = engine.sampleAtDistance(length / 4);
        assertPosition(halfway, 0, 0.0005);
        assertFalse(halfway.arrived);
        assertTrue(engine.current().arrived);
    }

    @Test public void pingpongSamplesReverseGeometryAndEndpointTurn() {
        RouteEngine engine = new RouteEngine(CORNER, 5, "pingpong");
        double length = engine.current().totalMeters;
        RouteEngine.Sample turned = engine.sampleAtDistance(length);
        assertPosition(turned, 0.001, 0.001);
        assertEquals(180, turned.bearingDegrees, 1e-6);
        assertEquals(1, turned.leg);
        RouteEngine.Sample reverse = engine.sampleAtDistance(length * 1.25);
        assertPosition(reverse, 0.0005, 0.001);
        assertEquals(180, reverse.bearingDegrees, 1e-6);
        RouteEngine.Sample reverseCorner = engine.sampleAtDistance(length * 1.5);
        assertPosition(reverseCorner, 0, 0.001);
        assertEquals(270, reverseCorner.bearingDegrees, 1e-6);
        RouteEngine.Sample returned = engine.sampleAtDistance(length * 2);
        assertPosition(returned, 0, 0);
        assertEquals(90, returned.bearingDegrees, 1e-6);
        assertEquals(2, returned.leg);
        assertFalse(returned.arrived);
    }

    @Test public void loopIncludesClosingSegmentAndWraps() {
        RouteEngine engine = new RouteEngine(new double[][]{{0,0},{0,0.001}}, 5, "loop");
        double length = engine.current().totalMeters;
        assertPosition(engine.sampleAtDistance(length * 0.75), 0, 0.0005);
        assertEquals(270, engine.sampleAtDistance(length * 0.75).bearingDegrees, 1e-6);
        RouteEngine.Sample wrap = engine.sampleAtDistance(length);
        assertPosition(wrap, 0, 0);
        assertEquals(1, wrap.leg);
        assertFalse(wrap.arrived);
    }

    @Test public void dateLineUsesShortGreatCircleInBothDirections() {
        RouteEngine engine = new RouteEngine(new double[][]{{0,179.999},{0,-179.999}}, 5, "pingpong");
        double length = engine.current().totalMeters;
        RouteEngine.Sample outward = engine.sampleAtDistance(length / 2);
        assertEquals(0, outward.latitude, EPS);
        assertEquals(180, Math.abs(outward.longitude), 1e-8);
        assertEquals(90, outward.bearingDegrees, 1e-6);
        RouteEngine.Sample inward = engine.sampleAtDistance(length * 1.5);
        assertEquals(180, Math.abs(inward.longitude), 1e-8);
        assertEquals(270, inward.bearingDegrees, 1e-6);
    }

    @Test public void samplingDoesNotMutatePausedPlayheadOrSpeed() {
        RouteEngine engine = new RouteEngine(CORNER, 8, "pingpong");
        engine.advance(20); engine.setPaused(true);
        RouteEngine.Sample before = engine.current();
        RouteEngine.Sample independent = engine.sampleAtDistance(before.totalMeters * 1.75);
        assertFalse(independent.paused);
        assertEquals(8 / 3.6, independent.speedMps, 0);
        assertSampleEquals(before, engine.current());
        engine.setPaused(false);
        RouteEngine.Sample resumed = engine.advance(1);
        assertEquals(before.distanceMeters + 8 / 3.6, resumed.distanceMeters, 1e-8);
    }

    @Test public void staticAndZeroLengthDoNotDivideByZero() {
        RouteEngine fixed = new RouteEngine(new double[][]{{52.5,13.4}}, 5, "static");
        RouteEngine.Sample stationary = fixed.sampleAtDistance(Double.MAX_VALUE);
        assertPosition(stationary, 52.5, 13.4);
        assertFalse(stationary.arrived);
        assertEquals(0, stationary.speedMps, 0);
        RouteEngine zero = new RouteEngine(new double[][]{{0,0},{0,0}}, 5, "once");
        assertTrue(zero.sampleAtDistance(0).arrived);
    }

    @Test public void rejectsInvalidDistancesAndKeepsState() {
        RouteEngine engine = new RouteEngine(CORNER, 5, "once");
        RouteEngine.Sample before = engine.current();
        for (double invalid : new double[]{-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            try { engine.sampleAtDistance(invalid); fail("Expected invalid distance rejection"); }
            catch (IllegalArgumentException expected) { /* expected */ }
        }
        assertSampleEquals(before, engine.current());
    }

    @Test public void hugeFiniteDistancesSaturateCounterWithoutNonfinitePosition() {
        RouteEngine engine = new RouteEngine(CORNER, 5, "pingpong");
        RouteEngine.Sample sample = engine.sampleAtDistance(Double.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, sample.leg);
        assertTrue(Double.isFinite(sample.latitude));
        assertTrue(Double.isFinite(sample.longitude));
        assertTrue(sample.progress >= 0 && sample.progress <= 1);
    }

    private static void assertPosition(RouteEngine.Sample sample, double latitude, double longitude) {
        assertEquals(latitude, sample.latitude, EPS);
        assertEquals(longitude, sample.longitude, EPS);
    }
    private static void assertSampleEquals(RouteEngine.Sample expected, RouteEngine.Sample actual) {
        assertPosition(actual, expected.latitude, expected.longitude);
        assertEquals(expected.bearingDegrees, actual.bearingDegrees, 0);
        assertEquals(expected.speedMps, actual.speedMps, 0);
        assertEquals(expected.distanceMeters, actual.distanceMeters, 0);
        assertEquals(expected.progress, actual.progress, 0);
        assertEquals(expected.leg, actual.leg);
        assertEquals(expected.paused, actual.paused);
        assertEquals(expected.arrived, actual.arrived);
    }
}
