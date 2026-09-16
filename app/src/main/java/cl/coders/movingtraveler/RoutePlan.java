// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class RoutePlan {
    final double[][] points;
    final double speedKmh;
    final String mode;
    final RouteEngine engine;

    RoutePlan(String json) throws JSONException {
        if (json == null || json.length() > 1_500_000) throw new IllegalArgumentException("Route is too large");
        JSONObject o = new JSONObject(json);
        JSONArray a = o.getJSONArray("points");
        if (a.length() < 1 || a.length() > RouteEngine.MAX_POINTS) throw new IllegalArgumentException("Invalid route size");
        points = new double[a.length()][2];
        for (int i = 0; i < a.length(); i++) {
            JSONArray p = a.getJSONArray(i);
            if (p.length() != 2) throw new IllegalArgumentException("Invalid coordinate pair");
            points[i][0] = p.getDouble(0);
            points[i][1] = p.getDouble(1);
        }
        speedKmh = o.getDouble("speedKmh");
        mode = o.optString("mode", "once");
        engine = new RouteEngine(points, speedKmh, mode);
        if (!"static".equals(mode) && engine.current().totalMeters < 1) {
            throw new IllegalArgumentException("Choose points at least one meter apart");
        }
    }
}
