package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Live weather for Singapore, from NEA's data.gov.sg APIs (free, no key):
 * air temperature from the station nearest home (Meyer Place), and the condition
 * from the two-hour forecast for the nearest area. NEA is the ground truth here:
 * Open-Meteo's model said "light drizzle" on a night NEA called heavy thundery
 * showers, and its temperature runs cool. Open-Meteo stays as the fallback when
 * NEA is unreachable. Refreshed at most every 15 minutes; the last good reading
 * is cached in SharedPreferences and shown offline, so Home never blanks the
 * weather card. WMO weather codes to words (the NEA forecast is mapped onto the
 * same codes, so the rain overlay keeps working).
 */
public final class BentoWeather {
    private static final String NEA_AIR_TEMP_URL = "https://api-open.data.gov.sg/v2/real-time/api/air-temperature";
    private static final String NEA_TWO_HR_URL = "https://api-open.data.gov.sg/v2/real-time/api/two-hr-forecast";
    private static final String OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast?latitude=1.3521&longitude=103.8198&current=temperature_2m,weather_code&hourly=precipitation_probability&forecast_days=2&timezone=Asia%2FSingapore";
    /** Meyer Place, the home this launcher runs in: the nearest NEA station/area to here. */
    static final double HOME_LAT = 1.2988;
    static final double HOME_LON = 103.887;
    private static final String PREFS = "bento_weather";
    private static final String KEY_TEXT = "text";
    private static final String KEY_CODE = "code";
    private static final String KEY_TIME = "time_ms";
    private static final String KEY_RAIN_LINE = "rain_line";
    /** A precipitation probability at or above this counts as rain coming. */
    static final int RAIN_PROBABILITY_THRESHOLD = 50;
    private static final long REFRESH_MS = 15L * 60L * 1000L;

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

    /** NEA first, Open-Meteo when NEA is unreachable. */
    private static Reading fetch() {
        Reading nea = fetchNea();
        return nea != null ? nea : fetchOpenMeteo();
    }

    /** NEA air temperature (nearest station) + two-hour forecast (nearest area).
     * Air-temperature shape: data.stations[] {id, name, location{latitude, longitude}},
     * data.readings[0].data[] {stationId, value}. Two-hour shape: data.area_metadata[]
     * {name, label_location{latitude, longitude}}, data.items[0].forecasts[] {area, forecast}. */
    private static Reading fetchNea() {
        try {
            String tempJson = get(NEA_AIR_TEMP_URL);
            String fcJson = get(NEA_TWO_HR_URL);
            if (tempJson == null || fcJson == null) return null;
            JSONObject tempData = new JSONObject(tempJson).optJSONObject("data");
            JSONObject fcData = new JSONObject(fcJson).optJSONObject("data");
            if (tempData == null || fcData == null) return null;
            JSONArray stations = tempData.optJSONArray("stations");
            JSONArray readingsArr = tempData.optJSONArray("readings");
            if (stations == null || readingsArr == null || readingsArr.length() == 0) return null;
            JSONArray readings = readingsArr.optJSONObject(0).optJSONArray("data");
            Double temp = nearestStationValue(stations, readings, HOME_LAT, HOME_LON);
            if (temp == null) return null;
            JSONArray areas = fcData.optJSONArray("area_metadata");
            JSONArray items = fcData.optJSONArray("items");
            if (areas == null || items == null || items.length() == 0) return null;
            JSONArray forecasts = items.optJSONObject(0).optJSONArray("forecasts");
            String forecast = nearestAreaForecast(areas, forecasts, HOME_LAT, HOME_LON);
            if (forecast == null) return null;
            String word = shortForecast(forecast);
            int code = codeForForecast(forecast);
            String text = Math.round(temp) + "°" + (word.isEmpty() ? "" : " " + word);
            // The two-hour forecast covers the next two hours, so the line is about now.
            String rainLine = isRainingForecast(forecast) ? "Raining now" : "No rain in the next 2 hours";
            return new Reading(text, code, rainLine);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Temperature of the station nearest (lat, lon) that has a reading, or null. */
    static Double nearestStationValue(JSONArray stations, JSONArray readings, double lat, double lon) {
        if (stations == null || readings == null) return null;
        Map<String, Double> values = new HashMap<>();
        for (int i = 0; i < readings.length(); i++) {
            JSONObject r = readings.optJSONObject(i);
            if (r != null) values.put(r.optString("stationId", ""), r.optDouble("value", Double.NaN));
        }
        Double best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < stations.length(); i++) {
            JSONObject s = stations.optJSONObject(i);
            if (s == null) continue;
            JSONObject loc = s.optJSONObject("location");
            if (loc == null) continue;
            Double v = values.get(s.optString("id", ""));
            if (v == null || v.isNaN()) continue;
            double dLat = loc.optDouble("latitude", Double.NaN) - lat;
            double dLon = loc.optDouble("longitude", Double.NaN) - lon;
            if (Double.isNaN(dLat) || Double.isNaN(dLon)) continue;
            double dist = dLat * dLat + dLon * dLon;
            if (dist < bestDist) {
                bestDist = dist;
                best = v;
            }
        }
        return best;
    }

    /** Forecast of the area nearest (lat, lon), or null. */
    static String nearestAreaForecast(JSONArray areas, JSONArray forecasts, double lat, double lon) {
        if (areas == null || forecasts == null) return null;
        Map<String, String> byArea = new HashMap<>();
        for (int i = 0; i < forecasts.length(); i++) {
            JSONObject f = forecasts.optJSONObject(i);
            if (f != null) byArea.put(f.optString("area", ""), f.optString("forecast", null));
        }
        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < areas.length(); i++) {
            JSONObject a = areas.optJSONObject(i);
            if (a == null) continue;
            JSONObject loc = a.optJSONObject("label_location");
            if (loc == null) continue;
            String f = byArea.get(a.optString("name", ""));
            if (f == null) continue;
            double dLat = loc.optDouble("latitude", Double.NaN) - lat;
            double dLon = loc.optDouble("longitude", Double.NaN) - lon;
            if (Double.isNaN(dLat) || Double.isNaN(dLon)) continue;
            double dist = dLat * dLat + dLon * dLon;
            if (dist < bestDist) {
                bestDist = dist;
                best = f;
            }
        }
        return best;
    }

    /** NEA forecast strings are long ("Heavy Thundery Showers with Gusty Winds");
     * shorten to what fits the weather card. */
    static String shortForecast(String forecast) {
        if (forecast == null) return "";
        String f = forecast.trim();
        if (f.startsWith("Heavy ")) f = f.substring("Heavy ".length());
        f = f.replace(" with Gusty Winds", "");
        if (f.equals("Passing Showers")) return "Showers";
        if (f.equals("Fair & Warm") || f.equals("Fair (Day)") || f.equals("Fair (Night)")) return "Fair";
        return f;
    }

    /** Map an NEA forecast onto the WMO code set (keeps isRaining / the rain overlay working). */
    static int codeForForecast(String forecast) {
        if (forecast == null) return -1;
        String f = forecast.toLowerCase(Locale.ROOT);
        if (f.contains("thundery")) return 95;
        if (f.contains("rain")) return 61;
        if (f.contains("shower")) return 80;
        if (f.contains("partly cloudy")) return 2;
        if (f.contains("cloudy")) return 3;
        if (f.contains("hazy") || f.contains("haze")) return 45;
        if (f.contains("fair")) return 0;
        if (f.contains("windy")) return 2;
        return -1;
    }

    /** An NEA forecast that means rain is falling (thundery showers, rain, showers). */
    static boolean isRainingForecast(String forecast) {
        if (forecast == null) return false;
        String f = forecast.toLowerCase(Locale.ROOT);
        return f.contains("thundery") || f.contains("rain") || f.contains("shower");
    }

    /** GET a URL's body, or null on any failure / non-200. */
    private static String get(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Open-Meteo fallback (the previous primary): current temperature + WMO code,
     * rain timing from the hourly precipitation probabilities. */
    private static Reading fetchOpenMeteo() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(OPEN_METEO_URL).openConnection();
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
