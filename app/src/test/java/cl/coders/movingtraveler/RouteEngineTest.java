// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

/** Standalone regression suite: compile with javac and run this main class. */
public final class RouteEngineTest {
    private static int assertions;

    public static void main(String[] args) {
        distanceTimeAndBearing();
        crossesVerticesAndHoldsDestination();
        pauseResumeAndSpeedChanges();
        pingpongRetainsOvershoot();
        loopClosesWithoutTeleporting();
        duplicatePointsAndStaticMode();
        datelinePolesAndAntipodes();
        invalidInputs();
        inputArrayIsCopied();
        fineAndCoarseStepsAgree();
        System.out.println("RouteEngine: " + assertions + " assertions passed");
    }

    private static void distanceTimeAndBearing() {
        RouteEngine engine = route(new double[][] {{0, 0}, {0, 1}}, 36, "once");
        near(engine.current().totalMeters, 111_195.0802335, 0.00001, "one degree distance");
        RouteEngine.Sample sample = engine.advance(10);
        near(sample.distanceMeters, 100, 1e-9, "10 m/s for ten seconds");
        near(sample.latitude, 0, 1e-10, "equatorial latitude");
        near(sample.longitude, 100 / sample.totalMeters, 1e-10, "distance interpolation");
        near(sample.bearingDegrees, 90, 1e-9, "east bearing");
        near(sample.speedMps, 10, 1e-9, "meters per second");
        check(!sample.arrived, "moving sample is not arrived");
    }

    private static void crossesVerticesAndHoldsDestination() {
        RouteEngine engine = route(new double[][] {{0, 0}, {0, 0.001}, {0.001, 0.001}}, 36, "once");
        double half = engine.current().totalMeters / 2;
        RouteEngine.Sample sample = engine.advance((half + 25) / 10);
        near(sample.longitude, 0.001, 1e-9, "turn longitude");
        near(sample.distanceMeters, half + 25, 1e-8, "vertex overshoot retained");
        near(sample.bearingDegrees, 0, 1e-7, "north after turn");
        sample = engine.advance(1000);
        check(sample.arrived, "arrival");
        near(sample.progress, 1, 0, "arrival progress");
        near(sample.speedMps, 0, 0, "arrival speed");
        near(sample.latitude, 0.001, 1e-9, "destination latitude");
        near(sample.longitude, 0.001, 1e-9, "destination longitude");
        near(engine.advance(100).distanceMeters, sample.totalMeters, 0, "arrival holds");
    }

    private static void pauseResumeAndSpeedChanges() {
        RouteEngine engine = route(new double[][] {{0, 0}, {0, 1}}, 36, "once");
        engine.advance(10);
        engine.setPaused(true);
        RouteEngine.Sample sample = engine.advance(300);
        near(sample.distanceMeters, 100, 0, "pause holds position");
        near(sample.speedMps, 0, 0, "paused reports zero speed");
        check(sample.paused, "pause flag");
        engine.setSpeedKmh(72);
        engine.setPaused(false);
        sample = engine.advance(5);
        near(sample.distanceMeters, 200, 1e-9, "resume uses new speed without catching up");
        near(sample.speedMps, 20, 1e-9, "new speed");
    }

    private static void pingpongRetainsOvershoot() {
        RouteEngine engine = route(new double[][] {{0, 0}, {0, 0.001}}, 36, "pingpong");
        double total = engine.current().totalMeters;
        RouteEngine.Sample sample = engine.advance((total + 30) / 10);
        check(sample.leg == 1, "reverse leg");
        near(sample.distanceMeters, 30, 1e-9, "reverse overshoot");
        near(sample.longitude, 0.001 * (1 - 30 / total), 1e-10, "reverse position");
        near(sample.bearingDegrees, 270, 1e-9, "reverse bearing");
        check(!sample.arrived, "pingpong runs continuously");
        sample = engine.advance((total * 4 + 7) / 10);
        check(sample.leg == 5, "multiple legs counted");
        near(sample.distanceMeters, 37, 1e-8, "multiple leg remainder");
        near(sample.bearingDegrees, 270, 1e-9, "odd traversal points west");
        engine = route(new double[][] {{0, 0}, {0, 0.001}}, 36, "pingpong");
        sample = engine.advance(total / 10);
        check(sample.leg == 1, "exact turn begins next leg");
        near(sample.longitude, 0.001, 1e-10, "exact turn at destination");
        near(sample.distanceMeters, 0, 1e-9, "exact turn resets progress");
        near(sample.bearingDegrees, 270, 1e-9, "exact turn reverses bearing");
    }

    private static void loopClosesWithoutTeleporting() {
        RouteEngine engine = route(new double[][] {{0, 0}, {0, 0.001}}, 36, "loop");
        double total = engine.current().totalMeters;
        near(total, 222.390160467, 1e-8, "loop includes return segment");
        RouteEngine.Sample sample = engine.advance(total * 0.75 / 10);
        near(sample.longitude, 0.0005, 1e-9, "loop returns continuously");
        near(sample.bearingDegrees, 270, 1e-8, "loop return bearing");
        sample = engine.advance(total * 0.5 / 10);
        check(sample.leg == 1, "loop count");
        near(sample.longitude, 0.0005, 1e-9, "loop remainder after wrap");
        near(sample.bearingDegrees, 90, 1e-8, "new loop direction");
    }

    private static void duplicatePointsAndStaticMode() {
        RouteEngine engine = route(new double[][] {{1, 2}, {1, 2}, {1, 2}}, 5, "once");
        RouteEngine.Sample sample = engine.advance(999);
        near(sample.latitude, 1, 0, "duplicates preserve latitude");
        near(sample.longitude, 2, 0, "duplicates preserve longitude");
        check(sample.arrived && sample.speedMps == 0, "zero-length route is complete");
        engine = route(new double[][] {{0, 0}, {0, 0}, {0, 0.001}, {0, 0.001}}, 36, "once");
        near(engine.advance(5).distanceMeters, 50, 1e-9, "duplicates do not alter speed");
        engine = route(new double[][] {{51.5, -0.12}}, 5, "static");
        sample = engine.advance(1e100);
        near(sample.latitude, 51.5, 0, "static latitude");
        near(sample.longitude, -0.12, 0, "static longitude");
        check(!sample.arrived && sample.speedMps == 0, "static remains active at zero speed");
    }

    private static void datelinePolesAndAntipodes() {
        RouteEngine engine = route(new double[][] {{0, 179.9}, {0, -179.9}}, 36, "once");
        RouteEngine.Sample sample = engine.advance(engine.current().totalMeters / 20);
        near(Math.abs(sample.longitude), 180, 1e-8, "dateline shortest path");
        near(sample.totalMeters, 22_239.0160467, 0.00001, "dateline distance");
        near(sample.bearingDegrees, 90, 1e-8, "dateline east bearing");
        engine = route(new double[][] {{89, 0}, {89, 180}}, 200, "once");
        sample = engine.advance(engine.current().totalMeters / (2 * (200 / 3.6)));
        near(sample.latitude, 90, 1e-8, "great circle over north pole");
        check(Double.isFinite(sample.bearingDegrees), "pole bearing finite");
        engine = route(new double[][] {{0, 0}, {0, 180}}, 200, "once");
        sample = engine.advance(engine.current().totalMeters / (2 * (200 / 3.6)));
        near(sample.latitude, 0, 1e-8, "antipodal path stable");
        near(sample.longitude, 90, 1e-8, "antipodal midpoint deterministic");
        engine = route(new double[][] {{90, -150}, {90, 40}}, 10, "once");
        near(engine.current().totalMeters, 0, 0, "equivalent pole positions collapse");
    }

    private static void invalidInputs() {
        double[][] valid = {{0, 0}, {1, 1}};
        fails(() -> route(null, 5, "once"), "null route");
        fails(() -> route(new double[0][], 5, "once"), "empty route");
        fails(() -> route(new double[][] {{0, 0}}, 5, "once"), "one point moving route");
        fails(() -> route(new double[][] {{91, 0}}, 5, "static"), "invalid latitude");
        fails(() -> route(new double[][] {{0, 181}}, 5, "static"), "invalid longitude");
        fails(() -> route(new double[][] {{Double.NaN, 0}}, 5, "static"), "NaN coordinate");
        fails(() -> route(new double[][] {{0, Double.POSITIVE_INFINITY}}, 5, "static"), "infinite coordinate");
        fails(() -> route(new double[][] {{0, 0, 1}}, 5, "static"), "malformed point");
        fails(() -> route(new double[][] {null}, 5, "static"), "null point");
        fails(() -> route(new double[20_001][2], 5, "once"), "too many points");
        fails(() -> route(valid, 0.49, "once"), "too slow");
        fails(() -> route(valid, 201, "once"), "too fast");
        fails(() -> route(valid, Double.NaN, "once"), "NaN speed");
        fails(() -> route(valid, Double.POSITIVE_INFINITY, "once"), "infinite speed");
        fails(() -> route(valid, 5, "invalid"), "invalid mode");
        fails(() -> route(valid, 5, null), "null mode");
        RouteEngine engine = route(valid, 36, "once");
        fails(() -> engine.advance(-1), "negative elapsed time");
        fails(() -> engine.advance(Double.NaN), "NaN elapsed time");
        fails(() -> engine.advance(Double.POSITIVE_INFINITY), "infinite elapsed time");
        fails(() -> engine.advance(Double.MAX_VALUE), "overflow elapsed time");
        fails(() -> engine.setSpeedKmh(-5), "invalid speed update");
        near(engine.current().speedMps, 10, 0, "invalid updates leave state intact");
        near(engine.current().distanceMeters, 0, 0, "invalid time leaves state intact");
    }

    private static void inputArrayIsCopied() {
        double[][] points = {{0, 0}, {0, 1}};
        RouteEngine engine = route(points, 36, "once");
        points[0][0] = 80;
        points[1][1] = -50;
        RouteEngine.Sample sample = engine.advance(100);
        near(sample.latitude, 0, 1e-10, "caller cannot mutate route");
        near(sample.totalMeters, 111_195.0802335, 0.00001, "caller cannot mutate length");
    }

    private static void fineAndCoarseStepsAgree() {
        double[][] points = {{37.7749, -122.4194}, {37.7751, -122.4189}, {37.7762, -122.4190}};
        RouteEngine coarse = route(points, 42, "pingpong");
        RouteEngine fine = route(points, 42, "pingpong");
        RouteEngine.Sample expected = coarse.advance(3000);
        for (int i = 0; i < 12_000; i++) {
            fine.advance(0.25);
        }
        RouteEngine.Sample actual = fine.current();
        near(actual.distanceMeters, expected.distanceMeters, 1e-6, "no accumulated leg overshoot");
        near(actual.latitude, expected.latitude, 1e-9, "fine/coarse latitude");
        near(actual.longitude, expected.longitude, 1e-9, "fine/coarse longitude");
        check(actual.leg == expected.leg, "fine/coarse leg count");
        near(actual.bearingDegrees, expected.bearingDegrees, 1e-5, "fine/coarse bearing");
    }

    private static RouteEngine route(double[][] points, double speed, String mode) {
        return new RouteEngine(points, speed, mode);
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void fails(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            assertions++;
            return;
        }
        throw new AssertionError(message + ": expected IllegalArgumentException");
    }
}
