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
    private static final String URL = "https://api.open-meteo.com/v1/forecast?latitude=1.3521&longitude=103.8198&current=temperature_2m,weather_code&timezone=Asia%2FSingapore";
    private static final String PREFS = "bento_weather";
    private static final String KEY_TEXT = "text";
    private static final String KEY_TIME = "time_ms";
    private static final long REFRESH_MS = 30L * 60L * 1000L;

    public interface Callback {
        void onWeather(String text);
    }

    private BentoWeather() {}

    public static String lastGood(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TEXT, null);
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
            String text = fetch();
            Handler main = new Handler(Looper.getMainLooper());
            if (text != null) {
                prefs.edit().putString(KEY_TEXT, text).putLong(KEY_TIME, System.currentTimeMillis()).apply();
                main.post(() -> { if (cb != null) cb.onWeather(text); });
            } else if (cached != null) {
                main.post(() -> { if (cb != null) cb.onWeather(cached); });
            }
        }).start();
    }

    private static String fetch() {
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
            JSONObject current = new JSONObject(sb.toString()).optJSONObject("current");
            if (current == null) return null;
            double temp = current.optDouble("temperature_2m", Double.NaN);
            int code = current.optInt("weather_code", -1);
            if (Double.isNaN(temp)) return null;
            return Math.round(temp) + "° " + wordFor(code) + " · Singapore";
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
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
            default: return "Singapore";
        }
    }
}
