/* SPDX-License-Identifier: GPL-3.0-or-later */
package cl.coders.movingtraveler;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.maplibre.android.MapLibre;
import org.maplibre.android.RenderingEngine;
import org.maplibre.android.WellKnownTileServer;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonOptions;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.maplibre.android.style.expressions.Expression.get;
import static org.maplibre.android.style.layers.PropertyFactory.*;

/** Native, credential-free basemap. It never enables a device location component. */
final class MapSurface {
    interface Listener {
        void onTap(double latitude, double longitude);
        void onDrag(boolean destination, double latitude, double longitude);
        void onReady();
        void onMapError(String message);
    }

    static final String STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
    private static final String ROUTE = "journey-route", START = "journey-start", END = "journey-end";
    private static final String TRAVELER = "journey-traveler";
    private static final String WAYPOINTS = "journey-waypoints", ALTERNATIVES = "journey-alternatives";
    private static final double[][] NO_POINTS = new double[0][];
    private static final double[][][] NO_ALTERNATIVES = new double[0][][];
    private static final FeatureCollection EMPTY = FeatureCollection.fromFeatures(new Feature[0]);
    private static final int TEAL = Color.rgb(28, 100, 84), ORANGE = Color.rgb(193, 99, 42);
    private final Activity activity;
    private final FrameLayout container;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MapView view;
    private DragHost host;
    private TextView attribution;
    private MapLibreMap map;
    private Style style;
    private GeoJsonSource routeSource, startSource, endSource, travelerSource;
    private GeoJsonSource waypointSource, alternativesSource;
    private int waypointImageCount;
    private boolean destroyed, started, resumed, locked, stationary, fitPending;
    private boolean initialTilesLoaded, failed;
    private final int[] contentInsets = new int[4];
    private boolean insetsPending = true;
    private double[] start, end, traveler;
    // Route geometry is immutable for the lifetime of a route. Playback changes only TRAVELER.
    private double[][] geometry;
    private double[][] waypoints = NO_POINTS;
    private double[][][] alternatives = NO_ALTERNATIVES;
    private String startLabel = "", endLabel = "";
    private int dragging = -1;
    private double[] dragOriginal;
    private final Runnable timeout = () -> {
        if (!destroyed && !initialTilesLoaded) reportError("Map is taking longer to load. Check your internet connection.");
    };

    MapSurface(Activity activity, FrameLayout container, Listener listener) {
        this.activity = activity;
        this.container = container;
        this.listener = listener;
    }

    void create(Bundle saved) {
        if (view != null || destroyed) return;
        MapLibre.getInstance(activity.getApplicationContext(), null, WellKnownTileServer.MapLibre, RenderingEngine.Type.OPENGL);
        org.maplibre.android.module.http.HttpRequestUtil.setLogEnabled(false);
        host = new DragHost();
        container.addView(host, new FrameLayout.LayoutParams(-1, -1));
        MapLibreMapOptions options = new MapLibreMapOptions()
                .camera(new CameraPosition.Builder().target(new LatLng(20, 0)).zoom(2).build())
                .rotateGesturesEnabled(false).tiltGesturesEnabled(false)
                .compassEnabled(false).logoEnabled(false).attributionEnabled(true)
                .attributionGravity(Gravity.BOTTOM | Gravity.END)
                .attributionMargins(new int[]{dp(8), dp(8), dp(8), dp(8)})
                .foregroundLoadColor(Color.rgb(232, 239, 228));
        view = new MapView(activity, options);
        host.addView(view, new FrameLayout.LayoutParams(-1, -1));
        addAttribution();
        view.addOnDidFailLoadingMapListener(error -> {
            if (!destroyed) reportError("Map could not load. Check your internet connection and try again.");
        });
        view.addOnDidFinishRenderingMapListener(fully -> {
            if (!destroyed && fully && style != null) {
                initialTilesLoaded = true;
                main.removeCallbacks(timeout);
                if (failed) { failed = false; listener.onReady(); }
            }
        });
        view.onCreate(saved);
        view.getMapAsync(readyMap -> {
            if (destroyed) return;
            map = readyMap;
            map.getUiSettings().setRotateGesturesEnabled(false);
            map.getUiSettings().setTiltGesturesEnabled(false);
            applyContentInsets();
            map.addOnMapClickListener(position -> {
                if (!locked && dragging < 0) listener.onTap(position.getLatitude(), wrap(position.getLongitude()));
                return true;
            });
            map.addOnMapLongClickListener(this::beginDrag);
            map.setStyle(new Style.Builder().fromUri(STYLE_URL), loaded -> {
                if (destroyed) return;
                style = loaded;
                installLayers();
                renderEndpoints();
                renderGeometry();
                renderWaypoints();
                renderAlternatives();
                if (traveler != null) moveTraveler(traveler[0], traveler[1], traveler[2]);
                failed = false;
                listener.onReady();
                if (fitPending) fitRoute();
            });
        });
        main.postDelayed(timeout, 20_000);
    }

    private void addAttribution() {
        attribution = new TextView(activity);
        attribution.setTextSize(10);
        attribution.setTextColor(Color.rgb(38, 59, 51));
        attribution.setLinkTextColor(Color.rgb(38, 59, 51));
        attribution.setBackgroundColor(Color.argb(230, 255, 255, 255));
        attribution.setPadding(dp(4), dp(3), dp(4), dp(3));
        attribution.setMovementMethod(LinkMovementMethod.getInstance());
        // The native attribution button also lists source credits. These links stay visible.
        attribution.setText(Html.fromHtml("<a href='https://openfreemap.org/'>OpenFreeMap</a> · "
                + "<a href='https://openmaptiles.org/'>© OpenMapTiles</a> · "
                + "<a href='https://www.openstreetmap.org/copyright'>© OpenStreetMap</a>"));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        params.setMarginStart(dp(4)); params.setMarginEnd(dp(44)); params.bottomMargin = dp(5);
        host.addView(attribution, params);
        updateAttributionInsets();
    }

    /**
     * Physical pixel areas occupied by floating UI: left, top, right, bottom.
     * Call after stable sheet/layout changes. Idle routes refit once; active playback
     * keeps its camera target and zoom and only shifts the logical viewport.
     */
    void setContentInsets(int left, int top, int right, int bottom) {
        if (destroyed) return;
        int[] next = {Math.max(0, left), Math.max(0, top), Math.max(0, right), Math.max(0, bottom)};
        boolean changed = !Arrays.equals(contentInsets, next);
        if (changed) {
            System.arraycopy(next, 0, contentInsets, 0, next.length);
            insetsPending = true;
            if (geometry != null && !locked) fitPending = true;
        }
        applyContentInsets();
        if (fitPending) fitRoute();
    }

    private void applyContentInsets() {
        if (destroyed) return;
        updateAttributionInsets();
        if (map == null || host == null || !insetsPending || !hasUsableViewport(dp(32), dp(32))) return;
        try {
            // paddingTo applies immediately; deprecated setPadding waits for another camera change.
            map.moveCamera(CameraUpdateFactory.paddingTo(contentInsets[0], contentInsets[1], contentInsets[2], contentInsets[3]));
            insetsPending = false;
        } catch (RuntimeException ignored) {
            // A transient zero-sized/IME layout retries after the next stable layout.
        }
    }

    private void updateAttributionInsets() {
        if (host == null) return;
        boolean rtl = host.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int startInset = contentInsets[rtl ? 2 : 0], endInset = contentInsets[rtl ? 0 : 2];
        if (attribution != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) attribution.getLayoutParams();
            int startMargin = safeAdd(startInset, dp(4)), endMargin = safeAdd(endInset, dp(44));
            int topMargin = safeAdd(contentInsets[1], dp(4)), bottomMargin = safeAdd(contentInsets[3], dp(5));
            if (params.getMarginStart() != startMargin || params.getMarginEnd() != endMargin
                    || params.topMargin != topMargin || params.bottomMargin != bottomMargin) {
                params.setMarginStart(startMargin); params.setMarginEnd(endMargin);
                params.topMargin = topMargin; params.bottomMargin = bottomMargin;
                attribution.setLayoutParams(params);
            }
        }
        if (map != null) {
            // UiSettings stores its left/right values as start/end margins internally.
            org.maplibre.android.maps.UiSettings controls = map.getUiSettings();
            int left = safeAdd(startInset, dp(8)), top = safeAdd(contentInsets[1], dp(8));
            int right = safeAdd(endInset, dp(8)), bottom = safeAdd(contentInsets[3], dp(8));
            if (controls.getAttributionMarginLeft() != left || controls.getAttributionMarginTop() != top
                    || controls.getAttributionMarginRight() != right || controls.getAttributionMarginBottom() != bottom) {
                controls.setAttributionMargins(left, top, right, bottom);
            }
        }
    }

    private boolean hasUsableViewport(int minimumWidth, int minimumHeight) {
        return host != null && (long) host.getWidth() - contentInsets[0] - contentInsets[2] >= minimumWidth
                && (long) host.getHeight() - contentInsets[1] - contentInsets[3] >= minimumHeight;
    }

    private int[] routePadding() {
        int width = (int) Math.max(0L, (long) host.getWidth() - contentInsets[0] - contentInsets[2]);
        int height = (int) Math.max(0L, (long) host.getHeight() - contentInsets[1] - contentInsets[3]);
        int side = Math.min(dp(36), width / 4);
        int top = Math.min(dp(28), height / 5);
        int creditsHeight = attribution == null ? dp(16) : Math.max(dp(16), attribution.getMeasuredHeight());
        int bottom = Math.min(safeAdd(creditsHeight, dp(28)), Math.max(0, height - top - dp(28)));
        return new int[]{safeAdd(contentInsets[0], side), safeAdd(contentInsets[1], top),
                safeAdd(contentInsets[2], side), safeAdd(contentInsets[3], bottom)};
    }

    private static int safeAdd(int value, int extra) { return (int) Math.min(Integer.MAX_VALUE, (long) value + extra); }

    private void installLayers() {
        waypointImageCount = 0;
        alternativesSource = new GeoJsonSource(ALTERNATIVES, EMPTY, new GeoJsonOptions().withTolerance(0f));
        waypointSource = new GeoJsonSource(WAYPOINTS, EMPTY);
        routeSource = new GeoJsonSource(ROUTE, EMPTY, new GeoJsonOptions().withTolerance(0f));
        startSource = new GeoJsonSource(START, EMPTY);
        endSource = new GeoJsonSource(END, EMPTY);
        travelerSource = new GeoJsonSource(TRAVELER, EMPTY);
        style.addSource(alternativesSource); style.addSource(waypointSource);
        style.addSource(routeSource); style.addSource(startSource);
        style.addSource(endSource); style.addSource(travelerSource);
        style.addLayer(new LineLayer(ALTERNATIVES + "-outline", ALTERNATIVES).withProperties(
                lineColor(Color.WHITE), lineOpacity(.8f), lineWidth(7f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)));
        style.addLayer(new LineLayer(ALTERNATIVES + "-line", ALTERNATIVES).withProperties(
                lineColor(Color.rgb(128, 146, 141)), lineWidth(4f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)));
        style.addLayer(new LineLayer(ROUTE + "-outline", ROUTE).withProperties(
                lineColor(Color.WHITE), lineWidth(8f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)));
        style.addLayer(new LineLayer(ROUTE + "-line", ROUTE).withProperties(
                lineColor(TEAL), lineWidth(5f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)));
        style.addImage(START, endpointBitmap("A", TEAL));
        style.addImage(END, endpointBitmap("B", ORANGE));
        style.addImage(TRAVELER, travelerBitmap());
        style.addLayer(new SymbolLayer(WAYPOINTS + "-pin", WAYPOINTS).withProperties(
                iconImage(get("icon")), iconSize(.85f), iconAllowOverlap(true), iconIgnorePlacement(true),
                iconAnchor(Property.ICON_ANCHOR_CENTER)));
        style.addLayer(new SymbolLayer(START + "-pin", START).withProperties(
                iconImage(START), iconAllowOverlap(true), iconIgnorePlacement(true), iconAnchor(Property.ICON_ANCHOR_CENTER)));
        style.addLayer(new SymbolLayer(END + "-pin", END).withProperties(
                iconImage(END), iconAllowOverlap(true), iconIgnorePlacement(true), iconAnchor(Property.ICON_ANCHOR_CENTER)));
        style.addLayer(new CircleLayer(TRAVELER + "-halo", TRAVELER).withProperties(
                circleColor(Color.rgb(39, 110, 241)), circleRadius(21f), circleOpacity(0.13f)));
        style.addLayer(new SymbolLayer(TRAVELER + "-arrow", TRAVELER).withProperties(
                iconImage(TRAVELER), iconAllowOverlap(true), iconIgnorePlacement(true),
                iconRotate(get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)));
    }

    boolean ready() { return !destroyed && style != null; }

    /** Ordered intermediate points only, in [latitude, longitude] order. Null/empty clears them. */
    void setWaypoints(double[][] viaPoints) {
        if (destroyed) return;
        double[][] requested = viaPoints == null ? NO_POINTS : viaPoints;
        if (Arrays.deepEquals(waypoints, requested)) return;
        waypoints = copyCoordinates(requested, 0);
        renderWaypoints();
        updateMapDescription();
    }

    /** Unselected route geometries; they stay behind the selected route and do not affect its fit. */
    void setAlternatives(double[][][] geometries) {
        if (destroyed) return;
        double[][][] requested = geometries == null ? NO_ALTERNATIVES : geometries;
        if (Arrays.deepEquals(alternatives, requested)) return;
        double[][][] copied = new double[requested.length][][];
        for (int i = 0; i < requested.length; i++) copied[i] = copyCoordinates(requested[i], 2);
        alternatives = copied;
        renderAlternatives();
    }

    void drawRoute(double[] newStart, double[] newEnd, String newStartLabel, String newEndLabel,
                   double[][] newGeometry, boolean newStationary, boolean newLocked, boolean fit) {
        if (destroyed) return;
        boolean stationaryChanged = stationary != newStationary;
        boolean endpointsChanged = !Arrays.equals(start, newStart) || !Arrays.equals(end, newEnd)
                || stationaryChanged;
        boolean geometryChanged = geometry != newGeometry || stationaryChanged;
        if (newLocked && dragging >= 0) finishDrag(false);
        start = copy(newStart); end = copy(newEnd);
        startLabel = newStartLabel == null ? "" : newStartLabel;
        endLabel = newEndLabel == null ? "" : newEndLabel;
        geometry = newGeometry;
        stationary = newStationary; locked = newLocked;
        if (ready()) {
            if (endpointsChanged) renderEndpoints();
            if (geometryChanged) renderGeometry();
            if (stationaryChanged) { renderWaypoints(); renderAlternatives(); }
            updateMapDescription();
        }
        if (fit) { fitPending = true; fitRoute(); }
    }

    private void updateMapDescription() {
        if (view == null || !ready()) return;
        view.setContentDescription("Journey map. Start " + coordinateLabel(startLabel, start)
                + (stationary ? ". Holding one location." : ". Destination " + coordinateLabel(endLabel, end)
                    + (waypoints.length == 0 ? "" : ". " + waypoints.length + " numbered intermediate points"))
                + (locked ? ". Journey controls below." : ". Tap to choose a point; long press A or B to move it. Edit intermediate points in journey controls. Coordinates are also available in From and To."));
    }

    private void renderEndpoints() {
        if (!ready()) return;
        startSource.setGeoJson(pointCollection(start));
        endSource.setGeoJson(stationary ? EMPTY : pointCollection(end));
    }

    private void renderGeometry() {
        if (!ready()) return;
        if (stationary || geometry == null || geometry.length < 2) { routeSource.setGeoJson(EMPTY); return; }
        routeSource.setGeoJson(routeFeature(geometry));
    }

    private void renderWaypoints() {
        if (!ready()) return;
        if (stationary) { waypointSource.setGeoJson(EMPTY); return; }
        while (waypointImageCount < waypoints.length) {
            int number = ++waypointImageCount;
            style.addImage(WAYPOINTS + "-" + number, endpointBitmap(String.valueOf(number), Color.rgb(67, 103, 94)));
        }
        waypointSource.setGeoJson(waypointFeatures(waypoints));
    }

    private void renderAlternatives() {
        if (!ready()) return;
        alternativesSource.setGeoJson(stationary ? EMPTY : alternativeFeatures(alternatives));
    }

    static FeatureCollection waypointFeatures(double[][] points) {
        List<Feature> features = new ArrayList<>(points.length);
        for (int i = 0; i < points.length; i++) {
            Feature feature = Feature.fromGeometry(Point.fromLngLat(points[i][1], points[i][0]));
            feature.addNumberProperty("index", i);
            feature.addStringProperty("label", String.valueOf(i + 1));
            feature.addStringProperty("icon", WAYPOINTS + "-" + (i + 1));
            features.add(feature);
        }
        return FeatureCollection.fromFeatures(features);
    }

    static FeatureCollection alternativeFeatures(double[][][] routes) {
        List<Feature> features = new ArrayList<>(routes.length);
        for (int i = 0; i < routes.length; i++) {
            Feature feature = routeFeature(routes[i]);
            feature.addNumberProperty("index", i);
            features.add(feature);
        }
        return FeatureCollection.fromFeatures(features);
    }

    private static Feature routeFeature(double[][] coordinates) {
        List<Point> points = new ArrayList<>(coordinates.length);
        double previous = coordinates[0][1];
        for (double[] coordinate : coordinates) {
            // Preserve short crossings of the date line instead of drawing across the planet.
            double longitude = previous + wrap(coordinate[1] - previous);
            points.add(Point.fromLngLat(longitude, coordinate[0]));
            previous = longitude;
        }
        return Feature.fromGeometry(LineString.fromLngLats(points));
    }

    private static double[][] copyCoordinates(double[][] points, int minimum) {
        if (points == null || points.length < minimum) throw new IllegalArgumentException("Route coordinates are missing.");
        double[][] result = new double[points.length][];
        for (int i = 0; i < points.length; i++) {
            double[] point = points[i];
            if (point == null || point.length != 2 || !Double.isFinite(point[0]) || !Double.isFinite(point[1])
                    || Math.abs(point[0]) > 90 || Math.abs(point[1]) > 180)
                throw new IllegalArgumentException("Each point needs a valid latitude and longitude.");
            result[i] = point.clone();
        }
        return result;
    }

    void moveTraveler(double latitude, double longitude, double bearing) {
        if (destroyed) return;
        traveler = new double[]{latitude, wrap(longitude), bearing};
        if (!ready()) return;
        Feature feature = Feature.fromGeometry(Point.fromLngLat(traveler[1], traveler[0]));
        feature.addNumberProperty("bearing", !Float.isNaN((float) bearing) && !Float.isInfinite((float) bearing) ? (float) bearing : 0f);
        travelerSource.setGeoJson(feature);
    }

    void clearTraveler() {
        traveler = null;
        if (ready()) travelerSource.setGeoJson(EMPTY);
    }

    private void fitRoute() {
        if (!ready() || start == null || !fitPending) return;
        host.post(() -> {
            if (!ready() || start == null || !fitPending || !hasUsableViewport(dp(100), dp(100))) return;
            applyContentInsets();
            fitPending = false;
            try {
                if (stationary || end == null || (Arrays.equals(start, end) && (geometry == null || geometry.length < 2))) {
                    fitPoint(15);
                    return;
                }
                double[] extent = fitExtent(start, end, geometry, waypoints);
                if (extent[0] - extent[2] < 0.00001 && extent[1] - extent[3] < 0.00001) {
                    fitPoint(17);
                    return;
                }
                LatLngBounds bounds = LatLngBounds.from(extent[0], extent[1], extent[2], extent[3]);
                int[] padding = routePadding();
                // The native bounds API accepts absolute padding, not padding added to the
                // current viewport. Include overlays once, plus room for pins and credits.
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding[0], padding[1], padding[2], padding[3]));
            } catch (RuntimeException ignored) {
                // A changing IME/window can briefly make the viewport too small; the next layout retries.
                fitPending = true;
            }
        });
    }

    private void fitPoint(double zoom) {
        map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(start[0], start[1])).zoom(zoom).bearing(0).tilt(0)
                .padding(new double[]{contentInsets[0], contentInsets[1], contentInsets[2], contentInsets[3]}).build()));
    }

    /** North/east/south/west in the shortest longitude interval, including every route bend. */
    static double[] fitExtent(double[] start, double[] end, double[][] geometry) {
        return fitExtent(start, end, geometry, NO_POINTS);
    }

    static double[] fitExtent(double[] start, double[] end, double[][] geometry, double[][] viaPoints) {
        ArrayList<Double> longitudes = new ArrayList<>();
        double north = start[0], south = start[0];
        longitudes.add(wrap(start[1]));
        if (end != null) { north = Math.max(north, end[0]); south = Math.min(south, end[0]); longitudes.add(wrap(end[1])); }
        if (geometry != null) for (double[] p : geometry) {
            north = Math.max(north, p[0]); south = Math.min(south, p[0]); longitudes.add(wrap(p[1]));
        }
        if (viaPoints != null) for (double[] p : viaPoints) {
            north = Math.max(north, p[0]); south = Math.min(south, p[0]); longitudes.add(wrap(p[1]));
        }
        Collections.sort(longitudes);
        double biggestGap = -1; int gapAfter = 0;
        for (int i = 0; i < longitudes.size(); i++) {
            double next = i + 1 < longitudes.size() ? longitudes.get(i + 1) : longitudes.get(0) + 360;
            double gap = next - longitudes.get(i);
            if (gap > biggestGap) { biggestGap = gap; gapAfter = i; }
        }
        double west = longitudes.get((gapAfter + 1) % longitudes.size());
        double east = longitudes.get(gapAfter);
        if (east < west) east += 360;
        return new double[]{north, east, south, west};
    }

    private boolean beginDrag(LatLng position) {
        if (!ready() || locked || dragging >= 0) return false;
        PointF touched = map.getProjection().toScreenLocation(position);
        double startDistance = distanceOnScreen(touched, start);
        double endDistance = stationary ? Double.POSITIVE_INFINITY : distanceOnScreen(touched, end);
        double viaDistance = Double.POSITIVE_INFINITY;
        if (!stationary) for (double[] point : waypoints) viaDistance = Math.min(viaDistance, distanceOnScreen(touched, point));
        dragging = dragEndpoint(startDistance, endDistance, viaDistance, dp(30));
        if (dragging < 0) return false;
        dragOriginal = copy(dragging == 1 ? end : start);
        map.cancelTransitions();
        // Let the parent intercept the next movement; Android cancels the map gesture cleanly.
        host.requestDisallowInterceptTouchEvent(false);
        host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        return true;
    }

    static int dragEndpoint(double startDistance, double endDistance, double viaDistance, double radius) {
        double endpointDistance = Math.min(startDistance, endDistance);
        if (endpointDistance > radius || viaDistance <= endpointDistance) return -1;
        return endDistance < startDistance ? 1 : 0;
    }

    private double distanceOnScreen(PointF touch, double[] coordinate) {
        if (coordinate == null) return Double.POSITIVE_INFINITY;
        PointF point = map.getProjection().toScreenLocation(new LatLng(coordinate[0], coordinate[1]));
        return Math.hypot(touch.x - point.x, touch.y - point.y);
    }

    private void dragTo(float x, float y) {
        if (dragging < 0 || !ready()) return;
        LatLng position = map.getProjection().fromScreenLocation(new PointF(x, y));
        double[] coordinate = {position.getLatitude(), wrap(position.getLongitude())};
        if (dragging == 1) end = coordinate; else start = coordinate;
        renderEndpoints();
    }

    private void finishDrag(boolean commit) {
        int finished = dragging;
        if (finished < 0) return;
        double[] position = copy(finished == 1 ? end : start);
        dragging = -1;
        boolean changed = position != null && dragOriginal != null
                && (Math.abs(position[0] - dragOriginal[0]) > 1e-7
                    || Math.abs(wrap(position[1] - dragOriginal[1])) > 1e-7);
        if (!commit || !changed) {
            if (finished == 1) end = dragOriginal; else start = dragOriginal;
            renderEndpoints();
        }
        dragOriginal = null;
        if (commit && changed && !locked) listener.onDrag(finished == 1, position[0], position[1]);
    }

    private final class DragHost extends FrameLayout {
        DragHost() {
            super(activity);
            addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
                applyContentInsets();
                if (fitPending) fitRoute();
            });
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && dragging >= 0) finishDrag(false);
            boolean wasDragging = dragging >= 0;
            boolean handled = super.dispatchTouchEvent(event);
            // If UP/CANCEL is the first intercepted event, ViewGroup sends CANCEL to
            // the child and never invokes our onTouchEvent for that same event.
            if (dragging >= 0 && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                finishDrag(false);
            }
            return handled || wasDragging;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            return dragging >= 0 || super.onInterceptTouchEvent(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (dragging < 0) return super.onTouchEvent(event);
            if (event.getPointerCount() > 1 || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                finishDrag(false); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) dragTo(event.getX(), event.getY());
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                dragTo(event.getX(), event.getY()); finishDrag(true); performClick();
            }
            return true;
        }
        @Override public boolean performClick() { return super.performClick(); }
    }

    private Bitmap endpointBitmap(String label, int color) {
        Bitmap bitmap = bitmap(40);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(bitmap.getWidth() / 40f, bitmap.getHeight() / 40f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.argb(30, 0, 0, 0)); canvas.drawCircle(20, 22, 17, paint);
        paint.setColor(Color.WHITE); canvas.drawCircle(20, 20, 17, paint);
        paint.setColor(color); canvas.drawCircle(20, 20, 14, paint);
        paint.setColor(Color.WHITE); paint.setTextSize(16); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label, 20, 20 - (paint.ascent() + paint.descent()) / 2, paint);
        return bitmap;
    }

    private Bitmap travelerBitmap() {
        Bitmap bitmap = bitmap(40);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(bitmap.getWidth() / 40f, bitmap.getHeight() / 40f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE); canvas.drawCircle(20, 20, 18, paint);
        paint.setColor(Color.rgb(39, 110, 241)); canvas.drawCircle(20, 20, 15, paint);
        Path arrow = new Path(); arrow.moveTo(20, 8); arrow.lineTo(29, 28); arrow.lineTo(20, 24); arrow.lineTo(11, 28); arrow.close();
        paint.setColor(Color.WHITE); canvas.drawPath(arrow, paint);
        return bitmap;
    }

    private Bitmap bitmap(int size) {
        Bitmap bitmap = Bitmap.createBitmap(dp(size), dp(size), Bitmap.Config.ARGB_8888);
        bitmap.setDensity(activity.getResources().getDisplayMetrics().densityDpi);
        return bitmap;
    }
    private static FeatureCollection pointCollection(double[] p) {
        return p == null ? EMPTY : FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(p[1], p[0])));
    }
    private static double[] copy(double[] p) { return p == null ? null : p.clone(); }
    private static String coordinateLabel(String label, double[] p) {
        return p == null ? "not selected" : !label.isEmpty() ? label : String.format(java.util.Locale.US, "%.5f, %.5f", p[0], p[1]);
    }
    static double wrap(double longitude) { return ((longitude + 180) % 360 + 360) % 360 - 180; }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void reportError(String message) { failed = true; listener.onMapError(message); }

    void start() { if (view != null && !started && !destroyed) { view.onStart(); started = true; } }
    void resume() { if (view != null && !resumed && !destroyed) { start(); view.onResume(); resumed = true; } }
    void pause() { if (view != null && resumed && !destroyed) { finishDrag(false); view.onPause(); resumed = false; } }
    void stop() { if (view != null && started && !destroyed) { pause(); view.onStop(); started = false; } }
    void saveState(Bundle state) { if (view != null && !destroyed) view.onSaveInstanceState(state); }
    void lowMemory() { if (view != null && !destroyed) view.onLowMemory(); }
    void destroy() {
        if (destroyed) return;
        main.removeCallbacks(timeout);
        stop(); destroyed = true;
        if (view != null) view.onDestroy();
        if (host != null) container.removeView(host);
        view = null; host = null; map = null; style = null; attribution = null;
        routeSource = startSource = endSource = travelerSource = null;
        waypointSource = alternativesSource = null;
        waypoints = NO_POINTS; alternatives = NO_ALTERNATIVES;
    }
}
