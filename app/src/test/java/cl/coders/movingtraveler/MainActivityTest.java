// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.os.Bundle;
import android.os.Process;
import android.view.inputmethod.EditorInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import org.maplibre.android.maps.MapView;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.io.FileOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Native activity lifecycle regressions with online planning disabled. Android
 * records service Intents; these tests do not start mock providers, contact map
 * services, or initialize the native MapLibre renderer. API 35 covers the runtime
 * notification-request branch, API 23 checks coordinate-only operation, and
 * configuration changes exercise retained planning state without API calls.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class MainActivityTest {
    private static final String MANUAL_ROUTE = "{\"start\":[28.6001,77.2001],\"end\":[28.6201,77.2301],"
            + "\"speed\":5,\"kind\":\"direct\",\"playback\":\"once\",\"travel\":\"walk\"}";
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before public void resetSession() {
        SimulationService.active = false;
        SimulationService.state = "{\"status\":\"idle\"}";
        SimulationService.stagedPlan = null;
        SimulationService.routePoints = null;
        application().getSharedPreferences("settings", 0).edit().clear()
                .putBoolean("online", false).putInt("openMapConsentVersion", 1).commit();
        application().getSharedPreferences("nativeDraft", 0).edit().clear().commit();
        RoutingCredentials.clear(application());
        drainServiceIntents();
    }

    @After public void cleanupSession() {
        if (controller != null) controller.pause().stop().destroy();
        SimulationService.active = false;
        SimulationService.state = "{\"status\":\"idle\"}";
        SimulationService.stagedPlan = null;
        SimulationService.routePoints = null;
        drainServiceIntents();
    }

    @Test public void offlineLaunchOffersChooseStartWithoutCreatingMapOrStartingPlayback() {
        launch();
        assertNull(find(activity.findViewById(android.R.id.content), MapView.class));
        assertTrue(button("Choose start").isEnabled());
        assertNull(nextServiceIntent());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void offlineInstructionsRemainAboveTheExpandedSheetAndMoveWhenItCollapses() {
        readyRoute();
        JourneyUi ui = ReflectionHelpers.getField(activity, "ui");
        ui.root().setPadding(8, 24, 8, 28);
        layoutJourney(ui, 360, 740);
        Rect expandedText = placeholderTextBounds(ui);
        View header = ReflectionHelpers.getField(ui, "header");
        View panel = ReflectionHelpers.getField(ui, "panel");
        assertTrue("Offline guidance stays below the floating header", expandedText.top >= header.getBottom());
        assertTrue("All offline guidance stays above the expanded controls: " + expandedText,
                expandedText.bottom <= panel.getTop());

        ui.setSheetExpanded(false, false);
        layoutJourney(ui, 360, 740);
        Rect collapsedText = placeholderTextBounds(ui);
        assertTrue("Guidance is re-centered in the newly exposed map area", collapsedText.top > expandedText.top);
        assertTrue(collapsedText.bottom <= panel.getTop());
        TextView placeholder = ReflectionHelpers.getField(activity, "mapPlaceholder");
        assertTrue("The offline map still offers its enable-map action", placeholder.performClick());
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        assertNull(nextServiceIntent());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void offlineInstructionsRemainBesideTheLandscapeCardAfterRecreation() {
        readyRoute();
        controller.recreate(); activity = controller.get(); idle();
        JourneyUi ui = ReflectionHelpers.getField(activity, "ui");
        layoutJourney(ui, 740, 360);
        Rect text = placeholderTextBounds(ui);
        View header = ReflectionHelpers.getField(ui, "header");
        View panel = ReflectionHelpers.getField(ui, "panel");
        assertTrue("Landscape guidance stays below the header", text.top >= header.getBottom());
        assertTrue("Landscape guidance remains to the left of the card: " + text, text.right <= panel.getLeft());
        assertTrue(text.bottom <= ui.root().getHeight());
        assertNull(find(activity.findViewById(android.R.id.content), MapView.class));
        assertNull(nextServiceIntent());
    }

    @Test @Config(sdk = 23)
    public void api23CoordinatesRemainUsableWithoutMapsOrRoutingCredentials() throws Exception {
        saveDraft("{\"kind\":\"direct\"}");
        launch();
        assertNull(find(activity.findViewById(android.R.id.content), MapView.class));
        assertTrue(button("Choose start").isEnabled());

        enterCoordinates(false, "28.6139,77.2090");
        enterCoordinates(true, "28.6200,77.2200");
        assertTrue(button("Start journey").isEnabled());
        assertEquals(28.6139, savedDraft().getJSONArray("start").getDouble(0), 0);
        assertNull(find(activity.findViewById(android.R.id.content), MapView.class));
        assertNull(nextServiceIntent());
    }

    @Test public void coordinatesCanPlanAnOfflineRouteAndRejectInvalidNumbers() throws Exception {
        saveDraft("{\"kind\":\"direct\"}");
        launch();
        enterCoordinates(false, " 28.6139, 77.2090 ");
        enterCoordinates(true, "28.6200, 77.2200");
        assertTrue(button("Start journey").isEnabled());
        JSONObject saved = savedDraft();
        assertEquals(28.6139, saved.getJSONArray("start").getDouble(0), 0);
        assertEquals(77.2200, saved.getJSONArray("end").getDouble(1), 0);
        assertArrayEquals(new double[]{-90, 180}, MainActivity.parseCoordinates("-90, 180"), 0);
        for (String bad : new String[]{"91,0", "0,181", "NaN,0", "Infinity,0", "1,2,3", "one,two", ""}) {
            assertNull(bad, MainActivity.parseCoordinates(bad));
        }
        assertNull(nextServiceIntent());
    }

    @Test public void successiveStationaryMapTapsUpdateStartWithoutCreatingHiddenDestination() throws Exception {
        saveDraft("{\"kind\":\"direct\",\"playback\":\"static\"}");
        launch();
        tapMap(28.6139, 77.2090);
        tapMap(28.6200, 77.2200);
        JSONObject saved = savedDraft();
        assertEquals(28.6200, saved.getJSONArray("start").getDouble(0), 0);
        assertEquals(77.2200, saved.getJSONArray("start").getDouble(1), 0);
        assertFalse("Stationary map taps must not create an invisible destination", saved.has("end"));
        assertTrue(button("Start location").isEnabled());
        assertNull(nextServiceIntent());
    }

    @Test public void stationaryMapTapUpdatesStartEvenWhenOldJourneyHasDestination() throws Exception {
        saveDraft(MANUAL_ROUTE.replace("\"once\"", "\"static\""));
        launch();
        tapMap(28.6300, 77.2400);
        JSONObject saved = savedDraft();
        assertEquals(28.6300, saved.getJSONArray("start").getDouble(0), 0);
        assertEquals(77.2400, saved.getJSONArray("start").getDouble(1), 0);
        assertEquals("A hidden previous destination must remain unchanged", 28.6201,
                saved.getJSONArray("end").getDouble(0), 0);
        assertTrue(button("Start location").isEnabled());
        assertNull(nextServiceIntent());
    }

    @Test public void rationaleSurvivesBackgroundResumeWithoutStartingUntilExplicitSkip() {
        readyRoute();
        activity.onPrimary();
        AlertDialog rationale = rationale();
        assertTrue(rationale.isShowing());
        assertFalse(button("Please wait…").isEnabled());
        controller.pause().stop().start().resume();
        idle();
        assertNull("Returning to the app must not choose Skip for the user", nextServiceIntent());
        assertNull(SimulationService.stagedPlan);
        rationale.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertSingleStart();
        controller.pause().resume();
        idle();
        assertNull("Resume must not enqueue another start", nextServiceIntent());
    }

    @Test public void cancellingPermissionRationaleImmediatelyRestoresReadyControls() {
        readyRoute();
        activity.onPrimary();
        rationale().cancel();
        idle();
        assertTrue(button("Start journey").isEnabled());
        assertNull(nextServiceIntent());
        assertNull(SimulationService.stagedPlan);
        controller.pause().resume();
        idle();
        assertNull("A cancelled start cannot return on resume", nextServiceIntent());
    }

    @Test public void recreationKeepsPermissionExplanationAndStartsOnlyAfterChoice() {
        readyRoute();
        activity.onPrimary();
        AlertDialog previous = rationale();
        controller.recreate();
        activity = controller.get();
        idle();
        AlertDialog restored = rationale();
        assertFalse("The old activity must release its dialog window", previous.isShowing());
        assertTrue(restored.isShowing());
        assertNull(nextServiceIntent());
        restored.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertSingleStart();
    }

    @Test @Config(sdk = 35)
    public void recreationDuringRuntimePermissionRequestContinuesOnceEvenWhenDenied() {
        shadowOf(application()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        readyRoute();
        activity.onPrimary();
        rationale().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertNotNull("API 35 must request the optional notification permission",
                shadowOf(activity).getLastRequestedPermission());
        assertNull(nextServiceIntent());
        controller.recreate();
        activity = controller.get();
        idle();
        assertNull("Recreation must still wait for the platform permission result", nextServiceIntent());
        activity.onRequestPermissionsResult(10, new String[]{Manifest.permission.POST_NOTIFICATIONS},
                new int[]{PackageManager.PERMISSION_DENIED});
        idle();
        assertSingleStart();
        activity.onRequestPermissionsResult(10, new String[]{Manifest.permission.POST_NOTIFICATIONS},
                new int[]{PackageManager.PERMISSION_DENIED});
        idle();
        assertNull("A duplicate callback must not duplicate the service start", nextServiceIntent());
    }

    @Test public void restoringServiceGeometryDoesNotPersistUnknownProviderEndpoints() throws Exception {
        activeService();
        launch();
        activity.onSpeed(8);
        JSONObject saved = savedDraft();
        assertFalse("Service-derived start stays in memory", saved.has("start"));
        assertFalse("Service-derived destination stays in memory", saved.has("end"));
        assertFalse(saved.has("geometry"));
        assertEquals(8, saved.getDouble("speed"), 0);
    }

    @Test public void serviceUpdatesPreserveOriginalManualEndpointsInsteadOfSnappedPoints() throws Exception {
        saveDraft(MANUAL_ROUTE);
        activeService();
        launch();
        activity.onSpeed(12);
        JSONObject saved = savedDraft();
        assertEquals(28.6001, saved.getJSONArray("start").getDouble(0), 0);
        assertEquals(77.2001, saved.getJSONArray("start").getDouble(1), 0);
        assertEquals(28.6201, saved.getJSONArray("end").getDouble(0), 0);
        assertEquals(77.2301, saved.getJSONArray("end").getDouble(1), 0);
    }

    @Test public void offlineRoadRouteDoesNotBecomeADirectLine() throws Exception {
        saveDraft(MANUAL_ROUTE.replace("\"direct\"", "\"road\""));
        launch();
        MainActivity.Draft draft = ReflectionHelpers.getField(activity, "draft");
        assertNull("A missing provider response cannot become a direct shortcut", draft.geometry);
        assertTrue(button("Enable routes").isEnabled());
        assertEquals("road", savedDraft().getString("kind"));
        assertNull(nextServiceIntent());
    }

    @Test public void selectedProviderCoordinatesAndLabelsStayOutOfSavedDraft() throws Exception {
        saveDraft(MANUAL_ROUTE);
        launch();
        ReflectionHelpers.callInstanceMethod(activity, "setPoint",
                ReflectionHelpers.ClassParameter.from(boolean.class, false),
                ReflectionHelpers.ClassParameter.from(double[].class, new double[]{51.5, -0.12}),
                ReflectionHelpers.ClassParameter.from(String.class, "Selected provider address"),
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        activity.onSpeed(7);
        JSONObject saved = savedDraft();
        assertFalse(saved.has("start"));
        assertTrue("The other manually selected endpoint is retained", saved.has("end"));
        assertFalse(saved.has("geometry"));
        assertFalse(saved.toString().contains("Selected provider address"));
        assertNull(nextServiceIntent());
    }

    @Test public void recreationRetainsTheSameRouteAndGatewayWithoutRefetching() {
        saveDraft(MANUAL_ROUTE);
        launch();
        MainActivity previous = activity;
        MainActivity.PlanningSession session = ReflectionHelpers.getField(activity, "session");
        MainActivity.Draft draft = session.draft;
        draft.kind = "road";
        draft.startFromProvider = true;
        draft.startLabel = "Provider selection retained in memory";
        double[][] geometry = draft.geometry;
        long generation = session.generation;
        controller.recreate(); activity = controller.get(); idle();
        assertSame(session, ReflectionHelpers.getField(activity, "session"));
        assertSame(session.network, ReflectionHelpers.getField(activity, "network"));
        assertSame(geometry, session.draft.geometry);
        assertEquals(generation, session.generation);
        assertEquals("Provider selection retained in memory", session.draft.startLabel);
        assertTrue(session.draft.startFromProvider);
        assertSame(activity, session.owner);
        assertTrue(previous.isDestroyed());
        assertFalse(session.closed);
        assertTrue(button("Start journey").isEnabled());
        assertNull(find(activity.findViewById(android.R.id.content), MapView.class));
        assertNull(nextServiceIntent());
    }

    @Test public void recreationPreservesPendingRouteSessionAndUpdatesItsNewOwner() {
        saveDraft(MANUAL_ROUTE);
        launch();
        MainActivity.PlanningSession session = ReflectionHelpers.getField(activity, "session");
        session.draft.kind = "road";
        session.draft.geometry = null;
        session.loading = true;
        session.message = "Finding a route along roads and paths…";
        session.generation = 42;
        session.notifyOwner(false);
        controller.recreate(); activity = controller.get(); idle();
        assertSame(session, ReflectionHelpers.getField(activity, "session"));
        assertSame(activity, session.owner);
        assertEquals("Rotation must not restart an in-flight route request", 42, session.generation);
        assertTrue(session.loading);
        assertFalse(session.closed);
        // Deliver a provider-shaped result to the retained state, without a native map or HTTP call.
        session.draft.geometry = new double[][]{{28.6001, 77.2001}, {28.6100, 77.2200}, {28.6201, 77.2301}};
        session.draft.distance = 3400;
        session.loading = false; session.message = "";
        session.notifyOwner(false);
        assertTrue(button("Start journey").isEnabled());
        assertFalse(ReflectionHelpers.<Boolean>getField(activity, "routeLoading"));
        assertNull(nextServiceIntent());
    }

    @Test public void recreationRestoresPickerTextAndReleasesTheOldWindow() {
        launch();
        activity.onPoint(true);
        AlertDialog previous = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = find(previous.getWindow().getDecorView(), EditText.class);
        assertNotNull(input);
        input.setText("東京駅, Japan");
        controller.recreate(); activity = controller.get(); idle();
        AlertDialog restored = ShadowAlertDialog.getLatestAlertDialog();
        assertFalse(previous.isShowing());
        assertTrue(restored.isShowing());
        EditText restoredInput = find(restored.getWindow().getDecorView(), EditText.class);
        assertNotNull(restoredInput);
        assertEquals("東京駅, Japan", restoredInput.getText().toString());
        Object picker = ReflectionHelpers.getField(activity, "currentPicker");
        assertTrue(ReflectionHelpers.<Boolean>getField(picker, "destination"));
        assertNull(nextServiceIntent());
    }

    @Test public void finishingAnActivityClosesItsPlanningSessionAndDetachesItsOwner() {
        launch();
        MainActivity.PlanningSession session = ReflectionHelpers.getField(activity, "session");
        controller.pause().stop().destroy(); controller = null;
        assertTrue(session.closed);
        assertNull(session.owner);
    }

    @Test public void routingKeyEntryIsNotRetainedAndItsWindowIsReleasedOnRotation() {
        launch();
        ReflectionHelpers.callInstanceMethod(activity, "showRoutingSetup");
        AlertDialog previous = ReflectionHelpers.getField(activity, "routingDialog");
        assertNotNull(previous);
        EditText input = find(previous.getWindow().getDecorView(), EditText.class);
        assertNotNull(input);
        assertFalse("A routing secret must not enter Android saved view state", input.isSaveEnabled());
        input.setText("test_fixture_key_not_real_123456");
        controller.recreate(); activity = controller.get(); idle();
        assertFalse(previous.isShowing());
        assertEquals("", input.getText().toString());
        assertNull(ReflectionHelpers.getField(activity, "routingDialog"));
        assertFalse(application().getSharedPreferences("routingAccess", 0).contains("sealed"));
        assertNull(nextServiceIntent());
    }

    @Test public void collapsedJourneyPanelSurvivesPickerRotationAndSavedStateRestoration() {
        launch();
        JourneyUi ui = ReflectionHelpers.getField(activity, "ui");
        ui.setSheetExpanded(false, false);
        activity.onPoint(true);
        AlertDialog previous = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = find(previous.getWindow().getDecorView(), EditText.class);
        assertNotNull(input); input.setText("Tokyo Station");
        idle();
        int[] originalInsets = ((int[]) ReflectionHelpers.getField(activity, "mapInsets")).clone();
        assertTrue(originalInsets[3] > 0);
        Bundle saved = new Bundle(); controller.saveInstanceState(saved);
        assertFalse(saved.getBoolean("sheetExpanded", true));
        assertArrayEquals(originalInsets, saved.getIntArray("mapInsets"));
        controller.recreate(); activity = controller.get(); idle();
        JourneyUi recreated = ReflectionHelpers.getField(activity, "ui");
        assertFalse(recreated.isSheetExpanded());
        AlertDialog restored = ShadowAlertDialog.getLatestAlertDialog();
        assertFalse(previous.isShowing()); assertTrue(restored.isShowing());
        EditText restoredInput = find(restored.getWindow().getDecorView(), EditText.class);
        assertNotNull(restoredInput); assertEquals("Tokyo Station", restoredInput.getText().toString());
        // A saved bundle must also restore the panel without a retained Activity/session object.
        controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).setup(saved);
        activity = controller.get();
        assertArrayEquals("Restore viewport padding before a newly loaded map receives its geometry",
                originalInsets, (int[]) ReflectionHelpers.getField(activity, "mapInsets"));
        idle();
        JourneyUi fromBundle = ReflectionHelpers.getField(activity, "ui");
        assertFalse(fromBundle.isSheetExpanded());
        assertNull(nextServiceIntent());
    }

    @Test public void searchKeepsFieldAndCloseVisibleWhenTheKeyboardConstrainsItsHeight() {
        launch();
        activity.onPoint(true);
        Object picker = ReflectionHelpers.getField(activity, "currentPicker");
        View content = ReflectionHelpers.getField(picker, "content");
        content.measure(View.MeasureSpec.makeMeasureSpec(336, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.AT_MOST));
        content.layout(0, 0, content.getMeasuredWidth(), content.getMeasuredHeight());
        EditText input = find(content, EditText.class);
        assertNotNull(input);
        View field = (View) input.getParent();
        assertTrue(field.getBottom() <= content.getHeight());
        View close = findDescription(content, "Close place search");
        assertNotNull(close); assertTrue(close.getHeight() >= 48);
        View scroll = ReflectionHelpers.getField(picker, "bodyScroll");
        View body = ReflectionHelpers.getField(picker, "searchBody");
        assertTrue("The body keeps some usable space above the keyboard", scroll.getHeight() > 0);
        assertTrue("All help and result content remains reachable by scrolling", body.getHeight() > scroll.getHeight());
        assertTrue(scroll.getBottom() <= content.getHeight());
    }

    @Test public void clearSearchLeavesThePickerOpenAndDoesNotChangeThePlannedPoint() throws Exception {
        saveDraft(MANUAL_ROUTE);
        launch();
        activity.onPoint(false);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = find(dialog.getWindow().getDecorView(), EditText.class);
        assertNotNull(input);
        input.setText("Delhi railway station");
        View clear = findDescription(dialog.getWindow().getDecorView(), "Clear search");
        assertNotNull(clear);
        assertEquals(View.VISIBLE, clear.getVisibility());
        assertTrue("Clear has a full touch target", clear.getLayoutParams().width >= 48);
        assertTrue(clear.performClick());
        assertEquals("", input.getText().toString());
        assertEquals(View.INVISIBLE, clear.getVisibility());
        assertTrue(dialog.isShowing());
        assertEquals(28.6001, savedDraft().getJSONArray("start").getDouble(0), 0);
        assertNull(nextServiceIntent());
    }

    @Test public void coordinateEntryAcceptsTheKeyboardSearchActionAndClosesThePicker() throws Exception {
        saveDraft("{\"kind\":\"direct\"}");
        launch();
        activity.onPoint(false);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = find(dialog.getWindow().getDecorView(), EditText.class);
        assertNotNull(input);
        input.setText("-33.8688, 151.2093");
        input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        idle();
        assertFalse(dialog.isShowing());
        assertEquals(-33.8688, savedDraft().getJSONArray("start").getDouble(0), 0);
        assertEquals(151.2093, savedDraft().getJSONArray("start").getDouble(1), 0);
        assertNull(nextServiceIntent());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void searchResultsKeepFullAddressAndAttributionWhileRejectingUnusableCoordinates() throws Exception {
        saveDraft(MANUAL_ROUTE);
        launch();
        activity.onPoint(true);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        Object picker = ReflectionHelpers.getField(activity, "currentPicker");
        EditText input = find(dialog.getWindow().getDecorView(), EditText.class);
        assertNotNull(input); input.setText("Tokyo Station");
        JSONObject place = new JSONObject().put("label", "Tokyo Station, Marunouchi, Tokyo, Japan")
                .put("lat", 35.6812).put("lon", 139.7671)
                .put("attributions", new JSONArray().put(new JSONObject().put("provider", "OpenStreetMap")
                        .put("providerUri", "https://www.openstreetmap.org/copyright")));
        JSONArray data = new JSONArray().put(new JSONObject().put("label", "Invalid place").put("lat", 100).put("lon", 0))
                .put(place);
        ReflectionHelpers.callInstanceMethod(picker, "showSearchResponse",
                ReflectionHelpers.ClassParameter.from(JSONObject.class, new JSONObject().put("ok", true).put("data", data)));
        View result = findDescription(dialog.getWindow().getDecorView(), place.getString("label"));
        assertNotNull(result);
        assertNull(findDescription(dialog.getWindow().getDecorView(), "Invalid place"));
        captureSearch(dialog);
        assertTrue(result.performClick());
        assertFalse(dialog.isShowing());
        MainActivity.Draft draft = ReflectionHelpers.getField(activity, "draft");
        assertArrayEquals(new double[]{35.6812, 139.7671}, draft.end, 0);
        assertEquals(place.getString("label"), draft.endLabel);
        assertTrue(draft.endFromProvider);
        assertEquals("OpenStreetMap", draft.endAttributions.getJSONObject(0).getString("provider"));
        assertFalse("Provider-derived addresses must remain in memory", savedDraft().has("end"));
        assertNull(nextServiceIntent());
    }

    @Test public void closeSearchReleasesWindowAndKeepsCoordinatesUnchanged() throws Exception {
        saveDraft(MANUAL_ROUTE);
        launch();
        activity.onPoint(true);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = find(dialog.getWindow().getDecorView(), EditText.class);
        assertNotNull(input);
        input.setText("51.5074, -0.1278");
        View close = findDescription(dialog.getWindow().getDecorView(), "Close place search");
        assertNotNull(close);
        assertTrue(close.performClick());
        idle(); // Android delivers OnDismissListener through the main message queue.
        assertFalse(dialog.isShowing());
        assertNull(ReflectionHelpers.getField(activity, "currentPicker"));
        assertEquals(28.6201, savedDraft().getJSONArray("end").getDouble(0), 0);
        assertNull(nextServiceIntent());
    }

    @Test public void manualViaPointsCreateAnOrderedDirectPathAndSurviveAColdRelaunch() throws Exception {
        readyRoute();
        setVia(0, new double[]{28.607, 77.209}, "First manual via", false);
        setVia(1, new double[]{28.615, 77.22}, "Second manual via", false);
        MainActivity.Draft draft = draft();
        assertPoints(new double[][]{{28.6001, 77.2001}, {28.607, 77.209}, {28.615, 77.22}, {28.6201, 77.2301}}, draft.geometry);
        assertPoints(draft.geometry, ReflectionHelpers.callStaticMethod(MainActivity.class, "plannedPoints",
                ReflectionHelpers.ClassParameter.from(MainActivity.Draft.class, draft)));
        activity.onSpeed(12);
        JSONObject saved = savedDraft();
        assertEquals("[[28.607,77.209],[28.615,77.22]]", saved.getJSONArray("vias").toString());
        assertFalse(saved.toString().contains("First manual via"));
        assertFalse(saved.has("geometry"));
        controller.pause().stop().destroy(); controller = null;
        launch();
        assertEquals(2, draft().vias.size());
        assertPoints(new double[][]{{28.6001, 77.2001}, {28.607, 77.209}, {28.615, 77.22}, {28.6201, 77.2301}}, draft().geometry);
        assertEquals(12, draft().speed, 0);
        assertNull(nextServiceIntent());
    }

    @Test public void viaEditingReorderingRemovalAndEndpointSwapKeepTheRequestedVisitOrder() throws Exception {
        readyRoute();
        setVia(0, new double[]{28.607, 77.209}, "First", false);
        setVia(1, new double[]{28.615, 77.22}, "Second", false);
        activity.onViaPoint(0); selectDialogItem("Move later");
        assertEquals("Second", draft().vias.get(0).label);
        assertEquals("First", draft().vias.get(1).label);
        activity.onViaPoint(1); selectDialogItem("Move earlier");
        assertEquals("First", draft().vias.get(0).label);
        activity.onViaPoint(0); selectDialogItem("Change place");
        enterPickerCoordinates("28.608,77.210");
        assertEquals(2, draft().vias.size());
        assertArrayEquals(new double[]{28.608, 77.210}, draft().vias.get(0).point, 0);
        activity.onOptions(); selectDialogItem("Swap start and destination");
        assertPoints(new double[][]{{28.6201, 77.2301}, {28.615, 77.22}, {28.608, 77.210}, {28.6001, 77.2001}}, draft().geometry);
        activity.onViaPoint(0); selectDialogItem("Remove via point");
        assertPoints(new double[][]{{28.6201, 77.2301}, {28.608, 77.210}, {28.6001, 77.2001}}, draft().geometry);
        assertEquals(1, savedDraft().getJSONArray("vias").length());
        assertNull(nextServiceIntent());
    }

    @Test public void providerViaPreventsRestoringAShortcutThatSilentlyDropsThatPoint() throws Exception {
        readyRoute();
        setVia(0, new double[]{28.607, 77.209}, "Manual", false);
        setVia(1, new double[]{28.615, 77.22}, "Provider-derived private place", true);
        activity.onSpeed(8);
        JSONObject saved = savedDraft();
        assertFalse("No endpoint-only plan may be restored while a via was omitted", saved.has("start"));
        assertFalse(saved.has("end"));
        assertFalse(saved.has("vias"));
        assertFalse(saved.toString().contains("Provider-derived"));
        assertEquals(4, draft().geometry.length);
        MainActivity.Draft original = draft();
        controller.recreate(); activity = controller.get(); idle();
        assertSame(original, draft());
        assertEquals(2, draft().vias.size());
        assertTrue(draft().vias.get(1).fromProvider);
        controller.pause().stop().destroy(); controller = null;
        launch();
        assertNull(draft().start); assertNull(draft().end); assertTrue(draft().vias.isEmpty());
        assertNull(draft().geometry);
        assertTrue(button("Choose start").isEnabled());
        assertNull(nextServiceIntent());
    }

    @Test public void damagedPersistedViaListsCannotBecomeAStartToDestinationShortcut() throws Exception {
        JSONObject damaged = new JSONObject(MANUAL_ROUTE).put("vias", new JSONArray("[[28.61,77.21],[91,0]]"));
        saveDraft(damaged.toString()); launch();
        assertNull(draft().start); assertNull(draft().end); assertNull(draft().geometry);
        assertTrue(draft().vias.isEmpty());
        assertTrue(button("Choose start").isEnabled());
        assertNull(nextServiceIntent());
    }

    @Test public void sixViaLimitAndInvalidCoordinatesCannotCorruptTheExistingPath() throws Exception {
        readyRoute();
        for (int i = 0; i < 6; i++) setVia(i, new double[]{28.602 + i * .002, 77.205 + i * .003}, "Via " + i, false);
        double[][] before = draft().geometry;
        setVia(6, new double[]{28.619, 77.229}, "Seventh", false);
        setVia(1, new double[]{Double.NaN, 0}, "Invalid", false);
        setVia(-1, new double[]{0, 0}, "Invalid index", false);
        activity.onAddVia();
        assertEquals(6, draft().vias.size());
        assertSame(before, draft().geometry);
        assertNull(ReflectionHelpers.getField(activity, "currentPicker"));
        assertEquals(6, savedDraft().getJSONArray("vias").length());
        assertNull(nextServiceIntent());
    }

    @Test public void viaPickerRotationRetainsItsIndexAndCoordinatesChangeOnlyThatVia() throws Exception {
        readyRoute();
        setVia(0, new double[]{28.607, 77.209}, "First", false);
        setVia(1, new double[]{28.615, 77.22}, "Second", false);
        activity.onViaPoint(1); selectDialogItem("Change place");
        AlertDialog old = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = find(old.getWindow().getDecorView(), EditText.class);
        assertNotNull(input); input.setText("28.616,77.221");
        controller.recreate(); activity = controller.get(); idle();
        Object picker = ReflectionHelpers.getField(activity, "currentPicker");
        assertEquals(1, ReflectionHelpers.<Integer>getField(picker, "viaIndex").intValue());
        AlertDialog restored = ShadowAlertDialog.getLatestAlertDialog();
        assertFalse(old.isShowing()); assertTrue(restored.isShowing());
        EditText restoredInput = find(restored.getWindow().getDecorView(), EditText.class);
        assertNotNull(restoredInput); assertEquals("28.616,77.221", restoredInput.getText().toString());
        restoredInput.onEditorAction(EditorInfo.IME_ACTION_SEARCH); idle();
        assertEquals(2, draft().vias.size());
        assertArrayEquals(new double[]{28.607, 77.209}, draft().vias.get(0).point, 0);
        assertArrayEquals(new double[]{28.616, 77.221}, draft().vias.get(1).point, 0);
        assertArrayEquals(new double[]{28.6201, 77.2301}, draft().end, 0);
        assertNull(nextServiceIntent());
    }

    @Test public void addingViaThroughSearchAndPendingMapSelectionKeepTheirTargetsOnRotation() throws Exception {
        readyRoute();
        activity.onAddVia();
        Object picker = ReflectionHelpers.getField(activity, "currentPicker");
        assertEquals(0, ReflectionHelpers.<Integer>getField(picker, "viaIndex").intValue());
        enterPickerCoordinates("28.607,77.209");
        assertEquals(1, draft().vias.size());
        // Exercise the native map callback target without creating a renderer or loading tiles.
        ReflectionHelpers.setField(activity, "selection", 3); // append via index 1
        controller.recreate(); activity = controller.get(); idle();
        assertEquals(3, ReflectionHelpers.<Integer>getField(activity, "selection").intValue());
        tapMap(28.615, 77.22);
        assertEquals(-1, ReflectionHelpers.<Integer>getField(activity, "selection").intValue());
        assertPoints(new double[][]{{28.6001, 77.2001}, {28.607, 77.209}, {28.615, 77.22}, {28.6201, 77.2301}}, draft().geometry);
        assertNull(nextServiceIntent());
    }

    @Test public void routeChoiceChangesGeometryAndPaceDurationWithoutRefetchOrProviderTime() throws Exception {
        readyRoute();
        draft().kind = "road"; draft().speed = 36;
        loadChoices(routeChoicesFixture());
        MainActivity.PlanningSession session = ReflectionHelpers.getField(activity, "session");
        long generation = session.generation;
        session.notifyOwner(false);
        double[][] balanced = draft().geometry;
        activity.onRouteChoice(1);
        assertEquals(generation, session.generation);
        assertEquals(1, draft().selectedRoute); assertEquals("short", draft().routePreference);
        assertEquals(36, draft().speed, 0);
        assertEquals(2, draft().geometry.length);
        assertSame(balanced, draft().otherRoutes[0]);
        assertSame(draft().routes.get(1).points, draft().geometry);
        assertTrue(draft().distance > 2220 && draft().distance < 2230);
        JourneyUi ui = ReflectionHelpers.getField(activity, "ui");
        ViewGroup cards = ReflectionHelpers.getField(ui, "routeChoiceCards");
        assertTrue(((Button) cards.getChildAt(1)).getText().toString().contains("4 min"));
        assertTrue(cards.getChildAt(1).isSelected());
        double[][] chosen = draft().geometry;
        activity.onSpeed(72);
        assertEquals(generation, session.generation);
        assertSame(chosen, draft().geometry);
        assertTrue(((Button) cards.getChildAt(1)).getText().toString().contains("2 min"));
        assertEquals("short", savedDraft().getString("routePreference"));
        assertFalse(savedDraft().has("routes"));
        assertFalse(savedDraft().has("geometry"));
        assertNull(nextServiceIntent());
    }

    @Test public void routePreferenceAndSelectedGeometrySurviveRotationAndWarnForViaSnaps() throws Exception {
        readyRoute(); draft().kind = "road";
        JSONObject choices = routeChoicesFixture();
        choices.getJSONArray("routes").getJSONObject(1).put("waypointSnapMeters", new JSONArray("[0,120,0]"));
        draft().routePreference = "short";
        loadChoices(choices);
        assertEquals(1, draft().selectedRoute);
        assertTrue(draft().routeWarning.contains("120 m"));
        double[][] selected = draft().geometry;
        MainActivity.PlanningSession session = ReflectionHelpers.getField(activity, "session");
        long generation = session.generation; session.notifyOwner(false);
        controller.recreate(); activity = controller.get(); idle();
        assertSame(selected, draft().geometry);
        assertEquals(1, draft().selectedRoute); assertEquals("short", draft().routePreference);
        assertEquals(generation, session.generation);
        activity.onRouteChoice(0);
        assertEquals("", draft().routeWarning);
        assertNull(nextServiceIntent());
    }

    @Test public void activeSessionBlocksViaAndRouteChoiceEditsIncludingAnAlreadyOpenMenu() throws Exception {
        readyRoute();
        setVia(0, new double[]{28.607, 77.209}, "First", false);
        draft().kind = "road"; loadChoices(routeChoicesFixture());
        activity.onViaPoint(0);
        AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
        MainActivity.PlanningSession session = ReflectionHelpers.getField(activity, "session");
        long generation = session.generation; double[][] geometry = draft().geometry;
        activeService();
        selectDialogItem("Remove via point");
        activity.onAddVia(); activity.onViaPoint(0); activity.onRouteChoice(1);
        setVia(0, new double[]{0, 0}, "Blocked", false); tapMap(0, 0);
        assertEquals(1, draft().vias.size());
        assertEquals("First", draft().vias.get(0).label);
        assertEquals(0, draft().selectedRoute);
        assertSame(geometry, draft().geometry);
        assertEquals(generation, session.generation);
        assertNull(ReflectionHelpers.getField(activity, "currentPicker"));
        assertSame(menu, ShadowAlertDialog.getLatestAlertDialog());
        assertNull(nextServiceIntent());
    }

    @Test public void savedKeyAuthFailureOffersRetryWithoutAskingForTheKeyAgain() throws Exception {
        readyRoute(); saveFixtureKey();
        // Set planning online after launch to test the state without initializing the map renderer.
        application().getSharedPreferences("settings", 0).edit().putBoolean("online", true).commit();
        draft().kind = "road"; draft().geometry = null;
        MainActivity.PlanningSession session = ReflectionHelpers.getField(activity, "session");
        session.failed = true; session.errorCode = "AUTH"; session.message = "Geoapify rejected this key.";
        session.notifyOwner(false);
        assertTrue(button("Retry route").isEnabled());
        assertNull(findButton(activity.findViewById(android.R.id.content), "Set up routing"));
        assertTrue(RoutingCredentials.hasSavedPersonalKey(activity));
        assertEquals("test_fixture_key_not_real_123456", RoutingCredentials.get(activity));
        assertNull(ReflectionHelpers.getField(activity, "routingDialog"));
        assertNull(nextServiceIntent());
    }

    @Test public void confirmingDisplayedProviderCoordinatesPreservesTheirOriginalProvenance() throws Exception {
        readyRoute();
        double[] original = {28.6071234, 77.2091234};
        setVia(0, original, "Provider via", true);
        JSONArray credits = new JSONArray().put(new JSONObject().put("provider", "Fixture attribution")
                .put("providerUri", "https://www.openstreetmap.org/copyright"));
        draft().vias.get(0).attributions = credits;
        activity.onViaPoint(0); selectDialogItem("Change place");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = find(dialog.getWindow().getDecorView(), EditText.class); assertNotNull(input);
        assertEquals("28.60712, 77.20912", input.getText().toString());
        input.onEditorAction(EditorInfo.IME_ACTION_SEARCH); idle();
        assertTrue(draft().vias.get(0).fromProvider);
        assertEquals("Provider via", draft().vias.get(0).label);
        assertArrayEquals(original, draft().vias.get(0).point, 0);
        assertEquals(credits.toString(), draft().vias.get(0).attributions.toString());
        assertFalse(savedDraft().has("start")); assertFalse(savedDraft().has("vias"));

        activity.onViaPoint(0); selectDialogItem("Remove via point");
        ReflectionHelpers.callInstanceMethod(activity, "setPoint",
                ReflectionHelpers.ClassParameter.from(boolean.class, false),
                ReflectionHelpers.ClassParameter.from(double[].class, original),
                ReflectionHelpers.ClassParameter.from(String.class, "Provider start"),
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        draft().startAttributions = credits;
        activity.onPoint(false);
        dialog = ShadowAlertDialog.getLatestAlertDialog();
        input = find(dialog.getWindow().getDecorView(), EditText.class); assertNotNull(input);
        input.onEditorAction(EditorInfo.IME_ACTION_SEARCH); idle();
        assertTrue(draft().startFromProvider);
        assertEquals("Provider start", draft().startLabel);
        assertArrayEquals(original, draft().start, 0);
        assertEquals(credits.toString(), draft().startAttributions.toString());
        assertFalse(savedDraft().has("start"));
        assertNull(nextServiceIntent());
    }

    @Test public void temporaryAbsenceOfPreferredRouteDoesNotEraseTheUsersChoice() throws Exception {
        readyRoute(); draft().kind = "road"; draft().routePreference = "short";
        JSONObject available = routeChoicesFixture();
        loadChoices(new JSONObject().put("routes", new JSONArray().put(available.getJSONArray("routes").getJSONObject(0))));
        assertEquals(0, draft().selectedRoute);
        assertEquals("short", draft().routePreference);
        activity.onSpeed(10);
        assertEquals("short", savedDraft().getString("routePreference"));
        loadChoices(available);
        assertEquals(1, draft().selectedRoute);
        assertEquals("short", draft().routePreference);
        activity.onRouteChoice(0);
        assertEquals("balanced", draft().routePreference);
        assertEquals("balanced", savedDraft().getString("routePreference"));
        assertNull(nextServiceIntent());
    }

    @Test public void savedRoutingAccessShowsItsStateAndOnlyRevealsABlankFieldOnReplace() throws Exception {
        launch(); saveFixtureKey();
        String sealed = application().getSharedPreferences("routingAccess", 0).getString("sealed", "");
        ReflectionHelpers.callInstanceMethod(activity, "showRoutingSetup");
        AlertDialog dialog = ReflectionHelpers.getField(activity, "routingDialog");
        assertNotNull(dialog);
        View decor = dialog.getWindow().getDecorView();
        EditText input = find(decor, EditText.class); assertNotNull(input);
        assertEquals(View.GONE, input.getVisibility());
        assertEquals("", input.getText().toString());
        assertNotNull(findText(decor, "Key saved · reused automatically"));
        assertEquals("Done", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        Button replace = findButton(decor, "Replace key"); assertNotNull(replace); replace.performClick();
        assertEquals(View.VISIBLE, input.getVisibility());
        assertEquals("", input.getText().toString());
        assertFalse(input.isSaveEnabled());
        assertEquals("Save key", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        dialog.dismiss(); idle();
        assertEquals(sealed, application().getSharedPreferences("routingAccess", 0).getString("sealed", ""));
        assertEquals("test_fixture_key_not_real_123456", RoutingCredentials.get(activity));
        assertNull(nextServiceIntent());
    }

    private MainActivity.Draft draft() { return ReflectionHelpers.getField(activity, "draft"); }

    private void setVia(int index, double[] point, String label, boolean provider) {
        ReflectionHelpers.callInstanceMethod(activity, "setViaPoint",
                ReflectionHelpers.ClassParameter.from(int.class, index),
                ReflectionHelpers.ClassParameter.from(double[].class, point),
                ReflectionHelpers.ClassParameter.from(String.class, label),
                ReflectionHelpers.ClassParameter.from(boolean.class, provider),
                ReflectionHelpers.ClassParameter.from(JSONArray.class, null));
    }

    private void loadChoices(JSONObject data) {
        ReflectionHelpers.callStaticMethod(MainActivity.class, "loadRouteChoices",
                ReflectionHelpers.ClassParameter.from(MainActivity.Draft.class, draft()),
                ReflectionHelpers.ClassParameter.from(JSONObject.class, data));
    }

    private void enterPickerCoordinates(String coordinates) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = find(dialog.getWindow().getDecorView(), EditText.class);
        assertNotNull(input); input.setText(coordinates); input.onEditorAction(EditorInfo.IME_ACTION_SEARCH); idle();
        assertFalse(dialog.isShowing());
    }

    private void selectDialogItem(String label) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        android.widget.ListView list = dialog.getListView(); assertNotNull(list);
        int found = -1;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if (label.equals(list.getAdapter().getItem(i).toString())) { found = i; break; }
        }
        assertTrue("Missing dialog option: " + label, found >= 0);
        assertTrue(list.performItemClick(null, found, list.getAdapter().getItemId(found))); idle();
    }

    private static JSONObject routeChoicesFixture() throws Exception {
        JSONObject balanced = new JSONObject().put("points", new JSONArray("[[0,0],[0.01,0],[0.01,0.02],[0,0.02]]"))
                .put("preference", "balanced").put("durationSeconds", 1).put("distanceMeters", 1);
        JSONObject shortest = new JSONObject().put("points", new JSONArray("[[0,0],[0,0.02]]"))
                .put("preference", "short").put("durationSeconds", 999999).put("distanceMeters", 1);
        return new JSONObject().put("routes", new JSONArray().put(balanced).put(shortest));
    }

    private static void saveFixtureKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(128);
        SecretKey encryptionKey = generator.generateKey();
        RoutingCredentials.save(application(), "test_fixture_key_not_real_123456", create -> encryptionKey);
    }

    private static void assertPoints(double[][] expected, double[][] actual) {
        assertNotNull(actual); assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], actual[i], 0);
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }

    private static void captureSearch(AlertDialog dialog) throws Exception {
        String outputDirectory = System.getenv("JOURNEY_UI_SCREENSHOT_DIR");
        if (outputDirectory == null || outputDirectory.isBlank()) return;
        File directory = new File(outputDirectory);
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create screenshot directory");
        idle(); // Attach the dialog and lay out its scroll indicators before native drawing.
        View decor = dialog.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(336, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, 336, decor.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(336, decor.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(directory, "native-place-search-results.png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }

    private void readyRoute() {
        saveDraft(MANUAL_ROUTE);
        launch();
        AppOpsManager appOps = (AppOpsManager) activity.getSystemService(Context.APP_OPS_SERVICE);
        shadowOf(appOps).setMode("android:mock_location", Process.myUid(), activity.getPackageName(), AppOpsManager.MODE_ALLOWED);
        assertTrue(button("Start journey").isEnabled());
    }

    private static void layoutJourney(JourneyUi ui, int width, int height) {
        // Run the real stable-layout callback synchronously; a window traversal in
        // Robolectric would otherwise replace this explicit landscape test size.
        for (int pass = 0; pass < 2; pass++) {
            ui.root().measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            ui.root().layout(0, 0, width, height);
            ReflectionHelpers.<Runnable>getField(ui.root(), "sendInsets").run();
        }
    }

    private Rect placeholderTextBounds(JourneyUi ui) {
        TextView placeholder = ReflectionHelpers.getField(activity, "mapPlaceholder");
        assertNotNull(placeholder);
        assertNotNull(placeholder.getLayout());
        Rect first = new Rect(), last = new Rect();
        placeholder.getLineBounds(0, first);
        placeholder.getLineBounds(placeholder.getLineCount() - 1, last);
        first.union(last);
        ((ViewGroup) ui.root()).offsetDescendantRectToMyCoords(placeholder, first);
        return first;
    }

    private void launch() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        idle();
    }

    private void enterCoordinates(boolean destination, String text) {
        activity.onPoint(destination);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = find(dialog.getWindow().getDecorView(), EditText.class);
        assertNotNull(input);
        input.setText(text);
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertFalse(dialog.isShowing());
    }

    private AlertDialog rationale() {
        AlertDialog dialog = ReflectionHelpers.getField(activity, "permissionDialog");
        assertNotNull("Starting a fresh session must display its explanation", dialog);
        return dialog;
    }

    private void tapMap(double latitude, double longitude) {
        // Invoke the map callback directly; no native renderer or routing access is involved.
        ReflectionHelpers.callInstanceMethod(activity, "mapTapped",
                ReflectionHelpers.ClassParameter.from(double.class, latitude),
                ReflectionHelpers.ClassParameter.from(double.class, longitude));
        idle();
    }

    private void assertSingleStart() {
        Intent intent = nextServiceIntent();
        assertNotNull("Expected one start service Intent", intent);
        assertEquals("start", intent.getAction());
        assertEquals(SimulationService.class.getName(), intent.getComponent().getClassName());
        assertNotNull(SimulationService.stagedPlan);
        assertNull(nextServiceIntent());
    }

    private static void activeService() {
        SimulationService.active = true;
        SimulationService.routePoints = new double[][]{{28.6009, 77.2009}, {28.6209, 77.2309}};
        SimulationService.state = "{\"status\":\"running\",\"speedKmh\":5,\"mode\":\"once\","
                + "\"totalMeters\":3200,\"distanceMeters\":0,\"lat\":28.6009,\"lon\":77.2009}";
    }
    private static Application application() { return RuntimeEnvironment.getApplication(); }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static Intent nextServiceIntent() { return shadowOf(application()).getNextStartedService(); }
    private static void drainServiceIntents() { while (nextServiceIntent() != null) { } }
    private static void saveDraft(String value) {
        application().getSharedPreferences("nativeDraft", 0).edit().putString("value", value).commit();
    }
    private static JSONObject savedDraft() throws Exception {
        return new JSONObject(application().getSharedPreferences("nativeDraft", 0).getString("value", "{}"));
    }
    private Button button(String label) {
        Button result = findButton(activity.findViewById(android.R.id.content), label);
        assertNotNull("Missing button: " + label, result);
        return result;
    }
    private static Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = findButton(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }
    private static View findDescription(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) view).getChildAt(i), description);
            if (found != null) return found;
        }
        return null;
    }
    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T found = find(((ViewGroup) view).getChildAt(i), type);
            if (found != null) return found;
        }
        return null;
    }
}
