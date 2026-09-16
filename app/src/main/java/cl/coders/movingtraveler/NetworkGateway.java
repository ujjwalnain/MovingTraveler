// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Geoapify routing and search client. Each lane is independently cancellable and
 * latest-request-wins. Geometry is preserved, never connected by an invented
 * segment. API keys, request URLs, coordinates and responses are not logged or cached.
 */
final class NetworkGateway {
    interface Callback { void accept(JSONObject response); }
    interface Transport {
        HttpResult get(URL endpoint, Cancellation cancellation) throws Exception;
    }

    static final long REQUEST_TIMEOUT_MILLIS = 20_000;
    static final int MAX_RESPONSE_BYTES = 2_000_000;
    static final int MAX_WAYPOINTS = 8;
    static final int MAX_ROUTE_CHOICES = 3;
    // App quality guard, not a provider coverage claim. Users can move a pin closer
    // to a mapped road/path instead of unexpectedly starting over a kilometre away.
    static final double MAX_SNAP_METERS = 1000;
    private final BooleanSupplier online;
    private final Supplier<String> keys;
    private final Transport transport;
    private final long timeoutMillis;
    private final Map<String, Lane> lanes = new HashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            runnable -> daemon(runnable, "Geoapify-request-deadlines"));
    private boolean closed;

    NetworkGateway(Context context) {
        this(() -> context.getApplicationContext().getSharedPreferences("settings", 0)
                        .getBoolean("online", false),
                () -> RoutingCredentials.get(context.getApplicationContext()),
                new HttpTransport(), REQUEST_TIMEOUT_MILLIS);
    }

    /** Test seam. Fixtures never contact a provider or consume API credits. */
    NetworkGateway(BooleanSupplier online, Supplier<String> keys,
            Transport transport, long timeoutMillis) {
        if (timeoutMillis < 1) throw new IllegalArgumentException("Invalid request deadline");
        this.online = online;
        this.keys = keys;
        this.transport = transport;
        this.timeoutMillis = timeoutMillis;
        for (String type : new String[] {"route", "search"}) lanes.put(type, new Lane(type));
    }

    void request(String raw, Callback callback) {
        if (callback == null) return;
        JSONObject request;
        try {
            if (raw == null || raw.length() > 64_000) return;
            request = new JSONObject(raw);
            if (!(request.opt("id") instanceof String) || request.getString("id").length() > 120
                    || request.getString("id").isEmpty()) return;
        } catch (JSONException invalid) { return; }
        String type = request.optString("type");
        Lane lane = lanes.get(type);
        synchronized (this) {
            if (closed) return;
            if (lane == null) {
                callback.accept(response(request, null,
                        new UserError("INVALID_REQUEST", "Choose a result from place search again.")));
                return;
            }
            Job job = new Job(request, callback, lane, timeoutMillis);
            if (lane.current != null) lane.current.cancel();
            lane.executor.purge();
            lane.current = job;
            job.deadline = deadlines.schedule(() -> expire(job), timeoutMillis, TimeUnit.MILLISECONDS);
            job.future = lane.executor.submit(() -> run(job));
        }
    }

    /** Route/search cancel independently; "all" cancels both. Unknown lanes are harmless. */
    void cancel(String channel) {
        synchronized (this) {
            if ("all".equals(channel)) {
                for (Lane lane : lanes.values()) cancelLane(lane);
            } else {
                Lane lane = lanes.get(channel);
                if (lane != null) cancelLane(lane);
            }
        }
    }

    private void cancelLane(Lane lane) {
        if (lane.current != null) lane.current.cancel();
        lane.current = null;
        lane.executor.purge();
    }

    private void run(Job job) {
        try {
            Object data = "route".equals(job.lane.type) ? fetchRoutes(job)
                    : fetch(job.request, keys.get(), job);
            job.cancellation.check();
            requireOnline();
            complete(job, data, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (CancellationException ignored) {
            // A newer request or explicit cancellation owns this lane now.
        } catch (UserError invalid) {
            complete(job, null, invalid);
        } catch (SocketTimeoutException timeout) {
            completeWithPartial(job, timedOut());
        } catch (Exception failure) {
            // Never expose exception messages: HTTP exceptions can contain the key-bearing URL.
            complete(job, null, new UserError("NETWORK",
                    "Couldn't reach the routing service. Check your connection and try again."));
        }
    }

    private Object fetch(JSONObject request, String key, Job job) throws Exception {
        job.cancellation.check();
        requireOnline();
        URL endpoint = endpoint(request, key);
        job.cancellation.check();
        requireOnline();
        HttpResult result = transport.get(endpoint, job.cancellation);
        job.cancellation.check();
        requireOnline();
        if (result.status < 200 || result.status >= 300) {
            throw httpError(result.status, result.body, request);
        }
        return parseData(request, result.body);
    }

    /**
     * Geoapify documents preference types, but not an alternatives request parameter.
     * These are separate, billable, bounded requests for real provider routes. A local
     * choice between returned routes never invokes this method again.
     */
    private JSONObject fetchRoutes(Job job) throws Exception {
        String key = keys.get();
        endpoint(job.request, key); // Validate the complete request before the first call.
        String[] preferences = job.request.optBoolean("routeChoices", false)
                ? "drive".equals(travelMode(job.request))
                    ? new String[]{"balanced", "short", "less_maneuvers"}
                    : new String[]{"balanced", "short"}
                : new String[]{routePreference(job.request)};
        JSONArray routes = new JSONArray();
        String choicesMessage = "";
        for (String preference : preferences) {
            JSONObject request = new JSONObject(job.request.toString()).put("preference", preference);
            try {
                JSONObject route = (JSONObject) fetch(request, key, job);
                if (!containsGeometry(routes, route.getJSONArray("points"))) routes.put(route);
                // Published snapshots are immutable; the deadline thread can safely return one.
                job.partialData = routeEnvelope(routes, choicesMessage);
            } catch (UserError error) {
                if (routes.length() == 0 || "OFFLINE".equals(error.code)) throw error;
                choicesMessage = auxiliaryMessage(error.code);
                job.partialData = routeEnvelope(routes, choicesMessage);
                if (!("NO_ROUTE".equals(error.code) || "INVALID_RESPONSE".equals(error.code)
                        || "SNAP_TOO_FAR".equals(error.code))) break;
            } catch (SocketTimeoutException timeout) {
                if (routes.length() == 0) throw timeout;
                return routeEnvelope(routes, auxiliaryMessage("TIMEOUT"));
            } catch (InterruptedException | CancellationException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                if (routes.length() == 0) throw failure;
                return routeEnvelope(routes, auxiliaryMessage("NETWORK"));
            }
        }
        return routeEnvelope(routes, choicesMessage);
    }

    private static JSONObject routeEnvelope(JSONArray routes, String choicesMessage) throws JSONException {
        if (routes.length() < 1 || routes.length() > MAX_ROUTE_CHOICES) throw new JSONException("Invalid route count");
        JSONObject output = new JSONObject();
        JSONObject first = routes.getJSONObject(0);
        for (Iterator<String> keys = first.keys(); keys.hasNext();) {
            String key = keys.next(); output.put(key, first.get(key));
        }
        JSONArray snapshot = new JSONArray();
        for (int i = 0; i < routes.length(); i++) snapshot.put(routes.getJSONObject(i));
        return output.put("routes", snapshot).put("choicesMessage", choicesMessage);
    }

    private static String auxiliaryMessage(String code) {
        if ("AUTH".equals(code)) return "A route is ready, but extra choices were rejected. Check routing access to try again.";
        if ("QUOTA".equals(code)) return "A route is ready. The service limit prevented additional choices.";
        if ("TIMEOUT".equals(code)) return "A route is ready. Additional choices took too long to load.";
        return "A route is ready. Some additional choices could not be loaded.";
    }

    private static boolean containsGeometry(JSONArray routes, JSONArray candidate) throws JSONException {
        for (int r = 0; r < routes.length(); r++) {
            JSONArray existing = routes.getJSONObject(r).getJSONArray("points");
            int i = 0, j = 0;
            boolean equal = true;
            while (i < existing.length() && j < candidate.length()) {
                JSONArray a = existing.getJSONArray(i), b = candidate.getJSONArray(j);
                if (!samePosition(a, b)) { equal = false; break; }
                do { i++; } while (i < existing.length() && samePosition(a, existing.getJSONArray(i)));
                do { j++; } while (j < candidate.length() && samePosition(b, candidate.getJSONArray(j)));
            }
            if (equal && i == existing.length() && j == candidate.length()) return true;
        }
        return false;
    }

    private void completeWithPartial(Job job, UserError error) {
        JSONObject data = job.partialData;
        if (data != null && online.getAsBoolean()) {
            try {
                data = routeEnvelope(data.getJSONArray("routes"), auxiliaryMessage(error.code));
                complete(job, data, null);
                return;
            } catch (JSONException ignored) { }
        }
        complete(job, null, error);
    }

    private void expire(Job job) {
        synchronized (this) {
            if (closed || job.cancellation.isCancelled() || job.lane.current != job) return;
            job.lane.current = null;
            job.cancel();
            job.lane.executor.purge();
            JSONObject data = job.partialData;
            if (data != null && online.getAsBoolean()) {
                try {
                    data = routeEnvelope(data.getJSONArray("routes"), auxiliaryMessage("TIMEOUT"));
                    job.callback.accept(response(job.request, data, null));
                    return;
                } catch (JSONException ignored) { }
            }
            job.callback.accept(response(job.request, null, timedOut()));
        }
    }

    private void complete(Job job, Object data, UserError error) {
        synchronized (this) {
            if (closed || job.cancellation.isCancelled() || job.lane.current != job) return;
            job.lane.current = null;
            if (job.deadline != null) job.deadline.cancel(false);
            // Callback commitment is serialized with replacement and cancellation.
            job.callback.accept(response(job.request, data, error));
        }
    }

    private void requireOnline() throws UserError {
        if (!online.getAsBoolean()) throw new UserError("OFFLINE",
                "Online maps are off. Enable them to search or request a route.");
    }

    private static UserError timedOut() {
        return new UserError("TIMEOUT", "The routing request timed out. Check your connection and try again.");
    }

    /** Only fixed provider endpoints are reachable; inputs become encoded query values. */
    static URL endpoint(JSONObject request, String rawKey) throws UserError {
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isEmpty()) throw new UserError("CONFIGURATION",
                "Road routing needs your Geoapify key. Add it in Settings to search and plan routes.");
        if (!key.matches("[A-Za-z0-9_-]{16,128}")) throw new UserError("CONFIGURATION",
                "The Geoapify key looks incomplete. Check the key in Settings.");
        try {
            StringBuilder query = new StringBuilder();
            String path;
            switch (request.optString("type")) {
                case "route":
                    path = "/v1/routing";
                    JSONArray points = checkedPoints(request.optJSONArray("points"), 2, MAX_WAYPOINTS);
                    if (request.has("routeChoices") && !(request.opt("routeChoices") instanceof Boolean)) {
                        throw new UserError("INVALID_REQUEST", "Choose valid route options.");
                    }
                    StringBuilder waypoints = new StringBuilder();
                    for (int i = 0; i < points.length(); i++) {
                        if (i > 0) waypoints.append('|');
                        waypoints.append(points.getJSONArray(i).getDouble(0)).append(',')
                                .append(points.getJSONArray(i).getDouble(1));
                    }
                    parameter(query, "waypoints", waypoints.toString());
                    parameter(query, "mode", travelMode(request));
                    parameter(query, "type", routePreference(request));
                    if (points.length() > 2) {
                        parameter(query, "intermediate_waypoint_mode", "through_stop");
                        parameter(query, "optimize_stops", "false");
                    }
                    parameter(query, "units", "metric");
                    parameter(query, "format", "geojson");
                    break;
                case "search":
                    path = "/v1/geocode/autocomplete";
                    String text = checkedText(request.opt("query"), 200, "Enter a place name between 2 and 200 characters.");
                    if (text.length() < 2) throw new UserError("INVALID_REQUEST", "Enter at least two characters to search.");
                    parameter(query, "text", text);
                    parameter(query, "limit", "5");
                    parameter(query, "format", "json");
                    // Worldwide search by default; no implicit IP/device-location bias.
                    parameter(query, "bias", "countrycode:none");
                    break;
                default:
                    throw new UserError("INVALID_REQUEST", "Choose a result from place search again.");
            }
            parameter(query, "apiKey", key);
            return new URL("https://api.geoapify.com" + path + "?" + query);
        } catch (UserError invalid) {
            if ("INVALID_RESPONSE".equals(invalid.code)) {
                throw new UserError("INVALID_REQUEST", invalid.getMessage());
            }
            throw invalid;
        } catch (JSONException | IOException invalid) {
            throw new UserError("INVALID_REQUEST", "Choose valid points or enter a place name again.");
        }
    }

    private static void parameter(StringBuilder query, String name, String value) throws IOException {
        if (query.length() > 0) query.append('&');
        query.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8.name()));
    }

    private static String travelMode(JSONObject request) throws UserError {
        switch (request.optString("travelMode")) {
            case "WALK": return "walk";
            case "BICYCLE": return "bicycle";
            case "DRIVE": return "drive";
            default: throw new UserError("INVALID_REQUEST", "Choose driving, walking or cycling.");
        }
    }

    private static String routePreference(JSONObject request) throws UserError {
        Object value = request.has("preference") ? request.opt("preference") : "balanced";
        if ("balanced".equals(value) || "short".equals(value)) return (String) value;
        if ("less_maneuvers".equals(value) && "drive".equals(travelMode(request))) return (String) value;
        throw new UserError("INVALID_REQUEST", "Choose a supported route preference for this travel mode.");
    }

    private static String preferenceLabel(String preference) {
        return "short".equals(preference) ? "Shortest" : "less_maneuvers".equals(preference) ? "Fewer turns" : "Balanced";
    }

    static Object parseData(String type, String text) throws UserError {
        JSONObject request = new JSONObject();
        try { request.put("type", type); } catch (JSONException ignored) { }
        return parseData(request, text);
    }

    static Object parseData(JSONObject request, String text) throws UserError {
        try {
            if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                throw new UserError("INVALID_RESPONSE", "The routing response was too large. Choose a shorter route.");
            }
            JSONTokener parser = new JSONTokener(text);
            Object value = parser.nextValue();
            if (parser.nextClean() != 0 || !(value instanceof JSONObject)) throw new JSONException("Expected object");
            JSONObject input = (JSONObject) value;
            if (input.optInt("statusCode", 200) >= 400) {
                throw httpError(input.getInt("statusCode"), text, request);
            }
            if ("search".equals(request.optString("type"))) return parseSearch(input);
            if (!"route".equals(request.optString("type"))) throw new JSONException("Unknown response");
            return parseRoute(request, input);
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new UserError("INVALID_RESPONSE", "The service returned unreadable route or place data. Try different points or search again.");
        }
    }

    private static JSONObject parseRoute(JSONObject request, JSONObject input) throws JSONException, UserError {
        if (!"FeatureCollection".equals(input.optString("type"))) throw new JSONException("Expected GeoJSON");
        JSONArray features = input.getJSONArray("features");
        if (features.length() == 0) throw noRoute();
        if (features.length() != 1) throw new JSONException("Unexpected alternatives");
        JSONObject feature = features.getJSONObject(0);
        if (!"Feature".equals(feature.optString("type"))) throw new JSONException("Expected feature");
        JSONObject properties = feature.getJSONObject("properties");
        checkReportedMode(request, input.optJSONObject("properties"));
        checkReportedMode(request, properties);
        checkReportedPreference(request, input.optJSONObject("properties"));
        checkReportedPreference(request, properties);
        JSONArray requested = request.has("points")
                ? checkedPoints(request.optJSONArray("points"), 2, MAX_WAYPOINTS) : null;
        if (properties.has("distance_units") && !"meters".equalsIgnoreCase(properties.optString("distance_units"))) {
            throw new JSONException("Unexpected distance units");
        }
        if (properties.has("units") && !"metric".equals(properties.optString("units"))) {
            throw new JSONException("Unexpected units");
        }
        JSONObject geometry = feature.getJSONObject("geometry");
        JSONArray parts;
        if ("MultiLineString".equals(geometry.optString("type"))) {
            parts = geometry.getJSONArray("coordinates");
        } else if ("LineString".equals(geometry.optString("type"))) {
            parts = new JSONArray().put(geometry.getJSONArray("coordinates"));
        } else throw new JSONException("Expected route lines");
        if (parts.length() == 0 || parts.length() > RouteEngine.MAX_POINTS / 2) throw noRoute();
        if (requested != null && requested.length() > 2 && parts.length() != requested.length() - 1) {
            throw new UserError("INVALID_RESPONSE", "The route did not include every via point in order. Move a point or retry.");
        }
        JSONArray points = new JSONArray();
        JSONArray legWaypoints = new JSONArray();
        JSONArray waypointIndices = new JSONArray().put(0);
        int count = 0;
        for (int partIndex = 0; partIndex < parts.length(); partIndex++) {
            JSONArray part = parts.getJSONArray(partIndex);
            if (part.length() < 2 || (count += part.length()) > RouteEngine.MAX_POINTS) {
                throw new UserError("INVALID_RESPONSE", "This route has too many or too few points. Choose a shorter journey.");
            }
            for (int pointIndex = 0; pointIndex < part.length(); pointIndex++) {
                JSONArray coordinate = part.getJSONArray(pointIndex);
                // GeoJSON positions are longitude first, optionally followed by altitude.
                if (coordinate.length() < 2 || coordinate.length() > 3) throw new JSONException("Invalid position");
                double lon = number(coordinate.opt(0)), lat = number(coordinate.opt(1));
                checkCoordinate(lat, lon);
                if (coordinate.length() == 3) number(coordinate.opt(2));
                JSONArray point = new JSONArray().put(lat).put(lon);
                if (pointIndex == 0 && points.length() > 0) {
                    if (!samePosition(points.getJSONArray(points.length() - 1), point)) {
                        throw new UserError("INVALID_RESPONSE", "The route contains a disconnected section. Move a point or retry; no direct shortcut was added.");
                    }
                    continue;
                }
                points.put(point);
                if (points.length() == 1) legWaypoints.put(point);
            }
            legWaypoints.put(points.getJSONArray(points.length() - 1));
            waypointIndices.put(points.length() - 1);
        }
        double distance = number(properties.opt("distance")), duration = number(properties.opt("time"));
        if (distance < 1 || duration < 0 || distance > 100_000_000 || duration > 100_000_000) throw noRoute();
        double[][] route = new double[points.length()][2];
        for (int i = 0; i < route.length; i++) {
            route[i][0] = points.getJSONArray(i).getDouble(0);
            route[i][1] = points.getJSONArray(i).getDouble(1);
        }
        if (new RouteEngine(route, 5, "once").current().totalMeters < 1) {
            throw new UserError("NO_ROUTE", "These points resolve to the same position. Choose another destination.");
        }
        JSONArray start = points.getJSONArray(0), end = points.getJSONArray(points.length() - 1);
        JSONObject output = new JSONObject().put("points", points).put("distanceMeters", distance)
                .put("durationSeconds", duration).put("snappedStart", start).put("snappedEnd", end)
                .put("attributions", providerCredits(null)).put("preference", routePreference(request))
                .put("label", preferenceLabel(routePreference(request)));
        if (requested != null) {
            JSONArray snapped = requested.length() > 2 ? legWaypoints : new JSONArray().put(start).put(end);
            JSONArray indices = requested.length() > 2 ? waypointIndices : new JSONArray().put(0).put(points.length() - 1);
            JSONArray snaps = new JSONArray();
            for (int i = 0; i < requested.length(); i++) {
                double snap = separation(requested.getJSONArray(i), snapped.getJSONArray(i));
                if (snap > MAX_SNAP_METERS) {
                    String pointName = i == 0 ? "The starting point" : i == requested.length() - 1
                            ? "The destination" : "Via point " + i;
                    throw new UserError("SNAP_TOO_FAR", pointName
                            + " is over 1 km from the returned route. Move the pin closer to a road or path for this travel mode.");
                }
                snaps.put(snap);
            }
            output.put("startSnapMeters", snaps.getDouble(0)).put("endSnapMeters", snaps.getDouble(snaps.length() - 1))
                    .put("snappedWaypoints", snapped).put("waypointSnapMeters", snaps).put("waypointIndices", indices);
        } else {
            output.put("snappedWaypoints", legWaypoints).put("waypointIndices", waypointIndices);
        }
        return output;
    }

    private static void checkReportedPreference(JSONObject request, JSONObject properties) throws UserError {
        if (properties == null) return;
        if (properties.has("type") && !routePreference(request).equals(properties.opt("type"))) {
            throw new UserError("INVALID_RESPONSE", "The service returned a different route preference. Please retry.");
        }
        if (request.optJSONArray("points") != null && request.optJSONArray("points").length() > 2) {
            if (properties.has("intermediate_waypoint_mode")
                    && !"through_stop".equals(properties.opt("intermediate_waypoint_mode"))) {
                throw new UserError("INVALID_RESPONSE", "The service changed how via points are visited. Please retry.");
            }
            if (properties.has("optimize_stops") && !Boolean.FALSE.equals(properties.opt("optimize_stops"))) {
                throw new UserError("INVALID_RESPONSE", "The service changed the via-point order. Please retry.");
            }
        }
    }

    private static void checkReportedMode(JSONObject request, JSONObject properties) throws UserError {
        if (properties == null || !properties.has("mode")) return;
        String mode = checkedText(properties.opt("mode"), 32, "Invalid travel mode in route response.");
        if (!("drive".equals(mode) || "walk".equals(mode) || "bicycle".equals(mode))
                || (request.has("travelMode") && !travelMode(request).equals(mode))) {
            throw new UserError("INVALID_RESPONSE", "The service returned a different travel mode. Choose your mode and retry.");
        }
    }

    private static JSONArray parseSearch(JSONObject input) throws JSONException, UserError {
        JSONArray results = input.getJSONArray("results");
        if (results.length() > 5) throw new JSONException("Too many suggestions");
        JSONArray output = new JSONArray();
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.getJSONObject(i);
            double lat = number(result.opt("lat")), lon = number(result.opt("lon"));
            checkCoordinate(lat, lon);
            String label = checkedText(result.opt("formatted"), 1024, "Invalid place response.");
            String placeId = result.has("place_id")
                    ? checkedText(result.opt("place_id"), 1024, "Invalid place response.")
                    : "coordinate:" + UUID.nameUUIDFromBytes((lat + "," + lon + ":" + label).getBytes(StandardCharsets.UTF_8));
            output.put(new JSONObject().put("placeId", placeId).put("label", label)
                    .put("lat", lat).put("lon", lon).put("attributions", providerCredits(result.opt("datasource"))));
        }
        return output;
    }

    private static JSONArray providerCredits(Object rawSource) throws JSONException, UserError {
        JSONArray output = new JSONArray()
                .put(new JSONObject().put("provider", "Geoapify").put("providerUri", "https://www.geoapify.com/"))
                .put(new JSONObject().put("provider", "© OpenStreetMap contributors")
                        .put("providerUri", "https://www.openstreetmap.org/copyright"));
        if (rawSource == null || rawSource == JSONObject.NULL) return output;
        if (!(rawSource instanceof JSONObject)) throw new JSONException("Invalid data source");
        JSONObject source = (JSONObject) rawSource;
        if (!source.has("attribution")) return output;
        String provider = checkedText(source.opt("attribution"), 512, "Invalid place attribution.");
        String link = source.has("url") ? checkedText(source.opt("url"), 2048, "Invalid place attribution.") : "";
        if (!link.isEmpty()) {
            URI uri = URI.create(link);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new JSONException("Invalid provider URL");
        }
        if (!(provider.equals("© OpenStreetMap contributors") && link.equals("https://www.openstreetmap.org/copyright"))) {
            output.put(new JSONObject().put("provider", provider).put("providerUri", link));
        }
        return output;
    }

    private static JSONArray checkedPoints(JSONArray input, int minimum, int maximum)
            throws JSONException, UserError {
        if (input == null || input.length() < minimum || input.length() > maximum) {
            throw new UserError("INVALID_REQUEST", "Choose a start, a destination and no more than six via points.");
        }
        JSONArray output = new JSONArray();
        for (int i = 0; i < input.length(); i++) {
            JSONArray point = input.getJSONArray(i);
            if (point.length() != 2) throw new UserError("INVALID_REQUEST", "Invalid map coordinate.");
            double lat = number(point.opt(0)), lon = number(point.opt(1));
            checkCoordinate(lat, lon);
            output.put(new JSONArray().put(lat).put(lon));
        }
        return output;
    }

    private static boolean samePosition(JSONArray one, JSONArray two) throws JSONException {
        return one.getDouble(0) == two.getDouble(0) && (one.getDouble(1) == two.getDouble(1)
                || Math.abs(one.getDouble(1) - two.getDouble(1)) == 360);
    }

    private static double separation(JSONArray one, JSONArray two) throws JSONException {
        double lat1 = Math.toRadians(one.getDouble(0)), lat2 = Math.toRadians(two.getDouble(0));
        double dlat = (lat2 - lat1) / 2;
        double dlon = Math.toRadians(two.getDouble(1) - one.getDouble(1)) / 2;
        double a = Math.sin(dlat) * Math.sin(dlat) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dlon) * Math.sin(dlon);
        return RouteEngine.EARTH_RADIUS_METERS * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, a))));
    }

    private static double number(Object value) throws UserError {
        if (!(value instanceof Number)) throw new UserError("INVALID_RESPONSE", "The service returned invalid coordinate or route data.");
        double result = ((Number) value).doubleValue();
        if (Double.isNaN(result) || Double.isInfinite(result)) throw new UserError("INVALID_RESPONSE", "The service returned invalid coordinate or route data.");
        return result;
    }

    private static void checkCoordinate(double lat, double lon) throws UserError {
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new UserError("INVALID_RESPONSE", "Invalid map coordinate.");
    }

    private static String checkedText(Object value, int maximum, String error) throws UserError {
        if (!(value instanceof String)) throw new UserError("INVALID_RESPONSE", error);
        String text = ((String) value).trim();
        if (text.isEmpty() || text.length() > maximum || text.matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw new UserError("INVALID_RESPONSE", error);
        return text;
    }

    private static UserError noRoute() {
        return new UserError("NO_ROUTE", "No route was found for these points and travel mode. Move a point or try another mode.");
    }

    private static UserError httpError(int status, String body, JSONObject request) {
        String type = request.optString("type");
        if (status == 401 || status == 403) return new UserError("AUTH", "Geoapify rejected this key. Check the key and its restrictions in Settings.");
        if (status == 402 || status == 429) return new UserError("QUOTA", "The routing request limit was reached. Wait before retrying or check your Geoapify plan.");
        if (status == 408 || status == 504) return timedOut();
        String message = "";
        try {
            if (body != null && body.length() <= MAX_RESPONSE_BYTES) {
                JSONObject error = new JSONObject(body);
                message = (error.optString("message") + " " + error.optString("error")).toLowerCase(Locale.ROOT);
            }
        } catch (JSONException ignored) { }
        if ("route".equals(type) && status < 500 && message.contains("distance")
                && (message.contains("limit") || message.contains("exceed") || message.contains("maximum")))
            return new UserError("DISTANCE_LIMIT", "This route exceeds the provider's distance limit for this travel mode. Add via points closer together, shorten the trip, or choose another mode. Your saved key is unchanged.");
        if ("route".equals(type) && (status == 404 || status == 422 || message.contains("no route")
                || message.contains("route not found") || message.contains("could not find")
                || message.contains("no path") || message.contains("unreachable"))) return noRoute();
        if (status == 400 || status == 404 || status == 413 || status == 422) return new UserError("INVALID_REQUEST",
                "route".equals(type)
                        ? "WALK".equals(request.optString("travelMode"))
                            ? "The walking route was rejected. Try closer points or add via points; Geoapify's standard walking limit is 100 km between points. Your saved key is unchanged."
                            : "The route was rejected. Try closer points, another travel mode, or via points. Your saved key is unchanged."
                        : "The service could not use this search. Try another place name or address.");
        return new UserError("SERVICE", "The routing service is unavailable right now. Please try again later.");
    }

    private static JSONObject response(JSONObject request, Object data, UserError error) {
        JSONObject result = new JSONObject();
        try {
            result.put("id", request.optString("id"));
            result.put("type", request.optString("type"));
            result.put("ok", error == null);
            if (data != null) result.put("data", data);
            if (error != null) result.put("error", error.getMessage()).put("errorCode", error.code);
        } catch (JSONException ignored) { }
        return result;
    }
    void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (Lane lane : lanes.values()) {
                cancelLane(lane);
                lane.executor.shutdownNow();
            }
            deadlines.shutdownNow();
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class Lane {
        final String type;
        final ThreadPoolExecutor executor;
        Job current;
        Lane(String type) {
            this.type = type;
            executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1), runnable -> daemon(runnable, "Geoapify-" + type));
        }
    }

    private static final class Job {
        final JSONObject request;
        final Callback callback;
        final Lane lane;
        final Cancellation cancellation;
        volatile Future<?> future;
        volatile ScheduledFuture<?> deadline;
        volatile JSONObject partialData;
        Job(JSONObject request, Callback callback, Lane lane, long timeoutMillis) {
            this.request = request;
            this.callback = callback;
            this.lane = lane;
            cancellation = new Cancellation(timeoutMillis);
        }
        void cancel() {
            cancellation.cancel();
            if (future != null) future.cancel(true);
            if (deadline != null) deadline.cancel(false);
        }
    }

    static final class Cancellation {
        private final long deadlineNanos;
        private volatile boolean cancelled;
        private Runnable abort;
        Cancellation(long timeoutMillis) { deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis); }
        boolean isCancelled() { return cancelled; }
        void check() throws SocketTimeoutException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
            remainingMillis();
        }
        long remainingMillis() throws SocketTimeoutException {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remaining <= 0) throw new SocketTimeoutException();
            return remaining;
        }
        void onCancel(Runnable action) {
            boolean runNow;
            synchronized (this) { runNow = cancelled; if (!runNow) abort = action; }
            if (runNow) action.run();
        }
        void clearAbort() { synchronized (this) { abort = null; } }
        void cancel() {
            Runnable action;
            synchronized (this) { cancelled = true; action = abort; abort = null; }
            if (action != null) try { action.run(); } catch (RuntimeException ignored) { }
        }
    }

    static final class HttpResult {
        final int status;
        final String body;
        HttpResult(int status, String body) { this.status = status; this.body = body; }
    }

    static final class HttpTransport implements Transport {
        @Override public HttpResult get(URL endpoint, Cancellation cancellation) throws Exception {
            cancellation.check();
            HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
            cancellation.onCancel(connection::disconnect);
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout((int) Math.min(8000, cancellation.remainingMillis()));
                connection.setReadTimeout((int) Math.min(12000, cancellation.remainingMillis()));
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Cache-Control", "no-store");
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                if (stream == null) return new HttpResult(status, "{}");
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                try (InputStream input = stream) {
                    byte[] buffer = new byte[8192];
                    while (true) {
                        cancellation.check();
                        connection.setReadTimeout((int) Math.min(12000, cancellation.remainingMillis()));
                        int count = input.read(buffer);
                        if (count == -1) break;
                        if (response.size() + count > MAX_RESPONSE_BYTES) throw new UserError("INVALID_RESPONSE", "The routing response was too large. Choose a shorter route.");
                        response.write(buffer, 0, count);
                    }
                }
                return new HttpResult(status, response.toString(StandardCharsets.UTF_8.name()));
            } finally {
                cancellation.clearAbort();
                connection.disconnect();
            }
        }
    }

    static final class UserError extends Exception {
        final String code;
        UserError(String code, String message) { super(message); this.code = code; }
    }
}
