// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;

/**
 * Two-position drawer interaction, deliberately attached only to the sheet handle.
 * The owner lays out its panel from {@link #getFraction()} and keeps its action bar visible.
 * Measuring, changing the travel distance, and restoring state never call the listener.
 */
final class JourneySheet implements View.OnTouchListener {
    interface Listener {
        /** Zero means collapsed and one means expanded. */
        void onFractionChanged(float fraction);
        void onSettled(boolean expanded);
    }

    private final View handle;
    private final Listener listener;
    private final int touchSlop;
    private final float flingThreshold;
    private ValueAnimator animator;
    private VelocityTracker velocity;
    private float travelDistance = 1;
    private float fraction = 1;
    private boolean expanded = true;
    private boolean downExpanded;
    private boolean dragging;
    private boolean rejected;
    private boolean tapEligible;
    private boolean tracking;
    private float downX;
    private float downY;
    private float downFraction;
    private int pointerId = -1;

    JourneySheet(Context context, View handle, Listener listener) {
        if (context == null || handle == null || listener == null) {
            throw new IllegalArgumentException("Context, handle and listener are required");
        }
        this.handle = handle;
        this.listener = listener;
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        flingThreshold = Math.max(configuration.getScaledMinimumFlingVelocity(),
                600 * context.getResources().getDisplayMetrics().density);
        handle.setClickable(true);
        handle.setFocusable(true);
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        handle.setOnTouchListener(this);
        handle.setOnClickListener(v -> {
            if (!v.isEnabled()) return;
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            setExpanded(!expanded, true);
        });
        handle.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(host.isEnabled() ? "android.widget.Button" : "android.view.View");
                if (host.isEnabled()) {
                    info.addAction(expanded ? AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                            : AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
                }
                info.setContentDescription(host.getContentDescription());
            }

            @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                if (!host.isEnabled()) return false;
                if (action == AccessibilityNodeInfo.ACTION_EXPAND) {
                    setExpanded(true, true);
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
                    setExpanded(false, true);
                    return true;
                }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
        updateAccessibility();
    }

    float getFraction() { return fraction; }
    boolean isExpanded() { return expanded; }
    boolean isInteracting() { return tracking || animator != null; }

    /** Landscape side cards can suspend gestures while preserving the portrait position. */
    void setEnabled(boolean enabled) {
        if (handle.isEnabled() == enabled) return;
        cancel();
        handle.setEnabled(enabled);
        handle.setClickable(enabled);
        handle.setFocusable(enabled);
        updateAccessibility();
    }

    /** Layout input; not a user action and never a source of callbacks. */
    void setTravelDistance(float pixels) {
        travelDistance = Float.isFinite(pixels) ? Math.max(1, pixels) : 1;
    }

    /** Restores the logical position before the owner performs its first layout. */
    void restore(boolean expanded) {
        cancelAnimation();
        releaseGesture();
        this.expanded = expanded;
        fraction = expanded ? 1 : 0;
        updateAccessibility();
    }

    void setExpanded(boolean expanded, boolean animate) {
        cancelAnimation();
        releaseGesture();
        this.expanded = expanded;
        updateAccessibility();
        float target = expanded ? 1 : 0;
        if (Math.abs(fraction - target) < .0001f || !animate || !animationsEnabled()) {
            updateFraction(target);
            listener.onSettled(expanded);
            return;
        }
        ValueAnimator transition = ValueAnimator.ofFloat(fraction, target);
        animator = transition;
        transition.setDuration(Math.max(100, Math.round(220 * Math.abs(target - fraction))));
        transition.setInterpolator(new DecelerateInterpolator());
        transition.addUpdateListener(value -> updateFraction((float) value.getAnimatedValue()));
        transition.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animator != transition) return;
                animator = null;
                updateFraction(target);
                listener.onSettled(JourneySheet.this.expanded);
            }
        });
        transition.start();
    }

    /** Stops work when the containing screen is detached; no delayed callbacks survive. */
    void cancel() {
        cancelAnimation();
        releaseGesture();
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        if (!view.isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelAnimation();
                releaseGesture();
                tracking = true;
                tapEligible = true;
                downExpanded = expanded;
                downFraction = fraction;
                downX = event.getRawX();
                downY = event.getRawY();
                pointerId = event.getPointerId(0);
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                disallowParent(true);
                handle.setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                velocity.addMovement(event);
                if (event.getPointerCount() != 1 || event.getPointerId(0) != pointerId) {
                    cancelGesture();
                    return true;
                }
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) tapEligible = false;
                if (!dragging && !rejected) {
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                        rejected = true;
                        handle.setPressed(false);
                    } else if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) * 1.15f) {
                        dragging = true;
                        handle.setPressed(false);
                    }
                }
                if (dragging) updateFraction(downFraction - dy / travelDistance);
                return true;
            case MotionEvent.ACTION_UP:
                if (!tracking) return false;
                velocity.addMovement(event);
                boolean wasDragging = dragging;
                boolean wasTap = tapEligible && !rejected && Math.abs(event.getRawX() - downX) <= touchSlop
                        && Math.abs(event.getRawY() - downY) <= touchSlop;
                if (wasDragging) updateFraction(downFraction - (event.getRawY() - downY) / travelDistance);
                velocity.computeCurrentVelocity(1000);
                float yVelocity = velocity.getYVelocity(pointerId);
                float remaining = fraction;
                releaseGesture();
                if (wasDragging) {
                    boolean target = Math.abs(yVelocity) >= flingThreshold ? yVelocity < 0 : remaining >= .5f;
                    setExpanded(target, true);
                } else if (wasTap) {
                    view.performClick();
                } else if (Math.abs(fraction - (downExpanded ? 1 : 0)) > .0001f) {
                    // A rejected gesture must not leave an interrupted animation halfway open.
                    setExpanded(downExpanded, true);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!tracking) return false;
                cancelGesture();
                return true;
            default:
                return tracking;
        }
    }

    private void cancelGesture() {
        boolean returnToExpanded = downExpanded;
        releaseGesture();
        setExpanded(returnToExpanded, true);
    }

    private void updateFraction(float next) {
        next = Math.max(0, Math.min(1, next));
        if (Math.abs(fraction - next) < .0001f) return;
        fraction = next;
        listener.onFractionChanged(fraction);
    }

    private void updateAccessibility() {
        handle.setContentDescription(!handle.isEnabled() ? "Journey controls"
                : expanded ? "Collapse journey controls" : "Expand journey controls");
        if (Build.VERSION.SDK_INT >= 30) handle.setStateDescription(!handle.isEnabled() ? null
                : expanded ? "Expanded" : "Collapsed");
    }

    private boolean animationsEnabled() {
        if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled();
        try {
            return Settings.Global.getFloat(handle.getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1) != 0;
        } catch (SecurityException ignored) {
            return true;
        }
    }

    private void cancelAnimation() {
        ValueAnimator previous = animator;
        animator = null;
        if (previous != null) {
            previous.removeAllUpdateListeners();
            previous.removeAllListeners();
            previous.cancel();
        }
    }

    private void releaseGesture() {
        if (velocity != null) velocity.recycle();
        velocity = null;
        tracking = false;
        dragging = false;
        rejected = false;
        tapEligible = false;
        pointerId = -1;
        handle.setPressed(false);
        disallowParent(false);
    }

    private void disallowParent(boolean disallow) {
        ViewParent parent = handle.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }
}
