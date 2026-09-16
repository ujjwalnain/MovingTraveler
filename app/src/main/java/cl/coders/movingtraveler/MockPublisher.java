// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
// LocationManager mechanism adapted from FakeTraveler by Matias Castillo Felmer.
package cl.coders.movingtraveler;

import android.content.Context;
import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Publishes explicitly marked Android test locations; never conceals mock status. */
final class MockPublisher {
    private static final AtomicLong OWNER = new AtomicLong();
    private final long owner = OWNER.incrementAndGet();
    private final LocationManager manager;
    private final Context context;
    private final List<String> providers = new ArrayList<>();
    private FusedLocationProviderClient fused;
    private boolean fusedReady, fusedPending, closed;
    private String fusedStatus = "Unavailable on this device";
    private final Runnable changed;

    MockPublisher(Context context, Runnable changed) {
        this.context = context;
        this.changed = changed;
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("WrongConstant") // Criteria values equal API31 ProviderProperties constants and support API23.
    void start() {
        try {
            for (String name : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                manager.addTestProvider(name, false, false, false, false, true, true, true,
                        Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
                providers.add(name);
                manager.setTestProviderEnabled(name, true);
            }
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    manager.addTestProvider(LocationManager.FUSED_PROVIDER, false, false, false,
                            false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
                    providers.add(LocationManager.FUSED_PROVIDER);
                    manager.setTestProviderEnabled(LocationManager.FUSED_PROVIDER, true);
                } catch (IllegalArgumentException ignored) { /* OEM may not expose a fused provider. */ }
            }
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                fusedStatus = "Unavailable: allow approximate location in app permissions for fused compatibility";
            } else if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
                fused = LocationServices.getFusedLocationProviderClient(context);
                fusedStatus = "Connecting";
                try { fused.setMockMode(true).addOnSuccessListener(unused -> {
                    if (closed) {
                        if (OWNER.get() == owner) disableFused();
                        return;
                    }
                    fusedReady = true;
                    fusedStatus = "Active";
                    changed.run();
                }).addOnFailureListener(e -> {
                    if (closed) return;
                    fusedStatus = "Unavailable: " + e.getClass().getSimpleName();
                    changed.run();
                }); } catch (SecurityException e) {
                    fusedStatus = "Unavailable: Google Play Services rejected mock mode";
                }
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    void push(RouteEngine.Sample s) {
        if (closed) throw new IllegalStateException("Simulation has stopped");
        for (String provider : providers) manager.setTestProviderLocation(provider, fix(provider, s));
        if (fusedReady && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            fusedReady = false;
            fusedStatus = "Unavailable: location permission was revoked";
            changed.run();
        }
        if (fusedReady && !fusedPending && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedPending = true;
            try { fused.setMockLocation(fix(LocationManager.GPS_PROVIDER, s)).addOnCompleteListener(task -> {
                fusedPending = false;
                if (!task.isSuccessful() && !closed) {
                    fusedReady = false;
                    fusedStatus = "Update failed; using Android providers";
                    disableFused();
                    changed.run();
                }
            }); } catch (SecurityException e) {
                fusedPending = false; fusedReady = false;
                fusedStatus = "Unavailable: location permission was revoked";
                changed.run();
            }
        }
    }

    private Location fix(String provider, RouteEngine.Sample sample) {
        Location l = new Location(provider);
        l.setLatitude(sample.latitude);
        l.setLongitude(sample.longitude);
        l.setAccuracy(5f);
        l.setAltitude(0);
        l.setSpeed((float) sample.speedMps);
        l.setBearing((float) sample.bearingDegrees);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (Build.VERSION.SDK_INT >= 26) {
            l.setSpeedAccuracyMetersPerSecond(0.2f);
            l.setBearingAccuracyDegrees(2f);
            l.setVerticalAccuracyMeters(5f);
        }
        return l;
    }

    List<String> providers() { return new ArrayList<>(providers); }
    String fusedStatus() { return fusedStatus; }

    void close() {
        if (closed) return;
        closed = true;
        for (String provider : providers) {
            try { manager.removeTestProvider(provider); } catch (RuntimeException ignored) { }
        }
        providers.clear();
        if (fused != null && OWNER.get() == owner) disableFused();
        else context.getSharedPreferences("settings", 0).edit().putBoolean("staleMock", false).apply();
        fusedReady = false;
    }

    private void disableFused() {
        try {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            fused.setMockMode(false).addOnSuccessListener(unused -> {
                if (OWNER.get() == owner && closed) context.getSharedPreferences("settings", 0).edit().putBoolean("staleMock", false).apply();
            });
        } catch (RuntimeException ignored) { /* Keep stale marker so next launch retries cleanup. */ }
    }

    static void recoverStale(Context context) {
        if (!context.getSharedPreferences("settings", 0).getBoolean("staleMock", false)) return;
        long generation = OWNER.get();
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        for (String name : new String[]{"gps", "network", "fused"}) {
            try { manager.removeTestProvider(name); } catch (RuntimeException ignored) { }
        }
        try {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
                LocationServices.getFusedLocationProviderClient(context).setMockMode(false).addOnSuccessListener(unused -> {
                    if (OWNER.get() == generation) context.getSharedPreferences("settings", 0).edit().putBoolean("staleMock", false).apply();
                });
            } else context.getSharedPreferences("settings", 0).edit().putBoolean("staleMock", false).apply();
        } catch (RuntimeException ignored) { }
    }
}
