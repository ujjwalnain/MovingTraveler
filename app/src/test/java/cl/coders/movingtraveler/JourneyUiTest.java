// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
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

/** Native widget/layout checks; these do not emulate map tiles or Android mock delivery. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class JourneyUiTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private JourneyUi ui;
    private RecordingListener listener;

    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        listener = new RecordingListener();
        ui = new JourneyUi(activity, listener);
        activity.setContentView(ui.root());
    }

    @After public void cleanup() { controller.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1); }

    @Test public void initialAndCleanFocusedSpeedArePopulated() {
        EditText editor = find(EditText.class);
        assertEquals("5", editor.getText().toString());
        ready();
        assertTrue(editor.requestFocus());
        ui.render("idle", "", "Start", "End", 15, 1600, 200,
                false, false, true, "cycle", "once", "road", false);
        assertEquals("15", editor.getText().toString());
        assertTrue(listener.events.isEmpty());
        layout(360, 740);
        assertTrue("Numeric text uses the actual field width", editor.getLayout().getWidth() <= editor.getWidth());
    }

    @Test public void compactInsetsKeepTheCoreSpeedSliderAboveTheActionBar() {
        ready();
        ui.root().setPadding(8, 24, 8, 28);
        layout(360, 740);
        Rect sliderBounds = bounds(find(SeekBar.class));
        Rect formBounds = bounds(find(ScrollView.class));
        assertTrue("Full slider must remain visible before scrolling: slider=" + sliderBounds + ", form=" + formBounds
                + ", editor=" + bounds(find(EditText.class)) + ", map=" + bounds(ui.mapContainer)
                + ", fontScale=" + RuntimeEnvironment.getFontScale(), sliderBounds.bottom <= formBounds.bottom);
        assertTrue(sliderBounds.top >= formBounds.top);
        assertTrue(sliderBounds.height() >= 48);
        assertTrue(bounds(ui.mapContainer).height() >= 250);
    }

    @Test public void compactReadySheetLeavesEnoughMapSpaceToFitTheRoute() {
        ready();
        ui.root().setPadding(8, 24, 8, 28);
        final int[] insets = new int[4];
        ui.setMapInsetsListener((l, t, r, b) -> { insets[0] = l; insets[1] = t; insets[2] = r; insets[3] = b; });
        layout(360, 740);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue("Floating controls must leave room for route fitting",
                ui.mapContainer.getHeight() - insets[1] - insets[3] >= 100);
        assertTrue(insets[3] > 300);
    }

    @Test public void doubleSizeTextRemainsReadableAndActionsStayVisible() {
        RuntimeEnvironment.setFontScale(2);
        ui = new JourneyUi(activity, listener);
        activity.setContentView(ui.root());
        ui.render("idle", "Walking routes may not include every path. Use caution.",
                "Madison Square Park", "Washington Square Park", 15, 1600, 200,
                false, false, true, "walk", "once", "road", false);
        ui.root().setPadding(8, 24, 8, 28);
        layout(360, 740);
        EditText editor = find(EditText.class);
        assertTrue("Speed editor cannot clip large text", editor.getHeight() >= editor.getLineHeight());
        assertTrue(bounds(editor).right <= 352);
        assertTrue(bounds(button("Start journey")).bottom <= 712);
        assertTrue(bounds(button("Start journey")).height() >= 48);
        assertTrue("Large text has a scrollable form", find(ScrollView.class).canScrollVertically(1));
    }

    @Test public void renderingNeverDispatchesACommand() {
        ready();
        ui.render("running", "", "Start", "End", 15, 1600, 200,
                true, false, false, "cycle", "once", "road", false);
        ui.render("paused", "", "Start", "End", 50, 1600, 100,
                true, false, false, "drive", "once", "road", false);
        assertEquals(0, listener.events.size());
    }

    @Test public void portraitMapFillsSafeAreaBehindVisibleSheetActions() {
        ready();
        ui.root().setPadding(8, 24, 8, 28);
        layout(360, 740);
        Rect primaryBounds = bounds(button("Start journey"));
        Rect mapBounds = bounds(ui.mapContainer);
        assertTrue(primaryBounds.bottom <= 740 - 28);
        assertTrue(primaryBounds.height() >= 48);
        assertEquals(8, mapBounds.left);
        assertEquals(352, mapBounds.right);
        assertTrue("Map retains useful portrait space", mapBounds.height() >= 250);
        assertEquals("Map fills the safe area behind controls", 712, mapBounds.bottom);
        assertEquals(24, mapBounds.top);
        assertTrue(mapBounds.contains(primaryBounds));
        for (View view : descendants(ui.root())) {
            if (view instanceof Button && view.getVisibility() == View.VISIBLE && view.isShown()) {
                assertTrue("Button touch target: " + ((Button) view).getText(), view.getHeight() >= 48);
            }
        }
    }

    @Test public void landscapeKeepsMapBesideVisiblePrimaryAction() {
        ready();
        layout(740, 360);
        Rect mapBounds = bounds(ui.mapContainer);
        Rect primaryBounds = bounds(button("Start journey"));
        assertTrue(mapBounds.width() >= 350);
        assertEquals("Map also fills the landscape background", 740, mapBounds.right);
        assertTrue("The journey card is on the right", primaryBounds.left >= 390);
        assertTrue(primaryBounds.bottom <= 360);
    }

    @Test public void retryRouteIsAnEnabledPrimaryActionAndLoadingIsDisabled() {
        ui.render("route_error", "No route found", "Start", "End", 5, 0, 0,
                false, false, true, "walk", "once", "road", false);
        Button retry = button("Retry route");
        assertTrue(retry.isEnabled());
        assertTrue(hasVisibleText("Route unavailable"));
        assertFalse(hasVisibleText("Choose a destination"));
        retry.performClick();
        assertEquals("primary", listener.events.get(0));
        ui.render("idle", "", "Start", "End", 5, 0, 0,
                false, false, false, "walk", "once", "road", true);
        assertFalse(button("Finding route…").isEnabled());
    }

    @Test public void primaryActionGuidesEveryPlanningStepWithoutCallingStartDuringRender() {
        String[] states = {"setup_required", "online_required", "select_start", "select_destination", "route_error"};
        String[] labels = {"Set up routing", "Enable routes", "Choose start", "Choose destination", "Retry route"};
        for (int i = 0; i < states.length; i++) {
            listener.events.clear();
            ui.render(states[i], "", i < 3 ? "" : "Start", "", 5, 0, 0,
                    false, false, true, "walk", "once", "road", false);
            layout(360, 740);
            Button action = button(labels[i]);
            assertTrue(states[i], action.isEnabled());
            assertTrue(states[i], bounds(action).bottom <= 740);
            assertTrue(listener.events.isEmpty());
            assertFalse("Pace comes after the route preview", find(SeekBar.class).isShown());
            assertFalse("Planning is not route ready", hasVisibleText("Route ready"));
            action.performClick();
            assertEquals(1, listener.events.size());
            assertEquals("primary", listener.events.get(0));
        }
    }

    @Test public void routePreviewRevealsPaceAndKeepsExplicitDirectAndStationaryOptions() {
        ui.render("select_destination", "", "Start", "", 5, 0, 0,
                false, false, true, "walk", "once", "road", false);
        layout(360, 740);
        assertTrue(button("Walk").isShown());
        assertTrue(button("Cycle").isShown());
        assertTrue(button("Drive").isShown());
        assertFalse(find(EditText.class).isShown());
        ready();
        layout(360, 740);
        assertTrue(hasVisibleText("Your route"));
        assertTrue(hasVisibleText("1.6 km · 20 min"));
        assertTrue(find(EditText.class).isShown());
        ui.render("idle", "", "Start", "End", 5, 1600, 1152,
                false, false, true, "walk", "once", "direct", false);
        button("Direct line · One way").performClick();
        assertEquals("options", listener.events.get(0));
        ui.render("idle", "", "Start", "", 5, 0, 0,
                false, false, true, "walk", "static", "direct", false);
        layout(360, 740);
        assertTrue(button("Start location").isEnabled());
        assertFalse(button("Walk").isShown());
        assertFalse(find(EditText.class).isShown());
        assertTrue(button("Stationary").isShown());
    }

    @Test public void pendingOrLoadingDisablesPrimaryRegardlessOfPlanningStep() {
        ui.render("setup_required", "", "", "", 5, 0, 0,
                false, true, true, "walk", "once", "road", false);
        assertFalse(button("Please wait…").isEnabled());
        ui.render("route_error", "", "Start", "End", 5, 0, 0,
                false, false, true, "walk", "once", "road", true);
        assertFalse(button("Finding route…").isEnabled());
        ui.render("select_start", "", "", "", 5, 0, 0,
                false, false, false, "walk", "once", "road", false);
        assertFalse(button("Choose start").isEnabled());
    }

    @Test public void runningAndPausedOfferCorrectActionsWithoutEditableRoute() {
        ui.render("running", "", "Start", "End", 5, 1600, 200,
                true, false, false, "walk", "once", "road", false);
        layout(360, 740);
        assertTrue(button("Pause").isEnabled());
        assertTrue(button("Stop").isShown());
        assertTrue(bounds(button("Stop")).bottom <= 740);
        assertFalse(button("Walk").isShown());
        for (View view : descendants(ui.root())) {
            if (view.getContentDescription() != null && view.getContentDescription().toString().startsWith("Starting point.")) assertFalse(view.isEnabled());
        }
        ui.restoreSheet(false);
        ui.render("paused", "", "Start", "End", 5, 1600, 200,
                true, false, false, "walk", "once", "road", false);
        layout(360, 740);
        assertEquals(View.GONE, find(ScrollView.class).getVisibility());
        assertTrue(button("Resume").isShown());
        assertTrue(button("Stop").isShown());
        assertTrue(bounds(button("Stop")).bottom <= 740);
        button("Resume").performClick();
        assertEquals("primary", listener.events.get(0));
        button("Stop").performClick();
        assertEquals("stop", listener.events.get(1));
    }

    @Test public void arrivedAndStationarySessionsHaveOneFullWidthStopAction() {
        ui.render("arrived", "", "Start", "End", 5, 1600, 0,
                true, false, false, "walk", "once", "road", false);
        layout(360, 740);
        assertTrue(bounds(button("Stop")).width() >= 300);
        ui.render("running", "", "Start", "", 5, 0, 0,
                true, false, false, "walk", "static", "direct", false);
        layout(360, 740);
        assertTrue(bounds(button("Stop")).width() >= 300);
        assertFalse(find(SeekBar.class).isShown());
        button("Stop").performClick();
        assertEquals("stop", listener.events.get(0));
    }

    @Test public void stateRefreshPreservesFocusedSpeedTextAndCursor() {
        ready();
        EditText editor = find(EditText.class);
        assertTrue(editor.requestFocus());
        editor.setText("17.");
        editor.setSelection(2);
        ui.render("idle", "", "Start", "End", 50, 1600, 200,
                false, false, true, "walk", "once", "road", false);
        assertEquals("17.", editor.getText().toString());
        assertEquals(2, editor.getSelectionStart());
        assertTrue(listener.events.isEmpty());
        editor.onEditorAction(EditorInfo.IME_ACTION_DONE);
        assertEquals(1, listener.events.size());
        assertEquals("speed:17.0", listener.events.get(0));
    }

    @Test public void touchSliderDispatchesOnlyTheReleasedValue() {
        ready();
        layout(360, 740);
        SeekBar slider = find(SeekBar.class);
        long when = SystemClock.uptimeMillis();
        dispatch(slider, when, MotionEvent.ACTION_DOWN, 10);
        dispatch(slider, when + 20, MotionEvent.ACTION_MOVE, slider.getWidth() * .3f);
        dispatch(slider, when + 40, MotionEvent.ACTION_MOVE, slider.getWidth() * .6f);
        assertEquals(0, listener.events.size());
        dispatch(slider, when + 60, MotionEvent.ACTION_UP, slider.getWidth() * .6f);
        assertEquals(1, listener.events.size());
        assertTrue(listener.events.get(0).startsWith("speed:"));
    }

    @Test public void invalidSpeedNeverReachesTheListener() {
        ready();
        EditText editor = find(EditText.class);
        editor.requestFocus();
        editor.setText("201");
        editor.onEditorAction(EditorInfo.IME_ACTION_DONE);
        assertNotNull(editor.getError());
        assertTrue(listener.events.isEmpty());
        editor.setText("0.5");
        editor.onEditorAction(EditorInfo.IME_ACTION_DONE);
        assertEquals("speed:0.5", listener.events.get(0));
    }

    @Test public void invalidPaceCannotBlockPauseOrResumeAndKeepsLastValidSpeed() {
        String[] states = {"running", "paused"};
        String[] actions = {"Pause", "Resume"};
        for (int i = 0; i < states.length; i++) {
            ui.render(states[i], "", "Start", "End", 15, 1600, 200,
                    true, false, false, "cycle", "once", "road", false);
            EditText editor = find(EditText.class);
            assertTrue(editor.requestFocus());
            editor.setText("999");
            listener.events.clear();
            button(actions[i]).performClick();
            assertEquals("Only the playback action is dispatched", 1, listener.events.size());
            assertEquals("primary", listener.events.get(0));
            assertEquals("15", editor.getText().toString());
        }
    }

    @Test public void invalidPaceStillPreventsStartingANewJourney() {
        ready();
        EditText editor = find(EditText.class);
        assertTrue(editor.requestFocus());
        editor.setText("999");
        button("Start journey").performClick();
        assertTrue(listener.events.isEmpty());
        assertNotNull(editor.getError());
        assertEquals("999", editor.getText().toString());
    }

    @Test public void invalidPaceCannotBlockAnyPlanningPrimaryAction() {
        String[] states = {"setup_required", "online_required", "select_start", "select_destination", "route_error"};
        String[] actions = {"Set up routing", "Enable routes", "Choose start", "Choose destination", "Retry route"};
        for (int i = 0; i < states.length; i++) {
            ready();
            EditText editor = find(EditText.class);
            assertTrue(editor.requestFocus());
            editor.setText("999");
            ui.render(states[i], "", "Start", "End", 5, 0, 0,
                    false, false, true, "walk", "once", "road", false);
            listener.events.clear();
            button(actions[i]).performClick();
            assertEquals(states[i], 1, listener.events.size());
            assertEquals("primary", listener.events.get(0));
            assertEquals("5", editor.getText().toString());
        }
    }

    @Test public void collapsedSheetKeepsActionsAndReleasesMapSpace() {
        final List<int[]> insets = new ArrayList<>();
        ui.setMapInsetsListener((l, t, r, b) -> insets.add(new int[]{l, t, r, b}));
        ready(); layout(390, 844);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        int expandedInset = insets.get(insets.size() - 1)[3];
        Rect before = bounds(button("Start journey"));
        ui.setSheetExpanded(false, false); layout(390, 844);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(ui.isSheetExpanded());
        assertEquals(View.GONE, find(ScrollView.class).getVisibility());
        assertEquals(before.bottom, bounds(button("Start journey")).bottom);
        assertTrue(button("Start journey").isShown());
        assertTrue(insets.get(insets.size() - 1)[3] < expandedInset - 100);
        assertTrue("Collapsing does not execute a journey command", listener.events.isEmpty());
        ui.setSheetExpanded(true, false); layout(390, 844);
        assertTrue(find(ScrollView.class).isShown());
    }

    @Test public void landscapeSideCardKeepsThePortraitSheetChoice() {
        ready(); ui.restoreSheet(false); layout(390, 844);
        assertEquals(View.GONE, find(ScrollView.class).getVisibility());
        layout(844, 390);
        assertTrue(find(ScrollView.class).isShown());
        assertFalse(ui.isSheetExpanded());
        layout(390, 844);
        assertEquals(View.GONE, find(ScrollView.class).getVisibility());
        assertTrue(button("Start journey").isShown());
        assertTrue(listener.events.isEmpty());
    }

    @Test public void mapInsetsReserveTheSheetAndOnlyNotifyForChangedBounds() {
        final List<int[]> insets = new ArrayList<>();
        ui.setMapInsetsListener((l, t, r, b) -> insets.add(new int[]{l, t, r, b}));
        ready(); layout(390, 844);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(1, insets.size());
        int[] portrait = insets.get(0);
        assertTrue(portrait[1] >= 56);
        assertTrue(portrait[3] >= 300);
        assertEquals(0, portrait[2]);
        layout(390, 844);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("Unchanged view layout must not reframe the map: " + insets.stream().map(java.util.Arrays::toString).collect(java.util.stream.Collectors.joining(" / ")), 1, insets.size());
        layout(844, 390);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(insets.get(insets.size() - 1)[2] >= 300);
        assertEquals(0, insets.get(insets.size() - 1)[3]);
    }

    @Test public void travelModesUseOneGroupWithExclusiveSelection() {
        ready(); layout(390, 844);
        Button walk = button("Walk"), cycle = button("Cycle"), drive = button("Drive");
        assertEquals(walk.getParent(), cycle.getParent());
        assertEquals(cycle.getParent(), drive.getParent());
        assertTrue(walk.isSelected()); assertFalse(cycle.isSelected()); assertFalse(drive.isSelected());
        cycle.performClick();
        assertEquals("preset:cycle:15.0", listener.events.get(0));
        ui.render("idle", "", "Start", "End", 15, 1600, 384,
                false, false, true, "cycle", "once", "road", false);
        assertFalse(walk.isSelected()); assertTrue(cycle.isSelected()); assertFalse(drive.isSelected());
    }

    @Test public void viaPointsAppearInJourneyOrderAndEditingUsesTheStableIndex() {
        ready();
        ui.setWaypoints(new String[]{"Union Square", "Astor Place"}, true, false);
        layout(390, 844);
        View first = description("Via point 1. Union Square. Edit, move or remove.");
        View second = description("Via point 2. Astor Place. Edit, move or remove.");
        View start = description("Starting point. Madison Square Park. Select a place, coordinates, or a point on the map.");
        View end = description("Destination. Washington Square Park. Select a place, coordinates, or a point on the map.");
        assertTrue(bounds(start).bottom <= bounds(first).top);
        assertTrue(bounds(first).bottom <= bounds(second).top);
        assertTrue(bounds(second).bottom <= bounds(end).top);
        assertTrue(bounds(first).height() >= 48);
        assertTrue(listener.events.isEmpty());
        ui.setWaypoints(new String[]{"Union Square", "Astor Place"}, true, false);
        assertSame("Unchanged labels reuse the native rows", first, description("Via point 1. Union Square. Edit, move or remove."));
        assertTrue(second.performClick());
        assertEquals("via:1", listener.events.get(0));
        button("+ Add via point").performClick();
        assertEquals("add-via", listener.events.get(1));
    }

    @Test public void addingViaPointsRequiresEndpointsAndStopsAtSix() {
        ui.render("select_start", "", "", "", 5, 0, 0, false, false, true, "walk", "once", "road", false);
        ui.setWaypoints(new String[0], true, false);
        assertEquals(View.GONE, button("+ Add via point").getVisibility());
        ready(); ui.setWaypoints(new String[]{"One", "Two", "Three", "Four", "Five", "Six"}, true, false);
        assertEquals(View.GONE, button("+ Add via point").getVisibility());
        ui.setWaypoints(new String[]{"One", "Two", "Three", "Four", "Five"}, true, false);
        assertEquals(View.VISIBLE, button("+ Add via point").getVisibility());
        assertTrue(listener.events.isEmpty());
    }

    @Test public void pendingPlaybackDisablesViaAndRouteChoiceCallbacksAndStaticHidesThem() {
        ready();
        ui.setWaypoints(new String[]{"Union Square"}, true, false);
        ui.setRouteChoices(new String[]{"Balanced", "Shortest"}, new double[]{1600, 1500}, 5, 0, true);
        ui.render("idle", "", "Start", "End", 5, 1600, 1152, false, true, false, "walk", "once", "road", false);
        View via = description("Via point 1. Union Square.");
        assertFalse(via.isEnabled()); via.performClick();
        Button shortest = routeCard("Shortest"); assertFalse(shortest.isEnabled()); shortest.performClick();
        assertEquals(View.GONE, button("+ Add via point").getVisibility());
        assertTrue(listener.events.isEmpty());
        ui.render("idle", "", "Start", "End", 5, 0, 0, false, false, true, "walk", "static", "direct", false);
        ui.setWaypoints(new String[]{"Union Square"}, true, true);
        assertFalse(via.isShown()); assertFalse(shortest.isShown());
        assertTrue(listener.events.isEmpty());
    }

    @Test public void routeCardsUpdateChosenPaceDurationAndSelectionWithoutDispatchingRequests() {
        ready();
        ui.setRouteChoices(new String[]{"Balanced", "Shortest", "Fewer turns"}, new double[]{1600, 1000, 2000}, 5, 0, true);
        layout(390, 844);
        Button balanced = routeCard("Balanced"), shortest = routeCard("Shortest");
        assertTrue(balanced.isSelected()); assertFalse(shortest.isSelected());
        assertEquals("Shortest\n1.0 km · 12 min", shortest.getText().toString());
        assertTrue(shortest.getHeight() >= 48);
        assertTrue(listener.events.isEmpty());
        shortest.performClick(); assertEquals("route:1", listener.events.get(0));
        listener.events.clear();
        ui.setRouteChoices(new String[]{"Balanced", "Shortest", "Fewer turns"}, new double[]{1600, 1000, 2000}, 10, 1, true);
        assertSame(shortest, routeCard("Shortest"));
        assertEquals("Shortest\n1.0 km · 6 min", shortest.getText().toString());
        assertTrue(shortest.isSelected()); assertFalse(balanced.isSelected());
        android.view.accessibility.AccessibilityNodeInfo info = shortest.createAccessibilityNodeInfo();
        assertTrue(info.isCheckable()); assertTrue(info.isChecked()); info.recycle();
        assertTrue(listener.events.isEmpty());
        ui.setRouteChoices(new String[]{"Balanced"}, new double[]{1600}, 5, 0, true);
        assertFalse("A single route needs no alternative selector", routeCard("Balanced").isShown());
    }

    @Test public void routesWithSixViaPointsKeepStartActionsAndMapFitAreaAvailable() {
        ready();
        ui.setWaypoints(new String[]{"One", "Two", "Three", "Four", "Five", "Six"}, true, false);
        ui.setRouteChoices(new String[]{"Balanced", "Shortest"}, new double[]{5600, 5000}, 5, 0, true);
        ui.root().setPadding(8, 24, 8, 28);
        final int[] insets = new int[4];
        ui.setMapInsetsListener((l, t, r, b) -> { insets[0] = l; insets[1] = t; insets[2] = r; insets[3] = b; });
        layout(360, 740);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(bounds(button("Start journey")).bottom <= 712);
        assertTrue(bounds(button("Start journey")).height() >= 48);
        assertTrue(find(ScrollView.class).canScrollVertically(1));
        assertTrue(ui.mapContainer.getHeight() - insets[1] - insets[3] >= 100);
    }

    /** Optional capture of actual native widgets; deliberately contains no fabricated map. */
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeWidgetRenderCanBeCapturedForReview() throws Exception {
        String outputDirectory = System.getenv("JOURNEY_UI_SCREENSHOT_DIR");
        if (outputDirectory == null || outputDirectory.isBlank()) return;
        ready();
        ui.setMapHint("Native UI preview · Map tiles are not loaded");
        File directory = new File(outputDirectory);
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create screenshot directory");
        capture(directory, "native-ui-preview-no-map.png", 390, 844);
        ui.setSheetExpanded(false, false);
        capture(directory, "native-ui-collapsed-no-map.png", 390, 844);
        ui.render("running", "", "Madison Square Park", "Washington Square Park", 5, 1600, 200,
                true, false, false, "walk", "once", "road", false);
        capture(directory, "native-ui-running-collapsed-no-map.png", 390, 844);
        ui.setSheetExpanded(true, false);
        ui.render("select_start", "", "", "", 5, 0, 0,
                false, false, true, "walk", "once", "road", false);
        capture(directory, "native-ui-choose-start-no-map.png", 390, 844);
        ui.render("route_error", "No walking route found. Move a point nearer a road or path and try again.",
                "Start", "Destination", 5, 0, 0, false, false, true, "walk", "once", "road", false);
        capture(directory, "native-ui-retry-route-no-map.png", 390, 844);
        ready();
        capture(directory, "native-ui-landscape-no-map.png", 740, 360);
        ui.render("idle", "", "Madison Square Park", "Washington Square Park", 5, 2400, 1728,
                false, false, true, "walk", "once", "road", false);
        ui.setWaypoints(new String[]{"Union Square", "Astor Place"}, true, false);
        ui.setRouteChoices(new String[]{"Balanced", "Shortest"}, new double[]{2400, 2100}, 5, 0, true);
        capture(directory, "native-ui-vias-route-choices-no-map.png", 390, 844);
    }

    private void capture(File directory, String filename, int width, int height) throws Exception {
        layout(width, height);
        ui.root().getViewTreeObserver().dispatchOnPreDraw();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        ui.root().draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(directory, filename))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }

    private void ready() {
        ui.render("idle", "", "Madison Square Park", "Washington Square Park", 5, 1600, 1152,
                false, false, true, "walk", "once", "road", false);
    }

    private void layout(int width, int height) {
        ui.root().measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        ui.root().layout(0, 0, width, height);
    }

    private Rect bounds(View view) {
        Rect rect = new Rect();
        view.getDrawingRect(rect);
        ((ViewGroup) ui.root()).offsetDescendantRectToMyCoords(view, rect);
        return rect;
    }

    private Button button(String label) {
        for (View view : descendants(ui.root())) {
            if (view instanceof Button && label.contentEquals(((TextView) view).getText())) return (Button) view;
        }
        throw new AssertionError("Missing button: " + label);
    }

    private View description(String value) {
        for (View view : descendants(ui.root())) if (value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        throw new AssertionError("Missing content description: " + value);
    }

    private Button routeCard(String title) {
        for (View view : descendants(ui.root())) if (view instanceof Button && ((Button) view).getText().toString().startsWith(title + "\n")) return (Button) view;
        throw new AssertionError("Missing route card: " + title);
    }

    private boolean hasVisibleText(String text) {
        for (View view : descendants(ui.root())) {
            if (view instanceof TextView && view.isShown() && text.contentEquals(((TextView) view).getText())) return true;
        }
        return false;
    }

    private <T extends View> T find(Class<T> type) {
        for (View view : descendants(ui.root())) if (type.isInstance(view)) return type.cast(view);
        throw new AssertionError("Missing widget: " + type.getSimpleName());
    }

    private static List<View> descendants(View root) {
        List<View> result = new ArrayList<>();
        result.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(descendants(group.getChildAt(i)));
        }
        return result;
    }

    private static void dispatch(SeekBar slider, long when, int action, float x) {
        MotionEvent event = MotionEvent.obtain(when, when, action, x, slider.getHeight() / 2f, 0);
        try { slider.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    private static final class RecordingListener implements JourneyUi.Listener {
        final List<String> events = new ArrayList<>();
        @Override public void onPoint(boolean destination) { events.add("point:" + destination); }
        @Override public void onPrimary() { events.add("primary"); }
        @Override public void onStopPlayback() { events.add("stop"); }
        @Override public void onSettings() { events.add("settings"); }
        @Override public void onOptions() { events.add("options"); }
        @Override public void onSpeed(double speedKmh) { events.add("speed:" + speedKmh); }
        @Override public void onPreset(String mode, double speedKmh) { events.add("preset:" + mode + ":" + speedKmh); }
        @Override public void onAddVia() { events.add("add-via"); }
        @Override public void onViaPoint(int index) { events.add("via:" + index); }
        @Override public void onRouteChoice(int index) { events.add("route:" + index); }
    }
}
