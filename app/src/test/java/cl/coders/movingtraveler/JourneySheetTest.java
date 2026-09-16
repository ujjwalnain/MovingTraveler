// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Drawer gestures must not interfere with the form's independent scrolling surface. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class JourneySheetTest {
    private ActivityController<Activity> activity;
    private View handle;
    private JourneySheet sheet;
    private final List<Float> fractions = new ArrayList<>();
    private final List<Boolean> settled = new ArrayList<>();

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup();
        FrameLayout frame = new FrameLayout(activity.get());
        handle = new View(activity.get());
        frame.addView(handle, new FrameLayout.LayoutParams(320, 72));
        activity.get().setContentView(frame);
        sheet = new JourneySheet(activity.get(), handle, new JourneySheet.Listener() {
            @Override public void onFractionChanged(float fraction) { fractions.add(fraction); }
            @Override public void onSettled(boolean expanded) { settled.add(expanded); }
        });
        sheet.setTravelDistance(200);
    }

    @After public void cleanup() {
        sheet.cancel();
        activity.pause().stop().destroy();
    }

    @Test public void restoreAndLayoutCannotTriggerActionsOrReenterLayout() {
        sheet.restore(false);
        sheet.setTravelDistance(420);
        handle.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(72, View.MeasureSpec.EXACTLY));
        handle.layout(0, 0, 320, 72);
        assertEquals(0, sheet.getFraction(), 0);
        assertFalse(sheet.isExpanded());
        assertFalse(sheet.isInteracting());
        assertTrue(fractions.isEmpty());
        assertTrue(settled.isEmpty());
    }

    @Test public void downwardDragPastHalfCollapsesAndClampsToItsTwoPositions() {
        touch(MotionEvent.ACTION_DOWN, 100, 100, 0);
        touch(MotionEvent.ACTION_MOVE, 100, 250, 600);
        assertEquals(.25f, sheet.getFraction(), .001f);
        assertTrue(sheet.isInteracting());
        touch(MotionEvent.ACTION_UP, 100, 250, 1200);
        finishAnimation();
        assertFalse(sheet.isExpanded());
        assertFalse(sheet.isInteracting());
        assertEquals(0, sheet.getFraction(), 0);
        assertEquals(Boolean.FALSE, settled.get(settled.size() - 1));
        for (float value : fractions) assertTrue(value >= 0 && value <= 1);
    }

    @Test public void shortFastUpwardFlingExpandsDespiteNotCrossingHalf() {
        sheet.restore(false);
        touch(MotionEvent.ACTION_DOWN, 100, 400, 0);
        touch(MotionEvent.ACTION_MOVE, 100, 370, 15);
        assertEquals(.15f, sheet.getFraction(), .001f);
        touch(MotionEvent.ACTION_UP, 100, 350, 25);
        finishAnimation();
        assertTrue(sheet.isExpanded());
        assertEquals(1, sheet.getFraction(), 0);
    }

    @Test public void cancelledDragRestoresThePositionAtGestureStart() {
        touch(MotionEvent.ACTION_DOWN, 100, 100, 0);
        touch(MotionEvent.ACTION_MOVE, 100, 280, 400);
        assertEquals(.1f, sheet.getFraction(), .001f);
        touch(MotionEvent.ACTION_CANCEL, 100, 280, 450);
        finishAnimation();
        assertTrue(sheet.isExpanded());
        assertFalse(sheet.isInteracting());
        assertEquals(1, sheet.getFraction(), 0);
    }

    @Test public void horizontalGestureDoesNotBecomeATapOrDrag() {
        touch(MotionEvent.ACTION_DOWN, 100, 100, 0);
        touch(MotionEvent.ACTION_MOVE, 240, 110, 400);
        touch(MotionEvent.ACTION_UP, 240, 110, 600);
        finishAnimation();
        assertTrue(sheet.isExpanded());
        assertEquals(1, sheet.getFraction(), 0);
        assertFalse(sheet.isInteracting());
        assertTrue(fractions.isEmpty());
        assertTrue(settled.isEmpty());
    }

    @Test public void tappingHandleTogglesWithoutAHiddenSheetState() {
        touch(MotionEvent.ACTION_DOWN, 100, 100, 0);
        touch(MotionEvent.ACTION_UP, 101, 101, 100);
        finishAnimation();
        assertFalse(sheet.isExpanded());
        assertEquals(0, sheet.getFraction(), 0);
        handle.performClick();
        finishAnimation();
        assertTrue(sheet.isExpanded());
        assertEquals(1, sheet.getFraction(), 0);
    }

    @Test public void diagonalSwipeCannotAccidentallyToggleTheSheet() {
        touch(MotionEvent.ACTION_DOWN, 100, 100, 0);
        touch(MotionEvent.ACTION_MOVE, 230, 230, 400);
        touch(MotionEvent.ACTION_UP, 230, 230, 600);
        finishAnimation();
        assertTrue(sheet.isExpanded());
        assertTrue(fractions.isEmpty());
        assertTrue(settled.isEmpty());
    }

    @Test public void landscapeSuspendsActionsWithoutLosingThePortraitPosition() {
        sheet.restore(false);
        sheet.setEnabled(false);
        assertEquals(0, sheet.getFraction(), 0);
        assertFalse(sheet.isExpanded());
        assertFalse(handle.isClickable());
        assertFalse(handle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null));
        assertFalse(handle.createAccessibilityNodeInfo().getActionList()
                .contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND));
        assertTrue(fractions.isEmpty());
        assertTrue(settled.isEmpty());
        sheet.setEnabled(true);
        handle.performClick();
        finishAnimation();
        assertTrue(sheet.isExpanded());
    }

    @Test public void explicitAccessibilityActionsControlTheSamePosition() {
        AccessibilityNodeInfo before = handle.createAccessibilityNodeInfo();
        assertTrue(before.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE));
        assertTrue(handle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null));
        finishAnimation();
        assertFalse(sheet.isExpanded());
        assertEquals("Expand journey controls", handle.getContentDescription().toString());
        assertTrue(handle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null));
        finishAnimation();
        assertTrue(sheet.isExpanded());
    }

    @Test public void lifecycleCancellationLeavesNoAnimationCallbacks() {
        sheet.setExpanded(false, true);
        sheet.cancel();
        int previousFractions = fractions.size();
        int previousSettled = settled.size();
        finishAnimation();
        assertFalse(sheet.isInteracting());
        assertEquals(previousFractions, fractions.size());
        assertEquals(previousSettled, settled.size());
    }

    @Test public void zeroDistanceAndOverscrollStayFiniteAndBounded() {
        sheet.setTravelDistance(0);
        touch(MotionEvent.ACTION_DOWN, 100, 100, 0);
        touch(MotionEvent.ACTION_MOVE, 100, 2000, 500);
        assertEquals(0, sheet.getFraction(), 0);
        touch(MotionEvent.ACTION_CANCEL, 100, 2000, 600);
        finishAnimation();
        assertEquals(1, sheet.getFraction(), 0);
    }

    private void touch(int action, float x, float y, long elapsedMillis) {
        MotionEvent event = MotionEvent.obtain(1000, 1000 + elapsedMillis, action, x, y, 0);
        try { assertTrue(handle.dispatchTouchEvent(event)); }
        finally { event.recycle(); }
    }

    private static void finishAnimation() {
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
    }
}
