package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for NEA weather (BentoWeather): nearest station/area picks and forecast mapping. */
public class BentoWeatherTest {

    private static final double HOME_LAT = BentoWeather.HOME_LAT;
    private static final double HOME_LON = BentoWeather.HOME_LON;

    private static JSONArray stations() throws Exception {
        return new JSONArray("["
                + "{\"id\":\"S43\",\"name\":\"Kim Chuan Road\",\"location\":{\"latitude\":1.3398,\"longitude\":103.8876}},"
                + "{\"id\":\"S111\",\"name\":\"Scotts Road\",\"location\":{\"latitude\":1.3087,\"longitude\":103.8243}},"
                + "{\"id\":\"S06\",\"name\":\"Paya Lebar\",\"location\":{\"latitude\":1.3504,\"longitude\":103.9064}},"
                + "{\"id\":\"S60\",\"name\":\"Sentosa\",\"location\":{\"latitude\":1.2494,\"longitude\":103.8319}}]");
    }

    private static JSONArray readings() throws Exception {
        return new JSONArray("["
                + "{\"stationId\":\"S43\",\"value\":27.2},"
                + "{\"stationId\":\"S111\",\"value\":25.9},"
                + "{\"stationId\":\"S06\",\"value\":27.4},"
                + "{\"stationId\":\"S60\",\"value\":29.1}]");
    }

    @Test
    public void nearestStationIsKimChuan() throws Exception {
        // Meyer Place (1.2988, 103.887): Kim Chuan Road is the closest station with a reading.
        Double v = BentoWeather.nearestStationValue(stations(), readings(), HOME_LAT, HOME_LON);
        assertEquals(27.2, v, 0.001);
    }

    @Test
    public void nearestStationSkipsMissingReadings() throws Exception {
        JSONArray noKimChuan = new JSONArray("["
                + "{\"stationId\":\"S111\",\"value\":25.9},"
                + "{\"stationId\":\"S06\",\"value\":27.4}]");
        Double v = BentoWeather.nearestStationValue(stations(), noKimChuan, HOME_LAT, HOME_LON);
        assertEquals(27.4, v, 0.001);
    }

    @Test
    public void nearestStationNullWhenNothing() throws Exception {
        assertNull(BentoWeather.nearestStationValue(stations(), new JSONArray("[]"), HOME_LAT, HOME_LON));
        assertNull(BentoWeather.nearestStationValue(null, readings(), HOME_LAT, HOME_LON));
    }

    private static JSONArray areas() throws Exception {
        return new JSONArray("["
                + "{\"name\":\"Marine Parade\",\"label_location\":{\"latitude\":1.3002,\"longitude\":103.9056}},"
                + "{\"name\":\"Geylang\",\"label_location\":{\"latitude\":1.3157,\"longitude\":103.8927}},"
                + "{\"name\":\"Bedok\",\"label_location\":{\"latitude\":1.3240,\"longitude\":103.9300}},"
                + "{\"name\":\"Tuas\",\"label_location\":{\"latitude\":1.3200,\"longitude\":103.6400}}]");
    }

    private static JSONArray forecasts() throws Exception {
        return new JSONArray("["
                + "{\"area\":\"Marine Parade\",\"forecast\":\"Heavy Thundery Showers\"},"
                + "{\"area\":\"Geylang\",\"forecast\":\"Heavy Thundery Showers\"},"
                + "{\"area\":\"Bedok\",\"forecast\":\"Heavy Thundery Showers\"},"
                + "{\"area\":\"Tuas\",\"forecast\":\"Fair\"}]");
    }

    @Test
    public void nearestAreaIsMarineParade() throws Exception {
        String f = BentoWeather.nearestAreaForecast(areas(), forecasts(), HOME_LAT, HOME_LON);
        assertEquals("Heavy Thundery Showers", f);
    }

    @Test
    public void shortForecastStripsHeavyAndGusts() {
        assertEquals("Thundery Showers", BentoWeather.shortForecast("Heavy Thundery Showers with Gusty Winds"));
        assertEquals("Thundery Showers", BentoWeather.shortForecast("Thundery Showers"));
        assertEquals("Showers", BentoWeather.shortForecast("Passing Showers"));
        assertEquals("Fair", BentoWeather.shortForecast("Fair & Warm"));
        assertEquals("Cloudy", BentoWeather.shortForecast("Cloudy"));
        assertEquals("", BentoWeather.shortForecast(null));
    }

    @Test
    public void codeForForecastMapsToWmo() {
        assertEquals(95, BentoWeather.codeForForecast("Heavy Thundery Showers"));
        assertEquals(61, BentoWeather.codeForForecast("Moderate Rain"));
        assertEquals(80, BentoWeather.codeForForecast("Passing Showers"));
        assertEquals(3, BentoWeather.codeForForecast("Cloudy"));
        assertEquals(2, BentoWeather.codeForForecast("Partly Cloudy"));
        assertEquals(0, BentoWeather.codeForForecast("Fair"));
        assertEquals(-1, BentoWeather.codeForForecast(null));
    }

    @Test
    public void rainingForecastMatchesOverlay() {
        assertTrue(BentoWeather.isRainingForecast("Heavy Thundery Showers"));
        assertTrue(BentoWeather.isRainingForecast("Light Rain"));
        assertTrue(BentoWeather.isRainingForecast("Showers"));
        assertFalse(BentoWeather.isRainingForecast("Cloudy"));
        assertFalse(BentoWeather.isRainingForecast("Fair"));
        // The mapped code drives the same overlay decision.
        assertTrue(BentoWeather.isRaining(BentoWeather.codeForForecast("Heavy Thundery Showers")));
        assertFalse(BentoWeather.isRaining(BentoWeather.codeForForecast("Cloudy")));
    }

    @Test
    public void rainLineLogicUnchanged() {
        assertEquals("Raining now", BentoWeather.rainLineFor(95, null));
        assertEquals("No rain expected today", BentoWeather.rainLineFor(0, null));
        assertEquals("No rain expected today", BentoWeather.rainLineFor(0, new JSONObject()));
    }
}
