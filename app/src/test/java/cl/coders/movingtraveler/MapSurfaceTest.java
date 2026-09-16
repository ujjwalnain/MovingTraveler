/* SPDX-License-Identifier: GPL-3.0-or-later */
package cl.coders.movingtraveler;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Camera math and Android gesture ownership; no native GPU or live tile testing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MapSurfaceTest {
    @Test public void previewContainsBendsOutsideEndpointRectangle() {
        double[] bounds = MapSurface.fitExtent(new double[]{10, 20}, new double[]{11, 21},
                new double[][]{{10,20}, {13,18}, {8,24}, {11,21}});
        assertArrayEquals(new double[]{13,24,8,18}, bounds, 1e-9);
    }

    @Test public void dateLineRouteFitsNearbyWorldCopy() {
        double[] bounds = MapSurface.fitExtent(new double[]{-16,179.5}, new double[]{-17,-179.7},
                new double[][]{{-16,179.5},{-16.4,179.9},{-16.7,-179.9},{-17,-179.7}});
        assertArrayEquals(new double[]{-16,180.3,-17,179.5}, bounds, 1e-8);
        assertTrue(bounds[1] - bounds[3] < 1);
    }

    @Test public void singlePointHasZeroSpan() {
        assertArrayEquals(new double[]{52.5,13.4,52.5,13.4},
                MapSurface.fitExtent(new double[]{52.5,13.4}, null, null), 1e-9);
    }

    @Test public void cameraIncludesManuallySelectedPointsOutsideSnappedGeometry() {
        assertArrayEquals(new double[]{13,24,8,18}, MapSurface.fitExtent(
                new double[]{13,18}, new double[]{8,24}, new double[][]{{12,20},{10,22}}), 1e-9);
    }

    @Test public void draggedWorldCopiesReturnConventionalCoordinates() {
        assertEquals(-179, MapSurface.wrap(181), 0);
        assertEquals(179, MapSurface.wrap(-181), 0);
        assertEquals(1, MapSurface.wrap(721), 0);
        assertEquals(-180, MapSurface.wrap(180), 0);
    }

    @Test public void numberedViaMarkersKeepOrderAndLatitudeLongitudeOrientation() {
        FeatureCollection collection = MapSurface.waypointFeatures(new double[][]{{51.5,-.12},{35.68,139.76}});
        List<Feature> features = collection.features();
        assertNotNull(features);
        assertEquals(2, features.size());
        for (int i = 0; i < features.size(); i++) {
            assertEquals(i, features.get(i).getNumberProperty("index").intValue());
            assertEquals(String.valueOf(i + 1), features.get(i).getStringProperty("label"));
            assertEquals("journey-waypoints-" + (i + 1), features.get(i).getStringProperty("icon"));
        }
        Point first = (Point) features.get(0).geometry();
        assertNotNull(first);
        assertEquals(51.5, first.latitude(), 0);
        assertEquals(-.12, first.longitude(), 0);
    }

    @Test public void alternativesPreserveAllBendsWithoutConnectingSeparateRoutes() {
        double[][][] paths = {{{1,10},{5,12},{2,15}}, {{1,10},{-4,14},{3,13},{2,15}}};
        List<Feature> features = MapSurface.alternativeFeatures(paths).features();
        assertNotNull(features);
        assertEquals("Each alternative is an independent line", 2, features.size());
        for (int i = 0; i < paths.length; i++) {
            LineString line = (LineString) features.get(i).geometry();
            assertNotNull(line);
            assertEquals(paths[i].length, line.coordinates().size());
            for (int j = 0; j < paths[i].length; j++) {
                assertEquals(paths[i][j][0], line.coordinates().get(j).latitude(), 0);
                assertEquals(paths[i][j][1], line.coordinates().get(j).longitude(), 1e-9);
            }
        }
    }

    @Test public void eachAlternativeIndependentlyUnwrapsDateLineCrossings() {
        List<Feature> features = MapSurface.alternativeFeatures(new double[][][]{
                {{10,179.6},{11,-179.8},{12,-179.4}},
                {{-10,-179.5},{-11,179.8},{-12,179.2}}}).features();
        assertNotNull(features);
        assertEquals(180.6, ((LineString) features.get(0).geometry()).coordinates().get(2).longitude(), 1e-9);
        assertEquals(-180.8, ((LineString) features.get(1).geometry()).coordinates().get(2).longitude(), 1e-9);
    }

    @Test public void selectedRouteFitIncludesViaMarkersOutsideSnappedGeometry() {
        double[] fitted = MapSurface.fitExtent(new double[]{10,20}, new double[]{11,21},
                new double[][]{{10,20},{13,22},{11,21}}, new double[][]{{13.2,22.1},{8,18}});
        assertArrayEquals(new double[]{13.2,22.1,8,18}, fitted, 1e-9);
        double[] crossing = MapSurface.fitExtent(new double[]{10,179}, new double[]{11,-179},
                new double[][]{{10,179},{11,-179}}, new double[][]{{12,-178.5}});
        assertArrayEquals(new double[]{12,181.5,10,179}, crossing, 1e-9);
    }

    @Test public void aViaLongPressNeverMovesANearbyDestinationPin() {
        assertEquals(-1, MapSurface.dragEndpoint(90, 12, 2, 30));
        assertEquals("Ambiguous overlapping via/endpoint touches do not edit an endpoint", -1,
                MapSurface.dragEndpoint(90, 0, 0, 30));
        assertEquals(1, MapSurface.dragEndpoint(90, 2, 12, 30));
        assertEquals(0, MapSurface.dragEndpoint(3, 90, 14, 30));
        assertEquals(-1, MapSurface.dragEndpoint(45, 60, Double.POSITIVE_INFINITY, 30));
    }

    @Test public void overlayUpdatesOwnTheirDataSkipUnchangedArraysAndCanClear() throws Exception {
        DragFixture fixture = new DragFixture();
        double[][] vias = {{10,20}, {11,21}};
        double[][][] choices = {{{10,20},{12,22},{11,21}}};
        fixture.surface.setWaypoints(vias);
        fixture.surface.setAlternatives(choices);
        double[][] storedVias = ReflectionHelpers.getField(fixture.surface, "waypoints");
        double[][][] storedChoices = ReflectionHelpers.getField(fixture.surface, "alternatives");
        assertNotSame(vias, storedVias);
        assertNotSame(vias[0], storedVias[0]);
        assertNotSame(choices[0][0], storedChoices[0][0]);
        fixture.surface.setWaypoints(new double[][]{{10,20},{11,21}});
        fixture.surface.setAlternatives(new double[][][]{{{10,20},{12,22},{11,21}}});
        assertSame("Unchanged data does not trigger another source rebuild", storedVias,
                ReflectionHelpers.getField(fixture.surface, "waypoints"));
        assertSame(storedChoices, ReflectionHelpers.getField(fixture.surface, "alternatives"));
        vias[0][0] = 80; choices[0][0][0] = 80;
        assertEquals(10, storedVias[0][0], 0);
        assertEquals(10, storedChoices[0][0][0], 0);
        assertFalse("Overlay updates do not change the camera", (boolean) ReflectionHelpers.getField(fixture.surface, "fitPending"));
        fixture.surface.setWaypoints(null); fixture.surface.setAlternatives(null);
        assertEquals(0, ReflectionHelpers.<double[][]>getField(fixture.surface, "waypoints").length);
        assertEquals(0, ReflectionHelpers.<double[][][]>getField(fixture.surface, "alternatives").length);
    }

    @Test public void invalidOverlayUpdatesDoNotReplaceThePreviousValidData() throws Exception {
        DragFixture fixture = new DragFixture();
        fixture.surface.setWaypoints(new double[][]{{10,20}});
        fixture.surface.setAlternatives(new double[][][]{{{10,20},{11,21}}});
        double[][] previousVias = ReflectionHelpers.getField(fixture.surface, "waypoints");
        double[][][] previousChoices = ReflectionHelpers.getField(fixture.surface, "alternatives");
        assertThrows(IllegalArgumentException.class, () -> fixture.surface.setWaypoints(new double[][]{{Double.NaN,20}}));
        assertThrows(IllegalArgumentException.class, () -> fixture.surface.setAlternatives(new double[][][]{{{10,20}}}));
        assertThrows(IllegalArgumentException.class, () -> fixture.surface.setAlternatives(new double[][][]{{{10,20},{91,21}}}));
        assertSame(previousVias, ReflectionHelpers.getField(fixture.surface, "waypoints"));
        assertSame(previousChoices, ReflectionHelpers.getField(fixture.surface, "alternatives"));
        fixture.surface.destroy();
        fixture.surface.setWaypoints(new double[][]{{10,20}});
        fixture.surface.setAlternatives(new double[][][]{{{10,20},{11,21}}});
        assertEquals(0, ReflectionHelpers.<double[][]>getField(fixture.surface, "waypoints").length);
        assertEquals(0, ReflectionHelpers.<double[][][]>getField(fixture.surface, "alternatives").length);
    }

    @Test public void releaseImmediatelyAfterLongPressClearsDragWithoutMovingPin() throws Exception {
        DragFixture fixture = new DragFixture();
        fixture.dispatch(MotionEvent.ACTION_DOWN);
        fixture.begin(); // Native long-press detection is outside this renderer-free test.
        fixture.dispatch(MotionEvent.ACTION_UP);
        assertEquals(-1, (int) ReflectionHelpers.getField(fixture.surface, "dragging"));
        assertEquals(1, fixture.childCancels);
        assertEquals(0, fixture.commits);
        fixture.dispatch(MotionEvent.ACTION_DOWN);
        fixture.dispatch(MotionEvent.ACTION_UP);
        assertEquals("The next gesture must remain a normal map gesture", 2, fixture.childDowns);
    }

    @Test public void cancelImmediatelyAfterLongPressClearsDrag() throws Exception {
        DragFixture fixture = new DragFixture();
        fixture.dispatch(MotionEvent.ACTION_DOWN); fixture.begin();
        fixture.dispatch(MotionEvent.ACTION_CANCEL);
        assertEquals(-1, (int) ReflectionHelpers.getField(fixture.surface, "dragging"));
        assertEquals(0, fixture.commits);
    }

    @Test public void noOpOrRoundingOnlyDragDoesNotRequestAnotherRoute() throws Exception {
        DragFixture fixture = new DragFixture(); fixture.begin();
        ReflectionHelpers.setField(fixture.surface, "start", new double[]{51.50000000001, -0.1});
        ReflectionHelpers.callInstanceMethod(fixture.surface, "finishDrag",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        assertEquals(0, fixture.commits);
        assertArrayEquals(new double[]{51.5, -0.1},
                ReflectionHelpers.getField(fixture.surface, "start"), 0);
    }

    @Test public void completedDragCommitsExactlyOnce() throws Exception {
        DragFixture fixture = new DragFixture(); fixture.begin();
        ReflectionHelpers.setField(fixture.surface, "start", new double[]{51.501, -0.102});
        ReflectionHelpers.callInstanceMethod(fixture.surface, "finishDrag",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        ReflectionHelpers.callInstanceMethod(fixture.surface, "finishDrag",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        assertEquals(1, fixture.commits);
        assertArrayEquals(new double[]{51.501, -0.102}, fixture.position, 0);
    }

    @Test public void newGestureCancelsAnOrphanedDrag() throws Exception {
        DragFixture fixture = new DragFixture(); fixture.begin();
        fixture.dispatch(MotionEvent.ACTION_DOWN);
        assertEquals(-1, (int) ReflectionHelpers.getField(fixture.surface, "dragging"));
        assertEquals(1, fixture.childDowns);
        assertEquals(0, fixture.commits);
    }

    @Test public void contentInsetsClampNegativeValuesAndDeferUntilMapReady() throws Exception {
        DragFixture fixture = new DragFixture();
        fixture.surface.setContentInsets(-1, 60, -2, 180);
        assertArrayEquals(new int[]{0,60,0,180}, ReflectionHelpers.getField(fixture.surface, "contentInsets"));
        assertTrue((boolean) ReflectionHelpers.getField(fixture.surface, "insetsPending"));
        assertFalse((boolean) ReflectionHelpers.getField(fixture.surface, "fitPending"));
    }

    @Test public void stableInsetsRefitIdleGeometryOnceButLeavePlaybackCameraAlone() throws Exception {
        DragFixture fixture = new DragFixture();
        ReflectionHelpers.setField(fixture.surface, "geometry", new double[][]{{0,0},{0.01,0.01}});
        fixture.surface.setContentInsets(0, 60, 0, 180);
        assertTrue((boolean) ReflectionHelpers.getField(fixture.surface, "fitPending"));
        ReflectionHelpers.setField(fixture.surface, "fitPending", false);
        fixture.surface.setContentInsets(0, 60, 0, 180);
        assertFalse("Repeated layout notifications must not refit", (boolean) ReflectionHelpers.getField(fixture.surface, "fitPending"));
        ReflectionHelpers.setField(fixture.surface, "locked", true);
        fixture.surface.setContentInsets(0, 60, 0, 240);
        assertFalse("Playback keeps its camera target and zoom", (boolean) ReflectionHelpers.getField(fixture.surface, "fitPending"));
    }

    @Test public void routeFramingReservesSheetHeaderAndSideCardExactlyOnce() throws Exception {
        DragFixture fixture = new DragFixture();
        fixture.surface.setContentInsets(20, 60, 120, 100);
        int[] padding = ReflectionHelpers.callInstanceMethod(fixture.surface, "routePadding");
        assertEquals(20 + fixture.dp(36), padding[0]);
        assertEquals(60 + fixture.dp(28), padding[1]);
        assertEquals(120 + fixture.dp(36), padding[2]);
        assertEquals(100 + fixture.dp(44), padding[3]);
        assertTrue(padding[0] + padding[2] < 400);
        assertTrue(padding[1] + padding[3] < 400);
    }

    @Test public void attributionSitsOutsideBottomAndRightOverlays() throws Exception {
        DragFixture fixture = new DragFixture();
        ReflectionHelpers.callInstanceMethod(fixture.surface, "addAttribution");
        fixture.surface.setContentInsets(12, 60, 120, 180);
        android.widget.TextView credits = ReflectionHelpers.getField(fixture.surface, "attribution");
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) credits.getLayoutParams();
        assertEquals(12 + fixture.dp(4), params.getMarginStart());
        assertEquals(120 + fixture.dp(44), params.getMarginEnd());
        assertEquals(60 + fixture.dp(4), params.topMargin);
        assertEquals(180 + fixture.dp(5), params.bottomMargin);
    }

    @Test public void attributionUsesPhysicalInsetsInRtlLayouts() throws Exception {
        DragFixture fixture = new DragFixture(); fixture.host.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ReflectionHelpers.callInstanceMethod(fixture.surface, "addAttribution");
        fixture.surface.setContentInsets(12, 60, 120, 180);
        android.widget.TextView credits = ReflectionHelpers.getField(fixture.surface, "attribution");
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) credits.getLayoutParams();
        assertEquals(120 + fixture.dp(4), params.getMarginStart());
        assertEquals(12 + fixture.dp(44), params.getMarginEnd());
    }

    @Test public void invalidViewportWaitsAndLargeInsetsCannotOverflow() throws Exception {
        DragFixture fixture = new DragFixture();
        fixture.surface.setContentInsets(Integer.MAX_VALUE, 300, 300, 300);
        boolean usable = ReflectionHelpers.callInstanceMethod(fixture.surface, "hasUsableViewport",
                ReflectionHelpers.ClassParameter.from(int.class, 100), ReflectionHelpers.ClassParameter.from(int.class, 100));
        assertFalse(usable);
        ReflectionHelpers.callInstanceMethod(fixture.surface, "addAttribution");
        android.widget.TextView credits = ReflectionHelpers.getField(fixture.surface, "attribution");
        assertTrue(((FrameLayout.LayoutParams) credits.getLayoutParams()).getMarginStart() > 0);
        fixture.surface.destroy();
        fixture.surface.setContentInsets(0,0,0,0); // No camera/view work is permitted after teardown.
        assertArrayEquals(new int[]{Integer.MAX_VALUE,300,300,300}, ReflectionHelpers.getField(fixture.surface, "contentInsets"));
    }

    private static final class DragFixture {
        final MapSurface surface;
        final FrameLayout host;
        int commits, childCancels, childDowns;
        double[] position;
        final long down = SystemClock.uptimeMillis();
        DragFixture() throws Exception {
            Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
            surface = new MapSurface(activity, new FrameLayout(activity), new MapSurface.Listener() {
                @Override public void onTap(double lat, double lon) { }
                @Override public void onDrag(boolean destination, double lat, double lon) {
                    commits++; position = new double[]{lat, lon};
                }
                @Override public void onReady() { }
                @Override public void onMapError(String message) { }
            });
            Class<?> hostClass = Class.forName("cl.coders.movingtraveler.MapSurface$DragHost");
            java.lang.reflect.Constructor<?> constructor = hostClass.getDeclaredConstructor(MapSurface.class);
            constructor.setAccessible(true); host = (FrameLayout) constructor.newInstance(surface);
            ReflectionHelpers.setField(surface, "host", host);
            View child = new View(activity);
            child.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) childDowns++;
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) childCancels++;
                return true;
            });
            host.addView(child, new FrameLayout.LayoutParams(-1, -1));
            activity.setContentView(host);
            int exact = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY);
            host.measure(exact, exact); host.layout(0, 0, 400, 400);
        }
        int dp(int value) { return Math.round(value * host.getResources().getDisplayMetrics().density); }
        void begin() {
            ReflectionHelpers.setField(surface, "start", new double[]{51.5,-0.1});
            ReflectionHelpers.setField(surface, "dragOriginal", new double[]{51.5,-0.1});
            ReflectionHelpers.setField(surface, "dragging", 0);
            host.requestDisallowInterceptTouchEvent(false);
        }
        void dispatch(int action) {
            MotionEvent event = MotionEvent.obtain(down, down + 800, action, 100, 100, 0);
            host.dispatchTouchEvent(event); event.recycle();
        }
    }
}
