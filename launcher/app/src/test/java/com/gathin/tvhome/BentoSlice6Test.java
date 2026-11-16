package com.gathin.tvhome;

import org.json.JSONObject;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for Slice 6: backdrop parsing, new-today, rain codes, rain line, cinema. */
public class BentoSlice6Test {

    private static final String AVAIL = "\"availability\":[{\"country\":\"SG\",\"provider\":\"Netflix\",\"provider_url\":\"https://www.netflix.com/title/83064751?source=30\"}]";

    private static String titleJson(String title, int id, String extra) {
        return "{\"title\":\"" + title + "\",\"media_type\":\"movie\",\"tmdb_id\":" + id
                + ",\"poster_path\":\"/abc.jpg\"," + AVAIL + extra + "}";
    }

    private static String todaySgt() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Singapore"));
        return f.format(new Date());
    }

    private static String yesterdaySgt() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Singapore"));
        return f.format(new Date(System.currentTimeMillis() - 24L * 60L * 60L * 1000L));
    }

    @Test
    public void backdropPathBuildsW780Url() throws Exception {
        BentoTitle t = BentoTitle.fromJson(
                new JSONObject(titleJson("Baththa", 1515692, ",\"backdrop_path\":\"/xyz.jpg\"")), false);
        assertEquals("/xyz.jpg", t.backdropPath);
        assertEquals("https://image.tmdb.org/t/p/w780/xyz.jpg", t.backdropUrl);
    }

    @Test
    public void missingBackdropPathIsNull() throws Exception {
        BentoTitle t = BentoTitle.fromJson(new JSONObject(titleJson("Baththa", 1515692, "")), false);
        assertNull(t.backdropPath);
        assertNull(t.backdropUrl);
    }

    @Test
    public void genresParsedMaxTwoShown() throws Exception {
        BentoTitle t = BentoTitle.fromJson(new JSONObject(titleJson("Baththa", 1515692,
                ",\"genres\":[\"Drama\",\"Comedy\",\"Action\"]")), false);
        assertEquals("Drama · Comedy", t.genreText());
        assertEquals(3, t.genres.size());
    }

    @Test
    public void genresAbsentMeansNoLabel() throws Exception {
        BentoTitle t = BentoTitle.fromJson(new JSONObject(titleJson("Baththa", 1515692, "")), false);
        assertEquals("", t.genreText());
        assertTrue(t.genres.isEmpty());
    }

    @Test
    public void genresSurviveRoundTrip() throws Exception {
        BentoTitle t = BentoTitle.fromJson(new JSONObject(titleJson("Baththa", 1515692,
                ",\"genres\":[\"Drama\",\"Comedy\"]")), false);
        assertEquals("Drama", t.toJson().optJSONArray("genres").optString(0));
        assertEquals("Comedy", t.toJson().optJSONArray("genres").optString(1));
    }

    @Test
    public void cinemaGenresAndPoster() throws Exception {
        JSONObject o = new JSONObject("{\"title\":\"Wildwood\",\"tmdb_id\":791523,\"year\":2026,"
                + "\"runtime_minutes\":132,\"language\":\"English\",\"rating\":8.5,"
                + "\"poster_path\":\"/snFM00mpNAfUzUtkdEW0uDEvXA0.jpg\","
                + "\"genres\":[\"Animation\",\"Fantasy\",\"Adventure\"],\"overview\":\"x\"}");
        CinemaTitle ct = CinemaTitle.fromJson(o);
        assertEquals("https://image.tmdb.org/t/p/w342/snFM00mpNAfUzUtkdEW0uDEvXA0.jpg", ct.posterUrl);
        assertEquals("Animation · Fantasy", ct.genreText());
    }

    @Test
    public void cinemaWithoutGenresOrPoster() throws Exception {
        JSONObject o = new JSONObject("{\"title\":\"X\",\"tmdb_id\":1,\"overview\":\"y\"}");
        CinemaTitle ct = CinemaTitle.fromJson(o);
        assertNull(ct.posterUrl);
        assertEquals("", ct.genreText());
    }

    @Test
    public void backdropSurvivesRoundTrip() throws Exception {
        BentoTitle t = BentoTitle.fromJson(
                new JSONObject(titleJson("Baththa", 1515692, ",\"backdrop_path\":\"/xyz.jpg\"")), false);
        assertEquals("/xyz.jpg", t.toJson().optString("backdrop_path"));
    }

    @Test
    public void feedWithBackdropStillValid() {
        String feed = "{\"version\":1,\"count\":1,\"titles\":["
                + titleJson("Baththa", 1515692, ",\"backdrop_path\":\"/xyz.jpg\"") + "]}";
        assertTrue(BentoFeedFetcher.isValidFeed(feed));
    }

    @Test
    public void firstSeenTodayIsNew() throws Exception {
        BentoTitle t = BentoTitle.fromJson(
                new JSONObject(titleJson("Baththa", 1515692, ",\"first_seen\":\"" + todaySgt() + "\"")), false);
        assertTrue(t.isNewToday());
    }

    @Test
    public void firstSeenYesterdayIsNotNew() throws Exception {
        BentoTitle t = BentoTitle.fromJson(
                new JSONObject(titleJson("Baththa", 1515692, ",\"first_seen\":\"" + yesterdaySgt() + "\"")), false);
        assertFalse(t.isNewToday());
    }

    @Test
    public void noFirstSeenIsNotNew() throws Exception {
        BentoTitle t = BentoTitle.fromJson(new JSONObject(titleJson("Baththa", 1515692, "")), false);
        assertFalse(t.isNewToday());
    }

    @Test
    public void rainCodes() {
        assertTrue(BentoWeather.isRaining(51));
        assertTrue(BentoWeather.isRaining(61));
        assertTrue(BentoWeather.isRaining(80));
        assertTrue(BentoWeather.isRaining(95));
        assertFalse(BentoWeather.isRaining(0));
        assertFalse(BentoWeather.isRaining(3));
        assertFalse(BentoWeather.isRaining(45));
        assertFalse(BentoWeather.isRaining(-1));
    }

    // ---------- Rain timing line ----------

    private static JSONObject hourlyJson(int[] hoursFromNow, int[] probs) throws Exception {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Singapore"))
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        org.json.JSONArray times = new org.json.JSONArray();
        org.json.JSONArray p = new org.json.JSONArray();
        for (int i = 0; i < hoursFromNow.length; i++) {
            times.put(now.plusHours(hoursFromNow[i]).toString());
            p.put(probs[i]);
        }
        JSONObject hourly = new JSONObject();
        hourly.put("time", times);
        hourly.put("precipitation_probability", p);
        return hourly;
    }

    @Test
    public void rainLineRainingNow() throws Exception {
        assertEquals("Raining now", BentoWeather.rainLineFor(61, hourlyJson(new int[]{1}, new int[]{0})));
    }

    @Test
    public void rainLineInAboutAnHour() throws Exception {
        assertEquals("Rain in about an hour",
                BentoWeather.rainLineFor(0, hourlyJson(new int[]{1, 2}, new int[]{70, 0})));
    }

    @Test
    public void rainLineInAboutThreeHours() throws Exception {
        assertEquals("Rain in about 3 hours",
                BentoWeather.rainLineFor(0, hourlyJson(new int[]{1, 2, 3}, new int[]{10, 20, 80})));
    }

    @Test
    public void rainLineNoRainToday() throws Exception {
        assertEquals("No rain expected today",
                BentoWeather.rainLineFor(0, hourlyJson(new int[]{1, 2, 3}, new int[]{10, 20, 30})));
    }

    @Test
    public void rainLineBeyondTwelveHoursIgnored() throws Exception {
        assertEquals("No rain expected today",
                BentoWeather.rainLineFor(0, hourlyJson(new int[]{13}, new int[]{90})));
    }

    @Test
    public void rainLineNullHourly() {
        assertEquals("No rain expected today", BentoWeather.rainLineFor(0, null));
    }

    // ---------- In cinemas: fresh only, at most two ----------

    private static String cinemaJson(String verifiedOn, String... titles) {
        StringBuilder sb = new StringBuilder("{\"verified_on\":\"" + verifiedOn + "\",\"titles\":[");
        for (int i = 0; i < titles.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"title\":\"").append(titles[i]).append("\"}");
        }
        return sb.append("]}").toString();
    }

    @Test
    public void cinemaFreshReturnsTitles() {
        java.util.List<CinemaTitle> out = BentoStore.loadCinemaFromJson(
                cinemaJson(todaySgt(), "Wildwood", "Ramayana Part 1"), java.time.LocalDate.now());
        assertEquals(2, out.size());
        assertEquals("Wildwood", out.get(0).title);
        assertEquals("Ramayana Part 1", out.get(1).title);
    }

    @Test
    public void cinemaStaleHides() {
        java.time.LocalDate eightDaysAgo = java.time.LocalDate.now().minusDays(8);
        java.util.List<CinemaTitle> out = BentoStore.loadCinemaFromJson(
                cinemaJson(eightDaysAgo.toString(), "Wildwood"), java.time.LocalDate.now());
        assertTrue(out.isEmpty());
    }

    @Test
    public void cinemaCapsAtTwo() {
        java.util.List<CinemaTitle> out = BentoStore.loadCinemaFromJson(
                cinemaJson(todaySgt(), "One", "Two", "Three"), java.time.LocalDate.now());
        assertEquals(2, out.size());
    }

    @Test
    public void cinemaEmptyOrUndatedHides() {
        assertTrue(BentoStore.loadCinemaFromJson(cinemaJson(todaySgt()), java.time.LocalDate.now()).isEmpty());
        assertTrue(BentoStore.loadCinemaFromJson("{\"titles\":[{\"title\":\"Wildwood\"}]}",
                java.time.LocalDate.now()).isEmpty());
        assertTrue(BentoStore.loadCinemaFromJson(null, java.time.LocalDate.now()).isEmpty());
    }

    @Test
    public void cinemaDetailsParse() throws Exception {
        String json = "{\"verified_on\":\"" + todaySgt() + "\",\"titles\":[{"
                + "\"title\":\"Wildwood\",\"tmdb_id\":791523,\"year\":2026,"
                + "\"runtime_minutes\":132,\"language\":\"English\",\"rating\":8.5,"
                + "\"overview\":\"Prue and her classmate Curtis are drawn into a magical forest.\"}]}";
        java.util.List<CinemaTitle> out = BentoStore.loadCinemaFromJson(json, java.time.LocalDate.now());
        assertEquals(1, out.size());
        CinemaTitle ct = out.get(0);
        assertEquals("Wildwood", ct.title);
        assertEquals(791523, ct.tmdbId);
        assertEquals(2026, ct.year);
        assertEquals(132, ct.runtimeMinutes);
        assertEquals("English", ct.language);
        assertEquals(8.5, ct.rating, 0.001);
        assertTrue(ct.overview.startsWith("Prue and her classmate"));
        assertEquals("2h 12m", ct.runtimeText());
        assertEquals("2026 · English · 2h 12m · ★ 8.5", ct.metaLine());
    }

    @Test
    public void cinemaRuntimeText() {
        assertEquals("3h 15m", new CinemaTitle("X", 1, 2026, 195, "Hindi", 7.5, "").runtimeText());
        assertEquals("45 min", new CinemaTitle("X", 1, 2026, 45, "Hindi", 7.5, "").runtimeText());
        assertEquals("2h", new CinemaTitle("X", 1, 2026, 120, "Hindi", 7.5, "").runtimeText());
        assertEquals("", new CinemaTitle("X", 1, 2026, 0, "Hindi", 7.5, "").runtimeText());
    }

    @Test
    public void cinemaMetaLineOmitsUnknowns() {
        assertEquals("2026 · Hindi", new CinemaTitle("X", 1, 2026, 0, "Hindi", 0, "").metaLine());
        assertEquals("", new CinemaTitle("X", 1, 0, 0, "", 0, "").metaLine());
        assertEquals("★ 7.5", new CinemaTitle("X", 1, 0, 0, "", 7.5, "").metaLine());
    }
}
