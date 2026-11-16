package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Live weather from Open-Meteo (free, no key): Singapore 1.3521, 103.8198.
 * Refreshed at most every 30 minutes; the last good reading is cached in SharedPreferences
 * and shown offline, so Home never blanks the weather card. WMO weather codes to words.
 */
public final class BentoWeather {
    private static final String URL = "https://api.open-meteo.com/v1/forecast?latitude=1.3521&longitude=103.8198&current=temperature_2m,weather_code&hourly=precipitation_probability&forecast_days=2&timezone=Asia%2FSingapore";
    private static final String PREFS = "bento_weather";
    private static final String KEY_TEXT = "text";
    private static final String KEY_CODE = "code";
    private static final String KEY_TIME = "time_ms";
    private static final String KEY_RAIN_LINE = "rain_line";
    /** A precipitation probability at or above this counts as rain coming. */
    static final int RAIN_PROBABILITY_THRESHOLD = 50;
    private static final long REFRESH_MS = 30L * 60L * 1000L;

    public interface Callback {
        void onWeather(String text);
    }

    private BentoWeather() {}

    public static String lastGood(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TEXT, null);
    }

    /** Rain timing line of the last good reading, or null when nothing is cached yet. */
    public static String lastGoodRainLine(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RAIN_LINE, null);
    }

    /** WMO weather code of the last good reading, or -1 when nothing is cached yet. */
    public static int lastGoodCode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CODE, -1);
    }

    /** WMO codes for drizzle, rain, showers and thunderstorms: the rain overlay is on. */
    public static boolean isRaining(int code) {
        switch (code) {
            case 51: case 53: case 55: case 56: case 57:
            case 61: case 63: case 65: case 66: case 67:
            case 80: case 81: case 82: case 95: case 96: case 99:
                return true;
            default:
                return false;
        }
    }

    private static final class Reading {
        final String text;
        final int code;
        final String rainLine;
        Reading(String text, int code, String rainLine) { this.text = text; this.code = code; this.rainLine = rainLine; }
    }

    public static void refresh(Context ctx, Callback cb) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(KEY_TIME, 0);
        String cached = prefs.getString(KEY_TEXT, null);
        if (cached != null && System.currentTimeMillis() - last < REFRESH_MS) {
            if (cb != null) cb.onWeather(cached);
            return;
        }
        new Thread(() -> {
            Reading r = fetch();
            Handler main = new Handler(Looper.getMainLooper());
            if (r != null) {
                prefs.edit().putString(KEY_TEXT, r.text).putInt(KEY_CODE, r.code)
                        .putString(KEY_RAIN_LINE, r.rainLine)
                        .putLong(KEY_TIME, System.currentTimeMillis()).apply();
                main.post(() -> { if (cb != null) cb.onWeather(r.text); });
            } else if (cached != null) {
                main.post(() -> { if (cb != null) cb.onWeather(cached); });
            }
        }).start();
    }

    private static Reading fetch() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(URL).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONObject current = root.optJSONObject("current");
            if (current == null) return null;
            double temp = current.optDouble("temperature_2m", Double.NaN);
            int code = current.optInt("weather_code", -1);
            if (Double.isNaN(temp)) return null;
            String word = wordFor(code);
            String text = Math.round(temp) + "°" + (word.isEmpty() ? "" : " " + word);
            return new Reading(text, code, rainLineFor(code, root.optJSONObject("hourly")));
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** One-line rain timing from the hourly precipitation probabilities.
     * Raining now when the current code is rain; otherwise the first hour in the next
     * 12 with probability at or above the threshold, phrased as "Rain in about an
     * hour" / "Rain in about N hours"; "No rain expected today" when none is coming. */
    static String rainLineFor(int currentCode, JSONObject hourly) {
        if (isRaining(currentCode)) return "Raining now";
        if (hourly == null) return "No rain expected today";
        org.json.JSONArray times = hourly.optJSONArray("time");
        org.json.JSONArray probs = hourly.optJSONArray("precipitation_probability");
        if (times == null || probs == null) return "No rain expected today";
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Singapore"))
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        int n = Math.min(times.length(), probs.length());
        for (int i = 0; i < n; i++) {
            String t = times.optString(i, null);
            if (t == null) continue;
            java.time.LocalDateTime hour;
            try {
                hour = java.time.LocalDateTime.parse(t);
            } catch (Exception ignored) {
                continue;
            }
            long until = java.time.temporal.ChronoUnit.HOURS.between(now, hour);
            if (until < 0 || until > 11) continue;
            if (probs.optInt(i, 0) >= RAIN_PROBABILITY_THRESHOLD) {
                if (until <= 1) return "Rain in about an hour";
                return "Rain in about " + until + " hours";
            }
        }
        return "No rain expected today";
    }

    static String wordFor(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Foggy";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: case 63: case 65: return "Rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: case 82: return "Showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm, hail";
            default: return "";
        }
    }
}
