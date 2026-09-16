// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public final class SimulationService extends Service {
    static final String CHANGE = BuildConfig.APPLICATION_ID + ".STATE";
    static volatile String state = "{\"status\":\"idle\"}";
    static volatile boolean active;
    static volatile String stagedPlan;
    static volatile double[][] routePoints;
    private static final String CHANNEL = "route_playback";
    private static final int NOTIFICATION_ID = 42;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RoutePlan plan;
    private MockPublisher publisher;
    private PowerManager.WakeLock wakeLock;
    private long previousTick, notificationAt;
    private long wakeLockAt;
    private double speedKmh;
    private boolean paused, failed;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Location simulation", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Progress and controls for a route you started");
            c.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @android.annotation.SuppressLint("ApplySharedPref") // Persist crash-recovery marker before changing providers.
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if ("stop".equals(action)) { stopPlayback(false, null); return START_NOT_STICKY; }
        try {
            if ("start".equals(action) && !active) {
                failed = false;
                String json = stagedPlan;
                stagedPlan = null;
                plan = new RoutePlan(json);
                routePoints = plan.points;
                speedKmh = plan.speedKmh;
                paused = false;
                Notification notification = notification("Starting location simulation");
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                else startForeground(NOTIFICATION_ID, notification);
                publisher = new MockPublisher(this, this::publishState);
                getSharedPreferences("settings", 0).edit().putBoolean("staleMock", true).commit();
                publisher.start();
                wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + ":route");
                // This user-controlled ongoing session needs monotonic one-second updates even with the screen off.
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(600_000L);
                wakeLockAt = SystemClock.elapsedRealtime();
                active = true;
                previousTick = SystemClock.elapsedRealtimeNanos();
                handler.post(tick);
            } else if (active && ("pause".equals(action) || "resume".equals(action))) {
                advanceNow();
                paused = "pause".equals(action);
                plan.engine.setPaused(paused);
                publisher.push(plan.engine.current());
                publishState();
                updateNotification(true);
            } else if (active && "speed".equals(action)) {
                advanceNow();
                double next = intent.getDoubleExtra("speedKmh", Double.NaN);
                plan.engine.setSpeedKmh(next);
                speedKmh = next;
                publisher.push(plan.engine.current());
                publishState();
                updateNotification(true);
            } else if (!active) stopSelf();
        } catch (Exception e) {
            String message = e instanceof SecurityException
                    ? "Select Moving Traveler as your mock location app in Developer options, then try again."
                    : "Simulation stopped: " + e.getMessage();
            stopPlayback(true, message);
        }
        // A killed process must never silently restart an old mock route.
        return START_NOT_STICKY;
    }

    private void advanceNow() {
        long now = SystemClock.elapsedRealtimeNanos();
        plan.engine.advance(Math.max(0, (now - previousTick) / 1_000_000_000d));
        previousTick = now;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            try {
                advanceNow();
                if (SystemClock.elapsedRealtime() - wakeLockAt >= 60_000L) {
                    wakeLock.acquire(600_000L);
                    wakeLockAt = SystemClock.elapsedRealtime();
                }
                publisher.push(plan.engine.current());
                publishState();
                updateNotification(false);
                handler.postDelayed(this, 1000);
            } catch (RuntimeException e) {
                stopPlayback(true, "Location simulation stopped. Check your mock-app selection in Developer options.");
            }
        }
    };

    private void publishState() {
        if (!active || plan == null) return;
        try {
            RouteEngine.Sample s = plan.engine.current();
            JSONObject o = new JSONObject();
            o.put("status", s.arrived ? "arrived" : paused ? "paused" : "running");
            o.put("lat", s.latitude); o.put("lon", s.longitude);
            o.put("speedKmh", speedKmh); o.put("actualSpeedKmh", s.speedMps * 3.6);
            o.put("bearing", s.bearingDegrees); o.put("progress", s.progress);
            o.put("distanceMeters", s.distanceMeters); o.put("totalMeters", s.totalMeters);
            o.put("mode", plan.mode); o.put("leg", s.leg);
            o.put("providers", new JSONArray(publisher.providers()));
            o.put("fusedStatus", publisher.fusedStatus());
            // Endpoints are enough for restoration. Full route stays off the once-per-second bridge.
            o.put("routeStart", new JSONArray(plan.points[0]));
            o.put("routeEnd", new JSONArray(plan.points[plan.points.length - 1]));
            o.put("message", s.arrived ? "Arrived · holding destination until you stop" : "static".equals(plan.mode) ? "Holding selected location" : "");
            state = o.toString();
            sendBroadcast(new Intent(CHANGE).setPackage(getPackageName()));
        } catch (Exception ignored) { /* Samples have already been validated. */ }
    }

    private void updateNotification(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - notificationAt < 5000) return;
        notificationAt = now;
        RouteEngine.Sample s = plan.engine.current();
        String text = s.arrived ? "Arrived · holding destination" : "static".equals(plan.mode) ? "Holding selected location" : paused ? "Paused · holding current location" : String.format(Locale.US, "%.1f km/h · %.0f%% of route", speedKmh, s.progress * 100);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notification).setContentTitle("Moving Traveler · simulation")
                .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE);
        if (plan != null && !"static".equals(plan.mode) && !plan.engine.current().arrived) {
            b.addAction(new Notification.Action.Builder(null, paused ? "Resume" : "Pause", serviceIntent(paused ? "resume" : "pause", 1)).build());
        }
        b.addAction(new Notification.Action.Builder(null, "Stop", serviceIntent("stop", 2)).build());
        return b.build();
    }

    private PendingIntent serviceIntent(String action, int request) {
        return PendingIntent.getService(this, request, new Intent(this, SimulationService.class).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void stopPlayback(boolean error, String message) {
        active = false;
        failed = error;
        cleanup();
        try {
            state = new JSONObject().put("status", error ? "error" : "idle").put("message", message == null ? "" : message).toString();
        } catch (Exception ignored) { }
        sendBroadcast(new Intent(CHANGE).setPackage(getPackageName()));
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private void cleanup() {
        handler.removeCallbacksAndMessages(null);
        try {
            if (publisher != null) publisher.close();
        } finally {
            publisher = null;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
            routePoints = null;
        }
    }

    @Override public void onDestroy() {
        active = false;
        cleanup();
        if (!failed) state = "{\"status\":\"idle\"}";
        sendBroadcast(new Intent(CHANGE).setPackage(getPackageName()));
        super.onDestroy();
    }
}
