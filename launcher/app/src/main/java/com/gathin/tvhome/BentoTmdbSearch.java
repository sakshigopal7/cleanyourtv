package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * On-TV TMDB search for the Search tile: multi-search (movies + TV together),
 * enriched per result with details + watch/providers (one call each, via
 * append_to_response) for SG + IN. A result's provider URL comes only from the
 * title's TMDB homepage when its host is a verified playable form (Netflix,
 * Apple TV, YouTube, SonyLIV, Prime with a GTI link); anything else is shown
 * with its provider chip and "Not streaming now" — never a guessed or dead link.
 * The key comes from BuildConfig (local.properties at build time) and is never
 * logged, stored, or sent anywhere except api.themoviedb.org.
 */
public final class BentoTmdbSearch {
    private static final String TAG = "TvHome.BentoSearch";
    private static final String BASE = "https://api.themoviedb.org/3";
    private static final int MAX_RESULTS = 8;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final long ENRICH_TIMEOUT_MS = 15000;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    public interface Callback {
        void onResults(List<BentoTitle> results);
        void onError(String message);
    }

    private BentoTmdbSearch() {
    }

    public static void search(String query, Callback cb) {
        if (query == null || query.trim().isEmpty()) {
            postError(cb, "Type a title to search");
            return;
        }
        String key = BuildConfig.TMDB_API_KEY;
        if (key == null || key.trim().isEmpty()) {
            postError(cb, "Search is unavailable right now");
            return;
        }
        final String q = query.trim();
        new Thread(() -> {
            try {
                String url = BASE + "/search/multi?api_key=" + key
                        + "&query=" + URLEncoder.encode(q, "UTF-8")
                        + "&page=1&include_adult=false&language=en-US";
                JSONObject root = getJson(url);
                if (root == null) {
                    postError(cb, "Search failed — check the connection and try again");
                    return;
                }
                JSONArray results = root.optJSONArray("results");
                List<JSONObject> candidates = new ArrayList<>();
                if (results != null) {
                    for (int i = 0; i < results.length() && candidates.size() < MAX_RESULTS; i++) {
                        JSONObject item = results.optJSONObject(i);
                        if (item == null) continue;
                        String type = item.optString("media_type", "");
                        if (!"movie".equals(type) && !"tv".equals(type)) continue; // people etc.
                        if (optNullable(item, "poster_path") == null) continue; // cards need posters
                        candidates.add(item);
                    }
                }
                final BentoTitle[] enriched = new BentoTitle[candidates.size()];
                CountDownLatch latch = new CountDownLatch(candidates.size());
                for (int i = 0; i < candidates.size(); i++) {
                    final int idx = i;
                    final JSONObject item = candidates.get(i);
                    POOL.execute(() -> {
                        try {
                            enriched[idx] = enrich(item, key);
                        } catch (Exception e) {
                            Log.w(TAG, "enrich failed for " + item.optInt("id", 0), e);
                            enriched[idx] = fromSearchOnly(item);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await(ENRICH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                List<BentoTitle> out = new ArrayList<>();
                for (BentoTitle t : enriched) if (t != null) out.add(t);
                postResults(cb, out);
            } catch (Exception e) {
                Log.w(TAG, "search failed", e);
                postError(cb, "Search failed — check the connection and try again");
            }
        }).start();
    }

    /** Details + providers for one search hit; falls back to the search hit alone. */
    static BentoTitle enrich(JSONObject item, String key) {
        String type = item.optString("media_type", "movie");
        int id = item.optInt("id", 0);
        if (id <= 0) return fromSearchOnly(item);
        String path = "movie".equals(type) ? "/movie/" : "/tv/";
        JSONObject details = getJson(BASE + path + id + "?api_key=" + key
                + "&append_to_response=watch/providers&language=en-US");
        if (details == null) return fromSearchOnly(item);
        return buildTitle(item, details);
    }

    /**
     * Build a feed-schema BentoTitle from a search hit + its details (with
     * watch/providers). Pure function of its inputs, so unit tests can drive it.
     */
    static BentoTitle buildTitle(JSONObject item, JSONObject details) {
        String type = item.optString("media_type", "movie");
        int id = item.optInt("id", 0);
        String title = firstNonNull(
                optNullable(details, "title"), optNullable(details, "name"),
                optNullable(item, "title"), optNullable(item, "name"));
        if (title == null) title = "";
        String date = firstNonNull(
                optNullable(details, "release_date"), optNullable(details, "first_air_date"),
                optNullable(item, "release_date"), optNullable(item, "first_air_date"));
        int year = parseYear(date);
        String posterPath = firstNonNull(
                optNullable(item, "poster_path"), optNullable(details, "poster_path"));
        String backdropPath = optNullable(details, "backdrop_path");
        String overview = optNullable(details, "overview");
        if (overview == null) overview = optNullable(item, "overview");
        String language = firstNonNull(
                optNullable(details, "original_language"), optNullable(item, "original_language"));

        List<String> genres = new ArrayList<>();
        JSONArray ga = details.optJSONArray("genres");
        if (ga != null) {
            for (int i = 0; i < ga.length(); i++) {
                JSONObject g = ga.optJSONObject(i);
                if (g != null) {
                    String name = optNullable(g, "name");
                    if (name != null) genres.add(name);
                }
            }
        }

        JSONArray avail = buildAvailability(details);
        String today = todaySgt();
        try {
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("media_type", type);
            o.put("tmdb_id", id);
            o.put("tmdb_type", type);
            if (posterPath != null) o.put("poster_path", posterPath);
            if (backdropPath != null) o.put("backdrop_path", backdropPath);
            o.put("year", year);
            if (language != null) o.put("language", language);
            if (overview != null) o.put("overview", overview);
            o.put("first_seen", today);
            o.put("last_seen", today);
            if (!genres.isEmpty()) {
                JSONArray g = new JSONArray();
                for (String s : genres) g.put(s);
                o.put("genres", g);
            }
            o.put("availability", avail);
            return BentoTitle.fromJson(o, false);
        } catch (Exception e) {
            return fromSearchOnly(item);
        }
    }

    /** A title with no details response: still addable, with poster + year, no provider. */
    static BentoTitle fromSearchOnly(JSONObject item) {
        String type = item.optString("media_type", "movie");
        int id = item.optInt("id", 0);
        String title = firstNonNull(optNullable(item, "title"), optNullable(item, "name"));
        if (title == null) title = "";
        String date = firstNonNull(
                optNullable(item, "release_date"), optNullable(item, "first_air_date"));
        String today = todaySgt();
        try {
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("media_type", type);
            o.put("tmdb_id", id);
            o.put("tmdb_type", type);
            String posterPath = optNullable(item, "poster_path");
            if (posterPath != null) o.put("poster_path", posterPath);
            o.put("year", parseYear(date));
            String language = optNullable(item, "original_language");
            if (language != null) o.put("language", language);
            String overview = optNullable(item, "overview");
            if (overview != null) o.put("overview", overview);
            o.put("first_seen", today);
            o.put("last_seen", today);
            o.put("availability", new JSONArray());
            return BentoTitle.fromJson(o, false);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Availability from watch/providers: SG flatrate first, then IN flatrate
     * (requires VPN). Within each country, entries whose provider URL verified
     * come first so the launcher's SG-first pick lands on a playable link.
     * The homepage URL is the title's own link: it is attached only to the
     * provider it belongs to (matched by host).
     */
    static JSONArray buildAvailability(JSONObject details) {
        JSONArray out = new JSONArray();
        try {
            String homepage = optNullable(details, "homepage");
            JSONObject wp = details.optJSONObject("watch/providers");
            JSONObject results = wp != null ? wp.optJSONObject("results") : null;
            if (results == null) return out;
            addCountryProviders(out, results.optJSONObject("SG"), "SG", homepage, false);
            addCountryProviders(out, results.optJSONObject("IN"), "IN", homepage, true);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void addCountryProviders(JSONArray out, JSONObject country,
                                            String code, String homepage, boolean needsVpn) {
        if (country == null) return;
        JSONArray flatrate = country.optJSONArray("flatrate");
        if (flatrate == null) return;
        List<JSONObject> withUrl = new ArrayList<>();
        List<JSONObject> withoutUrl = new ArrayList<>();
        for (int i = 0; i < flatrate.length(); i++) {
            JSONObject p = flatrate.optJSONObject(i);
            if (p == null) continue;
            String name = optNullable(p, "provider_name");
            if (name == null) continue;
            try {
                JSONObject entry = new JSONObject();
                entry.put("country", code);
                entry.put("provider", displayProviderName(name));
                String url = resolveProviderUrlFor(homepage, name);
                if (url != null) {
                    entry.put("provider_url", url);
                    entry.put("url_source", "tmdb_homepage");
                    withUrl.add(entry);
                } else {
                    withoutUrl.add(entry);
                }
                if (needsVpn) {
                    entry.put("requires_vpn", "IN");
                    entry.put("vpn_label", "ExpressVPN to India");
                }
            } catch (Exception ignored) {
            }
        }
        for (JSONObject e : withUrl) out.put(e);
        for (JSONObject e : withoutUrl) out.put(e);
    }

    /**
     * The only provider URLs this launcher fires from search: the title's TMDB
     * homepage, and only when its host is a form already verified on this TV.
     * Netflix gets ?source=30 (plain netflix.com/title links are ignored on
     * this set). Prime Video only with a GTI link (its other forms fail in-app
     * with HIERARCHY_FAILURE). Anything else returns null: the title is shown
     * with its provider chip and "Not streaming now", never a dead link.
     */
    static String resolveProviderUrl(String homepage) {
        if (homepage == null) return null;
        String url = homepage.trim();
        if (url.isEmpty()) return null;
        String lower = url.toLowerCase();
        if (lower.contains("netflix.com/title/")) {
            return url.contains("?") ? url + "&source=30" : url + "?source=30";
        }
        if (lower.contains("tv.apple.com/")) return url;
        if (lower.contains("youtube.com/watch") || lower.contains("youtu.be/")) return url;
        if (lower.contains("sonyliv.com/")) return url;
        if (lower.contains("app.primevideo.com/") && lower.contains("gti=")) return url;
        return null;
    }

    /** Attach the homepage URL only to the provider it belongs to (matched by host). */
    private static String resolveProviderUrlFor(String homepage, String providerName) {
        String url = resolveProviderUrl(homepage);
        if (url == null) return null;
        String host = url.toLowerCase();
        String name = providerName.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (host.contains("netflix.com") && name.contains("netflix")) return url;
        if (host.contains("tv.apple.com") && (name.contains("apple"))) return url;
        if ((host.contains("youtube.com") || host.contains("youtu.be")) && name.contains("youtube")) return url;
        if (host.contains("sonyliv.com") && name.contains("sonyliv")) return url;
        if (host.contains("primevideo.com") && name.contains("prime")) return url;
        return null;
    }

    static String displayProviderName(String tmdbProvider) {
        if (tmdbProvider == null) return null;
        if ("Amazon Prime Video".equalsIgnoreCase(tmdbProvider)) return "Prime Video";
        if ("Apple TV Plus".equalsIgnoreCase(tmdbProvider)) return "Apple TV";
        return tmdbProvider;
    }

    static String firstNonNull(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v;
        return null;
    }

    static int parseYear(String date) {
        if (date == null || date.length() < 4) return 0;
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (Exception e) {
            return 0;
        }
    }

    static String todaySgt() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Singapore"));
        return f.format(new java.util.Date());
    }

    static String optNullable(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String v = o.optString(key, null);
        return (v == null || v.trim().isEmpty()) ? null : v;
    }

    static JSONObject getJson(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "GET failed: " + urlStr.split("\\?")[0], e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void postResults(Callback cb, List<BentoTitle> results) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onResults(results));
    }

    private static void postError(Callback cb, String message) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onError(message));
    }
}
