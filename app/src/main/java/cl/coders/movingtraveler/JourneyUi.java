// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.ForegroundColorSpan;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.Arrays;

/**
 * Native presentation layer. The activity owns the map, permission flows, route planning,
 * haptics, and service commands; rendering this view never invokes a Listener method.
 */
public final class JourneyUi {
    public interface Listener {
        void onPoint(boolean destination);
        void onPrimary();
        void onStopPlayback();
        void onSettings();
        void onOptions();
        void onSpeed(double speedKmh);
        /** Mode is "walk", "cycle", or "drive". */
        void onPreset(String mode, double speedKmh);
        default void onAddVia() { }
        default void onViaPoint(int index) { }
        default void onRouteChoice(int index) { }
    }

    private static final int PAPER = Color.WHITE;
    private static final int WHITE = Color.WHITE;
    private static final int INK = Color.rgb(24, 39, 36);
    private static final int MUTED = Color.rgb(104, 116, 111);
    private static final int TEAL = Color.rgb(24, 101, 84);
    private static final int MINT = Color.rgb(232, 243, 239);
    private static final int LINE = Color.rgb(226, 232, 228);
    private static final int PALE = Color.rgb(245, 247, 246);
    private static final int WARNING = Color.rgb(121, 88, 36);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Activity activity;
    private final Listener listener;
    private final AdaptiveRoot rootView;
    /** Add the activity-owned MapView here; hint overlays live outside this container. */
    public final FrameLayout mapContainer;
    private final TextView mapHint;
    private final LinearLayout header;
    private final FrameLayout mapRegion;
    private final LinearLayout panel;
    private final LinearLayout sheetHandle;
    private final JourneySheet sheet;
    private final LinearLayout form;
    private final LinearLayout actions;
    private final LinearLayout primaryRow;
    private final ScrollView formScroll;
    private final TextView panelTitle;
    private final TextView statusPill;
    private final PointRow fromRow;
    private final PointRow toRow;
    private final View pointDivider;
    private final LinearLayout viaRows;
    private final Button addVia;
    private final HorizontalScrollView routeChoicesScroll;
    private final LinearLayout routeChoiceCards;
    private String[] waypointLabels = new String[0], choiceLabels = new String[0];
    private double[] choiceDistances = new double[0];
    private int[] choiceIndices = new int[0];
    private boolean waypointConfigurationReceived, waypointEditable, waypointStationary;
    private boolean planningEditable = true, renderedStationary, endpointsChosen, choicesRequested;
    private int selectedChoice = -1;
    private double choiceSpeed = 5;
    private final LinearLayout routeDetails;
    private final Button routeOptions;
    private final TextView routeSummary;
    private final LinearLayout speedControls;
    private final LinearLayout presets;
    private final Button walk;
    private final Button cycle;
    private final Button drive;
    private final EditText speedEditor;
    private final SeekBar speedSlider;
    private final TextView messageView;
    private final TextView attributionsView;
    private final Button primary;
    private final Button stop;
    private boolean rendering;
    private boolean playbackActive;
    private boolean planningPrimaryAction;
    private boolean sliderDragging;
    private boolean editorDirty;
    private double renderedSpeed = 5;
    private double visibleSpeed = 5;
    private String lastMessage = "";
    private MapInsetsListener mapInsetsListener;
    private final int[] deliveredInsets = {-1, -1, -1, -1};

    public interface MapInsetsListener { void onMapInsets(int left, int top, int right, int bottom); }

    public JourneyUi(Activity activity, Listener listener) {
        if (activity == null || listener == null) throw new IllegalArgumentException("Activity and Listener are required");
        this.activity = activity;
        this.listener = listener;

        header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(4), dp(4), dp(4));
        header.setMinimumHeight(dp(56));
        header.setBackground(shape(WHITE, 19, LINE));
        header.setElevation(dp(3));
        header.setClickable(true);
        header.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        TextView brand = text("", 18, INK, true);
        brand.setBackground(shape(TEAL, 12, 0));
        brand.setCompoundDrawablesWithIntrinsicBounds(new MarkDrawable(MarkDrawable.NAVIGATION, WHITE, dp(24)), null, null, null);
        brand.setPadding(dp(7), dp(7), dp(7), dp(7));
        brand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(brand, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView appTitle = text(activity.getString(R.string.app_name), 16, INK, true);
        appTitle.setMaxLines(2);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = dp(11);
        titleParams.rightMargin = dp(8);
        header.addView(appTitle, titleParams);
        Button settings = button("", PALE, INK, false);
        settings.setPadding(dp(12), dp(12), dp(12), dp(12));
        settings.setCompoundDrawablesWithIntrinsicBounds(new MarkDrawable(MarkDrawable.SETTINGS, INK, dp(24)), null, null, null);
        settings.setContentDescription("Settings, setup, and privacy");
        settings.setOnClickListener(v -> listener.onSettings());
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));

        mapRegion = new FrameLayout(activity);
        mapRegion.setBackgroundColor(Color.rgb(235, 242, 238));
        mapContainer = new FrameLayout(activity);
        mapContainer.setId(View.generateViewId());
        mapRegion.addView(mapContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mapHint = text("Tap the map to choose a starting point", 12, TEAL, true);
        mapHint.setPadding(dp(13), dp(11), dp(13), dp(11));
        mapHint.setMaxLines(2);
        mapHint.setGravity(Gravity.CENTER_VERTICAL);
        mapHint.setBackground(shape(Color.argb(248, 255, 255, 255), 16, LINE));
        mapHint.setElevation(dp(2));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        hintParams.setMargins(dp(16), dp(14), dp(16), 0);
        mapRegion.addView(mapHint, hintParams);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBackground = shape(PAPER, 0, 0);
        panelBackground.setCornerRadii(new float[]{dp(25), dp(25), dp(25), dp(25), 0, 0, 0, 0});
        panel.setBackground(panelBackground);
        panel.setElevation(dp(7));
        panel.setClipToOutline(true);
        panel.setClickable(true);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        sheetHandle = new LinearLayout(activity);
        sheetHandle.setOrientation(LinearLayout.VERTICAL);
        sheetHandle.setPadding(dp(20), dp(10), dp(20), dp(12));
        sheetHandle.setMinimumHeight(dp(72));
        View grabber = new View(activity);
        grabber.setBackground(shape(Color.rgb(207, 216, 211), 2, 0));
        grabber.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams grabberParams = new LinearLayout.LayoutParams(dp(34), dp(4));
        grabberParams.gravity = Gravity.CENTER_HORIZONTAL; grabberParams.bottomMargin = dp(14);
        sheetHandle.addView(grabber, grabberParams);
        panel.addView(sheetHandle, matchWrap());

        form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        formScroll = new ScrollView(activity);
        formScroll.setFillViewport(false);
        formScroll.setVerticalScrollBarEnabled(false);
        formScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        formScroll.setClipToPadding(false);
        formScroll.addView(form, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(formScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        panelTitle = text("Where to?", 22, INK, true);
        panelTitle.setMaxLines(2);
        heading.addView(panelTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        statusPill = text("Planning", 11, MUTED, true);
        statusPill.setPadding(dp(10), dp(7), dp(10), dp(7));
        statusPill.setBackground(shape(PALE, 18, 0));
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.leftMargin = dp(10);
        heading.addView(statusPill, pillParams);
        heading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        sheetHandle.addView(heading, matchWrap());

        LinearLayout points = new LinearLayout(activity);
        points.setOrientation(LinearLayout.VERTICAL);
        points.setBackground(shape(PALE, 16, LINE));
        points.setClipToOutline(true);
        fromRow = new PointRow(false);
        toRow = new PointRow(true);
        points.addView(fromRow, matchWrap());
        pointDivider = new View(activity);
        pointDivider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.leftMargin = dp(43);
        dividerParams.rightMargin = dp(14);
        points.addView(pointDivider, dividerParams);
        viaRows = new LinearLayout(activity); viaRows.setOrientation(LinearLayout.VERTICAL);
        viaRows.setVisibility(View.GONE); points.addView(viaRows, matchWrap());
        points.addView(toRow, matchWrap());
        form.addView(points, matchWrap());
        addVia = button("+ Add via point", Color.TRANSPARENT, TEAL, true);
        addVia.setTextSize(13); addVia.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        addVia.setPadding(dp(12), dp(6), dp(12), dp(6)); addVia.setVisibility(View.GONE);
        addVia.setOnClickListener(v -> { if (addVia.isEnabled() && addVia.isShown()) listener.onAddVia(); });
        form.addView(addVia, matchWrap());
        routeChoicesScroll = new HorizontalScrollView(activity);
        routeChoicesScroll.setHorizontalScrollBarEnabled(false);
        routeChoicesScroll.setClipToPadding(false); routeChoicesScroll.setFillViewport(false);
        routeChoicesScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        routeChoiceCards = horizontal();
        routeChoicesScroll.addView(routeChoiceCards, new HorizontalScrollView.LayoutParams(-2, -2));
        routeChoicesScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams choicesParams = matchWrap(); choicesParams.topMargin = dp(8);
        form.addView(routeChoicesScroll, choicesParams);

        routeDetails = horizontal();
        routeDetails.setGravity(Gravity.CENTER_VERTICAL);
        routeOptions = button("Road · One way", Color.TRANSPARENT, MUTED, false);
        routeOptions.setTextSize(12);
        routeOptions.setPadding(0, dp(4), dp(8), dp(4));
        routeOptions.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        routeOptions.setCompoundDrawablePadding(dp(5));
        routeOptions.setCompoundDrawablesWithIntrinsicBounds(null, null, new MarkDrawable(MarkDrawable.DOWN, MUTED, dp(16)), null);
        routeOptions.setOnClickListener(v -> listener.onOptions());
        routeDetails.addView(routeOptions, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        routeSummary = text("Choose two points", 12, MUTED, false);
        routeSummary.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        routeSummary.setMaxLines(2);
        routeSummary.setPadding(dp(10), dp(8), 0, dp(8));
        routeSummary.setMinimumHeight(dp(48));
        routeDetails.addView(routeSummary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        speedControls = new LinearLayout(activity);
        speedControls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout paceHeading = horizontal();
        paceHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView pace = text("Travel pace", 13, INK, true);
        paceHeading.addView(pace, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        speedEditor = new EditText(activity);
        speedEditor.setId(View.generateViewId());
        speedEditor.setTextSize(20);
        speedEditor.setTypeface(MEDIUM);
        speedEditor.setTextColor(TEAL);
        speedEditor.setSingleLine(true);
        speedEditor.setSelectAllOnFocus(true);
        speedEditor.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        speedEditor.setHorizontallyScrolling(false);
        speedEditor.setImeOptions(EditorInfo.IME_ACTION_DONE);
        speedEditor.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        speedEditor.setPadding(dp(4), 0, dp(4), 0);
        speedEditor.setMinimumHeight(dp(48));
        speedEditor.setMinHeight(dp(48));
        speedEditor.setBackground(ripple(PALE, 10, LINE));
        speedEditor.setContentDescription("Speed in kilometers per hour, from 0.5 to 200");
        if (Build.VERSION.SDK_INT >= 26) speedEditor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        int editorWidth = dp(72 * Math.max(1, activity.getResources().getConfiguration().fontScale * .8f));
        paceHeading.addView(speedEditor, new LinearLayout.LayoutParams(editorWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView speedUnit = text("km/h", 11, MUTED, false);
        speedUnit.setPadding(dp(5), 0, 0, 0);
        paceHeading.addView(speedUnit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        speedControls.addView(paceHeading, matchWrap());

        presets = horizontal();
        presets.setBackground(shape(PALE, 14, LINE));
        presets.setPadding(dp(4), dp(4), dp(4), dp(4));
        presets.setClipToOutline(true);
        walk = preset("Walk", "walk", 5);
        cycle = preset("Cycle", "cycle", 15);
        drive = preset("Drive", "drive", 50);
        presets.addView(walk, weighted(0));
        presets.addView(cycle, weighted(0));
        presets.addView(drive, weighted(0));

        speedSlider = new SeekBar(activity);
        speedSlider.setId(View.generateViewId());
        speedSlider.setMax(399);
        speedSlider.setProgressTintList(ColorStateList.valueOf(TEAL));
        speedSlider.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        speedSlider.setThumbTintList(ColorStateList.valueOf(TEAL));
        speedSlider.setSplitTrack(false);
        speedSlider.setPadding(dp(8), 0, dp(8), 0);
        speedSlider.setMinimumHeight(dp(48));
        speedSlider.setContentDescription("Adjust travel speed, 0.5 to 200 kilometers per hour");
        speedControls.addView(speedSlider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout.LayoutParams presetsParams = matchWrap();
        presetsParams.topMargin = dp(14); presetsParams.bottomMargin = dp(10);
        form.addView(presets, presetsParams);
        form.addView(speedControls, matchWrap());
        form.addView(routeDetails, matchWrap());

        messageView = text("", 12, WARNING, false);
        messageView.setPadding(dp(12), dp(10), dp(12), dp(10));
        messageView.setBackground(shape(Color.rgb(251, 245, 232), 12, Color.rgb(234, 222, 194)));
        messageView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        messageView.setVisibility(View.GONE);
        LinearLayout.LayoutParams messageParams = matchWrap();
        messageParams.topMargin = dp(8);
        form.addView(messageView, messageParams);
        attributionsView = text("", 12, MUTED, false);
        attributionsView.setPadding(dp(2), dp(8), dp(2), dp(6));
        attributionsView.setLinkTextColor(TEAL);
        attributionsView.setMovementMethod(LinkMovementMethod.getInstance());
        attributionsView.setLinksClickable(true);
        attributionsView.setVisibility(View.GONE);
        form.addView(attributionsView, matchWrap());

        actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(20), dp(12), dp(20), dp(14));
        actions.setBackgroundColor(PAPER);
        primaryRow = horizontal();
        primaryRow.setGravity(Gravity.CENTER_VERTICAL);
        primary = button("Start journey", TEAL, WHITE, true);
        primary.setMinimumHeight(dp(56));
        primary.setMinHeight(dp(56));
        primary.setOnClickListener(v -> {
            if (!commitEditor()) {
                if (!playbackActive && !planningPrimaryAction) return;
                // Setup, point selection, route retry, Pause and Resume are not speed edits.
                editorDirty = false;
                setSpeedVisual(renderedSpeed);
                speedEditor.setError(null);
            }
            clearSpeedFocus();
            listener.onPrimary();
        });
        primaryRow.addView(primary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        stop = button("Stop", MINT, TEAL, true);
        stop.setMinimumHeight(dp(54));
        stop.setVisibility(View.GONE);
        stop.setOnClickListener(v -> {
            editorDirty = false;
            speedEditor.setError(null);
            clearSpeedFocus();
            listener.onStopPlayback();
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(dp(94), ViewGroup.LayoutParams.WRAP_CONTENT);
        stopParams.leftMargin = dp(9);
        primaryRow.addView(stop, stopParams);
        actions.addView(primaryRow, matchWrap());
        panel.addView(actions, matchWrap());

        rootView = new AdaptiveRoot(activity);
        rootView.setBackgroundColor(PAPER);
        rootView.setFocusableInTouchMode(true);
        rootView.addView(mapRegion);
        rootView.addView(header);
        rootView.addView(panel);
        sheet = new JourneySheet(activity, sheetHandle, new JourneySheet.Listener() {
            @Override public void onFractionChanged(float fraction) {
                formScroll.setVisibility(fraction <= 0 ? View.GONE : View.VISIBLE);
                rootView.requestLayout();
            }
            @Override public void onSettled(boolean expanded) {
                if (!expanded) clearSpeedFocus();
                rootView.requestLayout();
            }
        });
        installSpeedListeners();
        render("idle", "", "", "", 5, 0, 0, false, false, false, "walk", "once", "road", false);
        rootView.requestFocus();
    }

    public View root() { return rootView; }
    public boolean isSheetExpanded() { return sheet.isExpanded(); }
    public void restoreSheet(boolean expanded) { sheet.restore(expanded); formScroll.setVisibility(expanded ? View.VISIBLE : View.GONE); rootView.requestLayout(); }
    public void setSheetExpanded(boolean expanded, boolean animate) { sheet.setExpanded(expanded, animate); }
    public void cancelSheetGesture() { restoreSheet(sheet.isExpanded()); }
    public void setMapInsetsListener(MapInsetsListener listener) {
        mapInsetsListener = listener;
        java.util.Arrays.fill(deliveredInsets, -1); rootView.requestLayout();
    }

    /** Via points retain their journey order; changing labels never dispatches editing actions. */
    public void setWaypoints(String[] labels, boolean editable, boolean stationary) {
        int count = Math.min(6, labels == null ? 0 : labels.length);
        String[] next = new String[count];
        for (int i = 0; i < count; i++) next[i] = safe(labels[i]);
        waypointConfigurationReceived = true; waypointEditable = editable; waypointStationary = stationary;
        if (!Arrays.equals(next, waypointLabels)) {
            waypointLabels = next; viaRows.removeAllViews();
            for (int i = 0; i < next.length; i++) {
                ViaRow row = new ViaRow(i, next[i]); viaRows.addView(row, matchWrap());
                View divider = new View(activity); divider.setBackgroundColor(LINE);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
                params.leftMargin = dp(43); params.rightMargin = dp(14); viaRows.addView(divider, params);
            }
        }
        updatePlanningExtras();
    }

    /** Alternatives are already fetched. Selecting a card only changes the chosen route. */
    public void setRouteChoices(String[] labels, double[] distances, double speedKmh, int selected, boolean visible) {
        int available = Math.min(3, Math.min(labels == null ? 0 : labels.length, distances == null ? 0 : distances.length));
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        java.util.ArrayList<Double> meters = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> indices = new java.util.ArrayList<>();
        for (int i = 0; i < available; i++) {
            if (safe(labels[i]).isEmpty() || !Double.isFinite(distances[i]) || distances[i] <= 0) continue;
            names.add(safe(labels[i])); meters.add(distances[i]); indices.add(i);
        }
        String[] nextLabels = names.toArray(new String[0]);
        double[] nextDistances = new double[meters.size()]; int[] nextIndices = new int[indices.size()];
        for (int i = 0; i < meters.size(); i++) { nextDistances[i] = meters.get(i); nextIndices[i] = indices.get(i); }
        boolean changed = !Arrays.equals(choiceLabels, nextLabels) || !Arrays.equals(choiceIndices, nextIndices);
        choiceLabels = nextLabels; choiceDistances = nextDistances; choiceIndices = nextIndices;
        selectedChoice = selected; choiceSpeed = normalizeSpeed(speedKmh); choicesRequested = visible;
        if (changed) {
            routeChoiceCards.removeAllViews();
            for (int i = 0; i < choiceLabels.length; i++) {
                final int index = choiceIndices[i];
                Button card = button("", PALE, INK, true);
                card.setTextSize(14); card.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                card.setMinHeight(dp(72)); card.setMinimumHeight(dp(72));
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                card.setMaxLines(3); card.setEllipsize(TextUtils.TruncateAt.END);
                card.setOnClickListener(v -> { if (card.isEnabled() && card.isShown()) listener.onRouteChoice(index); });
                card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setCheckable(true); info.setChecked(host.isSelected());
                    }
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(150 * Math.max(1, activity.getResources().getConfiguration().fontScale * .8f)), -2);
                if (i > 0) params.leftMargin = dp(8); routeChoiceCards.addView(card, params);
            }
        }
        updateRouteChoiceCards(); updatePlanningExtras();
    }

    private void updateRouteChoiceCards() {
        for (int i = 0; i < routeChoiceCards.getChildCount(); i++) {
            Button card = (Button) routeChoiceCards.getChildAt(i);
            boolean selected = choiceIndices[i] == selectedChoice;
            String detail = formatDistance(choiceDistances[i]) + " · " + formatDuration(choiceDistances[i] / (choiceSpeed / 3.6));
            String label = choiceLabels[i] + "\n" + detail;
            boolean stateChanged = card.isSelected() != selected;
            if (!TextUtils.equals(card.getText(), label) || stateChanged) {
                SpannableString copy = new SpannableString(label);
                copy.setSpan(new RelativeSizeSpan(.82f), choiceLabels[i].length() + 1, copy.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                copy.setSpan(new StyleSpan(Typeface.NORMAL), choiceLabels[i].length() + 1, copy.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                copy.setSpan(new ForegroundColorSpan(selected ? TEAL : MUTED), choiceLabels[i].length() + 1, copy.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                card.setText(copy);
            }
            if (card.getTag() == null || stateChanged) {
                card.setTag(Boolean.TRUE); card.setSelected(selected);
                card.setTextColor(selected ? TEAL : INK); card.setBackground(ripple(selected ? MINT : WHITE, 13, selected ? TEAL : LINE));
            }
            card.setContentDescription(choiceLabels[i] + " route. " + detail + ". At " + formatSpeed(choiceSpeed)
                    + " kilometers per hour." + (selected ? " Selected." : " Select route."));
        }
    }

    private void updatePlanningExtras() {
        boolean stationary = waypointStationary || renderedStationary;
        viaRows.setVisibility(!stationary && waypointLabels.length > 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < viaRows.getChildCount(); i++) {
            View row = viaRows.getChildAt(i);
            if (row instanceof ViaRow) ((ViaRow) row).setEditable(waypointEditable && planningEditable && !stationary);
        }
        boolean canAdd = waypointConfigurationReceived && !stationary && waypointEditable && planningEditable
                && endpointsChosen && waypointLabels.length < 6;
        addVia.setVisibility(canAdd ? View.VISIBLE : View.GONE); addVia.setEnabled(canAdd);
        boolean showChoices = choicesRequested && choiceLabels.length > 1 && !renderedStationary && !playbackActive;
        routeChoicesScroll.setVisibility(showChoices ? View.VISIBLE : View.GONE);
        for (int i = 0; i < routeChoiceCards.getChildCount(); i++) {
            View card = routeChoiceCards.getChildAt(i); card.setEnabled(planningEditable && showChoices);
            card.setAlpha(planningEditable ? 1 : .55f);
        }
    }

    private final class ViaRow extends LinearLayout {
        final int index;
        final String label;
        final TextView chevron;
        ViaRow(int index, String value) {
            super(activity); this.index = index; this.label = value.isEmpty() ? "Choose via point" : value;
            setGravity(Gravity.CENTER_VERTICAL); setMinimumHeight(dp(54)); setPadding(dp(14), dp(8), dp(12), dp(8));
            setBackground(ripple(Color.TRANSPARENT, 0, 0)); setFocusable(true); setClickable(true);
            setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
                }
            });
            TextView number = text(String.valueOf(index + 1), 11, TEAL, true);
            number.setGravity(Gravity.CENTER); number.setBackground(shape(MINT, 9, 0));
            number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(dp(19), dp(19)); numberParams.rightMargin = dp(8); addView(number, numberParams);
            LinearLayout copy = new LinearLayout(activity); copy.setOrientation(VERTICAL);
            copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            TextView caption = text("VIA " + (index + 1), 9, MUTED, true); caption.setLetterSpacing(.06f);
            copy.addView(caption, matchWrap());
            TextView name = text(label, 13, INK, true); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = matchWrap(); nameParams.topMargin = dp(3); copy.addView(name, nameParams);
            addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            chevron = text("", 12, MUTED, false);
            chevron.setCompoundDrawablesWithIntrinsicBounds(new MarkDrawable(MarkDrawable.RIGHT, MUTED, dp(17)), null, null, null);
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(24)));
            setOnClickListener(v -> { if (isEnabled() && isShown()) listener.onViaPoint(index); });
        }
        void setEditable(boolean editable) {
            setEnabled(editable); chevron.setVisibility(editable ? View.VISIBLE : View.INVISIBLE);
            setContentDescription("Via point " + (index + 1) + ". " + label + (editable ? ". Edit, move or remove." : "."));
        }
    }

    private void updateSheetDescription() {
        sheetHandle.setContentDescription(panelTitle.getText() + ". " + statusPill.getText()
                + (sheetHandle.isEnabled() ? sheet.isExpanded() ? ". Collapse journey controls." : ". Expand journey controls." : ""));
    }

    /** This view never consumes map gestures; an empty hint hides it. */
    public void setMapHint(String hint) {
        String value = safe(hint);
        setText(mapHint, value);
        mapHint.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Provider attribution is part of the scrollable form and preserves the supplied link spans. */
    public void setAttributions(CharSequence text) {
        boolean empty = text == null || text.toString().trim().isEmpty();
        attributionsView.setText(empty ? "" : text);
        attributionsView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** Called on the main thread by the activity whenever application state changes. */
    public void render(String status, String message, String startLabel, String endLabel,
                       double speedKmh, double distanceMeters, double remainingSeconds,
                       boolean active, boolean pending, boolean canStart,
                       String travelMode, String playbackMode, String routeKind, boolean routeLoading) {
        playbackActive = active;
        rendering = true;
        try {
            String state = safe(status);
            String mode = safe(playbackMode);
            boolean stationary = "static".equals(mode);
            boolean arrived = "arrived".equals(state);
            boolean paused = "paused".equals(state);
            boolean locked = active || pending;
            planningEditable = !locked; renderedStationary = stationary;
            endpointsChosen = !safe(startLabel).isEmpty() && !safe(endLabel).isEmpty();
            planningPrimaryAction = !active && isPlanningAction(state);
            boolean routeReady = !routeLoading && !planningPrimaryAction && canStart;
            boolean showPace = !stationary && (active || routeReady);
            String profile = normalizeTravelMode(travelMode);
            setText(panelTitle, stationary ? "Hold a location" : active ? "Your journey"
                    : routeReady ? "Your route" : "Where to?");
            String routeOverview = formatDistance(distanceMeters);
            if (remainingSeconds >= 0 && Double.isFinite(remainingSeconds)) routeOverview += " · " + formatDuration(remainingSeconds);
            setText(statusPill, pending ? "Preparing" : routeLoading ? "Finding route" : "route_error".equals(state) ? "Try again" : arrived ? "Arrived"
                    : paused ? "Paused" : active ? stationary ? "Holding" : "Moving"
                    : "setup_required".equals(state) ? "Set up"
                    : "online_required".equals(state) ? "Offline"
                    : routeReady && !stationary && distanceMeters > 0 && Double.isFinite(distanceMeters) ? routeOverview
                    : routeReady ? "Ready" : "Plan");
            updateSheetDescription();
            fromRow.render(startLabel, !locked);
            toRow.render(endLabel, !locked);
            toRow.setVisibility(stationary ? View.GONE : View.VISIBLE);
            pointDivider.setVisibility(stationary ? View.GONE : View.VISIBLE);
            String routeLabel = "direct".equals(routeKind) ? "Direct line"
                    : "walk".equals(profile) ? "Walking route"
                    : "cycle".equals(profile) ? "Cycle route" : "Driving route";
            String playbackLabel = "pingpong".equals(mode) ? "Return trip" : "One way";
            setText(routeOptions, stationary ? "Stationary" : routeLabel + " · " + playbackLabel);
            routeOptions.setContentDescription("Route options. " + (stationary ? "Hold one location" : routeLabel + ". " + playbackLabel));
            routeOptions.setEnabled(!locked);
            routeOptions.setVisibility(active ? View.GONE : View.VISIBLE);
            routeSummary.setVisibility(routeReady && !stationary ? View.GONE : View.VISIBLE);
            routeSummary.setGravity((active ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
            routeSummary.setPadding(active ? 0 : dp(10), dp(8), 0, dp(8));
            String summary;
            if (stationary) summary = active ? "Held until you tap Stop" : "Hold your chosen point";
            else if (routeLoading) summary = "Finding a route…";
            else if (arrived) summary = "Destination held until Stop";
            else if (distanceMeters > 0 && Double.isFinite(distanceMeters)) {
                summary = formatDistance(distanceMeters);
                if (remainingSeconds >= 0 && Double.isFinite(remainingSeconds)) summary += " · " + formatDuration(remainingSeconds) + (active ? " remaining" : "");
                if (active && "pingpong".equals(mode)) summary += " · Return trip";
            } else if ("route_error".equals(state)) summary = "Route unavailable";
            else if ("setup_required".equals(state)) summary = "Routing key needed";
            else if ("online_required".equals(state)) summary = "Routes are offline";
            else if (safe(startLabel).isEmpty()) summary = "Choose a start";
            else if (safe(endLabel).isEmpty()) summary = "Choose a destination";
            else summary = "Route not ready";
            setText(routeSummary, summary);
            speedControls.setVisibility(showPace ? View.VISIBLE : View.GONE);
            presets.setVisibility(active || stationary ? View.GONE : View.VISIBLE);
            walk.setEnabled(!locked);
            cycle.setEnabled(!locked);
            drive.setEnabled(!locked);
            updatePreset(walk, "walk".equals(profile));
            updatePreset(cycle, "cycle".equals(profile));
            updatePreset(drive, "drive".equals(profile));
            renderedSpeed = normalizeSpeed(speedKmh);
            if ((!speedEditor.hasFocus() || !editorDirty) && !sliderDragging) setSpeedVisual(renderedSpeed);
            speedSlider.setEnabled(!pending && !stationary);
            speedEditor.setEnabled(!pending && !stationary);
            showMessage(message);
            updatePlanningExtras();

            boolean primaryVisible = !active || (!stationary && !arrived);
            primary.setVisibility(primaryVisible ? View.VISIBLE : View.GONE);
            stop.setVisibility(active ? View.VISIBLE : View.GONE);
            boolean primaryEnabled = !pending && (active || (canStart && !routeLoading));
            primary.setEnabled(primaryEnabled);
            primary.setAlpha(primaryEnabled ? 1 : .48f);
            stop.setEnabled(!pending);
            stop.setAlpha(pending ? .48f : 1);
            setText(primary, pending ? "Please wait…" : active ? paused ? "Resume" : "Pause"
                    : routeLoading ? "Finding route…" : planningPrimaryAction ? planningActionLabel(state)
                    : stationary ? "Start location" : "Start journey");
            primary.setContentDescription(primary.getText());
            LinearLayout.LayoutParams stopParams = (LinearLayout.LayoutParams) stop.getLayoutParams();
            int stopWidth = primaryVisible ? dp(94) : 0;
            float stopWeight = primaryVisible ? 0 : 1;
            int stopMargin = primaryVisible ? dp(9) : 0;
            if (stopParams.width != stopWidth || stopParams.weight != stopWeight || stopParams.leftMargin != stopMargin) {
                stopParams.width = stopWidth;
                stopParams.weight = stopWeight;
                stopParams.leftMargin = stopMargin;
                stop.setLayoutParams(stopParams);
            }
        } finally {
            rendering = false;
        }
    }

    private static boolean isPlanningAction(String state) {
        return "setup_required".equals(state) || "online_required".equals(state)
                || "select_start".equals(state) || "select_destination".equals(state)
                || "route_error".equals(state);
    }

    private static String planningActionLabel(String state) {
        switch (state) {
            case "setup_required": return "Set up routing";
            case "online_required": return "Enable routes";
            case "select_start": return "Choose start";
            case "select_destination": return "Choose destination";
            default: return "Retry route";
        }
    }

    /** For a transient validation or routing message without reconstructing the UI. */
    public void showMessage(String message) {
        String value = safe(message);
        if (lastMessage.equals(value)) return;
        lastMessage = value;
        setText(messageView, value);
        messageView.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void installSpeedListeners() {
        speedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || rendering) return;
                visibleSpeed = .5 + progress * .5;
                if (!speedEditor.hasFocus()) setText(speedEditor, formatSpeed(visibleSpeed));
                seekBar.setContentDescription("Speed " + formatSpeed(visibleSpeed) + " kilometers per hour");
                // Discrete keyboard/TalkBack changes have no touch-release event.
                if (!sliderDragging) listener.onSpeed(visibleSpeed);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                sliderDragging = true;
                editorDirty = false;
                speedEditor.setError(null);
                clearSpeedFocus();
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                sliderDragging = false;
                visibleSpeed = .5 + seekBar.getProgress() * .5;
                setSpeedVisual(visibleSpeed);
                listener.onSpeed(visibleSpeed);
            }
        });
        speedEditor.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!rendering && speedEditor.hasFocus()) editorDirty = true;
            }
            @Override public void afterTextChanged(android.text.Editable editable) { }
        });
        speedEditor.setOnFocusChangeListener((v, focused) -> {
            if (focused) editorDirty = false;
            else if (!rendering && !commitEditor()) {
                editorDirty = false;
                setSpeedVisual(renderedSpeed);
                speedEditor.setError(null);
                showMessage("Speed was reset. Enter a value from 0.5 to 200 km/h.");
            }
        });
        speedEditor.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                if (commitEditor()) clearSpeedFocus();
                return true;
            }
            return false;
        });
    }

    private boolean commitEditor() {
        if (!editorDirty) return true;
        String value = speedEditor.getText().toString().trim().replace(',', '.');
        double speed;
        try { speed = Double.parseDouble(value); }
        catch (NumberFormatException ignored) { speed = Double.NaN; }
        if (!Double.isFinite(speed) || speed < .5 || speed > 200) {
            speedEditor.setError("Enter a speed from 0.5 to 200 km/h");
            return false;
        }
        speed = normalizeSpeed(speed);
        editorDirty = false;
        speedEditor.setError(null);
        setSpeedVisual(speed);
        if (Math.abs(speed - renderedSpeed) > .001) listener.onSpeed(speed);
        return true;
    }

    private void clearSpeedFocus() {
        if (!speedEditor.hasFocus()) return;
        speedEditor.clearFocus();
        rootView.requestFocus();
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(speedEditor.getWindowToken(), 0);
    }

    private void setSpeedVisual(double value) {
        visibleSpeed = normalizeSpeed(value);
        boolean wasRendering = rendering;
        rendering = true;
        try {
            setText(speedEditor, formatSpeed(visibleSpeed));
            speedSlider.setProgress((int) Math.round((visibleSpeed - .5) * 2));
        } finally { rendering = wasRendering; }
    }

    private Button preset(String label, String mode, double speed) {
        Button result = button(label, WHITE, MUTED, true);
        result.setContentDescription(label + ", " + formatSpeed(speed) + " kilometers per hour");
        result.setTag(null);
        result.setOnClickListener(v -> {
            editorDirty = false;
            speedEditor.setError(null);
            clearSpeedFocus();
            setSpeedVisual(speed);
            listener.onPreset(mode, speed);
        });
        return result;
    }

    private void updatePreset(Button button, boolean selected) {
        if (Boolean.valueOf(selected).equals(button.getTag())) return;
        button.setTag(selected);
        button.setSelected(selected);
        button.setTextColor(selected ? WHITE : MUTED);
        button.setBackground(ripple(selected ? TEAL : Color.TRANSPARENT, 10, 0));
    }

    private final class PointRow extends LinearLayout {
        final boolean destination;
        final TextView value;
        final TextView chevron;

        PointRow(boolean destination) {
            super(activity);
            this.destination = destination;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(10), dp(12), dp(10));
            setMinimumHeight(dp(62));
            setBackground(ripple(Color.TRANSPARENT, 0, 0));
            setFocusable(true);
            setClickable(true);
            setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                }
            });
            TextView pointMark = text("", 12, TEAL, false);
            pointMark.setCompoundDrawablesWithIntrinsicBounds(new MarkDrawable(destination ? MarkDrawable.DESTINATION : MarkDrawable.START, TEAL, dp(19)), null, null, null);
            pointMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(pointMark, new LinearLayout.LayoutParams(dp(27), dp(24)));
            LinearLayout copy = new LinearLayout(activity);
            copy.setOrientation(VERTICAL);
            copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            TextView caption = text(destination ? "TO" : "FROM", 10, MUTED, true);
            caption.setLetterSpacing(.06f);
            LinearLayout.LayoutParams captionParams = matchWrap(); captionParams.bottomMargin = dp(4);
            copy.addView(caption, captionParams);
            value = text(destination ? "Choose destination" : "Choose starting point", 14, INK, true);
            value.setMaxLines(2);
            value.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams valueParams = matchWrap();
            copy.addView(value, valueParams);
            addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            chevron = text("", 12, MUTED, false);
            chevron.setCompoundDrawablesWithIntrinsicBounds(new MarkDrawable(MarkDrawable.RIGHT, MUTED, dp(17)), null, null, null);
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(18), dp(24));
            chevronParams.leftMargin = dp(8);
            addView(chevron, chevronParams);
            setOnClickListener(v -> listener.onPoint(destination));
        }

        void render(String text, boolean enabled) {
            String label = safe(text);
            if (label.isEmpty()) label = destination ? "Choose destination" : "Choose starting point";
            setText(value, label);
            setEnabled(enabled);
            chevron.setVisibility(enabled ? VISIBLE : INVISIBLE);
            setContentDescription((destination ? "Destination. " : "Starting point. ") + label
                    + (enabled ? ". Select a place, coordinates, or a point on the map." : ""));
        }
    }

    /** A full-size map with a floating header and a bottom sheet or landscape side card. */
    private final class AdaptiveRoot extends ViewGroup {
        private int contentLeft, contentTop, contentWidth, contentHeight;
        private int headerWidth, headerHeight, panelWidth, panelHeight;
        private int expandedHeight, collapsedHeight;
        private boolean landscape;
        private final Drawable portraitBackground = panel.getBackground();
        private final Drawable landscapeBackground = shape(PAPER, 25, 0);
        private final Runnable sendInsets = () -> {
            if (mapInsetsListener == null || sheet.isInteracting()) return;
            int top = headerHeight + dp(24);
            if (mapHint.getVisibility() == VISIBLE) top += mapHint.getMeasuredHeight() + dp(10);
            int right = landscape ? panelWidth + dp(24) : 0;
            int bottom = landscape ? 0 : panelHeight;
            int[] next = {0, top, right, bottom};
            if (java.util.Arrays.equals(next, deliveredInsets)) return;
            System.arraycopy(next, 0, deliveredInsets, 0, next.length);
            mapInsetsListener.onMapInsets(next[0], next[1], next[2], next[3]);
        };

        AdaptiveRoot(Context context) { super(context); }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            contentLeft = getPaddingLeft(); contentTop = getPaddingTop();
            contentWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            contentHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
            boolean nextLandscape = contentWidth >= dp(600) && contentWidth > contentHeight * 1.35f;
            if (landscape != nextLandscape) {
                sheet.restore(sheet.isExpanded());
                panel.setBackground(nextLandscape ? landscapeBackground : portraitBackground);
            }
            landscape = nextLandscape;
            sheet.setEnabled(!landscape);
            formScroll.setVisibility(landscape || sheet.getFraction() > 0 ? VISIBLE : GONE);
            panelWidth = landscape ? Math.min(dp(350), Math.round(contentWidth * .46f)) : contentWidth;
            headerWidth = Math.max(0, (landscape ? contentWidth - panelWidth - dp(12) : contentWidth) - dp(24));
            header.measure(MeasureSpec.makeMeasureSpec(headerWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.min(dp(112), contentHeight), MeasureSpec.AT_MOST));
            headerHeight = header.getMeasuredHeight();
            int exactPanelWidth = MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY);
            form.measure(exactPanelWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            sheetHandle.measure(exactPanelWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            actions.measure(exactPanelWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            collapsedHeight = Math.min(contentHeight, sheetHandle.getMeasuredHeight() + actions.getMeasuredHeight());
            if (landscape) {
                panelHeight = Math.max(0, contentHeight - dp(24));
                expandedHeight = panelHeight;
            } else {
                int desired = collapsedHeight + form.getMeasuredHeight();
                int cap = Math.max(collapsedHeight + dp(48), Math.round(contentHeight * .68f));
                cap = Math.min(contentHeight, cap);
                expandedHeight = Math.min(desired, cap);
                panelHeight = Math.round(collapsedHeight + (expandedHeight - collapsedHeight) * sheet.getFraction());
            }
            sheet.setTravelDistance(Math.max(1, expandedHeight - collapsedHeight));
            panel.measure(exactPanelWidth, MeasureSpec.makeMeasureSpec(panelHeight, MeasureSpec.EXACTLY));
            FrameLayout.LayoutParams hint = (FrameLayout.LayoutParams) mapHint.getLayoutParams();
            int hintTop = headerHeight + dp(22);
            hint.topMargin = hintTop;
            hint.width = Math.max(0, headerWidth);
            hint.leftMargin = dp(12); hint.rightMargin = dp(12);
            int visibleHeight = landscape ? contentHeight : contentHeight - panelHeight;
            mapHint.measure(MeasureSpec.makeMeasureSpec(headerWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.AT_MOST));
            // Route fitting needs at least 100dp after all overlays, including this hint.
            int hintedTopInset = headerHeight + dp(34) + mapHint.getMeasuredHeight();
            boolean roomForHint = visibleHeight >= hintedTopInset + dp(100);
            mapHint.setVisibility(!mapHint.getText().toString().isEmpty() && roomForHint ? VISIBLE : GONE);
            mapRegion.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY));
            setMeasuredDimension(width, height);
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            mapRegion.layout(contentLeft, contentTop, contentLeft + contentWidth, contentTop + contentHeight);
            header.layout(contentLeft + dp(12), contentTop + dp(12),
                    contentLeft + dp(12) + headerWidth, contentTop + dp(12) + headerHeight);
            if (landscape) {
                int panelLeft = contentLeft + contentWidth - dp(12) - panelWidth;
                panel.layout(panelLeft, contentTop + dp(12), panelLeft + panelWidth, contentTop + contentHeight - dp(12));
            } else {
                panel.layout(contentLeft, contentTop + contentHeight - panelHeight,
                        contentLeft + contentWidth, contentTop + contentHeight);
            }
            removeCallbacks(sendInsets);
            if (!sheet.isInteracting()) post(sendInsets);
            updateSheetDescription();
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(sendInsets); sheet.cancel(); super.onDetachedFromWindow();
        }

        @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
            return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private Button button(String label, int background, int foreground, boolean bold) {
        Button result = new Button(activity);
        result.setText(label);
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setTextColor(foreground);
        result.setTypeface(bold ? MEDIUM : Typeface.DEFAULT);
        result.setGravity(Gravity.CENTER);
        result.setPadding(dp(12), dp(8), dp(12), dp(8));
        result.setMinimumHeight(dp(48));
        result.setMinHeight(dp(48));
        result.setMinimumWidth(0);
        result.setMinWidth(0);
        result.setMaxLines(2);
        result.setStateListAnimator(null);
        result.setBackground(ripple(background, 13, background == WHITE ? LINE : 0));
        result.setHapticFeedbackEnabled(true);
        return result;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView result = new TextView(activity);
        result.setText(value);
        result.setTextSize(sp);
        result.setTextColor(color);
        result.setTypeface(bold ? MEDIUM : Typeface.DEFAULT);
        result.setIncludeFontPadding(false);
        return result;
    }

    private LinearLayout horizontal() {
        LinearLayout result = new LinearLayout(activity);
        result.setOrientation(LinearLayout.HORIZONTAL);
        return result;
    }

    private GradientDrawable shape(int fill, float cornerDp, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(cornerDp));
        if (stroke != 0) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private Drawable ripple(int fill, float cornerDp, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(25, 28, 91, 71)),
                shape(fill, cornerDp, stroke), shape(WHITE, cornerDp, 0));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams marginBottom(int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = bottom;
        return params;
    }

    private LinearLayout.LayoutParams weighted(int left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.leftMargin = left;
        return params;
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static void setText(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }
    private static double normalizeSpeed(double speed) {
        if (!Double.isFinite(speed)) return 5;
        return Math.round(Math.max(.5, Math.min(200, speed)) * 2) / 2.0;
    }
    private static String normalizeTravelMode(String mode) {
        if ("cycle".equals(mode) || "bicycling".equals(mode) || "cycling".equals(mode)) return "cycle";
        if ("drive".equals(mode) || "driving".equals(mode)) return "drive";
        return "walk";
    }
    private static String formatSpeed(double speed) {
        return speed == Math.rint(speed) ? String.format(Locale.US, "%.0f", speed) : String.format(Locale.US, "%.1f", speed);
    }
    private static String formatDistance(double meters) {
        if (meters < 1000) return String.format(Locale.US, "%.0f m", meters);
        return String.format(Locale.US, meters < 10000 ? "%.1f km" : "%.0f km", meters / 1000);
    }
    private static String formatDuration(double seconds) {
        long minutes = Math.max(0, (long) Math.ceil(seconds / 60));
        if (minutes == 0) return "<1 min";
        if (minutes < 60) return minutes + " min";
        if (minutes % 60 == 0) return minutes / 60 + " hr";
        return minutes / 60 + " hr " + minutes % 60 + " min";
    }

    /** Small bundled native line icons; no remote assets or icon-font dependency. */
    private static final class MarkDrawable extends Drawable {
        static final int NAVIGATION = 0, SETTINGS = 1, START = 2, DESTINATION = 3, RIGHT = 4, DOWN = 5;
        private final int type, size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        MarkDrawable(int type, int color, int size) {
            this.type = type;
            this.size = size;
            paint.setColor(color);
            paint.setStrokeWidth(1.65f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            path.reset();
            paint.setStyle(Paint.Style.STROKE);
            switch (type) {
                case NAVIGATION:
                    paint.setStyle(Paint.Style.FILL);
                    path.moveTo(20, 4); path.lineTo(14.3f, 21); path.lineTo(10.7f, 13.3f); path.lineTo(3, 9.6f); path.close();
                    canvas.drawPath(path, paint);
                    break;
                case SETTINGS:
                    canvas.drawLine(4, 6, 20, 6, paint); canvas.drawLine(4, 12, 20, 12, paint); canvas.drawLine(4, 18, 20, 18, paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(9, 6, 2.5f, paint); canvas.drawCircle(15, 12, 2.5f, paint); canvas.drawCircle(9, 18, 2.5f, paint);
                    break;
                case START:
                    canvas.drawCircle(12, 12, 7, paint); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 12, 2.5f, paint);
                    break;
                case DESTINATION:
                    canvas.drawRoundRect(new RectF(5, 5, 19, 19), 4, 4, paint); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 12, 3, paint);
                    break;
                case RIGHT:
                    path.moveTo(9, 5); path.lineTo(16, 12); path.lineTo(9, 19); canvas.drawPath(path, paint);
                    break;
                default:
                    path.moveTo(5, 9); path.lineTo(12, 16); path.lineTo(19, 9); canvas.drawPath(path, paint);
            }
            canvas.restoreToCount(save);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
