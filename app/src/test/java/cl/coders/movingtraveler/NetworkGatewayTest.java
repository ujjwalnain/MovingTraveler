// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import static org.junit.Assert.*;
import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Provider-shaped fixtures, not live route quality evidence; no API calls or credits. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class NetworkGatewayTest {
    private static final String KEY = "test_fixture_key_not_real_123456";
    private static final String EMPTY_SEARCH = "{\"results\":[]}";
    private static final String SEARCH = "{\"results\":[{\"lat\":51.5,\"lon\":-0.12,\"formatted\":\"Selected address\",\"place_id\":\"fixture-id\"}]}";
    private static final String ROUTE = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
            + "\"properties\":{\"mode\":\"drive\",\"distance\":170,\"distance_units\":\"meters\",\"time\":30},"
            + "\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":[[[0,0],[0.0003,0.0004],[0.0007,0.0004],[0.001,0]]]}}]}";
    private final List<NetworkGateway> gateways = new ArrayList<>();

    @After public void closeGateways() { for (NetworkGateway gateway : gateways) gateway.close(); }

    @Test public void routeDistanceErrorDoesNotAskForAnotherKeyOrLeakProviderDetails() throws Exception {
        NetworkGateway gateway = gateway((url, cancellation) -> new NetworkGateway.HttpResult(400,
                "{\"error\":\"Path distance exceeds the maximum limit: " + KEY + "\"}"));
        JSONObject result = request(gateway, route("long-route"));
        assertEquals("DISTANCE_LIMIT", result.getString("errorCode"));
        assertTrue(result.getString("error").contains("via points"));
        assertTrue(result.getString("error").contains("key is unchanged"));
        assertFalse(result.toString().contains(KEY));
    }

    @Test public void walkingRejectionExplainsPointSpacingInsteadOfSearchOrKeySetup() throws Exception {
        NetworkGateway gateway = gateway((url, cancellation) -> new NetworkGateway.HttpResult(400, "{}"));
        JSONObject request = new JSONObject(route("walking-rejected")).put("travelMode", "WALK");
        JSONObject result = request(gateway, request.toString());
        assertEquals("INVALID_REQUEST", result.getString("errorCode"));
        assertTrue(result.getString("error").contains("100 km"));
        assertFalse(result.getString("error").contains("search terms"));
    }

    @Test public void routeContinuesWhileSearchIsPending() throws Exception {
        CountDownLatch searchEntered = new CountDownLatch(1), releaseSearch = new CountDownLatch(1);
        CountDownLatch routeDone = new CountDownLatch(1);
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<JSONObject> routeResponse = new AtomicReference<>();
        NetworkGateway gateway = gateway((url, cancellation) -> {
            assertEquals("https", url.getProtocol());
            assertEquals("api.geoapify.com", url.getHost());
            assertEquals(KEY, query(url).get("apiKey"));
            sent.add(url.getPath());
            if (url.getPath().endsWith("autocomplete")) {
                searchEntered.countDown();
                releaseSearch.await();
                return ok(EMPTY_SEARCH);
            }
            assertEquals("drive", query(url).get("mode"));
            return ok(ROUTE);
        });
        gateway.request(search("search-1"), ignored -> {});
        await(searchEntered);
        gateway.request(route("route-1"), response -> { routeResponse.set(response); routeDone.countDown(); });
        await(routeDone);
        assertEquals("route-1", routeResponse.get().getString("id"));
        assertTrue(routeResponse.get().getBoolean("ok"));
        assertTrue(sent.contains("/v1/routing") && sent.contains("/v1/geocode/autocomplete"));
        releaseSearch.countDown();
    }

    @Test public void replacingAnInflightRouteCancelsItAndOnlyReturnsTheNewestRoute() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1), firstCancelled = new CountDownLatch(1);
        CountDownLatch newestDone = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<String> responses = Collections.synchronizedList(new ArrayList<>());
        NetworkGateway gateway = gateway((url, cancellation) -> {
            if (calls.incrementAndGet() == 1) {
                cancellation.onCancel(firstCancelled::countDown);
                firstEntered.countDown();
                firstCancelled.await();
            }
            return ok(ROUTE);
        });
        gateway.request(route("old"), response -> responses.add(response.optString("id")));
        await(firstEntered);
        gateway.request(route("new"), response -> { responses.add(response.optString("id")); newestDone.countDown(); });
        await(firstCancelled);
        await(newestDone);
        assertEquals(Collections.singletonList("new"), responses);
        assertEquals(2, calls.get());
    }

    @Test public void rapidEditsKeepOnlyTheLatestQueuedRequest() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        CountDownLatch latestDone = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<String> responses = Collections.synchronizedList(new ArrayList<>());
        NetworkGateway gateway = gateway((url, cancellation) -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                // Model DNS/transport that completes after cancellation.
                boolean released = false;
                while (!released) {
                    try { releaseFirst.await(); released = true; }
                    catch (InterruptedException ignored) { }
                }
            }
            return ok(ROUTE);
        });
        gateway.request(route("old"), response -> responses.add(response.optString("id")));
        await(firstEntered);
        for (int i = 0; i < 40; i++) {
            gateway.request(route("edit-" + i), response -> {
                responses.add(response.optString("id"));
                if ("edit-39".equals(response.optString("id"))) latestDone.countDown();
            });
        }
        releaseFirst.countDown();
        await(latestDone);
        assertEquals(Collections.singletonList("edit-39"), responses);
        assertEquals("Superseded queued requests never consume API credits", 2, calls.get());
    }

    @Test public void cancelSuppressesLateCallbackAndDoesNotCancelOtherLanes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        CountDownLatch searchDone = new CountDownLatch(1), nextDone = new CountDownLatch(1);
        AtomicInteger routeCalls = new AtomicInteger(), staleResponses = new AtomicInteger();
        NetworkGateway gateway = gateway((url, cancellation) -> {
            if (url.getPath().endsWith("autocomplete")) return ok(EMPTY_SEARCH);
            if (routeCalls.incrementAndGet() == 1) {
                cancellation.onCancel(cancelled::countDown);
                entered.countDown();
                cancelled.await();
            }
            return ok(ROUTE);
        });
        gateway.request(route("cancelled"), response -> staleResponses.incrementAndGet());
        await(entered);
        gateway.cancel("route");
        gateway.cancel("place"); // Harmless compatibility with old picker cleanup.
        await(cancelled);
        gateway.request(search("search-after"), response -> searchDone.countDown());
        gateway.request(route("route-after"), response -> nextDone.countDown());
        await(searchDone);
        await(nextDone);
        assertEquals(0, staleResponses.get());
    }

    @Test public void requestDeadlineAbortsTransportAndLaneCanBeUsedAgain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), aborted = new CountDownLatch(1);
        CountDownLatch expired = new CountDownLatch(1), nextDone = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JSONObject> error = new AtomicReference<>();
        NetworkGateway gateway = gateway((url, cancellation) -> {
            if (calls.incrementAndGet() == 1) {
                cancellation.onCancel(aborted::countDown);
                entered.countDown();
                aborted.await();
            }
            return ok(ROUTE);
        }, 500);
        gateway.request(route("timeout"), response -> { error.set(response); expired.countDown(); });
        await(entered);
        await(expired);
        await(aborted);
        assertFalse(error.get().getBoolean("ok"));
        assertEquals("TIMEOUT", error.get().getString("errorCode"));
        gateway.request(route("retry"), response -> { assertTrue(response.optBoolean("ok")); nextDone.countDown(); });
        await(nextDone);
    }

    @Test public void closeAbortsAllLanesAndSuppressesCallbacks() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), aborted = new CountDownLatch(2);
        AtomicInteger responses = new AtomicInteger();
        NetworkGateway gateway = gateway((url, cancellation) -> {
            cancellation.onCancel(aborted::countDown);
            entered.countDown();
            aborted.await();
            return ok(url.getPath().endsWith("autocomplete") ? EMPTY_SEARCH : ROUTE);
        });
        gateway.request(route("r"), response -> responses.incrementAndGet());
        gateway.request(search("s"), response -> responses.incrementAndGet());
        await(entered);
        gateway.close();
        await(aborted);
        gateway.request(route("after-close"), response -> responses.incrementAndGet());
        gateway.request("{\"id\":\"p\",\"type\":\"place\"}", response -> responses.incrementAndGet());
        assertEquals(0, responses.get());
    }

    @Test public void routeRequestsUseEveryExplicitModeAndBalancedFullGeojson() throws Exception {
        String[] modes = {"WALK", "BICYCLE", "DRIVE"}, providerModes = {"walk", "bicycle", "drive"};
        for (int i = 0; i < modes.length; i++) {
            JSONObject request = new JSONObject(route("r")).put("travelMode", modes[i]);
            Map<String, String> query = query(NetworkGateway.endpoint(request, KEY));
            assertEquals(providerModes[i], query.get("mode"));
            assertEquals("balanced", query.get("type"));
            assertEquals("metric", query.get("units"));
            assertEquals("geojson", query.get("format"));
            assertEquals("0.0,0.0|0.0,0.001", query.get("waypoints"));
            assertFalse(query.containsKey("details"));
            assertFalse(query.containsKey("avoid"));
            assertFalse(query.containsKey("max_speed"));
            assertFalse(query.containsKey("id"));
        }
        invalidRequest(new JSONObject(route("r")).put("travelMode", "hovercraft"), KEY);
        invalidRequest(new JSONObject(route("r")).put("travelMode", JSONObject.NULL), KEY);
        JSONObject absentMode = new JSONObject(route("r")); absentMode.remove("travelMode");
        invalidRequest(absentMode, KEY);
    }

    @Test public void searchEncodesUnicodeAndInjectionWithoutCountryRestriction() throws Exception {
        String text = "दिल्ली &apiKey=bad# / 東京";
        JSONObject request = new JSONObject(search("s")).put("query", text);
        URL endpoint = NetworkGateway.endpoint(request, KEY);
        Map<String, String> query = query(endpoint);
        assertEquals(text, query.get("text"));
        assertEquals(KEY, query.get("apiKey"));
        assertEquals("5", query.get("limit"));
        assertEquals("json", query.get("format"));
        assertEquals("countrycode:none", query.get("bias"));
        assertFalse(query.containsKey("filter"));
        assertNull(endpoint.getRef());
        assertEquals("/v1/geocode/autocomplete", endpoint.getPath());
    }

    @Test public void credentialsAndInvalidRequestsCannotChangeTheFixedHttpsEndpoint() throws Exception {
        JSONObject request = new JSONObject(route("r"));
        for (String key : new String[]{null, "", "short", "bad key with spaces", "abcdefghijklmnop&other=value",
                "abcdefghijklmnop\r\nheader:evil", "abcdefghijklmnop/../../other", repeat('x', 129)}) {
            invalidRequest(request, key);
        }
        invalidRequest(request.put("points", new JSONArray("[[0,0],[91,0]]")), KEY);
        invalidRequest(new JSONObject(route("r")).put("points", new JSONArray("[[0,0]]")), KEY);
        invalidRequest(new JSONObject(search("s")).put("query", "x"), KEY);
        invalidRequest(new JSONObject(search("s")).put("query", repeat('x', 201)), KEY);
        invalidRequest(new JSONObject(search("s")).put("query", "bad\nquery"), KEY);
        assertEquals("https://api.geoapify.com/v1/routing", NetworkGateway.endpoint(new JSONObject(route("r")), KEY).toString().split("\\?")[0]);
    }

    @Test public void parserPreservesEveryBendAndConvertsLongitudeLatitudeOrder() throws Exception {
        JSONObject route = (JSONObject) NetworkGateway.parseData(new JSONObject(route("r")), ROUTE);
        JSONArray points = route.getJSONArray("points");
        assertEquals(4, points.length());
        assertEquals(0.0004, points.getJSONArray(1).getDouble(0), 0);
        assertEquals(0.0003, points.getJSONArray(1).getDouble(1), 0);
        assertEquals(0.0004, points.getJSONArray(2).getDouble(0), 0);
        assertEquals(0.0007, points.getJSONArray(2).getDouble(1), 0);
        assertEquals(170, route.getDouble("distanceMeters"), 0);
        assertEquals(30, route.getDouble("durationSeconds"), 0);
        assertEquals(0, route.getDouble("startSnapMeters"), 0);
        assertEquals(0, route.getDouble("endSnapMeters"), 0);
    }

    @Test public void connectedMultiLineLegsShareExactlyOneVertex() throws Exception {
        JSONObject fixture = new JSONObject(ROUTE);
        geometry(fixture).put("coordinates", new JSONArray("[[[0,0],[0.0003,0.0004]],[[0.0003,0.0004],[0.0007,0.0004],[0.001,0]]]"));
        JSONObject result = (JSONObject) NetworkGateway.parseData("route", fixture.toString());
        assertEquals(4, result.getJSONArray("points").length());
        assertEquals(0.0004, result.getJSONArray("points").getJSONArray(1).getDouble(0), 0);
        assertEquals(0.0003, result.getJSONArray("points").getJSONArray(1).getDouble(1), 0);
    }

    @Test public void disconnectedLegsAndReverseJoinedLegsNeverBecomeStraightShortcuts() throws Exception {
        for (String coordinates : new String[]{
                "[[[0,0],[0.0003,0.0004]],[[0.00031,0.0004],[0.001,0]]]",
                "[[[0,0],[0.0003,0.0004]],[[0.001,0],[0.0003,0.0004]]]"}) {
            JSONObject fixture = new JSONObject(ROUTE);
            geometry(fixture).put("coordinates", new JSONArray(coordinates));
            invalid("route", fixture.toString());
        }
    }

    @Test public void lineStringAndOptionalAltitudeStillPreserveThePath() throws Exception {
        JSONObject fixture = new JSONObject(ROUTE);
        geometry(fixture).put("type", "LineString").put("coordinates", new JSONArray("[[0,0,32],[0.0003,0.0004,35],[0.001,0,30]]"));
        JSONObject result = (JSONObject) NetworkGateway.parseData("route", fixture.toString());
        assertEquals(3, result.getJSONArray("points").length());
        assertEquals(2, result.getJSONArray("points").getJSONArray(1).length());
    }

    @Test public void parserRejectsInvalidGeometryUnitsAndOversizeWithoutFallback() throws Exception {
        for (String coordinates : new String[]{"[]", "[[[0,0]]]", "[[[0,0],[0,91]]]", "[[[0,0],[181,0]]]",
                "[[[0,0],[\"1\",0]]]", "[[[0,0],[0,0]]]", "[[[0,0],[0.001,0,null]]]",
                "[[[0,0],[0.001,0,5,6]]]", "[[[0,0],[null,0]]]", "[[[0,0],[1e309,0]]]"}) {
            JSONObject fixture = new JSONObject(ROUTE);
            geometry(fixture).put("coordinates", new JSONArray(coordinates));
            invalid("route", fixture.toString());
        }
        for (String field : new String[]{"distance", "time"}) {
            JSONObject fixture = new JSONObject(ROUTE); properties(fixture).put(field, -1);
            invalid("route", fixture.toString());
        }
        JSONObject fixture = new JSONObject(ROUTE); properties(fixture).put("distance_units", "miles");
        invalid("route", fixture.toString());
        properties(fixture).put("distance_units", "meters").put("units", "imperial");
        invalid("route", fixture.toString());
        invalid("route", ROUTE + "trailing");
        invalid("route", "{\"routes\":[]}");
        invalid("route", repeat('x', NetworkGateway.MAX_RESPONSE_BYTES + 1));
        JSONArray many = new JSONArray();
        for (int i = 0; i <= RouteEngine.MAX_POINTS; i++) many.put(new JSONArray().put(i * 0.00001).put(0));
        fixture = new JSONObject(ROUTE); geometry(fixture).put("coordinates", new JSONArray().put(many));
        invalid("route", fixture.toString());
    }

    @Test public void parserRejectsProviderModeSubstitution() throws Exception {
        JSONObject request = new JSONObject(route("r")).put("travelMode", "WALK");
        try {
            NetworkGateway.parseData(request, ROUTE);
            fail("Accepted a drive route for walking");
        } catch (NetworkGateway.UserError expected) {
            assertEquals("INVALID_RESPONSE", expected.code);
            assertTrue(expected.getMessage().contains("different travel mode"));
        }
        JSONObject fixture = new JSONObject(ROUTE); properties(fixture).put("mode", "transit");
        invalid("route", fixture.toString());
        fixture = new JSONObject(ROUTE).put("properties", new JSONObject().put("mode", "transit"));
        invalid("route", fixture.toString());
    }

    @Test public void snappedEndpointsAreReportedAndNeverPrependedToGeometry() throws Exception {
        JSONObject request = new JSONObject(route("r")).put("points", new JSONArray("[[0.0008,0],[0.0006,0.001]]"));
        JSONObject result = (JSONObject) NetworkGateway.parseData(request, ROUTE);
        assertTrue(result.getDouble("startSnapMeters") > 88 && result.getDouble("startSnapMeters") < 90);
        assertTrue(result.getDouble("endSnapMeters") > 66 && result.getDouble("endSnapMeters") < 68);
        assertEquals(4, result.getJSONArray("points").length());
        assertEquals(0, result.getJSONArray("snappedStart").getDouble(0), 0);
        assertEquals(0, result.getJSONArray("snappedEnd").getDouble(0), 0);
    }

    @Test public void endpointSnapOverOneKilometreIsRejectedWithSpecificGuidance() throws Exception {
        for (String points : new String[]{"[[0.01,0],[0,0.001]]", "[[0,0],[0.01,0.001]]", "[[0.01,0],[0.01,0.001]]"}) {
            JSONObject request = new JSONObject(route("r")).put("points", new JSONArray(points));
            try { NetworkGateway.parseData(request, ROUTE); fail("Accepted distant endpoint"); }
            catch (NetworkGateway.UserError expected) {
                assertEquals("SNAP_TOO_FAR", expected.code);
                assertTrue(expected.getMessage().contains("over 1 km"));
            }
        }
    }

    @Test public void antimeridianSnapsUseShortGeographicDistance() throws Exception {
        JSONObject fixture = new JSONObject(ROUTE);
        geometry(fixture).put("coordinates", new JSONArray("[[[179.999,0],[-179.999,0]]]"));
        JSONObject request = new JSONObject(route("r")).put("points", new JSONArray("[[0,-179.999],[0,179.999]]"));
        JSONObject result = (JSONObject) NetworkGateway.parseData(request, fixture.toString());
        assertTrue(result.getDouble("startSnapMeters") > 222 && result.getDouble("startSnapMeters") < 223);
        assertTrue(result.getDouble("endSnapMeters") > 222 && result.getDouble("endSnapMeters") < 223);
    }

    @Test public void searchSuppliesCoordinatesAndStableIdsWithoutASecondDetailsRequest() throws Exception {
        JSONArray suggestions = (JSONArray) NetworkGateway.parseData("search", SEARCH);
        JSONObject item = suggestions.getJSONObject(0);
        assertEquals("fixture-id", item.getString("placeId"));
        assertEquals("Selected address", item.getString("label"));
        assertEquals(51.5, item.getDouble("lat"), 0);
        assertEquals(-0.12, item.getDouble("lon"), 0);
        assertEquals(2, item.getJSONArray("attributions").length());
        assertEquals(0, ((JSONArray) NetworkGateway.parseData("search", EMPTY_SEARCH)).length());
        JSONObject noId = new JSONObject(SEARCH); noId.getJSONArray("results").getJSONObject(0).remove("place_id");
        String first = ((JSONArray) NetworkGateway.parseData("search", noId.toString())).getJSONObject(0).getString("placeId");
        String second = ((JSONArray) NetworkGateway.parseData("search", noId.toString())).getJSONObject(0).getString("placeId");
        assertEquals(first, second);
    }

    @Test public void searchPreservesProviderCreditsAndRejectsUnsafeLinks() throws Exception {
        JSONObject search = new JSONObject(SEARCH);
        JSONObject source = new JSONObject().put("attribution", "Additional data provider")
                .put("url", "https://provider.example.test/about");
        search.getJSONArray("results").getJSONObject(0).put("datasource", source);
        JSONArray credits = ((JSONArray) NetworkGateway.parseData("search", search.toString()))
                .getJSONObject(0).getJSONArray("attributions");
        assertEquals(3, credits.length());
        assertEquals("Additional data provider", credits.getJSONObject(2).getString("provider"));
        assertEquals("https://provider.example.test/about", credits.getJSONObject(2).getString("providerUri"));
        for (String url : new String[]{"javascript:alert(1)", "intent://example", "file:///private/file",
                "https://user:secret@provider.example.test", "//provider.example.test", "https://"}) {
            source.put("url", url); invalid("search", search.toString());
        }
    }

    @Test public void searchRejectsInvalidCoordinatesLabelsAndResultLimits() throws Exception {
        for (Object invalidLat : new Object[]{91, "1", JSONObject.NULL}) {
            JSONObject fixture = new JSONObject(SEARCH);
            fixture.getJSONArray("results").getJSONObject(0).put("lat", invalidLat);
            invalid("search", fixture.toString());
        }
        JSONObject fixture = new JSONObject(SEARCH);
        fixture.getJSONArray("results").getJSONObject(0).put("formatted", "bad\nlabel");
        invalid("search", fixture.toString());
        JSONArray many = new JSONArray();
        for (int i = 0; i < 6; i++) many.put(new JSONObject(SEARCH).getJSONArray("results").get(0));
        invalid("search", new JSONObject().put("results", many).toString());
    }

    @Test public void serviceFailuresHaveActionableCodesAndNeverExposeResponseSecrets() throws Exception {
        int[] statuses = {400, 401, 402, 403, 404, 429, 500, 503, 504};
        String[] expectedCodes = {"INVALID_REQUEST", "AUTH", "QUOTA", "AUTH", "NO_ROUTE", "QUOTA", "SERVICE", "SERVICE", "TIMEOUT"};
        for (int i = 0; i < statuses.length; i++) {
            final int status = statuses[i];
            NetworkGateway gateway = gateway((url, cancellation) -> new NetworkGateway.HttpResult(status,
                    "{\"message\":\"private upstream details apiKey=" + KEY + "\"}"));
            JSONObject result = request(gateway, route("failure-" + status));
            assertFalse(result.getBoolean("ok"));
            assertEquals(expectedCodes[i], result.getString("errorCode"));
            assertFalse(result.has("data"));
            assertFalse(result.toString().contains(KEY));
            assertFalse(result.toString().contains("private upstream details"));
        }
    }

    @Test public void noRoutePayloadsNeverProduceFallbackGeometry() throws Exception {
        String empty = "{\"type\":\"FeatureCollection\",\"features\":[]}";
        String error = "{\"statusCode\":400,\"message\":\"Could not find a route between points\"}";
        for (String body : new String[]{empty, error}) {
            JSONObject result = request(gateway((url, cancellation) -> ok(body)), route("no-route"));
            assertEquals("NO_ROUTE", result.getString("errorCode"));
            assertFalse(result.has("data"));
        }
    }

    @Test public void transportExceptionsDoNotLeakKeyBearingUrls() throws Exception {
        JSONObject response = request(gateway((url, cancellation) -> { throw new IOException(url.toString()); }), route("network"));
        assertEquals("NETWORK", response.getString("errorCode"));
        assertFalse(response.toString().contains(KEY));
        assertFalse(response.toString().contains("apiKey"));
    }

    @Test public void offlineAndUnconfiguredRequestsNeverCallTransport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NetworkGateway.Transport transport = (url, cancellation) -> { calls.incrementAndGet(); return ok(ROUTE); };
        NetworkGateway offline = new NetworkGateway(() -> false, () -> KEY, transport, 5000);
        gateways.add(offline);
        assertEquals("OFFLINE", request(offline, route("offline")).getString("errorCode"));
        NetworkGateway unconfigured = new NetworkGateway(() -> true, () -> "", transport, 5000);
        gateways.add(unconfigured);
        assertEquals("CONFIGURATION", request(unconfigured, route("configuration")).getString("errorCode"));
        assertEquals(0, calls.get());
    }

    @Test public void revokingOnlineConsentDuringRequestPreventsSuccessfulResult() throws Exception {
        AtomicBoolean online = new AtomicBoolean(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicReference<JSONObject> response = new AtomicReference<>();
        NetworkGateway gateway = new NetworkGateway(online::get, () -> KEY, (url, cancellation) -> {
            entered.countDown(); release.await(); return ok(ROUTE);
        }, 5000);
        gateways.add(gateway);
        gateway.request(route("consent"), result -> { response.set(result); done.countDown(); });
        await(entered); online.set(false); release.countDown(); await(done);
        assertEquals("OFFLINE", response.get().getString("errorCode"));
        assertFalse(response.get().has("data"));
    }

    @Test public void updatedCredentialsAreReadForTheNextRequest() throws Exception {
        AtomicReference<String> key = new AtomicReference<>(KEY);
        List<String> sentKeys = new ArrayList<>();
        NetworkGateway gateway = new NetworkGateway(() -> true, key::get, (url, cancellation) -> {
            sentKeys.add(query(url).get("apiKey")); return ok(ROUTE);
        }, 5000);
        gateways.add(gateway);
        assertTrue(request(gateway, route("first")).getBoolean("ok"));
        key.set("updated_fixture_key_not_real_1234");
        assertTrue(request(gateway, route("second")).getBoolean("ok"));
        assertEquals(KEY, sentKeys.get(0));
        assertEquals(key.get(), sentKeys.get(1));
    }

    @Test public void waypointRequestsPreserveAllEightPointsAndExplicitlyDisableReordering() throws Exception {
        JSONArray points = new JSONArray();
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            points.put(new JSONArray().put(i * .01).put(i * .02));
            if (i > 0) encoded.append('|');
            encoded.append(i * .01).append(',').append(i * .02);
        }
        JSONObject request = new JSONObject(route("vias")).put("points", points);
        Map<String, String> sent = query(NetworkGateway.endpoint(request, KEY));
        assertEquals(encoded.toString(), sent.get("waypoints"));
        assertEquals("through_stop", sent.get("intermediate_waypoint_mode"));
        assertEquals("false", sent.get("optimize_stops"));
        points.put(new JSONArray().put(.2).put(.3));
        invalidRequest(request, KEY);
        invalidRequest(new JSONObject(route("bad")).put("routeChoices", "true"), KEY);
        invalidRequest(new JSONObject(route("bad")).put("preference", "fastest"), KEY);
        invalidRequest(new JSONObject(route("bad")).put("travelMode", "WALK").put("preference", "less_maneuvers"), KEY);
        assertEquals("short", query(NetworkGateway.endpoint(new JSONObject(route("short"))
                .put("preference", "short"), KEY)).get("type"));
    }

    @Test public void drivingChoicesUseThreeSupportedRequestsAndKeepTheirRealGeometry() throws Exception {
        List<String> preferences = new ArrayList<>();
        NetworkGateway gateway = gateway((url, cancellation) -> {
            Map<String, String> parameters = query(url);
            String preference = parameters.get("type"); preferences.add(preference);
            assertFalse(parameters.containsKey("alternatives"));
            assertFalse(parameters.containsKey("routeChoices"));
            return ok(choiceFixture("drive", preference, "balanced".equals(preference) ? .0002
                    : "short".equals(preference) ? .0004 : .0006));
        });
        JSONObject response = request(gateway, choices("all", "DRIVE"));
        assertTrue(response.getBoolean("ok"));
        assertEquals(java.util.Arrays.asList("balanced", "short", "less_maneuvers"), preferences);
        JSONObject data = response.getJSONObject("data"); JSONArray routes = data.getJSONArray("routes");
        assertEquals(3, routes.length());
        assertEquals("Balanced", routes.getJSONObject(0).getString("label"));
        assertEquals("Shortest", routes.getJSONObject(1).getString("label"));
        assertEquals("Fewer turns", routes.getJSONObject(2).getString("label"));
        assertEquals("less_maneuvers", routes.getJSONObject(2).getString("preference"));
        assertEquals(.0006, routes.getJSONObject(2).getJSONArray("points").getJSONArray(1).getDouble(0), 0);
        assertEquals(routes.getJSONObject(0).getJSONArray("points").toString(), data.getJSONArray("points").toString());
        assertEquals("", data.getString("choicesMessage"));
    }

    @Test public void walkingAndCyclingChoicesNeverRequestUnsupportedFewerTurns() throws Exception {
        for (String mode : new String[]{"WALK", "BICYCLE"}) {
            List<String> preferences = new ArrayList<>();
            NetworkGateway gateway = gateway((url, cancellation) -> {
                Map<String, String> parameters = query(url);
                preferences.add(parameters.get("type"));
                return ok(choiceFixture(parameters.get("mode"), parameters.get("type"),
                        "balanced".equals(parameters.get("type")) ? .0002 : .0004));
            });
            JSONObject response = request(gateway, choices("choices-" + mode, mode));
            assertTrue(response.getBoolean("ok"));
            assertEquals(java.util.Arrays.asList("balanced", "short"), preferences);
            assertEquals(2, response.getJSONObject("data").getJSONArray("routes").length());
        }
    }

    @Test public void equalProviderGeometryIsShownOnlyOnceEvenWithDuplicateVertices() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NetworkGateway gateway = gateway((url, cancellation) -> {
            JSONObject fixture = new JSONObject(ROUTE);
            properties(fixture).put("type", query(url).get("type"));
            if (calls.incrementAndGet() == 2) geometry(fixture).put("coordinates",
                    new JSONArray("[[[0,0],[0.0003,0.0004],[0.0003,0.0004],[0.0007,0.0004],[0.001,0]]]"));
            return ok(fixture.toString());
        });
        JSONObject response = request(gateway, choices("duplicates", "DRIVE"));
        assertEquals(3, calls.get());
        assertEquals(1, response.getJSONObject("data").getJSONArray("routes").length());
        assertEquals(4, response.getJSONObject("data").getJSONArray("points").length());
        assertEquals("Balanced", response.getJSONObject("data").getString("label"));
    }

    @Test public void noChoiceFlagMakesExactlyOneRequestAndReturnsCompatiblePrimaryFields() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JSONObject response = request(gateway((url, cancellation) -> { calls.incrementAndGet(); return ok(ROUTE); }), route("single"));
        assertTrue(response.getBoolean("ok"));
        assertEquals(1, calls.get());
        assertEquals(1, response.getJSONObject("data").getJSONArray("routes").length());
        assertEquals(170, response.getJSONObject("data").getDouble("distanceMeters"), 0);
    }

    @Test public void connectedViaLegsPreserveFullGeometryAndReportEachSnapInOrder() throws Exception {
        JSONObject fixture = new JSONObject(ROUTE);
        geometry(fixture).put("coordinates", new JSONArray("[[[0,0],[0.0003,0.0004]],[[0.0003,0.0004],[0.0007,0.0004],[0.001,0]]]"));
        JSONObject query = new JSONObject(route("via")).put("points", new JSONArray("[[0,0],[0.0005,0.0003],[0,0.001]]"));
        JSONObject parsed = (JSONObject) NetworkGateway.parseData(query, fixture.toString());
        assertEquals(4, parsed.getJSONArray("points").length());
        assertEquals(3, parsed.getJSONArray("snappedWaypoints").length());
        assertEquals(.0004, parsed.getJSONArray("snappedWaypoints").getJSONArray(1).getDouble(0), 0);
        assertEquals(.0003, parsed.getJSONArray("snappedWaypoints").getJSONArray(1).getDouble(1), 0);
        assertTrue(parsed.getJSONArray("waypointSnapMeters").getDouble(1) > 11);
        assertTrue(parsed.getJSONArray("waypointSnapMeters").getDouble(1) < 12);
        assertEquals("[0,1,3]", parsed.getJSONArray("waypointIndices").toString());
    }

    @Test public void maximumViaRouteHasOneValidatedBoundaryPerOrderedPoint() throws Exception {
        JSONArray points = new JSONArray(), lines = new JSONArray();
        for (int i = 0; i < 8; i++) {
            points.put(new JSONArray().put(i * .02).put(i * .01));
            if (i > 0) lines.put(new JSONArray().put(new JSONArray().put((i - 1) * .01).put((i - 1) * .02))
                    .put(new JSONArray().put(i * .01).put(i * .02)));
        }
        JSONObject fixture = new JSONObject(ROUTE); geometry(fixture).put("coordinates", lines);
        JSONObject parsed = (JSONObject) NetworkGateway.parseData(new JSONObject(route("max")).put("points", points), fixture.toString());
        assertEquals(points.toString(), parsed.getJSONArray("snappedWaypoints").toString());
        assertEquals(8, parsed.getJSONArray("points").length());
        for (int i = 0; i < 8; i++) assertEquals(0, parsed.getJSONArray("waypointSnapMeters").getDouble(i), 0);
    }

    @Test public void missingOrMergedViaLegsAndDistantViaSnapsAreRejected() throws Exception {
        JSONObject query = new JSONObject(route("via")).put("points", new JSONArray("[[0,0],[0.02,0.0003],[0,0.001]]"));
        try { NetworkGateway.parseData(query, ROUTE); fail("Accepted omitted via boundary"); }
        catch (NetworkGateway.UserError expected) { assertEquals("INVALID_RESPONSE", expected.code); }
        JSONObject fixture = new JSONObject(ROUTE);
        geometry(fixture).put("coordinates", new JSONArray("[[[0,0],[0.0003,0.0004]],[[0.0003,0.0004],[0.001,0]]]"));
        try { NetworkGateway.parseData(query, fixture.toString()); fail("Accepted a via over one kilometre away"); }
        catch (NetworkGateway.UserError expected) {
            assertEquals("SNAP_TOO_FAR", expected.code);
            assertTrue(expected.getMessage().contains("Via point 1"));
        }
    }

    @Test public void reorderedViaLegsAreRejectedAgainstTheirOrderedRequestedPoints() throws Exception {
        JSONObject query = new JSONObject(route("order")).put("points", new JSONArray("[[0,0],[0,0.02],[0,0.04],[0,0.06]]"));
        JSONObject fixture = new JSONObject(ROUTE);
        geometry(fixture).put("coordinates", new JSONArray("[[[0,0],[0.04,0]],[[0.04,0],[0.02,0]],[[0.02,0],[0.06,0]]]"));
        try { NetworkGateway.parseData(query, fixture.toString()); fail("Accepted reordered via visits"); }
        catch (NetworkGateway.UserError expected) { assertEquals("SNAP_TOO_FAR", expected.code); }
    }

    @Test public void reportedPreferenceOrViaPolicySubstitutionIsRejected() throws Exception {
        JSONObject fixture = new JSONObject(ROUTE);
        properties(fixture).put("type", "short");
        try { NetworkGateway.parseData(new JSONObject(route("preference")), fixture.toString()); fail("Accepted substituted preference"); }
        catch (NetworkGateway.UserError expected) { assertEquals("INVALID_RESPONSE", expected.code); }
        JSONObject query = new JSONObject(route("via-policy")).put("points", new JSONArray("[[0,0],[0.0004,0.0003],[0,0.001]]"));
        for (String property : new String[]{"optimize_stops", "intermediate_waypoint_mode"}) {
            fixture = new JSONObject(ROUTE);
            geometry(fixture).put("coordinates", new JSONArray("[[[0,0],[0.0003,0.0004]],[[0.0003,0.0004],[0.001,0]]]"));
            properties(fixture).put(property, "optimize_stops".equals(property) ? Boolean.TRUE : "pass_through");
            try { NetworkGateway.parseData(query, fixture.toString()); fail("Accepted changed via policy"); }
            catch (NetworkGateway.UserError expected) { assertEquals("INVALID_RESPONSE", expected.code); }
        }
    }

    @Test public void auxiliaryAuthAndQuotaErrorsPreserveThePrimaryAndStopFurtherCalls() throws Exception {
        for (int status : new int[]{401, 429}) {
            AtomicInteger calls = new AtomicInteger();
            JSONObject response = request(gateway((url, cancellation) -> calls.incrementAndGet() == 1
                    ? ok(ROUTE) : new NetworkGateway.HttpResult(status, "{\"message\":\"private " + KEY + "\"}")), choices("partial", "DRIVE"));
            assertTrue(response.getBoolean("ok"));
            assertEquals(2, calls.get());
            assertEquals(1, response.getJSONObject("data").getJSONArray("routes").length());
            assertFalse(response.getJSONObject("data").getString("choicesMessage").isEmpty());
            assertFalse(response.toString().contains(KEY));
        }
        AtomicInteger calls = new AtomicInteger();
        JSONObject primary = request(gateway((url, cancellation) -> {
            calls.incrementAndGet(); return new NetworkGateway.HttpResult(401, "{}");
        }), choices("primary-auth", "DRIVE"));
        assertEquals("AUTH", primary.getString("errorCode"));
        assertFalse(primary.has("data"));
        assertEquals(1, calls.get());
    }

    @Test public void malformedAuxiliaryRouteIsSkippedWithoutLosingOtherRealChoices() throws Exception {
        JSONObject response = request(gateway((url, cancellation) -> {
            String preference = query(url).get("type");
            if ("short".equals(preference)) return ok("{\"type\":\"FeatureCollection\",\"features\":[{}]}");
            return ok(choiceFixture("drive", preference, "balanced".equals(preference) ? .0002 : .0006));
        }), choices("partial-geometry", "DRIVE"));
        assertTrue(response.getBoolean("ok"));
        JSONArray routes = response.getJSONObject("data").getJSONArray("routes");
        assertEquals(2, routes.length());
        assertEquals("less_maneuvers", routes.getJSONObject(1).getString("preference"));
        assertFalse(response.getJSONObject("data").getString("choicesMessage").isEmpty());
    }

    @Test public void overallDeadlineRetainsPrimaryAbortsTheAuxiliaryAndAllowsNextRequest() throws Exception {
        CountDownLatch auxiliaryEntered = new CountDownLatch(1), aborted = new CountDownLatch(1), completed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(), callbacks = new AtomicInteger();
        AtomicReference<JSONObject> received = new AtomicReference<>();
        NetworkGateway gateway = gateway((url, cancellation) -> {
            if (calls.incrementAndGet() == 2) {
                cancellation.onCancel(aborted::countDown); auxiliaryEntered.countDown(); aborted.await();
            }
            return ok(ROUTE);
        }, 700);
        gateway.request(choices("partial-timeout", "DRIVE"), response -> {
            callbacks.incrementAndGet(); received.set(response); completed.countDown();
        });
        await(auxiliaryEntered); await(completed); await(aborted);
        assertTrue(received.get().getBoolean("ok"));
        assertEquals(1, received.get().getJSONObject("data").getJSONArray("routes").length());
        assertTrue(received.get().getJSONObject("data").getString("choicesMessage").contains("too long"));
        assertTrue(request(gateway, route("next")).getBoolean("ok"));
        assertEquals(1, callbacks.get());
    }

    @Test public void replacingDuringAuxiliaryWorkNeverDeliversTheOldPartialRoute() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), aborted = new CountDownLatch(1), newest = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<String> responses = Collections.synchronizedList(new ArrayList<>());
        NetworkGateway gateway = gateway((url, cancellation) -> {
            if (calls.incrementAndGet() == 2) {
                cancellation.onCancel(aborted::countDown); entered.countDown(); aborted.await();
            }
            return ok(ROUTE);
        });
        gateway.request(choices("old-partial", "DRIVE"), response -> responses.add(response.optString("id")));
        await(entered);
        gateway.request(route("new-plan"), response -> { responses.add(response.optString("id")); newest.countDown(); });
        await(aborted); await(newest);
        assertEquals(Collections.singletonList("new-plan"), responses);
    }

    @Test public void onlineRevocationDuringAuxiliaryWorkCannotReturnCachedPrimary() throws Exception {
        AtomicBoolean online = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        NetworkGateway gateway = new NetworkGateway(online::get, () -> KEY, (url, cancellation) -> {
            if (calls.incrementAndGet() == 2) online.set(false);
            return ok(ROUTE);
        }, 5000);
        gateways.add(gateway);
        JSONObject response = request(gateway, choices("revoked", "DRIVE"));
        assertEquals("OFFLINE", response.getString("errorCode"));
        assertFalse(response.has("data"));
    }

    private static String choices(String id, String mode) throws Exception {
        return new JSONObject(route(id)).put("travelMode", mode).put("routeChoices", true).toString();
    }

    private static String choiceFixture(String mode, String preference, double bendLat) throws Exception {
        JSONObject fixture = new JSONObject(ROUTE);
        properties(fixture).put("mode", mode).put("type", preference);
        geometry(fixture).put("coordinates", new JSONArray().put(new JSONArray().put(new JSONArray().put(0).put(0))
                .put(new JSONArray().put(.0005).put(bendLat)).put(new JSONArray().put(.001).put(0))));
        return fixture.toString();
    }

    private NetworkGateway gateway(NetworkGateway.Transport transport) { return gateway(transport, 5000); }
    private NetworkGateway gateway(NetworkGateway.Transport transport, long timeout) {
        NetworkGateway result = new NetworkGateway(() -> true, () -> KEY, transport, timeout);
        gateways.add(result); return result;
    }
    private static NetworkGateway.HttpResult ok(String body) { return new NetworkGateway.HttpResult(200, body); }
    private static String route(String id) throws Exception {
        return new JSONObject().put("id", id).put("type", "route").put("travelMode", "DRIVE")
                .put("points", new JSONArray("[[0,0],[0,0.001]]")).toString();
    }
    private static String search(String id) throws Exception {
        return new JSONObject().put("id", id).put("type", "search").put("query", "Selected address").toString();
    }
    private static JSONObject request(NetworkGateway gateway, String input) throws Exception {
        AtomicReference<JSONObject> result = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
        gateway.request(input, response -> { result.set(response); done.countDown(); });
        await(done); return result.get();
    }
    private static Map<String, String> query(URL url) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (String pair : url.getQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(parts[0], URLDecoder.decode(parts[1], "UTF-8"));
        }
        return result;
    }
    private static JSONObject geometry(JSONObject fixture) throws Exception {
        return fixture.getJSONArray("features").getJSONObject(0).getJSONObject("geometry");
    }
    private static JSONObject properties(JSONObject fixture) throws Exception {
        return fixture.getJSONArray("features").getJSONObject(0).getJSONObject("properties");
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("Asynchronous operation did not finish", latch.await(5, TimeUnit.SECONDS));
    }
    private static void invalid(String type, String json) throws Exception {
        try { NetworkGateway.parseData(type, json); fail("Expected malformed payload rejection"); }
        catch (NetworkGateway.UserError expected) { }
    }
    private static void invalidRequest(JSONObject request, String key) throws Exception {
        try { NetworkGateway.endpoint(request, key); fail("Expected request rejection"); }
        catch (NetworkGateway.UserError expected) { }
    }
    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
