package io.cleanyourtv.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** On-demand lookup; account tokens stay in this app's private preferences. */
final class KinoPub {
    private KinoPub() {}
    private static final String API = "https://api.service-kp.com";
    // Public Kodi-client identity, also used by Neoniklain/kino-history-channel (Prefs.java).
    private static final String CLIENT_ID = "xbmc";
    private static final String CLIENT_SECRET = "cgg3gtifu46urtfp2zp1nqtba0k2ezxh";

    static final class ApiError extends IOException {
        final String error;
        ApiError(String error) { super(error); this.error = error; }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("kinopub", Context.MODE_PRIVATE);
    }

    static boolean connected(Context c) { return !prefs(c).getString("refresh_token", "").isEmpty(); }

    static synchronized void disconnect(Context c) throws IOException {
        if (!prefs(c).edit().clear().commit()) throw new IOException("Couldn't clear the local KinoPub session");
    }

    static JSONObject deviceCode() throws Exception { return oauth("/oauth2/device", "grant_type=device_code"); }

    static JSONObject poll(String code) throws Exception {
        return oauth("/oauth2/device", "grant_type=device_token&code=" + enc(code));
    }

    static synchronized void save(Context c, JSONObject tokens) throws Exception {
        String access = tokens.getString("access_token"), refresh = tokens.getString("refresh_token");
        long seconds = tokens.getLong("expires_in");
        if (access.isEmpty() || refresh.isEmpty() || seconds <= 0 || seconds > 366L * 24 * 3600)
            throw new IOException("Invalid KinoPub token response");
        if (!prefs(c).edit().putString("access_token", access).putString("refresh_token", refresh)
                .putLong("expires_at", System.currentTimeMillis() + seconds * 1000).commit())
            throw new IOException("Couldn't save the KinoPub session");
    }

    private static synchronized String accessToken(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        if (!connected(c)) throw new ApiError("not_authorized");
        if (System.currentTimeMillis() + 60_000 >= p.getLong("expires_at", 0)) {
            try {
                save(c, oauth("/oauth2/token", "grant_type=refresh_token&refresh_token=" + enc(p.getString("refresh_token", ""))));
            } catch (ApiError e) {
                if ("invalid_grant".equals(e.error) || "invalid_token".equals(e.error)) disconnect(c);
                throw e;
            }
        }
        return p.getString("access_token", "");
    }

    /** No title/year guessing: remakes must never open automatically. */
    static boolean sameImdb(String expected, String actual) {
        try {
            if (expected == null || actual == null || !expected.matches("tt[0-9]+") || !actual.matches("(?:tt)?[0-9]+")) return false;
            long a = Long.parseLong(expected.substring(2));
            long b = Long.parseLong(actual.startsWith("tt") ? actual.substring(2) : actual);
            return a > 0 && a == b;
        } catch (NumberFormatException e) { return false; }
    }

    static List<JSONObject> find(Context c, String kind, int id, String name) throws Exception {
        JSONObject tmdb = Tmdb.get("/" + kind + "/" + id + "?append_to_response=external_ids");
        JSONObject external = tmdb.optJSONObject("external_ids");
        String imdb = external == null ? tmdb.optString("imdb_id") : external.optString("imdb_id");
        if (!sameImdb(imdb, imdb)) throw new IOException("This TMDB title has no IMDb ID for exact matching");
        Set<String> queries = new LinkedHashSet<>();
        queries.add(name);
        queries.add(tmdb.optString("movie".equals(kind) ? "original_title" : "original_name"));
        Map<Integer, JSONObject> found = new LinkedHashMap<>();
        for (String query : queries) {
            if (query == null || query.trim().isEmpty()) continue;
            // shortcut: at most 500 results per title query; broaden if real titles need it.
            for (int page = 1; page <= 5; page++) {
                JSONObject result = request("/v1/items/search?field=title&perpage=100&page=" + page + "&q=" + enc(query), null, accessToken(c));
                JSONArray items = result.optJSONArray("items");
                if (items == null) throw new IOException("Unexpected KinoPub search response");
                for (int n = 0; n < items.length(); n++) {
                    JSONObject item = items.optJSONObject(n);
                    if (item != null && item.optInt("id") > 0 && sameImdb(imdb, item.optString("imdb")))
                        found.put(item.getInt("id"), item);
                }
                JSONObject pagination = result.optJSONObject("pagination");
                if (items.length() == 0 || (pagination != null && page >= pagination.optInt("total", 1))
                        || (pagination == null && items.length() < 100)) break;
            }
        }
        return new ArrayList<>(found.values());
    }

    static void open(Context c, int id) {
        if (id <= 0) throw new IllegalArgumentException("Invalid KinoPub item ID");
        c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://kino.watch/item/view/" + id))
                .setPackage("watch.kino").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static JSONObject oauth(String path, String form) throws Exception {
        return request(path, form + "&client_id=" + enc(CLIENT_ID) + "&client_secret=" + enc(CLIENT_SECRET), null);
    }

    private static JSONObject request(String path, String form, String bearer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API + path).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (form != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                byte[] data = form.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (java.io.OutputStream stream = connection.getOutputStream()) { stream.write(data); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new IOException("KinoPub HTTP " + status);
            JSONObject response;
            try (InputStream in = stream) { response = new JSONObject(Tmdb.readAll(in)); }
            if (status < 200 || status >= 300) {
                String error = response.optString("error", "http_" + status);
                // Never show raw server payloads, which may contain account tokens.
                throw new ApiError(error.matches("[a-z_0-9]{1,60}") ? error : "http_" + status);
            }
            return response;
        } finally { connection.disconnect(); }
    }

    private static String enc(String text) throws Exception { return URLEncoder.encode(text, "UTF-8"); }
}
