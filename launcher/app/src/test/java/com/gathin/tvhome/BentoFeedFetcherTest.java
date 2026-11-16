package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the live-feed validation gate (BentoFeedFetcher.isValidFeed). */
public class BentoFeedFetcherTest {

    private static final String GOOD_TITLE = "{\"title\":\"Baththa\",\"media_type\":\"movie\",\"tmdb_id\":1515692,\"poster_path\":\"/abc.jpg\",\"availability\":[{\"country\":\"SG\",\"provider\":\"Netflix\",\"provider_url\":\"https://www.netflix.com/title/83064751?source=30\"}]}";
    private static final String BAD_TITLE_NO_URL = "{\"title\":\"NoLink\",\"media_type\":\"movie\",\"tmdb_id\":123,\"poster_path\":\"/abc.jpg\",\"availability\":[{\"country\":\"SG\",\"provider\":\"Netflix\"}]}";
    private static final String GOOD = "{\"version\":1,\"generated_at\":\"2026-11-16T18:53:00+08:00\",\"count\":1,\"titles\":[" + GOOD_TITLE + "]}";

    @Test
    public void validFeedPasses() {
        assertTrue(BentoFeedFetcher.isValidFeed(GOOD));
    }

    @Test
    public void emptyTitlesFails() {
        assertFalse(BentoFeedFetcher.isValidFeed("{\"titles\":[]}"));
    }

    @Test
    public void missingTitlesFails() {
        assertFalse(BentoFeedFetcher.isValidFeed("{}"));
    }

    @Test
    public void malformedJsonFails() {
        assertFalse(BentoFeedFetcher.isValidFeed("{not json"));
    }

    @Test
    public void nullFails() {
        assertFalse(BentoFeedFetcher.isValidFeed(null));
    }

    @Test
    public void titleWithoutTmdbIdFails() {
        String feed = GOOD.replace("\"tmdb_id\":1515692", "\"tmdb_id\":0");
        assertFalse(BentoFeedFetcher.isValidFeed(feed));
    }

    @Test
    public void titleWithoutPosterFails() {
        String feed = GOOD.replace(",\"poster_path\":\"/abc.jpg\"", "");
        assertFalse(BentoFeedFetcher.isValidFeed(feed));
    }

    @Test
    public void titleWithoutProviderUrlFails() {
        String feed = "{\"titles\":[" + BAD_TITLE_NO_URL + "]}";
        assertFalse(BentoFeedFetcher.isValidFeed(feed));
    }

    @Test
    public void titleWithoutTitleFails() {
        String feed = GOOD.replace("\"title\":\"Baththa\",", "");
        assertFalse(BentoFeedFetcher.isValidFeed(feed));
    }

    @Test
    public void oneBadTitleFailsWholeFeed() {
        String feed = "{\"titles\":[" + GOOD_TITLE + "," + BAD_TITLE_NO_URL + "]}";
        assertFalse(BentoFeedFetcher.isValidFeed(feed));
    }
}
