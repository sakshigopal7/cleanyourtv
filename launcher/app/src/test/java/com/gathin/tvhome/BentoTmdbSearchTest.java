package com.gathin.tvhome;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for on-TV TMDB search (BentoTmdbSearch): URL resolution and title building. */
public class BentoTmdbSearchTest {

    private static final String SEARCH_HIT = "{\"id\":1288069,\"media_type\":\"movie\",\"title\":\"Tenzing\","
            + "\"original_language\":\"en\",\"release_date\":\"2026-10-01\",\"poster_path\":\"/abc.jpg\","
            + "\"overview\":\"A climber's story.\"}";

    private static JSONObject detailsWith(String homepage, String sgProvider, String inProvider) throws Exception {
        String sg = sgProvider == null ? "[]" : "[{\"provider_id\":8,\"provider_name\":\"" + sgProvider + "\"}]";
        String in = inProvider == null ? "[]" : "[{\"provider_id\":8,\"provider_name\":\"" + inProvider + "\"}]";
        String json = "{\"title\":\"Tenzing\",\"release_date\":\"2026-10-01\",\"poster_path\":\"/abc.jpg\","
                + "\"backdrop_path\":\"/def.jpg\",\"overview\":\"A climber's story.\","
                + "\"original_language\":\"en\","
                + "\"genres\":[{\"id\":18,\"name\":\"Drama\"},{\"id\":12,\"name\":\"Adventure\"}],"
                + "\"homepage\":\"" + homepage + "\","
                + "\"watch/providers\":{\"results\":{"
                + "\"SG\":{\"flatrate\":" + sg + "},"
                + "\"IN\":{\"flatrate\":" + in + "}}}}";
        return new JSONObject(json);
    }

    @Test
    public void netflixHomepageGetsSource30() {
        assertEquals("https://www.netflix.com/title/81720671?source=30",
                BentoTmdbSearch.resolveProviderUrl("https://www.netflix.com/title/81720671"));
    }

    @Test
    public void netflixHomepageWithQueryKeepsIt() {
        assertEquals("https://www.netflix.com/title/81720671?x=1&source=30",
                BentoTmdbSearch.resolveProviderUrl("https://www.netflix.com/title/81720671?x=1"));
    }

    @Test
    public void appleTvHomepagePassesThrough() {
        assertEquals("https://tv.apple.com/in/show/tenzing/umc.cmc.abc",
                BentoTmdbSearch.resolveProviderUrl("https://tv.apple.com/in/show/tenzing/umc.cmc.abc"));
    }

    @Test
    public void primeWithoutGtiIsNotPlayable() {
        assertNull(BentoTmdbSearch.resolveProviderUrl("https://www.primevideo.com/detail/0KRGHGZCHKS920ZQGY5NBRB9K6"));
    }

    @Test
    public void primeWithGtiPassesThrough() {
        String url = "https://app.primevideo.com/detail?gti=amzn1.dv.gti.10b8f041-e945-86f7-b080-6071bb2fc674";
        assertEquals(url, BentoTmdbSearch.resolveProviderUrl(url));
    }

    @Test
    public void unknownHomepageHostIsNotPlayable() {
        assertNull(BentoTmdbSearch.resolveProviderUrl("https://www.a24films.com/films/tenzing"));
    }

    @Test
    public void nullHomepageIsNotPlayable() {
        assertNull(BentoTmdbSearch.resolveProviderUrl(null));
    }

    @Test
    public void displayNamesMatchFeed() {
        assertEquals("Prime Video", BentoTmdbSearch.displayProviderName("Amazon Prime Video"));
        assertEquals("Apple TV", BentoTmdbSearch.displayProviderName("Apple TV Plus"));
        assertEquals("Netflix", BentoTmdbSearch.displayProviderName("Netflix"));
    }

    @Test
    public void netflixTitleBuildsPlayable() throws Exception {
        BentoTitle t = BentoTmdbSearch.buildTitle(new JSONObject(SEARCH_HIT),
                detailsWith("https://www.netflix.com/title/81720671", "Netflix", "Netflix"));
        assertEquals("Tenzing", t.title);
        assertEquals(1288069, t.tmdbId);
        assertEquals(2026, t.year);
        assertEquals("Drama \u00b7 Adventure", t.genreText());
        assertEquals("A climber's story.", t.overview);
        assertTrue(t.isPlayable());
        assertEquals("Netflix", t.provider);
        assertEquals("https://www.netflix.com/title/81720671?source=30", t.providerUrl);
        assertNull(t.requiresVpn);
    }

    @Test
    public void noHomepageMeansChipButNotPlayable() throws Exception {
        BentoTitle t = BentoTmdbSearch.buildTitle(new JSONObject(SEARCH_HIT),
                detailsWith("", "Netflix", null));
        assertFalse(t.isPlayable());
        assertEquals("Netflix", t.provider);
        assertNull(t.providerUrl);
    }

    @Test
    public void inUrlBeatsSgWithoutUrl() throws Exception {
        BentoTitle t = BentoTmdbSearch.buildTitle(new JSONObject(SEARCH_HIT),
                detailsWith("https://www.netflix.com/title/81720671", "Sony Liv", "Netflix"));
        assertTrue(t.isPlayable());
        assertEquals("IN", t.requiresVpn);
        assertEquals("ExpressVPN to India", t.vpnLabel);
    }

    @Test
    public void searchOnlyFallbackIsAddableButNotPlayable() throws Exception {
        BentoTitle t = BentoTmdbSearch.fromSearchOnly(new JSONObject(SEARCH_HIT));
        assertEquals("Tenzing", t.title);
        assertEquals(2026, t.year);
        assertFalse(t.isPlayable());
        assertEquals("A climber's story.", t.overview);
    }

    @Test
    public void parseYearHandlesBadDates() {
        assertEquals(2026, BentoTmdbSearch.parseYear("2026-10-01"));
        assertEquals(0, BentoTmdbSearch.parseYear(null));
        assertEquals(0, BentoTmdbSearch.parseYear("soon"));
    }
}
