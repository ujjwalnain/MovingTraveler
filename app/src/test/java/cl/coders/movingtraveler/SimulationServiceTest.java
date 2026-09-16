// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.Looper;
import android.os.PowerManager;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowLocationManager;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowService;

/**
 * Service lifecycle regression tests, with a recording LocationManager boundary.
 * Robolectric 4.16.1 does not implement Android's test-provider methods. The small
 * shadow below records those calls; it does not emulate GPS delivery, app-op
 * authorization, Play Services, another application's behavior, or process death.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class,
        shadows = SimulationServiceTest.RecordingLocationManager.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SimulationServiceTest {
    private static final String LONG_ROUTE =
            "{\"points\":[[0,0],[0,0.02]],\"speedKmh\":36,\"mode\":\"once\"}";
    private ServiceController<SimulationService> controller;
    private SimulationService service;
    private boolean destroyed;
    private int startId;

    @Before public void resetSession() {
        RecordingLocationManager.resetRecording();
        SimulationService.active = false;
        SimulationService.state = "{\"status\":\"idle\"}";
        SimulationService.stagedPlan = null;
        SimulationService.routePoints = null;
        application().getSharedPreferences("settings", 0).edit().clear().commit();
    }

    @After public void cleanupSession() {
        if (controller != null && !destroyed) controller.destroy();
        SimulationService.active = false;
        SimulationService.stagedPlan = null;
        SimulationService.routePoints = null;
    }

    @Test public void startConsumesStagedRouteAndPublishesCompleteLocationPayload() throws Exception {
        start(LONG_ROUTE);

        assertTrue(SimulationService.active);
        assertNull("The route is handed off only once", SimulationService.stagedPlan);
        assertNotNull(SimulationService.routePoints);
        assertEquals("running", state().getString("status"));
        assertEquals(2, state().getJSONArray("providers").length());
        assertTrue(state().getString("fusedStatus").contains("Unavailable"));
        assertTrue(application().getSharedPreferences("settings", 0).getBoolean("staleMock", false));

        Location gps = latest(LocationManager.GPS_PROVIDER);
        assertEquals(0, gps.getLatitude(), 0);
        assertEquals(0, gps.getLongitude(), 0);
        assertEquals(10, gps.getSpeed(), 0.00001);
        assertEquals(90, gps.getBearing(), 0.00001);
        assertTrue(gps.hasAccuracy());
        assertTrue(gps.hasAltitude());
        assertTrue(gps.hasSpeed());
        assertTrue(gps.hasBearing());
        assertTrue(gps.getTime() > 0);
        assertTrue(gps.getElapsedRealtimeNanos() >= 0);
        assertNotNull(latest(LocationManager.NETWORK_PROVIDER));

        ShadowService shadow = shadowOf(service);
        assertNotNull(shadow.getLastForegroundNotification());
        assertTrue(shadow.isLastForegroundNotificationAttached());
        Notification notification = shadow.getLastForegroundNotification();
        assertEquals("Stop", notification.actions[notification.actions.length - 1].title.toString());
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld());
    }

    @Test public void ticksUseElapsedTimeAndPauseResumeDoesNotCatchUpPausedTime() throws Exception {
        start(LONG_ROUTE);
        Location first = latest(LocationManager.GPS_PROVIDER);
        elapse(5);
        assertEquals(50, state().getDouble("distanceMeters"), 0.00001);
        Location moving = latest(LocationManager.GPS_PROVIDER);
        assertTrue(moving.getTime() > first.getTime());
        assertTrue(moving.getElapsedRealtimeNanos() - first.getElapsedRealtimeNanos() >= 5_000_000_000L);

        command("pause");
        elapse(20);
        assertEquals("paused", state().getString("status"));
        assertEquals(50, state().getDouble("distanceMeters"), 0.00001);
        assertEquals(0, latest(LocationManager.GPS_PROVIDER).getSpeed(), 0);

        command("resume");
        elapse(5);
        assertEquals("running", state().getString("status"));
        assertEquals(100, state().getDouble("distanceMeters"), 0.00001);

        command(new Intent().setAction("speed").putExtra("speedKmh", 72d));
        elapse(3);
        assertEquals(72, state().getDouble("speedKmh"), 0);
        assertEquals(160, state().getDouble("distanceMeters"), 0.00001);
        assertEquals(20, latest(LocationManager.GPS_PROVIDER).getSpeed(), 0.00001);
    }

    @Test public void arrivalHoldsDestinationWithFreshZeroSpeedFixesUntilStop() throws Exception {
        start("{\"points\":[[0,0],[0,0.0001]],\"speedKmh\":36,\"mode\":\"once\"}");
        elapse(2);
        assertEquals("arrived", state().getString("status"));
        assertEquals(1, state().getDouble("progress"), 0);
        Location arrived = latest(LocationManager.GPS_PROVIDER);
        assertEquals(0.0001, arrived.getLongitude(), 0.000000001);
        assertEquals(0, arrived.getSpeed(), 0);

        elapse(5);
        Location held = latest(LocationManager.GPS_PROVIDER);
        assertTrue(SimulationService.active);
        assertEquals(arrived.getLatitude(), held.getLatitude(), 0);
        assertEquals(arrived.getLongitude(), held.getLongitude(), 0);
        assertTrue(held.getElapsedRealtimeNanos() > arrived.getElapsedRealtimeNanos());
        command("stop");
        assertEquals("idle", state().getString("status"));
    }

    @Test public void staticModeKeepsPublishingStationaryFixes() throws Exception {
        start("{\"points\":[[51.5,-0.12]],\"speedKmh\":5,\"mode\":\"static\"}");
        elapse(30);
        assertTrue(SimulationService.active);
        assertEquals("running", state().getString("status"));
        assertEquals(51.5, latest(LocationManager.GPS_PROVIDER).getLatitude(), 0);
        assertEquals(-0.12, latest(LocationManager.GPS_PROVIDER).getLongitude(), 0);
        assertEquals(0, latest(LocationManager.GPS_PROVIDER).getSpeed(), 0);
        assertEquals("Holding selected location", state().getString("message"));
    }

    @Test public void stopRemovesProvidersReleasesWakeLockAndCancelsTicks() throws Exception {
        start(LONG_ROUTE);
        PowerManager.WakeLock lock = ShadowPowerManager.getLatestWakeLock();
        elapse(2);
        command("stop");
        int writesAtStop = RecordingLocationManager.writeCount;

        assertFalse(SimulationService.active);
        assertNull(SimulationService.routePoints);
        assertEquals("idle", state().getString("status"));
        assertTrue(RecordingLocationManager.registered.isEmpty());
        assertFalse(lock.isHeld());
        assertTrue(shadowOf(service).isStoppedBySelf());
        assertTrue(shadowOf(service).isForegroundStopped());
        assertFalse(application().getSharedPreferences("settings", 0).getBoolean("staleMock", true));
        elapse(60);
        assertEquals("Stopped sessions cannot keep publishing", writesAtStop, RecordingLocationManager.writeCount);
        command("stop");
        assertFalse(lock.isHeld());
    }

    @Test public void orderlyDestructionCleansAnActiveSession() throws Exception {
        start(LONG_ROUTE);
        PowerManager.WakeLock lock = ShadowPowerManager.getLatestWakeLock();
        controller.destroy();
        destroyed = true;
        int writesAtDestroy = RecordingLocationManager.writeCount;

        assertFalse(SimulationService.active);
        assertNull(SimulationService.routePoints);
        assertEquals("idle", state().getString("status"));
        assertTrue(RecordingLocationManager.registered.isEmpty());
        assertFalse(lock.isHeld());
        elapse(10);
        assertEquals(writesAtDestroy, RecordingLocationManager.writeCount);
    }

    @Test public void malformedRouteStopsWithoutProvidersAndPreservesErrorOnDestroy() throws Exception {
        start("{\"points\":[[100,0],[0,0]],\"speedKmh\":36,\"mode\":\"once\"}");
        assertFalse(SimulationService.active);
        assertNull(SimulationService.stagedPlan);
        assertNull(SimulationService.routePoints);
        assertEquals("error", state().getString("status"));
        assertTrue(RecordingLocationManager.registered.isEmpty());
        assertEquals(0, RecordingLocationManager.writeCount);
        assertTrue(shadowOf(service).isStoppedBySelf());

        controller.destroy();
        destroyed = true;
        assertEquals("error", state().getString("status"));
    }

    @Test public void providerRegistrationFailureCleansEarlierRegistrations() throws Exception {
        RecordingLocationManager.denyProvider = LocationManager.NETWORK_PROVIDER;
        start(LONG_ROUTE);
        assertFalse(SimulationService.active);
        assertEquals("error", state().getString("status"));
        assertTrue(state().getString("message").contains("Developer options"));
        assertTrue(RecordingLocationManager.registered.isEmpty());
        assertTrue(RecordingLocationManager.removed.contains(LocationManager.GPS_PROVIDER));
        assertTrue(shadowOf(service).isForegroundStopped());
        assertNull(SimulationService.routePoints);
    }

    @Test public void failedLocationUpdateStopsAndReleasesResources() throws Exception {
        start(LONG_ROUTE);
        PowerManager.WakeLock lock = ShadowPowerManager.getLatestWakeLock();
        RecordingLocationManager.failUpdates = true;
        elapse(1);
        assertFalse(SimulationService.active);
        assertEquals("error", state().getString("status"));
        assertTrue(RecordingLocationManager.registered.isEmpty());
        assertFalse(lock.isHeld());
        int writesAfterFailure = RecordingLocationManager.writeCount;
        elapse(10);
        assertEquals(writesAfterFailure, RecordingLocationManager.writeCount);
    }

    @Test public void invalidSpeedUpdateFailsClosed() throws Exception {
        start(LONG_ROUTE);
        PowerManager.WakeLock lock = ShadowPowerManager.getLatestWakeLock();
        command(new Intent().setAction("speed").putExtra("speedKmh", Double.NaN));
        assertEquals("error", state().getString("status"));
        assertFalse(SimulationService.active);
        assertTrue(RecordingLocationManager.registered.isEmpty());
        assertFalse(lock.isHeld());
    }

    @Test public void missingIntentDoesNotRestartAStagedOrPreviousRoute() {
        controller = Robolectric.buildService(SimulationService.class).create();
        service = controller.get();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1));
        assertFalse(SimulationService.active);
        assertTrue(shadowOf(service).isStoppedBySelf());
        assertEquals(0, RecordingLocationManager.writeCount);
    }

    @Test @Config(sdk = 35)
    public void android15UsesSpecialUseForegroundTypeAndPlatformFusedProvider() throws Exception {
        start(LONG_ROUTE);
        assertTrue(SimulationService.active);
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.getForegroundServiceType());
        assertEquals(3, state().getJSONArray("providers").length());
        assertNotNull(latest(LocationManager.FUSED_PROVIDER));
        command("stop");
        assertFalse(SimulationService.active);
        assertTrue(RecordingLocationManager.registered.isEmpty());
        assertTrue(shadowOf(service).isForegroundStopped());
    }

    private void start(String plan) {
        SimulationService.stagedPlan = plan;
        controller = Robolectric.buildService(SimulationService.class,
                new Intent(application(), SimulationService.class).setAction("start"));
        service = controller.get();
        controller.create().startCommand(0, ++startId);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void command(String action) { command(new Intent().setAction(action)); }

    @SuppressWarnings("deprecation")
    private void command(Intent intent) {
        controller.withIntent(intent.setClass(application(), SimulationService.class))
                .startCommand(0, ++startId);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void elapse(long seconds) {
        shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS);
    }

    private JSONObject state() throws Exception { return new JSONObject(SimulationService.state); }

    private static Application application() { return RuntimeEnvironment.getApplication(); }

    private static Location latest(String provider) {
        Location location = RecordingLocationManager.locations.get(provider);
        assertNotNull("Expected a published payload for " + provider, location);
        return new Location(location);
    }

    /**
     * Narrow recording double for methods missing from Robolectric's standard
     * LocationManager shadow. Assertions concern calls made by our service only.
     */
    @Implements(LocationManager.class)
    public static final class RecordingLocationManager extends ShadowLocationManager {
        static final Set<String> registered = new LinkedHashSet<>();
        static final Set<String> removed = new LinkedHashSet<>();
        static final Map<String, Location> locations = new LinkedHashMap<>();
        static String denyProvider;
        static boolean failUpdates;
        static int writeCount;

        @Implementation protected void addTestProvider(String name, boolean network,
                boolean satellite, boolean cell, boolean cost, boolean altitude,
                boolean speed, boolean bearing, int power, int accuracy) {
            if (name.equals(denyProvider)) throw new SecurityException("Test app-op denial");
            registered.add(name);
        }

        @Implementation protected void setTestProviderEnabled(String name, boolean enabled) {
            if (!registered.contains(name)) throw new IllegalArgumentException("Unregistered test provider");
        }

        @Implementation protected void setTestProviderLocation(String name, Location location) {
            if (failUpdates) throw new SecurityException("Test permission revoked");
            if (!registered.contains(name)) throw new IllegalArgumentException("Unregistered test provider");
            locations.put(name, new Location(location));
            writeCount++;
        }

        @Implementation protected void removeTestProvider(String name) {
            registered.remove(name);
            removed.add(name);
            locations.remove(name);
        }

        @Resetter public static void resetRecording() {
            registered.clear();
            removed.clear();
            locations.clear();
            denyProvider = null;
            failUpdates = false;
            writeCount = 0;
        }
    }
}
