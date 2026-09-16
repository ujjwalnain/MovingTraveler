// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe, Android-independent playback of a route on a spherical Earth.
 * Call {@link #advance(double)} with elapsed monotonic time, not wall-clock time.
 * Segments follow the shortest great circle; exactly antipodal points follow a
 * deterministic eastward great circle. No point is retained from the caller's array.
 */
public final class RouteEngine {
    public static final double EARTH_RADIUS_METERS = 6_371_008.8;
    public static final int MAX_POINTS = 20_000;
    public static final double MIN_SPEED_KMH = 0.5;
    public static final double MAX_SPEED_KMH = 200.0;

    private final String mode;
    private final double firstLatitude;
    private final double firstLongitude;
    private final Segment[] segments;
    private final double totalMeters;
    private double speedMps;
    private double distanceInLeg;
    private boolean paused;
    private boolean reverse;
    private boolean arrived;
    private int leg;

    /**
     * Modes are "once", "pingpong", "loop", or "static". A loop includes an
     * implicit last-to-first segment. A pingpong reverses along the supplied route.
     * Static mode holds the first point indefinitely and never reports arrival.
     */
    public RouteEngine(double[][] points, double speedKmh, String mode) {
        if (!"once".equals(mode) && !"pingpong".equals(mode)
                && !"loop".equals(mode) && !"static".equals(mode)) {
            throw new IllegalArgumentException("Unknown playback mode");
        }
        if (points == null || points.length == 0 || points.length > MAX_POINTS) {
            throw new IllegalArgumentException("Route must contain 1 to 20000 points");
        }
        if (!"static".equals(mode) && points.length < 2) {
            throw new IllegalArgumentException("A moving route needs at least two points");
        }
        for (double[] point : points) {
            if (point == null || point.length != 2
                    || !finite(point[0]) || !finite(point[1])
                    || point[0] < -90 || point[0] > 90
                    || point[1] < -180 || point[1] > 180) {
                throw new IllegalArgumentException("Each point must be a valid [latitude, longitude]");
            }
        }
        this.mode = mode;
        firstLatitude = points[0][0];
        firstLongitude = points[0][1];
        setSpeedKmh(speedKmh);
        List<Segment> built = new ArrayList<>();
        double length = 0;
        if (!"static".equals(mode)) {
            int count = points.length - 1 + ("loop".equals(mode) ? 1 : 0);
            for (int i = 0; i < count; i++) {
                Segment segment = new Segment(points[i], points[(i + 1) % points.length], length);
                // This also collapses equivalent poles and +180/-180 longitudes.
                if (segment.length > 0.0000001) {
                    built.add(segment);
                    length += segment.length;
                }
            }
        }
        segments = built.toArray(new Segment[0]);
        totalMeters = length;
        arrived = totalMeters == 0 && !"static".equals(mode);
    }

    public synchronized void setSpeedKmh(double speedKmh) {
        if (!finite(speedKmh) || speedKmh < MIN_SPEED_KMH || speedKmh > MAX_SPEED_KMH) {
            throw new IllegalArgumentException("Speed must be between 0.5 and 200 km/h");
        }
        speedMps = speedKmh / 3.6;
    }

    public synchronized void setPaused(boolean paused) {
        this.paused = paused;
    }

    /**
     * Advances by elapsed seconds at the currently selected speed. Time passed
     * while paused is discarded. Large steps retain the remainder across turns.
     */
    public synchronized Sample advance(double elapsedSeconds) {
        if (!finite(elapsedSeconds) || elapsedSeconds < 0) {
            throw new IllegalArgumentException("Elapsed time must be finite and nonnegative");
        }
        if (paused || arrived || totalMeters == 0 || elapsedSeconds == 0) {
            return current();
        }
        double movement = elapsedSeconds * speedMps;
        if (!finite(movement)) {
            throw new IllegalArgumentException("Elapsed time is too large");
        }
        if ("once".equals(mode)) {
            if (movement >= totalMeters - distanceInLeg) {
                distanceInLeg = totalMeters;
                arrived = true;
            } else {
                distanceInLeg += movement;
            }
        } else {
            // Reduce before addition so even very large finite steps cannot overflow.
            double completeLegs = Math.floor(movement / totalMeters);
            double remainder = movement % totalMeters;
            boolean oddLegs = movement % (totalMeters * 2) >= totalMeters;
            if (remainder >= totalMeters - distanceInLeg) {
                remainder -= totalMeters - distanceInLeg;
                completeLegs += 1;
                oddLegs = !oddLegs;
            } else {
                remainder += distanceInLeg;
            }
            distanceInLeg = Math.max(0, remainder);
            if ("pingpong".equals(mode) && oddLegs) {
                reverse = !reverse;
            }
            // The public leg counter saturates; reversal uses independent parity.
            leg = completeLegs >= Integer.MAX_VALUE - leg
                    ? Integer.MAX_VALUE : leg + (int) completeLegs;
        }
        return current();
    }

    public synchronized Sample current() {
        return sampleInLeg(distanceInLeg, reverse, arrived, paused, leg);
    }

    /**
     * Reads a position along the complete route without advancing or changing playback.
     * The distance includes completed traversals. One-way routes clamp at arrival;
     * pingpong routes reverse at each endpoint and loops include their closing segment.
     * Samples ignore the current pause/playhead state, and use the configured speed.
     */
    public synchronized Sample sampleAtDistance(double totalTraveledMeters) {
        if (!finite(totalTraveledMeters) || totalTraveledMeters < 0) {
            throw new IllegalArgumentException("Distance must be finite and nonnegative");
        }
        if (totalMeters == 0) {
            return sampleInLeg(0, false, !"static".equals(mode), false, 0);
        }
        if ("once".equals(mode)) {
            return sampleInLeg(Math.min(totalTraveledMeters, totalMeters), false,
                    totalTraveledMeters >= totalMeters, false, 0);
        }
        double traversals = Math.floor(totalTraveledMeters / totalMeters);
        int traversal = traversals >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) traversals;
        double position = totalTraveledMeters % totalMeters;
        // Parity remains correct even when the public traversal counter saturates.
        boolean backwards = "pingpong".equals(mode)
                && totalTraveledMeters % (totalMeters * 2) >= totalMeters;
        return sampleInLeg(position, backwards, false, false, traversal);
    }

    private Sample sampleInLeg(double legDistance, boolean backwards, boolean atDestination,
                               boolean samplePaused, int traversal) {
        double activeSpeed = samplePaused || atDestination || totalMeters == 0 ? 0 : speedMps;
        if (totalMeters == 0) {
            return new Sample(firstLatitude, firstLongitude, 0, 0, 0, 0,
                    atDestination ? 1 : 0, atDestination, samplePaused, traversal);
        }
        double position = backwards ? totalMeters - legDistance : legDistance;
        // At a vertex, use the segment that movement is about to enter.
        int low = 0;
        int high = segments.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            double end = segments[middle].end;
            if (backwards ? position <= end : position < end) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        Segment segment = segments[low];
        double fraction = clamp((position - segment.start) / segment.length, 0, 1);
        double angle = fraction * segment.angle;
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        double[] point = addScaled(segment.origin, cosine, segment.tangent, sine);
        double latitude = Math.atan2(point[2], Math.hypot(point[0], point[1]));
        double longitude = Math.atan2(point[1], point[0]);
        double[] velocity = addScaled(segment.origin, -sine, segment.tangent, cosine);
        double east = -Math.sin(longitude) * velocity[0] + Math.cos(longitude) * velocity[1];
        double north = -Math.sin(latitude) * Math.cos(longitude) * velocity[0]
                - Math.sin(latitude) * Math.sin(longitude) * velocity[1]
                + Math.cos(latitude) * velocity[2];
        if (backwards) {
            east = -east;
            north = -north;
        }
        double bearing = (Math.toDegrees(Math.atan2(east, north)) + 360) % 360;
        return new Sample(Math.toDegrees(latitude), Math.toDegrees(longitude), bearing,
                activeSpeed, legDistance, totalMeters,
                clamp(legDistance / totalMeters, 0, 1), atDestination, samplePaused, traversal);
    }

    public static final class Sample {
        public final double latitude;
        public final double longitude;
        public final double bearingDegrees;
        public final double speedMps;
        /** Distance traveled in the current traversal, including reverse traversals. */
        public final double distanceMeters;
        public final double totalMeters;
        public final double progress;
        public final boolean arrived;
        public final boolean paused;
        /** Zero-based traversal count; saturates at Integer.MAX_VALUE. */
        public final int leg;

        private Sample(double latitude, double longitude, double bearingDegrees,
                double speedMps, double distanceMeters, double totalMeters,
                double progress, boolean arrived, boolean paused, int leg) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.bearingDegrees = bearingDegrees;
            this.speedMps = speedMps;
            this.distanceMeters = distanceMeters;
            this.totalMeters = totalMeters;
            this.progress = progress;
            this.arrived = arrived;
            this.paused = paused;
            this.leg = leg;
        }
    }

    private static final class Segment {
        final double[] origin;
        final double[] tangent;
        final double angle;
        final double length;
        final double start;
        final double end;

        Segment(double[] from, double[] to, double start) {
            origin = vector(from);
            double[] destination = vector(to);
            double dot = clamp(dot(origin, destination), -1, 1);
            double[] cross = new double[] {
                    origin[1] * destination[2] - origin[2] * destination[1],
                    origin[2] * destination[0] - origin[0] * destination[2],
                    origin[0] * destination[1] - origin[1] * destination[0]};
            angle = Math.atan2(norm(cross), dot);
            length = angle * EARTH_RADIUS_METERS;
            this.start = start;
            end = start + length;
            double[] direction = addScaled(destination, 1, origin, -dot);
            double magnitude = norm(direction);
            if (magnitude < 1e-12) {
                double longitude = Math.toRadians(from[1]);
                tangent = new double[] {-Math.sin(longitude), Math.cos(longitude), 0};
            } else {
                tangent = new double[] {
                        direction[0] / magnitude,
                        direction[1] / magnitude,
                        direction[2] / magnitude};
            }
        }
    }

    private static double[] vector(double[] point) {
        double latitude = Math.toRadians(point[0]);
        double longitude = Math.toRadians(point[1]);
        return new double[] {Math.cos(latitude) * Math.cos(longitude),
                Math.cos(latitude) * Math.sin(longitude), Math.sin(latitude)};
    }

    private static double[] addScaled(double[] a, double aScale, double[] b, double bScale) {
        return new double[] {a[0] * aScale + b[0] * bScale,
                a[1] * aScale + b[1] * bScale, a[2] * aScale + b[2] * bScale};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double norm(double[] vector) {
        return Math.hypot(Math.hypot(vector[0], vector[1]), vector[2]);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
