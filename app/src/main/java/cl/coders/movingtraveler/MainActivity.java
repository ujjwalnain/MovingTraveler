// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native map and controls. Road geometry is resolved once; playback runs on the device. */
public final class MainActivity extends Activity implements JourneyUi.Listener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JourneyUi ui;
    private NetworkGateway network;
    private MapSurface mapSurface;
    private TextView mapPlaceholder;
    private final int[] mapInsets = new int[4];
    private ValueAnimator motion;
    private RouteEngine visualPath;
    private double[][] visualGeometry;
    private String visualMode = "";
    private double visualDistance = Double.NaN;
    private PlanningSession session;
    private Draft draft;
    private boolean started, resumed, registered, wasActive;
    private boolean routeLoading, routeFailed, permissionsPending;
    private boolean mapLoaded;
    private String mapError = "";
    private long sequence;
    private int selection = -1, commandGeneration;
    private String message = "", pendingAction = "", pendingPlan;
    private AlertDialog pointDialog, permissionDialog, routingDialog;
    private boolean permissionExplanation;
    private PointPicker currentPicker;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { readState(); }
    };

    /** Retained only in process. Provider geometry and search labels are never saved to disk. */
    static final class Draft {
        double[] start, end;
        boolean startFromProvider, endFromProvider;
        String startLabel = "", endLabel = "", travel = "walk", playback = "once", kind = "road";
        double speed = 5, distance, startSnapMeters, endSnapMeters;
        String routeWarning = "";
        double[][] geometry;
        JSONArray startAttributions = new JSONArray(), endAttributions = new JSONArray();
        final List<Waypoint> vias = new ArrayList<>();
        final List<RouteChoice> routes = new ArrayList<>();
        int selectedRoute;
        String routePreference = "balanced", choicesMessage = "";
        double[][] viaCoordinates = new double[0][];
        double[][][] otherRoutes = new double[0][][];
    }

    static final class Waypoint {
        final double[] point;
        final String label;
        final boolean fromProvider;
        JSONArray attributions = new JSONArray();
        Waypoint(double[] point, String label, boolean fromProvider) {
            this.point = point.clone(); this.label = label; this.fromProvider = fromProvider;
        }
    }

    static final class RouteChoice {
        final double[][] points;
        final String label, preference;
        final double distance, maxSnap, startSnap, endSnap;
        RouteChoice(JSONObject data) throws Exception {
            JSONArray array = data.getJSONArray("points");
            if (array.length() < 2 || array.length() > RouteEngine.MAX_POINTS) throw new IllegalArgumentException("Invalid route");
            points = new double[array.length()][2];
            for (int i = 0; i < array.length(); i++) {
                points[i][0] = array.getJSONArray(i).getDouble(0); points[i][1] = array.getJSONArray(i).getDouble(1);
            }
            distance = new RouteEngine(points, 5, "once").current().totalMeters;
            if (distance < 1) throw new IllegalArgumentException("Empty route");
            preference = data.optString("preference", "balanced");
            label = "short".equals(preference) ? "Shortest" : "less_maneuvers".equals(preference) ? "Fewer turns" : "Balanced";
            startSnap = data.optDouble("startSnapMeters", 0); endSnap = data.optDouble("endSnapMeters", 0);
            double snap = Math.max(startSnap, endSnap);
            JSONArray snaps = data.optJSONArray("waypointSnapMeters");
            if (snaps != null) for (int i = 0; i < snaps.length(); i++) snap = Math.max(snap, snaps.optDouble(i, 0));
            maxSnap = snap;
        }
    }

    static final class Retained {
        PlanningSession session;
        String plan, pickerQuery;
        int selection = -1;
        Boolean pickerDestination;
        int pickerVia = -1;
        boolean permissionsPending, explanation;
        boolean sheetExpanded = true;
    }

    /** Survives rotation without repeating a billable route request. No Activity is retained. */
    static final class PlanningSession {
        final Draft draft;
        final NetworkGateway network;
        final Handler main = new Handler(Looper.getMainLooper());
        MainActivity owner;
        long generation;
        boolean loading, failed, closed;
        String message = "", errorCode = "";
        PlanningSession(Context context, Draft draft) {
            this.draft = draft; network = new NetworkGateway(context.getApplicationContext());
        }
        void notifyOwner(boolean fit) {
            if (owner == null || owner.isDestroyed()) return;
            owner.routeLoading = loading; owner.routeFailed = failed; owner.message = message;
            owner.drawRoute(fit); owner.render();
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!SimulationService.active) MockPublisher.recoverStale(this);
        Object retained = getLastNonConfigurationInstance();
        Retained memory = retained instanceof Retained ? (Retained) retained : null;
        session = memory == null ? new PlanningSession(this, restoreDraft()) : memory.session;
        draft = session.draft; session.owner = this;
        routeLoading = session.loading; routeFailed = session.failed; message = session.message;
        if (memory != null) selection = memory.selection;
        if (memory != null && memory.plan != null) {
            pendingPlan = memory.plan; pendingAction = "start";
            permissionsPending = memory.permissionsPending; permissionExplanation = memory.explanation;
        }
        boolean needsConsent = settings().getInt("openMapConsentVersion", 0) < 1;
        if (needsConsent) settings().edit().putBoolean("online", false).apply();
        network = session.network;
        // Seed restored padding before installing geometry, preserving a saved camera on unchanged layouts.
        int[] restoredInsets = state == null ? null : state.getIntArray("mapInsets");
        if (restoredInsets != null && restoredInsets.length == mapInsets.length) {
            for (int i = 0; i < mapInsets.length; i++) mapInsets[i] = Math.max(0, restoredInsets[i]);
        }
        ui = new JourneyUi(this, this);
        if (memory != null) ui.restoreSheet(memory.sheetExpanded);
        else if (state != null) ui.restoreSheet(state.getBoolean("sheetExpanded", true));
        ui.setMapInsetsListener((left, top, right, bottom) -> {
            mapInsets[0] = left; mapInsets[1] = top; mapInsets[2] = right; mapInsets[3] = bottom;
            if (mapSurface != null) mapSurface.setContentInsets(left, top, right, bottom);
            updateMapPlaceholderInsets();
        });
        setContentView(ui.root());
        ui.root().setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 35) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        setupMap(state == null ? null : state.getBundle("map"));
        readState();
        if (!SimulationService.active && draft.geometry == null && !session.loading) refreshRoute();
        handleGeoIntent();
        if (needsConsent) handler.post(this::showOnlineChoice);
        if (permissionExplanation) handler.post(this::showPermissionRationale);
        if (memory != null && memory.pickerDestination != null) handler.post(() -> {
            if (!locked()) new PointPicker(memory.pickerDestination, memory.pickerQuery, memory.pickerVia).show();
        });
    }

    private SharedPreferences settings() { return getSharedPreferences("settings", MODE_PRIVATE); }
    private boolean online() { return settings().getBoolean("online", false); }
    private boolean routingConfigured() { return !RoutingCredentials.get(this).isEmpty(); }
    private boolean locked() { return SimulationService.active || !pendingAction.isEmpty(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void haptic() {
        ui.root().performHapticFeedback(Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void setupMap(Bundle saved) {
        destroyMap(); ui.mapContainer.removeAllViews(); mapError = "";
        if (!online()) {
            showMapPlaceholder("Your journey starts here\n\nEnable the map to choose points, or enter coordinates.",
                    this::showOnlineChoice);
            updateHint(); return;
        }
        try {
            mapSurface = new MapSurface(this, ui.mapContainer, new MapSurface.Listener() {
                private boolean announced;
                @Override public void onTap(double lat, double lon) { mapTapped(lat, lon); }
                @Override public void onDrag(boolean destination, double lat, double lon) {
                    setPoint(destination, new double[]{lat, lon}, "", false);
                }
                @Override public void onReady() {
                    boolean fit = !announced && saved == null; announced = true;
                    mapLoaded = true; mapError = ""; drawRoute(fit); readState();
                }
                @Override public void onMapError(String error) { mapError = error; updateHint(); }
            });
            mapSurface.create(saved);
            mapSurface.setContentInsets(mapInsets[0], mapInsets[1], mapInsets[2], mapInsets[3]);
            if (started) mapSurface.start();
            if (resumed) mapSurface.resume();
            drawRoute(false); updateHint();
        } catch (RuntimeException | LinkageError error) {
            destroyMap();
            mapError = "The map could not start on this device. You can still enter coordinates.";
            showMapPlaceholder(mapError, () -> setupMap(null)); updateHint();
        }
    }

    private void showMapPlaceholder(String message, Runnable action) {
        mapPlaceholder = text(message, 16);
        mapPlaceholder.setGravity(Gravity.CENTER);
        mapPlaceholder.setOnClickListener(v -> action.run());
        ui.mapContainer.addView(mapPlaceholder, new FrameLayout.LayoutParams(-1, -1));
        updateMapPlaceholderInsets();
    }

    private void updateMapPlaceholderInsets() {
        if (mapPlaceholder == null) return;
        // Center offline/error guidance in the map area left visible by the floating controls.
        mapPlaceholder.setPadding(mapInsets[0] + dp(20), mapInsets[1] + dp(12),
                mapInsets[2] + dp(20), mapInsets[3] + dp(12));
    }

    private void destroyMap() {
        mapPlaceholder = null;
        if (motion != null) { motion.cancel(); motion = null; }
        if (mapSurface != null) { mapSurface.destroy(); mapSurface = null; }
        mapLoaded = false; visualDistance = Double.NaN;
    }

    private void mapTapped(double latitude, double longitude) {
        if (locked()) return;
        if (selection >= 2) {
            setViaPoint(selection - 2, new double[]{latitude, longitude}, "", false, null); return;
        }
        if ("static".equals(draft.playback)) {
            setPoint(false, new double[]{latitude, longitude}, "", false);
            return;
        }
        if (selection >= 0 || draft.start == null || draft.end == null) {
            boolean destination = selection == 1 || (selection < 0 && draft.start != null);
            setPoint(destination, new double[]{latitude, longitude}, "", false);
        } else {
            new AlertDialog.Builder(this).setTitle("Use this point")
                    .setItems(new String[]{"Set start", "Set destination"}, (dialog, index) ->
                            setPoint(index == 1, new double[]{latitude, longitude}, "", false)).show();
        }
    }

    private void setPoint(boolean destination, double[] point, String label, boolean fromProvider) {
        if (locked() || !validPoint(point)) return;
        haptic();
        String shown = label == null || label.isEmpty() ? coordinates(point) : label;
        if (destination) { draft.end = point; draft.endLabel = shown; draft.endFromProvider = fromProvider; }
        else { draft.start = point; draft.startLabel = shown; draft.startFromProvider = fromProvider; }
        if (destination) draft.endAttributions = new JSONArray(); else draft.startAttributions = new JSONArray();
        selection = -1;
        saveDraft();
        refreshRoute();
    }

    private void setViaPoint(int index, double[] point, String label, boolean fromProvider, JSONArray credits) {
        if (locked() || !validPoint(point) || index < 0 || index > draft.vias.size() || index >= 6) return;
        Waypoint waypoint = new Waypoint(point, label == null || label.isEmpty() ? coordinates(point) : label, fromProvider);
        if (credits != null) waypoint.attributions = credits;
        if (index == draft.vias.size()) draft.vias.add(waypoint); else draft.vias.set(index, waypoint);
        updateViaCoordinates(draft); selection = -1; haptic(); saveDraft(); refreshRoute();
    }

    private static void updateViaCoordinates(Draft draft) {
        draft.viaCoordinates = new double[draft.vias.size()][];
        for (int i = 0; i < draft.vias.size(); i++) draft.viaCoordinates[i] = draft.vias.get(i).point;
    }

    private static double[][] plannedPoints(Draft draft) {
        double[][] points = new double[draft.vias.size() + 2][];
        points[0] = draft.start;
        for (int i = 0; i < draft.vias.size(); i++) points[i + 1] = draft.vias.get(i).point;
        points[points.length - 1] = draft.end; return points;
    }

    private static boolean validPoint(double[] p) {
        return p != null && p.length == 2 && Double.isFinite(p[0]) && Double.isFinite(p[1])
                && Math.abs(p[0]) <= 90 && Math.abs(p[1]) <= 180;
    }
    private static String coordinates(double[] p) {
        return String.format(Locale.US, "%.5f, %.5f", p[0], p[1]);
    }
    static double[] parseCoordinates(String raw) {
        try {
            String[] parts = raw.trim().split(",", -1);
            if (parts.length != 2) return null;
            double[] point = {Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
            return validPoint(point) ? point : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    private void refreshRoute() {
        if (locked()) return;
        network.cancel("route");
        long expected = ++session.generation;
        session.loading = session.failed = false; session.errorCode = "";
        session.message = ""; routeLoading = routeFailed = false; message = "";
        draft.geometry = null; draft.distance = 0; draft.routeWarning = "";
        draft.routes.clear(); draft.otherRoutes = new double[0][][]; draft.selectedRoute = 0; draft.choicesMessage = "";
        draft.startSnapMeters = draft.endSnapMeters = 0;
        if (draft.start == null || (!"static".equals(draft.playback) && draft.end == null)) {
            session.notifyOwner(true); return;
        }
        if ("static".equals(draft.playback) || "direct".equals(draft.kind)) {
            try { setGeometry(draft, "static".equals(draft.playback) ? new double[][]{draft.start}
                    : plannedPoints(draft)); }
            catch (IllegalArgumentException error) { session.message = error.getMessage(); }
            session.notifyOwner(true); return;
        }
        if (!online() || !routingConfigured()) {
            session.message = !online() ? "Enable online planning to find a road route."
                    : RoutingCredentials.hasSavedPersonalKey(this)
                        ? "Your routing key is saved, but Android secure storage could not read it. Retry, or update Routing access in Settings."
                        : "Add your free routing key once to find walking, cycling and driving routes.";
            if (online() && RoutingCredentials.hasSavedPersonalKey(this)) session.failed = true;
            session.notifyOwner(true); return;
        }
        session.loading = true; session.message = "Finding a route along roads and paths…";
        session.notifyOwner(true);
        final PlanningSession requested = session;
        try {
            JSONObject request = new JSONObject().put("id", String.valueOf(expected)).put("type", "route")
                    .put("points", new JSONArray(plannedPoints(draft))).put("routeChoices", true)
                    .put("travelMode", "cycle".equals(draft.travel) ? "BICYCLE" : draft.travel.toUpperCase(Locale.ROOT));
            network.request(request.toString(), result -> requested.main.post(() -> {
                if (requested.closed || requested.generation != expected) return;
                requested.loading = false;
                if (result.optBoolean("ok")) {
                    try {
                        JSONObject data = result.getJSONObject("data");
                        loadRouteChoices(requested.draft, data);
                        requested.message = ""; requested.failed = false; requested.errorCode = "";
                    } catch (Exception invalid) {
                        requested.failed = true; requested.message = "This route could not be read. Choose nearby points and retry.";
                        requested.errorCode = "INVALID_RESPONSE";
                    }
                } else {
                    requested.failed = true; requested.message = result.optString("error", "Could not find a route. Try again.");
                    requested.errorCode = result.optString("errorCode");
                }
                requested.notifyOwner(true);
            }));
        } catch (Exception invalid) {
            session.loading = false; session.failed = true; session.message = "Could not request the route.";
            session.notifyOwner(false);
        }
    }

    private static void setGeometry(Draft draft, double[][] points) {
        RouteEngine engine = new RouteEngine(points, draft.speed, draft.playback);
        if (!"static".equals(draft.playback) && engine.current().totalMeters < 1)
            throw new IllegalArgumentException("Choose points at least one metre apart.");
        draft.geometry = points; draft.distance = engine.current().totalMeters;
    }

    private static void loadRouteChoices(Draft draft, JSONObject data) throws Exception {
        JSONArray routes = data.optJSONArray("routes");
        if (routes == null) routes = new JSONArray().put(data);
        if (routes.length() < 1 || routes.length() > 3) throw new IllegalArgumentException("Invalid route choices");
        List<RouteChoice> choices = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < routes.length(); i++) {
            RouteChoice choice = new RouteChoice(routes.getJSONObject(i)); choices.add(choice);
            if (draft.routePreference.equals(choice.preference)) selected = i;
        }
        draft.routes.clear(); draft.routes.addAll(choices);
        draft.choicesMessage = data.optString("choicesMessage", "");
        if (routes.length() == 1 && draft.choicesMessage.isEmpty())
            draft.choicesMessage = "One distinct route found for these points.";
        applyRouteChoice(draft, selected);
    }

    private static void applyRouteChoice(Draft draft, int index) {
        RouteChoice choice = draft.routes.get(index);
        setGeometry(draft, choice.points);
        draft.selectedRoute = index;
        draft.startSnapMeters = choice.startSnap; draft.endSnapMeters = choice.endSnap;
        draft.routeWarning = choice.maxSnap > 50 ? String.format(Locale.US,
                "Nearest accessible path is %.0f m from a selected point. Playback follows the displayed route.", choice.maxSnap) : "";
        draft.otherRoutes = new double[draft.routes.size() - 1][][];
        for (int i = 0, other = 0; i < draft.routes.size(); i++)
            if (i != index) draft.otherRoutes[other++] = draft.routes.get(i).points;
    }

    @Override public void onRouteChoice(int index) {
        if (locked() || routeLoading || index < 0 || index >= draft.routes.size()) return;
        if (index == draft.selectedRoute && draft.routePreference.equals(draft.routes.get(index).preference)) return;
        applyRouteChoice(draft, index); draft.routePreference = draft.routes.get(index).preference;
        haptic(); saveDraft(); drawRoute(true); render();
    }

    private void drawRoute(boolean fit) {
        if (mapSurface == null) return;
        mapSurface.setWaypoints(draft.viaCoordinates);
        mapSurface.setAlternatives(locked() ? null : draft.otherRoutes);
        mapSurface.drawRoute(draft.start, draft.end, draft.startLabel, draft.endLabel,
                draft.geometry, "static".equals(draft.playback), locked(), fit);
    }

    private void moveTraveler(JSONObject state) {
        if (mapSurface == null || !state.has("lat") || draft.geometry == null) return;
        if (visualGeometry != draft.geometry || !visualMode.equals(draft.playback)) {
            visualGeometry = draft.geometry; visualMode = draft.playback;
            visualPath = new RouteEngine(draft.geometry, draft.speed, draft.playback); visualDistance = Double.NaN;
        }
        double target = state.optDouble("distanceMeters") + state.optInt("leg", 0) * state.optDouble("totalMeters");
        if (motion != null) { motion.cancel(); motion = null; }
        boolean followsPath = "running".equals(state.optString("status")) || "arrived".equals(state.optString("status"));
        if (!resumed || Double.isNaN(visualDistance) || !followsPath
                || target < visualDistance || Math.abs(target - visualDistance) < .001) {
            showTravelerAt(target); return;
        }
        double from = visualDistance;
        motion = ValueAnimator.ofFloat(0, 1); motion.setDuration(950);
        motion.setInterpolator(new android.view.animation.LinearInterpolator());
        motion.addUpdateListener(animation -> showTravelerAt(from + (target - from) * (float) animation.getAnimatedValue()));
        motion.start();
    }
    private void showTravelerAt(double distance) {
        if (mapSurface == null || visualPath == null) return;
        visualDistance = distance;
        RouteEngine.Sample sample = visualPath.sampleAtDistance(distance);
        mapSurface.moveTraveler(sample.latitude, sample.longitude, sample.bearingDegrees);
    }

    @Override public void onPrimary() {
        if (!pendingAction.isEmpty()) return;
        if (SimulationService.active) {
            try {
                String status = new JSONObject(SimulationService.state).optString("status");
                if ("arrived".equals(status) || "static".equals(draft.playback)) return;
                sendPlayback("paused".equals(status) ? "resume" : "pause");
            } catch (Exception ignored) { }
        } else if (draft.start == null) onPoint(false);
        else if (draft.end == null && !"static".equals(draft.playback)) onPoint(true);
        else if (draft.geometry == null && "road".equals(draft.kind) && !"static".equals(draft.playback) && !online()) showOnlineChoice();
        else if (draft.geometry == null && "road".equals(draft.kind) && !"static".equals(draft.playback)
                && !routingConfigured() && !RoutingCredentials.hasSavedPersonalKey(this)) showRoutingSetup();
        else if (routeFailed) { haptic(); refreshRoute(); }
        else beginStart();
    }
    @Override public void onStopPlayback() { if (SimulationService.active && pendingAction.isEmpty()) sendPlayback("stop"); }

    private void beginStart() {
        if (!resumed || draft.geometry == null || routeLoading || locked()) return;
        if (!mockReady()) { showSetup(); return; }
        try {
            pendingPlan = new JSONObject().put("points", new JSONArray(draft.geometry))
                    .put("speedKmh", draft.speed).put("mode", draft.playback).toString();
            new RoutePlan(pendingPlan);
            SimulationService.state = "{\"status\":\"idle\"}";
            pendingAction = "start"; message = "Starting journey…"; haptic(); render();
            if (!settings().getBoolean("playbackPermissionsAsked", false)) showPermissionRationale();
            else dispatchStart();
        } catch (Exception error) { clearPending(); message = "Could not start this route."; render(); }
    }

    private void showPermissionRationale() {
        if (isDestroyed() || isFinishing() || pendingPlan == null) return;
        permissionsPending = true; permissionExplanation = true;
        permissionDialog = new AlertDialog.Builder(this).setTitle("Playback permissions")
                .setMessage("Approximate location enables Google Play Services mock-location compatibility. Moving Traveler does not read your actual location. Notifications give you playback controls while another app is open.\n\nBoth permissions are optional.")
                .setPositiveButton("Continue", (dialog, which) -> {
                    permissionExplanation = false;
                    settings().edit().putBoolean("playbackPermissionsAsked", true).apply();
                    ArrayList<String> permissions = new ArrayList<>();
                    if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
                            && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        permissions.add(Manifest.permission.POST_NOTIFICATIONS);
                    if (permissions.isEmpty()) { permissionsPending = false; dispatchStart(); }
                    else requestPermissions(permissions.toArray(new String[0]), 10);
                }).setNegativeButton("Skip", (dialog, which) -> {
                    permissionsPending = permissionExplanation = false;
                    settings().edit().putBoolean("playbackPermissionsAsked", true).apply(); dispatchStart();
                }).setOnCancelListener(dialog -> { clearPending(); message = ""; render(); }).create();
        permissionDialog.show();
    }

    private void dispatchStart() {
        if (!resumed || permissionsPending || pendingPlan == null) return;
        if (SimulationService.active) { clearPending(); readState(); return; }
        SimulationService.stagedPlan = pendingPlan;
        pendingPlan = null;
        try {
            Intent intent = new Intent(this, SimulationService.class).setAction("start");
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            armCommandTimeout();
        } catch (RuntimeException error) {
            SimulationService.stagedPlan = null;
            clearPending(); message = "Couldn't start playback. Return to the app and try again."; render();
        }
    }
    private void sendPlayback(String action) {
        if (!SimulationService.active || !pendingAction.isEmpty()) return;
        haptic(); pendingAction = action; render();
        try { startService(new Intent(this, SimulationService.class).setAction(action)); armCommandTimeout(); }
        catch (RuntimeException error) { clearPending(); message = "Playback control failed. Try again."; render(); }
    }
    private void armCommandTimeout() {
        int expected = ++commandGeneration;
        handler.postDelayed(() -> {
            if (isDestroyed() || expected != commandGeneration || pendingAction.isEmpty()) return;
            if ("start".equals(pendingAction) && !SimulationService.active) SimulationService.stagedPlan = null;
            clearPending(); message = "Playback did not respond. Check the notification and try again."; readState();
        }, 10_000);
    }
    private void clearPending() { commandGeneration++; pendingAction = ""; pendingPlan = null; permissionsPending = permissionExplanation = false; }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == 10) { permissionsPending = false; handler.post(this::dispatchStart); }
        readState();
    }

    private void readState() {
        if (ui == null || isDestroyed()) return;
        try {
            JSONObject state = new JSONObject(SimulationService.state);
            boolean active = SimulationService.active;
            String status = state.optString("status", "idle");
            boolean acknowledged = ("start".equals(pendingAction) && active)
                    || ("pause".equals(pendingAction) && ("paused".equals(status) || "arrived".equals(status)))
                    || ("resume".equals(pendingAction) && ("running".equals(status) || "arrived".equals(status)))
                    || ("stop".equals(pendingAction) && !active) || "error".equals(status);
            if (acknowledged) { clearPending(); message = ""; }
            if (active) {
                draft.speed = state.optDouble("speedKmh", draft.speed);
                draft.playback = state.optString("mode", draft.playback);
                if (!wasActive && SimulationService.routePoints != null) {
                    draft.geometry = SimulationService.routePoints;
                    // Preserve user-selected endpoints. A cold service restore has unknown provenance;
                    // conservatively keep those derived coordinates in memory only.
                    if (draft.start == null) { draft.start = draft.geometry[0]; draft.startFromProvider = true; }
                    if (draft.end == null) { draft.end = draft.geometry[draft.geometry.length - 1]; draft.endFromProvider = true; }
                    if (draft.startLabel.isEmpty()) draft.startLabel = coordinates(draft.start);
                    if (draft.endLabel.isEmpty()) draft.endLabel = coordinates(draft.end);
                    draft.distance = state.optDouble("totalMeters");
                    drawRoute(false);
                }
                moveTraveler(state);
            } else if (wasActive) {
                if (motion != null) motion.cancel();
                if (mapSurface != null) mapSurface.clearTraveler();
                visualDistance = Double.NaN;
                drawRoute(false);
            }
            drawRoute(false);
            wasActive = active;
            if ("error".equals(status)) message = state.optString("message", "Playback stopped.");
            render();
        } catch (Exception ignored) { render(); }
    }

    private void render() {
        if (ui == null) return;
        boolean active = SimulationService.active;
        String status = routeFailed ? "route_error" : "idle", shown = message;
        double remaining = draft.distance / (draft.speed / 3.6);
        if (active) try {
            JSONObject state = new JSONObject(SimulationService.state);
            status = state.optString("status", "running");
            shown = state.optString("message", "");
            remaining = Math.max(0, state.optDouble("totalMeters") - state.optDouble("distanceMeters")) / (draft.speed / 3.6);
        } catch (Exception ignored) { }
        boolean canStart = !routeLoading && draft.geometry != null;
        if (!active && pendingAction.isEmpty() && !routeLoading) {
            if (draft.start == null) { status = "select_start"; canStart = true; }
            else if (draft.end == null && !"static".equals(draft.playback)) { status = "select_destination"; canStart = true; }
            else if (draft.geometry == null && "road".equals(draft.kind) && !"static".equals(draft.playback)) {
                if (!online()) { status = "online_required"; canStart = true; }
                else if (!routingConfigured() && !RoutingCredentials.hasSavedPersonalKey(this)) { status = "setup_required"; canStart = true; }
                else if (routeFailed) { status = "route_error"; canStart = true; }
            }
        }
        if (draft.geometry != null && !draft.routeWarning.isEmpty() && !active)
            shown += (shown.isEmpty() ? "" : "\n") + draft.routeWarning;
        if (!active && "AUTH".equals(session.errorCode) && routingConfigured())
            shown += (shown.isEmpty() ? "" : "\n") + "Your key is still saved. Check its Geoapify access restrictions, then retry. Change it only in Settings if needed.";
        if (!active && draft.geometry != null && !draft.choicesMessage.isEmpty())
            shown += (shown.isEmpty() ? "" : "\n") + draft.choicesMessage;
        ui.render(status, shown, draft.startLabel, draft.endLabel, draft.speed, draft.distance, remaining,
                active, !pendingAction.isEmpty(), canStart, draft.travel, draft.playback, draft.kind, routeLoading);
        String[] viaLabels = new String[draft.vias.size()];
        for (int i = 0; i < viaLabels.length; i++) viaLabels[i] = draft.vias.get(i).label;
        ui.setWaypoints(viaLabels, !locked(), "static".equals(draft.playback));
        String[] routeLabels = new String[draft.routes.size()]; double[] distances = new double[routeLabels.length];
        for (int i = 0; i < routeLabels.length; i++) { routeLabels[i] = draft.routes.get(i).label; distances[i] = draft.routes.get(i).distance; }
        ui.setRouteChoices(routeLabels, distances, draft.speed, draft.selectedRoute,
                !active && !routeLoading && "road".equals(draft.kind) && !"static".equals(draft.playback));
        renderAttributions();
        updateHint();
    }
    private void renderAttributions() {
        android.text.SpannableStringBuilder credits = new android.text.SpannableStringBuilder();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        if ("road".equals(draft.kind) && draft.geometry != null && !"static".equals(draft.playback)) {
            credits.append("Powered by Geoapify");
            credits.setSpan(new android.text.style.URLSpan("https://www.geoapify.com/"), 0, credits.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            credits.append(" · "); int begin = credits.length(); credits.append("© OpenStreetMap");
            credits.setSpan(new android.text.style.URLSpan("https://www.openstreetmap.org/copyright"), begin, credits.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            seen.add("Geoapifyhttps://www.geoapify.com/");
            seen.add("OpenStreetMaphttps://www.openstreetmap.org/copyright");
        }
        List<JSONArray> sources = new ArrayList<>(); sources.add(draft.startAttributions);
        if (!"static".equals(draft.playback)) {
            for (Waypoint point : draft.vias) sources.add(point.attributions);
            sources.add(draft.endAttributions);
        }
        for (JSONArray list : sources) for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i); if (item == null) continue;
            String provider = item.optString("provider"); String url = item.optString("providerUri");
            if (provider.isEmpty() || !seen.add(provider + url)) continue;
            credits.append(credits.length() == 0 ? "Place data: " : " · ");
            int begin = credits.length(); credits.append(provider);
            if (url.startsWith("https://") || url.startsWith("http://")) {
                credits.setSpan(new android.text.style.ClickableSpan() {
                    @Override public void onClick(View view) { openExternal(Uri.parse(url)); }
                }, begin, credits.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        ui.setAttributions(credits);
    }

    private void updateHint() {
        if (!online()) ui.setMapHint("Enable map in Settings · coordinates also work");
        else if (!mapError.isEmpty()) ui.setMapHint(mapError);
        else if (!mapLoaded) ui.setMapHint("Loading map…");
        else if (SimulationService.active) ui.setMapHint("Simulated location · " + ("static".equals(draft.playback) ? "Holding" : "Following route"));
        else if (selection >= 2) ui.setMapHint("Tap the map for via point " + (selection - 1));
        else if (selection == 0 || draft.start == null) ui.setMapHint("Tap the map to choose your start");
        else if (selection == 1 || (draft.end == null && !"static".equals(draft.playback))) ui.setMapHint("Tap the map to choose your destination");
        else if ("static".equals(draft.playback)) ui.setMapHint("Tap to move the stationary point");
        else if (routeLoading) ui.setMapHint("Finding a route along roads and paths…");
        else ui.setMapHint("Long press a pin to adjust it");
    }

    @Override public void onSpeed(double speed) {
        if (!Double.isFinite(speed) || speed < .5 || speed > 200 || !pendingAction.isEmpty()) return;
        draft.speed = speed; saveDraft();
        if (SimulationService.active) {
            try { startService(new Intent(this, SimulationService.class).setAction("speed").putExtra("speedKmh", speed)); }
            catch (RuntimeException error) { message = "Couldn't change the pace. Try again."; }
        }
        render();
    }
    @Override public void onPreset(String mode, double speed) {
        if (locked()) return;
        if (!"walk".equals(mode) && !"cycle".equals(mode) && !"drive".equals(mode)) return;
        boolean changed = !draft.travel.equals(mode);
        draft.travel = mode; draft.speed = speed; haptic(); saveDraft();
        if (changed && "road".equals(draft.kind) && !"static".equals(draft.playback)) refreshRoute(); else render();
    }

    @Override public void onOptions() {
        if (locked()) return;
        String[] choices = {"Road route", "Direct line", "One way · hold destination", "Back and forth", "Stay at start", "Swap start and destination", "Clear route"};
        new AlertDialog.Builder(this).setTitle("Route options").setItems(choices, (dialog, index) -> {
            if (locked()) return;
            haptic();
            String previousKind = draft.kind, previousPlayback = draft.playback;
            switch (index) {
                case 0: draft.kind = "road"; if ("static".equals(draft.playback)) draft.playback = "once"; break;
                case 1: draft.kind = "direct"; if ("static".equals(draft.playback)) draft.playback = "once"; break;
                case 2: draft.playback = "once"; break;
                case 3: draft.playback = "pingpong"; break;
                case 4: draft.playback = "static"; break;
                case 5:
                    double[] p = draft.start; draft.start = draft.end; draft.end = p;
                    String label = draft.startLabel; draft.startLabel = draft.endLabel; draft.endLabel = label;
                    boolean from = draft.startFromProvider; draft.startFromProvider = draft.endFromProvider; draft.endFromProvider = from;
                    JSONArray attribution = draft.startAttributions; draft.startAttributions = draft.endAttributions; draft.endAttributions = attribution;
                    java.util.Collections.reverse(draft.vias); updateViaCoordinates(draft);
                    break;
                case 6: draft.start = draft.end = null; draft.startLabel = draft.endLabel = ""; draft.startAttributions = new JSONArray(); draft.endAttributions = new JSONArray(); draft.vias.clear(); updateViaCoordinates(draft); break;
                default: return;
            }
            selection = -1; saveDraft();
            boolean samePath = ((index == 2 || index == 3) && !"static".equals(previousPlayback))
                    || ((index == 0 || index == 1) && previousKind.equals(draft.kind)
                        && previousPlayback.equals(draft.playback));
            // Back-and-forth playback reverses the same geometry without a new route request.
            if (samePath) { if (draft.geometry != null) setGeometry(draft, draft.geometry); render(); }
            else refreshRoute();
        }).show();
    }

    @Override public void onPoint(boolean destination) {
        if (locked()) return;
        new PointPicker(destination, null).show();
    }

    @Override public void onAddVia() {
        if (locked() || "static".equals(draft.playback)) return;
        if (draft.vias.size() >= 6) { toast("You can add up to six via points."); return; }
        new PointPicker(false, null, draft.vias.size()).show();
    }

    @Override public void onViaPoint(int index) {
        if (locked() || index < 0 || index >= draft.vias.size()) return;
        List<String> labels = new ArrayList<>(); List<Integer> actions = new ArrayList<>();
        labels.add("Change place"); actions.add(0);
        if (index > 0) { labels.add("Move earlier"); actions.add(1); }
        if (index < draft.vias.size() - 1) { labels.add("Move later"); actions.add(2); }
        labels.add("Remove via point"); actions.add(3);
        new AlertDialog.Builder(this).setTitle("Via " + (index + 1) + " · " + draft.vias.get(index).label)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (locked()) return;
                    int action = actions.get(which);
                    if (action == 0) { new PointPicker(false, null, index).show(); return; }
                    if (action == 1) java.util.Collections.swap(draft.vias, index, index - 1);
                    if (action == 2) java.util.Collections.swap(draft.vias, index, index + 1);
                    if (action == 3) draft.vias.remove(index);
                    selection = -1; updateViaCoordinates(draft); haptic(); saveDraft(); refreshRoute();
                }).show();
    }

    /** Native, keyboard-friendly place search inspired by Jolly UI's labeled search field. */
    private final class PointPicker {
        final boolean destination;
        final int viaIndex;
        final String restoredQuery;
        final LinearLayout content = new LinearLayout(MainActivity.this), results = new LinearLayout(MainActivity.this);
        final EditText input = new EditText(MainActivity.this);
        final TextView status = text("", 13);
        final LinearLayout searchBody = new LinearLayout(MainActivity.this);
        final ScrollView bodyScroll = new ScrollView(MainActivity.this);
        final SearchGlyph clear = new SearchGlyph("clear", "Clear search");
        final ProgressBar progress = new ProgressBar(MainActivity.this);
        long generation;
        boolean closed, routingAccessRejected;
        Runnable debounce;
        AlertDialog dialog;
        PointPicker(boolean destination, String query) { this(destination, query, -1); }
        PointPicker(boolean destination, String query, int viaIndex) { this.destination = destination; restoredQuery = query; this.viaIndex = viaIndex; }
        void show() {
            if (pointDialog != null) pointDialog.dismiss();
            currentPicker = this;
            content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(12), dp(20), dp(4));
            content.setBackground(searchShape(Color.WHITE, 24, 0));
            LinearLayout heading = new LinearLayout(MainActivity.this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(viaIndex >= 0 ? "Choose via point " + (viaIndex + 1) : destination ? "Choose destination" : "Choose start", 21);
            title.setTextColor(Color.rgb(24, 42, 38));
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            SearchGlyph close = new SearchGlyph("clear", "Close place search");
            close.setBackground(searchRipple(24)); close.setOnClickListener(v -> dialog.dismiss());
            heading.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
            content.addView(heading);
            TextView label = text("Place or address", 12);
            label.setTextColor(Color.rgb(93, 111, 106)); label.setPadding(0, dp(8), 0, dp(8));
            input.setId(View.generateViewId()); label.setLabelFor(input.getId()); content.addView(label);
            LinearLayout field = new LinearLayout(MainActivity.this); field.setGravity(Gravity.CENTER_VERTICAL);
            field.setBackground(searchShape(Color.rgb(249, 251, 250), 14, Color.rgb(218, 226, 222)));
            SearchGlyph search = new SearchGlyph("search", null);
            field.addView(search, new LinearLayout.LayoutParams(dp(42), dp(52)));
            input.setSingleLine(true); input.setHint("Search or enter coordinates"); input.setTextSize(15);
            input.setTextColor(Color.rgb(24, 42, 38)); input.setHintTextColor(Color.rgb(108, 124, 119));
            input.setBackgroundColor(Color.TRANSPARENT); input.setPadding(0, 0, 0, 0);
            input.setSelectAllOnFocus(false); input.setSaveEnabled(false);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            input.setOnFocusChangeListener((v, focus) -> field.setBackground(searchShape(
                    Color.rgb(249, 251, 250), 14, focus ? Color.rgb(21, 125, 112) : Color.rgb(218, 226, 222))));
            field.addView(input, new LinearLayout.LayoutParams(0, dp(54), 1));
            clear.setBackground(searchRipple(24)); clear.setOnClickListener(v -> { input.setText(""); input.requestFocus(); });
            field.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(54)));
            content.addView(field, new LinearLayout.LayoutParams(-1, dp(56)));
            // Keep the field and close control visible while the body scrolls above the keyboard.
            searchBody.setOrientation(LinearLayout.VERTICAL); bodyScroll.setFillViewport(false);
            bodyScroll.setClipToPadding(false); bodyScroll.addView(searchBody);
            content.addView(bodyScroll, new LinearLayout.LayoutParams(-1, -2, 1));
            LinearLayout statusRow = new LinearLayout(MainActivity.this); statusRow.setGravity(Gravity.CENTER_VERTICAL);
            statusRow.setPadding(0, dp(12), 0, dp(12));
            progress.setIndeterminateTintList(ColorStateList.valueOf(Color.rgb(21, 125, 112)));
            LinearLayout.LayoutParams spinner = new LinearLayout.LayoutParams(dp(16), dp(16)); spinner.setMarginEnd(dp(8));
            statusRow.addView(progress, spinner);
            status.setTextColor(Color.rgb(93, 111, 106)); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1)); searchBody.addView(statusRow);
            results.setOrientation(LinearLayout.VERTICAL);
            searchBody.addView(results, new LinearLayout.LayoutParams(-1, -2));
            if (mapSurface != null && mapSurface.ready()) {
                LinearLayout chooseMap = new LinearLayout(MainActivity.this); chooseMap.setGravity(Gravity.CENTER_VERTICAL);
                chooseMap.setMinimumHeight(dp(52)); chooseMap.setBackground(searchRipple(12));
                chooseMap.setFocusable(true); chooseMap.setContentDescription("Choose point on map");
                chooseMap.addView(new SearchGlyph("pin", null), new LinearLayout.LayoutParams(dp(40), dp(48)));
                TextView mapLabel = text("Choose on map", 15); mapLabel.setTextColor(Color.rgb(21, 125, 112));
                chooseMap.addView(mapLabel, new LinearLayout.LayoutParams(-2, -2));
                chooseMap.setOnClickListener(v -> { haptic(); selection = viaIndex >= 0 ? viaIndex + 2 : destination ? 1 : 0; updateHint(); dialog.dismiss(); });
                searchBody.addView(chooseMap, new LinearLayout.LayoutParams(-1, -2));
            }
            TextView attribution = text("Powered by Geoapify · OpenStreetMap", 10);
            attribution.setTextColor(Color.rgb(112, 127, 122));
            attribution.setPadding(0, dp(12), 0, dp(8)); searchBody.addView(attribution);
            dialog = new AlertDialog.Builder(MainActivity.this).setView(content).setPositiveButton("Use coordinates", null).create();
            pointDialog = dialog;
            dialog.setOnDismissListener(d -> {
                closed = true; generation++; if (debounce != null) handler.removeCallbacks(debounce);
                network.cancel("search");
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
                if (pointDialog == dialog) pointDialog = null;
                if (currentPicker == this) currentPicker = null;
            });
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            dialog.show();
            dialog.getWindow().setBackgroundDrawable(searchShape(Color.WHITE, 24, 0));
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(520)), -2);
            Button use = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            use.setAllCaps(false); use.setTextSize(14); use.setMinHeight(dp(48));
            use.setOnClickListener(v -> useTypedPoint());
            input.setOnEditorActionListener((v, action, event) -> {
                if (action != EditorInfo.IME_ACTION_SEARCH && action != EditorInfo.IME_ACTION_DONE) return false;
                if (parseCoordinates(input.getText().toString()) != null) useTypedPoint();
                else {
                    scheduleSearch(input.getText().toString());
                    if (debounce != null) { handler.removeCallbacks(debounce); debounce.run(); }
                }
                return true;
            });
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) { scheduleSearch(value.toString()); }
                @Override public void afterTextChanged(Editable value) { }
            });
            double[] existing = viaIndex >= 0 ? viaIndex < draft.vias.size() ? draft.vias.get(viaIndex).point : null : destination ? draft.end : draft.start;
            String initial = restoredQuery != null ? restoredQuery : existing == null ? "" : coordinates(existing);
            input.setText(initial); input.setSelection(input.length()); input.requestFocus();
            if (initial.isEmpty()) scheduleSearch("");
        }
        void useTypedPoint() {
            double[] point = parseCoordinates(input.getText().toString());
            if (point != null) {
                Waypoint previous = viaIndex >= 0 ? viaIndex < draft.vias.size() ? draft.vias.get(viaIndex) : null
                        : destination ? draft.end == null ? null : new Waypoint(draft.end, draft.endLabel, draft.endFromProvider)
                        : draft.start == null ? null : new Waypoint(draft.start, draft.startLabel, draft.startFromProvider);
                if (previous != null && previous.fromProvider
                        && Math.abs(previous.point[0] - point[0]) <= .000006
                        && Math.abs(previous.point[1] - point[1]) <= .000006) {
                    JSONArray credits = viaIndex >= 0 ? previous.attributions : destination ? draft.endAttributions : draft.startAttributions;
                    acceptPoint(previous.point, previous.label, true, credits);
                } else acceptPoint(point, "", false, null);
                dialog.dismiss();
            }
            else if (!online()) { dialog.dismiss(); showOnlineChoice(); }
            else if (!routingConfigured() || routingAccessRejected) { dialog.dismiss(); showRoutingSetup(); }
        }
        void acceptPoint(double[] point, String label, boolean fromProvider, JSONArray credits) {
            if (viaIndex >= 0) setViaPoint(viaIndex, point, label, fromProvider, credits);
            else {
                setPoint(destination, point, label, fromProvider);
                if (credits != null) { if (destination) draft.endAttributions = credits; else draft.startAttributions = credits; }
            }
        }
        void showStatus(CharSequence value, boolean loading) {
            status.setText(value); progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        void scheduleSearch(String query) {
            generation++; network.cancel("search");
            if (debounce != null) { handler.removeCallbacks(debounce); debounce = null; }
            results.removeAllViews(); results.setVisibility(View.GONE);
            clear.setVisibility(query.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            routingAccessRejected = false; input.setError(null);
            boolean coordinates = parseCoordinates(query) != null;
            Button use = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            use.setText(coordinates ? "Use coordinates" : !online() ? "Enable search" : "Set up routing");
            use.setVisibility(coordinates || !online() || !routingConfigured() ? View.VISIBLE : View.GONE);
            use.setEnabled(coordinates || !online() || !routingConfigured());
            if (coordinates) { showStatus(getText(R.string.search_coordinates_ready), false); return; }
            if (query.matches("[+\\-0-9.,\\s]+")) { showStatus(getText(R.string.search_coordinate_hint), false); return; }
            if (!online()) { showStatus(getText(R.string.search_offline), false); return; }
            if (!routingConfigured()) { showStatus(getText(R.string.search_setup_needed), false); return; }
            if (query.trim().length() < 3) { showStatus(getText(R.string.search_minimum), false); return; }
            showStatus(getText(R.string.search_loading), true);
            long expected = generation;
            debounce = () -> {
                debounce = null;
                try {
                    JSONObject request = new JSONObject().put("type", "search").put("id", String.valueOf(++sequence))
                            .put("query", query.trim());
                    network.request(request.toString(), response -> runOnUiThread(() -> {
                        if (closed || expected != generation || isDestroyed()) return;
                        showSearchResponse(response);
                    }));
                } catch (Exception invalid) { showStatus(getText(R.string.search_failure), false); }
            };
            handler.postDelayed(debounce, 600);
        }
        void showSearchResponse(JSONObject response) {
            if (!response.optBoolean("ok")) {
                showStatus(response.optString("error", "Search failed. Edit your search to retry."), false);
                String code = response.optString("errorCode");
                if ("AUTH".equals(code) || "CONFIGURATION".equals(code)) {
                    routingAccessRejected = true;
                    Button use = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    use.setText(R.string.routing_access_update); use.setVisibility(View.VISIBLE); use.setEnabled(true);
                }
                return;
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
            JSONArray list = response.optJSONArray("data");
            results.removeAllViews();
            if (list != null) for (int i = 0; i < list.length(); i++) {
                JSONObject place = list.optJSONObject(i); if (place == null) continue;
                String label = place.optString("label").trim();
                if (label.isEmpty() || !validPoint(new double[]{place.optDouble("lat", Double.NaN), place.optDouble("lon", Double.NaN)})) continue;
                LinearLayout item = new LinearLayout(MainActivity.this); item.setGravity(Gravity.CENTER_VERTICAL);
                item.setMinimumHeight(dp(68)); item.setPadding(0, dp(10), dp(8), dp(10));
                item.setBackground(searchRipple(12)); item.setFocusable(true); item.setContentDescription(label);
                item.addView(new SearchGlyph("pin", null), new LinearLayout.LayoutParams(dp(38), dp(40)));
                LinearLayout address = new LinearLayout(MainActivity.this); address.setOrientation(LinearLayout.VERTICAL);
                int comma = label.indexOf(','); String primary = comma > 0 ? label.substring(0, comma).trim() : label;
                TextView name = text(primary, 15); name.setTextColor(Color.rgb(24, 42, 38));
                name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END); address.addView(name);
                if (comma > 0 && comma < label.length() - 1) {
                    TextView detail = text(label.substring(comma + 1).trim(), 12); detail.setTextColor(Color.rgb(93, 111, 106));
                    detail.setMaxLines(2); detail.setEllipsize(android.text.TextUtils.TruncateAt.END); detail.setPadding(0, dp(3), 0, 0); address.addView(detail);
                }
                item.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
                item.setOnClickListener(v -> {
                    double[] point = {place.optDouble("lat", Double.NaN), place.optDouble("lon", Double.NaN)};
                    if (!validPoint(point)) { showStatus(getText(R.string.place_no_coordinates), false); return; }
                    JSONArray credits = place.optJSONArray("attributions");
                    acceptPoint(point, label, true, credits);
                    renderAttributions(); dialog.dismiss();
                });
                results.addView(item, new LinearLayout.LayoutParams(-1, -2));
            }
            int count = results.getChildCount();
            showStatus(count == 0 ? "No places found. Try an address, nearby landmark or map point." : "Search results", false);
            results.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
            bodyScroll.scrollTo(0, 0);
        }
    }

    private GradientDrawable searchShape(int fill, int corner, int border) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(corner));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }
    private RippleDrawable searchRipple(int corner) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(26, 21, 125, 112)),
                searchShape(Color.TRANSPARENT, corner, 0), searchShape(Color.WHITE, corner, 0));
    }
    /** Small code-drawn icons keep search controls crisp at every screen density. */
    private final class SearchGlyph extends View {
        final String kind;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path pin = new android.graphics.Path();
        SearchGlyph(String kind, String description) {
            super(MainActivity.this); this.kind = kind;
            if (description != null) { setContentDescription(description); setFocusable(true); }
            else setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND); paint.setColor(Color.rgb(93, 111, 106));
        }
        @Override public CharSequence getAccessibilityClassName() { return isClickable() ? Button.class.getName() : View.class.getName(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if ("clear".equals(kind)) {
                canvas.drawLine(cx - dp(5), cy - dp(5), cx + dp(5), cy + dp(5), paint);
                canvas.drawLine(cx - dp(5), cy + dp(5), cx + dp(5), cy - dp(5), paint);
            } else if ("search".equals(kind)) {
                canvas.drawCircle(cx - dp(2), cy - dp(2), dp(6), paint);
                canvas.drawLine(cx + dp(3), cy + dp(3), cx + dp(8), cy + dp(8), paint);
            } else {
                pin.reset();
                pin.moveTo(cx, cy + dp(9));
                pin.cubicTo(cx - dp(12), cy - dp(2), cx - dp(6), cy - dp(10), cx, cy - dp(10));
                pin.cubicTo(cx + dp(6), cy - dp(10), cx + dp(12), cy - dp(2), cx, cy + dp(9));
                canvas.drawPath(pin, paint); canvas.drawCircle(cx, cy - dp(3), dp(2), paint);
            }
        }
    }

    @Override public void onSettings() {
        ArrayList<String> choices = new ArrayList<>();
        choices.add(online() ? "Turn off online planning" : "Enable map and routes");
        choices.add("Routing access"); choices.add("Mock location setup");
        choices.add("Notifications and compatibility"); choices.add("Privacy and licenses");
        if (online() && !mapError.isEmpty()) choices.add("Reload map");
        new AlertDialog.Builder(this).setTitle("Settings").setItems(choices.toArray(new String[0]), (dialog, which) -> {
            switch (which) {
                case 0: if (online()) setOnline(false); else showOnlineChoice(); break;
                case 1: showRoutingSetup(); break;
                case 2: showSetup(); break;
                case 3: openExternal(Uri.parse("package:" + getPackageName()), Settings.ACTION_APPLICATION_DETAILS_SETTINGS); break;
                case 4: showPrivacy(); break;
                case 5: setupMap(null); break;
                default: break;
            }
        }).show();
    }
    private void showOnlineChoice() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle("Enable map and routes?")
                .setMessage("OpenFreeMap receives the map areas you view. Geoapify receives your place searches and selected route points when you add routing access. Providers receive network metadata. The app does not read your actual GPS location.\n\nMovement runs on your phone. You can also use direct paths and coordinates offline.")
                .setPositiveButton("Enable", (dialog, which) -> setOnline(true))
                .setNegativeButton("Coordinates only", (dialog, which) -> {
                    if (!locked()) { draft.kind = "direct"; draft.geometry = null; saveDraft(); }
                    setOnline(false);
                }).show();
    }
    private void setOnline(boolean enabled) {
        settings().edit().putBoolean("online", enabled).putInt("openMapConsentVersion", 1).apply();
        network.cancel("all"); ++session.generation; session.loading = false; routeLoading = false;
        if (pointDialog != null) pointDialog.dismiss();
        setupMap(null);
        if (!locked() && draft.geometry == null) refreshRoute(); else render();
    }
    private void showRoutingSetup() {
        if (isFinishing() || isDestroyed()) return;
        if (routingDialog != null) routingDialog.dismiss();
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(10), dp(22), dp(6));
        boolean configured = routingConfigured();
        TextView description = text(configured
                ? "Your saved Geoapify key is reused for every place search and route. You do not need to paste it again when changing points.\n\nAn access error can mean the provider rejected the request. Check the error and your Geoapify project restrictions before replacing the key."
                : "Add your Geoapify API key once to search places and calculate road routes. Create a project at myprojects.geoapify.com, then copy its API key here. Usage counts toward your provider plan.", 14);
        content.addView(description);
        TextView state = text(configured ? "Key saved · reused automatically"
                : RoutingCredentials.hasSavedPersonalKey(this) ? "A key is saved, but Android secure storage could not read it. Retry or replace it here." : "No routing key saved yet.", 13);
        state.setPadding(0, dp(12), 0, dp(4)); content.addView(state);
        EditText key = new EditText(this); key.setHint("Geoapify API key"); key.setSingleLine(true);
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        content.addView(key, new LinearLayout.LayoutParams(-1, dp(56)));
        key.setVisibility(configured ? View.GONE : View.VISIBLE);
        if (configured) {
            Button replace = new Button(this); replace.setText(R.string.routing_key_replace); replace.setAllCaps(false);
            replace.setOnClickListener(v -> {
                key.setVisibility(View.VISIBLE); key.requestFocus(); replace.setVisibility(View.GONE);
                if (routingDialog != null) routingDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.routing_key_save);
            }); content.addView(replace);
        }
        if (configured || RoutingCredentials.hasSavedPersonalKey(this)) {
            Button clear = new Button(this); clear.setText(R.string.routing_key_remove); clear.setAllCaps(false);
            clear.setOnClickListener(v -> {
                RoutingCredentials.clear(this); key.setText("");
                key.setVisibility(View.VISIBLE); clear.setVisibility(View.GONE);
                if (routingDialog != null) routingDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.routing_key_save);
                state.setText(routingConfigured() ? "Using the publisher's configured access." : "Saved routing key removed.");
                network.cancel("all"); ++session.generation; session.loading = false; routeLoading = false;
                session.errorCode = "";
                if (!locked() && draft.geometry == null) refreshRoute(); else render();
            }); content.addView(clear);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Routing access").setView(content)
                .setPositiveButton(configured ? "Done" : "Save key", null).setNegativeButton("Close", null)
                .setNeutralButton("Get free key", (d, w) -> openExternal(Uri.parse("https://myprojects.geoapify.com/"))).create();
        routingDialog = dialog;
        dialog.setOnDismissListener(d -> { key.setText(""); if (routingDialog == dialog) routingDialog = null; });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = key.getText().toString().trim();
            if (value.isEmpty() && routingConfigured()) { dialog.dismiss(); return; }
            if (!RoutingCredentials.valid(value)) { key.setError("Paste the API key from your Geoapify project."); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            key.setEnabled(false);
            for (int i = 0; i < content.getChildCount(); i++)
                if (content.getChildAt(i) instanceof Button) content.getChildAt(i).setEnabled(false);
            state.setText(R.string.routing_key_saving);
            Context app = getApplicationContext();
            PlanningSession savingSession = session;
            Thread saver = new Thread(() -> {
                boolean saved;
                try { RoutingCredentials.save(app, value); saved = true; } catch (Exception unavailable) { saved = false; }
                final boolean success = saved;
                savingSession.main.post(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        if (!success) { state.setText(R.string.routing_key_save_failure);
                            key.setEnabled(true);
                            for (int i = 0; i < content.getChildCount(); i++)
                                if (content.getChildAt(i) instanceof Button) content.getChildAt(i).setEnabled(true);
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); return; }
                        key.setText(""); dialog.dismiss();
                    }
                    MainActivity owner = savingSession.owner;
                    if (!success || savingSession.closed || owner == null || owner.isDestroyed() || owner.isFinishing()) return;
                    savingSession.errorCode = ""; owner.haptic();
                    if (!owner.online()) owner.showOnlineChoice(); else if (!owner.locked()) owner.refreshRoute(); else owner.render();
                });
            }, "Save-routing-access"); saver.start();
        });
    }
    @SuppressWarnings("deprecation") private boolean mockReady() {
        try { return ((AppOpsManager) getSystemService(APP_OPS_SERVICE)).checkOpNoThrow("android:mock_location", Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED; }
        catch (RuntimeException error) { return false; }
    }
    private void showSetup() {
        new AlertDialog.Builder(this).setTitle("Allow location simulation")
                .setMessage("1. Open Settings → About phone and tap Build number seven times to enable Developer options.\n\n2. Open Developer options → Select mock location app → Moving Traveler.\n\n3. Return here and start your journey. Other apps decide whether to accept mock locations.")
                .setPositiveButton("Developer options", (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
                    catch (RuntimeException error) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                }).setNegativeButton("Close", null).show();
    }
    private void showPrivacy() {
        new AlertDialog.Builder(this).setTitle("Privacy and licenses")
                .setMessage("Moving Traveler does not retrieve your actual GPS location. Approximate-location permission enables optional Google Play Services mock-location delivery.\n\nOpenFreeMap provides the map. Geoapify receives searches and selected route points directly when routing access is configured. They can process network metadata and keep logs under their policies. Turning online planning off stops new map, search and route requests.\n\nManual coordinate drafts stay on your device. Search results and road geometry remain in memory for this session. Your saved routing key is encrypted with Android Keystore. There are no added ads or analytics.\n\nApplication code is GPL-3.0-or-later with a Google Play Services linking permission. MapLibre and other libraries retain their own licenses.")
                .setPositiveButton("Close", null).setNegativeButton("Privacy policy", (d, w) -> {
                    if (BuildConfig.PUBLIC_PRIVACY_URL.startsWith("https://")) openExternal(Uri.parse(BuildConfig.PUBLIC_PRIVACY_URL));
                    else toast("The publisher's public privacy policy must be configured before release.");
                }).setNeutralButton("More", (d, w) -> new AlertDialog.Builder(this).setTitle("Providers and source")
                        .setItems(new String[]{"Geoapify privacy", "OpenFreeMap privacy", "Open source licenses", "Application source", "App terms"}, (dialog, index) -> {
                            if (index == 0) openExternal(Uri.parse("https://www.geoapify.com/privacy-policy/"));
                            if (index == 1) openExternal(Uri.parse("https://openfreemap.org/privacy/"));
                            if (index == 2) showLicenses();
                            if (index == 3) {
                                if (BuildConfig.PUBLIC_SOURCE_URL.startsWith("https://")) openExternal(Uri.parse(BuildConfig.PUBLIC_SOURCE_URL));
                                else toast("See the included source archive. The publisher must add a public source URL before release.");
                            }
                            if (index == 4) {
                                if (BuildConfig.PUBLIC_TERMS_URL.startsWith("https://")) openExternal(Uri.parse(BuildConfig.PUBLIC_TERMS_URL));
                                else toast("The publisher's public terms must be configured before release.");
                            }
                        }).show()).show();
    }
    private void showLicenses() {
        String licenses;
        try (java.io.InputStream in = getAssets().open("THIRD-PARTY-NOTICES.txt")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n; while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            licenses = bytes.toString("UTF-8");
        } catch (Exception ignored) { licenses = "See the corresponding source archive for complete licenses."; }
        TextView body = text(licenses, 13); body.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Open source licenses").setView(scroll).setPositiveButton("Close", null)
                .setNeutralButton("Library licenses", (dialog, which) -> LicenseViewer.show(this)).show();
    }
    private TextView text(String value, int size) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(Color.rgb(49,65,61)); return text;
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void openExternal(Uri uri) { openExternal(uri, Intent.ACTION_VIEW); }
    private void openExternal(Uri uri, String action) {
        try { startActivity(new Intent(action, uri)); } catch (RuntimeException error) { toast("No app is available to open this."); }
    }

    private Draft restoreDraft() {
        Draft value = new Draft();
        try {
            JSONObject json = new JSONObject(getSharedPreferences("nativeDraft", 0).getString("value", "{}"));
            value.start = savedPoint(json.optJSONArray("start")); value.end = savedPoint(json.optJSONArray("end"));
            value.startLabel = value.start == null ? "" : coordinates(value.start);
            value.endLabel = value.end == null ? "" : coordinates(value.end);
            value.speed = Math.max(.5, Math.min(200, json.optDouble("speed", 5)));
            if (!Double.isFinite(value.speed)) value.speed = 5;
            String travel = json.optString("travel", "walk");
            if ("walk".equals(travel) || "cycle".equals(travel) || "drive".equals(travel)) value.travel = travel;
            String playback = json.optString("playback", "once");
            if ("once".equals(playback) || "pingpong".equals(playback) || "static".equals(playback)) value.playback = playback;
            value.kind = "direct".equals(json.optString("kind")) ? "direct" : "road";
            String preference = json.optString("routePreference", "balanced");
            if ("balanced".equals(preference) || "short".equals(preference) || "less_maneuvers".equals(preference)) value.routePreference = preference;
            JSONArray vias = json.optJSONArray("vias");
            if (vias != null) {
                if (vias.length() > 6) throw new IllegalArgumentException("Too many via points");
                for (int i = 0; i < vias.length(); i++) {
                    double[] point = savedPoint(vias.optJSONArray(i));
                    if (point == null) throw new IllegalArgumentException("Invalid via point");
                    value.vias.add(new Waypoint(point, coordinates(point), false));
                }
                updateViaCoordinates(value);
            }
        } catch (Exception ignored) {
            // A damaged via list must not silently turn into a direct start-to-end plan.
            value.start = value.end = null; value.startLabel = value.endLabel = "";
            value.vias.clear(); updateViaCoordinates(value);
        }
        return value;
    }
    private double[] savedPoint(JSONArray array) {
        if (array == null || array.length() != 2) return null;
        double[] p = {array.optDouble(0), array.optDouble(1)}; return validPoint(p) ? p : null;
    }
    private void saveDraft() {
        try {
            JSONObject json = new JSONObject().put("speed", draft.speed).put("travel", draft.travel)
                    .put("playback", draft.playback).put("kind", draft.kind).put("routePreference", draft.routePreference);
            boolean providerVia = false;
            for (Waypoint point : draft.vias) providerVia |= point.fromProvider;
            // Provider-derived points stay in memory. Never restore a route with a via silently missing.
            if (!providerVia) {
                if (draft.start != null && !draft.startFromProvider) json.put("start", new JSONArray(draft.start));
                if (draft.end != null && !draft.endFromProvider) json.put("end", new JSONArray(draft.end));
                json.put("vias", new JSONArray(draft.viaCoordinates));
            }
            getSharedPreferences("nativeDraft", 0).edit().putString("value", json.toString()).apply();
        } catch (Exception ignored) { }
    }
    static double[] parseGeoUri(Uri uri) {
        if (uri == null || !"geo".equals(uri.getScheme())) return null;
        String raw = uri.getEncodedSchemeSpecificPart();
        int question = raw.indexOf('?');
        if (question >= 0) {
            for (String pair : raw.substring(question + 1).split("&")) {
                int equals = pair.indexOf('=');
                if (equals >= 0 && "q".equals(Uri.decode(pair.substring(0, equals)))) {
                    String query = Uri.decode(pair.substring(equals + 1)).replace('+', ' ');
                    int label = query.indexOf('('); if (label >= 0) query = query.substring(0, label);
                    return parseCoordinates(query);
                }
            }
        }
        return parseCoordinates(Uri.decode(raw.split("[?;]", 2)[0]));
    }
    private void handleGeoIntent() {
        Uri uri = getIntent().getData();
        if (uri == null || !"geo".equals(uri.getScheme()) || locked()) return;
        getIntent().setData(null);
        double[] point = parseGeoUri(uri);
        if (point != null) setPoint(false, point, "", false);
        else toast("Choose the shared place by name or enter its coordinates.");
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleGeoIntent(); }
    private void handleBack() {
        if (selection >= 0) { selection = -1; updateHint(); } else moveTaskToBack(true);
    }
    @SuppressWarnings("deprecation") @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }
    @Override public Object onRetainNonConfigurationInstance() {
        Retained value = new Retained(); value.session = session; value.plan = pendingPlan;
        value.sheetExpanded = ui == null || ui.isSheetExpanded();
        value.selection = selection;
        if (currentPicker != null) { value.pickerDestination = currentPicker.destination; value.pickerQuery = currentPicker.input.getText().toString(); value.pickerVia = currentPicker.viaIndex; }
        value.permissionsPending = permissionsPending; value.explanation = permissionExplanation;
        return value;
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("sheetExpanded", ui == null || ui.isSheetExpanded());
        state.putIntArray("mapInsets", mapInsets.clone());
        if (mapSurface != null) { Bundle mapState = new Bundle(); mapSurface.saveState(mapState); state.putBundle("map", mapState); }
    }
    @Override protected void onStart() {
        super.onStart(); started = true;
        if (mapSurface != null) mapSurface.start();
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (!registered) {
            IntentFilter filter = new IntentFilter(SimulationService.CHANGE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, RECEIVER_NOT_EXPORTED); else registerReceiver(updates, filter);
            registered = true;
        }
        if (mapSurface != null) mapSurface.resume();
        readState();
        if (pendingPlan != null) handler.post(this::dispatchStart);
    }
    @Override protected void onPause() {
        resumed = false;
        if (ui != null) ui.cancelSheetGesture();
        if (registered) { unregisterReceiver(updates); registered = false; }
        if (motion != null) motion.cancel();
        visualDistance = Double.NaN;
        if (mapSurface != null) mapSurface.pause();
        super.onPause();
    }
    @Override protected void onStop() {
        started = false;
        if (mapSurface != null) mapSurface.stop();
        super.onStop();
    }
    @Override public void onLowMemory() { super.onLowMemory(); if (mapSurface != null) mapSurface.lowMemory(); }
    @Override protected void onDestroy() {
        if (pointDialog != null) pointDialog.dismiss();
        if (permissionDialog != null) permissionDialog.dismiss();
        if (routingDialog != null) routingDialog.dismiss();
        handler.removeCallbacksAndMessages(null);
        if (session != null) {
            if (session.owner == this) session.owner = null;
            if (!isChangingConfigurations()) { session.closed = true; session.network.close(); session.main.removeCallbacksAndMessages(null); }
        }
        if (ui != null) destroyMap();
        super.onDestroy();
    }
}
