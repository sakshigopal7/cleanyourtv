package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Live "In cinemas" list: fetches cinema.json from the public GitHub raw URL on a
 * background thread, on Home resume and at most once an hour — the same cadence and
 * pattern as BentoFeedFetcher for recommendations.json. Conditional requests (ETag /
 * If-Modified-Since) keep the traffic tiny. The fetched JSON is validated before it
 * replaces the disk-cached copy; on any failure the last-good copy stays, and with
 * no cached copy the bundled assets file is the fallback (see BentoStore.loadCinema,
 * which reads the internal file first, then the asset). Home never blanks the
 * section and never shows a spinner for it. The pipeline writes cinema.json daily,
 * so a new verified cinema title appears without a launcher rebuild.
 */
public final class BentoCinemaFetcher {
    private static final String TAG = "TvHome.BentoCinema";
    static final String CINEMA_URL = "https://raw.githubusercontent.com/sakshigopal7/cleanyourtv/main/cinema.json";
    private static final String PREFS = "bento_cinema";
    private static final String KEY_LAST_ATTEMPT_MS = "last_attempt_ms";
    private static final String KEY_ETAG = "etag";
    private static final String KEY_LAST_MODIFIED = "last_modified";
    static final long REFRESH_MS = 60L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;

    public interface Callback {
        void onCinemaUpdated();
    }

    private BentoCinemaFetcher() {
    }

    /** Fetch now unless one was attempted within the last hour. Runs on a background thread. */
    public static void refresh(Context ctx, Callback cb) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(KEY_LAST_ATTEMPT_MS, 0);
        long now = System.currentTimeMillis();
        // A stored attempt time in the future (the clock jumped forward and back,
        // or was set ahead when the attempt was recorded) would make the throttle
        // skip forever, since now - last stays negative. Treat it as stale.
        if (last > 0 && last <= now && now - last < REFRESH_MS) return;
        prefs.edit().putLong(KEY_LAST_ATTEMPT_MS, now).apply();
        final String etag = prefs.getString(KEY_ETAG, null);
        final String lastModified = prefs.getString(KEY_LAST_MODIFIED, null);
        final Context appCtx = ctx.getApplicationContext();
        new Thread(() -> {
            boolean updated = fetchAndStore(appCtx, etag, lastModified);
            if (updated && cb != null) {
                new Handler(Looper.getMainLooper()).post(cb::onCinemaUpdated);
            }
        }).start();
    }

    /** A failed fetch must not poison the hourly throttle: clear the attempt so the next resume retries. */
    private static void clearLastAttempt(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_ATTEMPT_MS).apply();
    }

    /** Returns true when a new, valid list replaced the cached copy. */
    static boolean fetchAndStore(Context ctx, String etag, String lastModified) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(CINEMA_URL).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (etag != null) conn.setRequestProperty("If-None-Match", etag);
            if (lastModified != null) conn.setRequestProperty("If-Modified-Since", lastModified);
            conn.connect();
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                Log.d(TAG, "cinema list unchanged (304)");
                return false;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "cinema fetch HTTP " + code + ", keeping last good");
                clearLastAttempt(ctx);
                return false;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            String body = sb.toString();
            if (!isValidCinema(body)) {
                Log.w(TAG, "cinema list failed validation, keeping last good");
                clearLastAttempt(ctx);
                return false;
            }
            BentoStore.writeAtomicFile(new File(ctx.getFilesDir(), BentoStore.FILE_CINEMA), body);
            String newEtag = conn.getHeaderField("ETag");
            String newLastModified = conn.getHeaderField("Last-Modified");
            SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            if (newEtag != null) e.putString(KEY_ETAG, newEtag); else e.remove(KEY_ETAG);
            if (newLastModified != null) e.putString(KEY_LAST_MODIFIED, newLastModified); else e.remove(KEY_LAST_MODIFIED);
            e.apply();
            Log.d(TAG, "cinema list updated from " + CINEMA_URL);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cinema fetch failed, keeping last good", e);
            clearLastAttempt(ctx);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Schema gate: a "verified_on" date that parses, a "titles" array (which may be
     * empty — no verified titles means the section hides), and every title has a
     * title, a TMDB id and a poster. Order is the published order; this gate never
     * re-sorts. Freshness (verified within 7 days) is enforced at load time by
     * BentoStore.loadCinemaFromJson, not here.
     */
    static boolean isValidCinema(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(json);
            String verifiedOn = root.optString("verified_on", null);
            if (verifiedOn == null || verifiedOn.isEmpty()) return false;
            java.time.LocalDate.parse(verifiedOn);
            JSONArray titles = root.optJSONArray("titles");
            if (titles == null) return false;
            for (int i = 0; i < titles.length(); i++) {
                JSONObject item = titles.optJSONObject(i);
                if (item == null) return false;
                if (BentoTitle.optNullable(item, "title") == null) return false;
                if (item.optInt("tmdb_id", 0) <= 0) return false;
                if (BentoTitle.optNullable(item, "poster_path") == null) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
