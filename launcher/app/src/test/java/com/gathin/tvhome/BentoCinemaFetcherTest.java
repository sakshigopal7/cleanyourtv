package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the cinema-list validation gate (BentoCinemaFetcher.isValidCinema). */
public class BentoCinemaFetcherTest {

    private static final String GOOD_TITLE = "{\"title\":\"Wildwood\",\"tmdb_id\":791523,\"year\":2026,\"runtime_minutes\":132,\"language\":\"English\",\"rating\":8.5,\"overview\":\"A quest.\",\"poster_path\":\"/snFM00mpNAfUzUtkdEW0uDEvXA0.jpg\",\"genres\":[\"Animation\",\"Fantasy\"]}";
    private static final String GOOD = "{\"verified_on\":\"2026-11-17\",\"source\":\"reviewer-collective\",\"titles\":[" + GOOD_TITLE + "]}";

    @Test
    public void validCinemaPasses() {
        assertTrue(BentoCinemaFetcher.isValidCinema(GOOD));
    }

    @Test
    public void emptyTitlesPasses() {
        // No verified titles this week: the section hides, and that is a valid list.
        assertTrue(BentoCinemaFetcher.isValidCinema("{\"verified_on\":\"2026-11-17\",\"titles\":[]}"));
    }

    @Test
    public void missingTitlesFails() {
        assertFalse(BentoCinemaFetcher.isValidCinema("{\"verified_on\":\"2026-11-17\"}"));
    }

    @Test
    public void missingVerifiedOnFails() {
        assertFalse(BentoCinemaFetcher.isValidCinema("{\"titles\":[" + GOOD_TITLE + "]}"));
    }

    @Test
    public void unparseableVerifiedOnFails() {
        assertFalse(BentoCinemaFetcher.isValidCinema(GOOD.replace("2026-11-17", "yesterday")));
    }

    @Test
    public void malformedJsonFails() {
        assertFalse(BentoCinemaFetcher.isValidCinema("{not json"));
    }

    @Test
    public void nullFails() {
        assertFalse(BentoCinemaFetcher.isValidCinema(null));
    }

    @Test
    public void titleWithoutTmdbIdFails() {
        String cinema = GOOD.replace("\"tmdb_id\":791523", "\"tmdb_id\":0");
        assertFalse(BentoCinemaFetcher.isValidCinema(cinema));
    }

    @Test
    public void titleWithoutPosterFails() {
        String cinema = GOOD.replace(",\"poster_path\":\"/snFM00mpNAfUzUtkdEW0uDEvXA0.jpg\"", "");
        assertFalse(BentoCinemaFetcher.isValidCinema(cinema));
    }

    @Test
    public void titleWithoutTitleFails() {
        String cinema = GOOD.replace("\"title\":\"Wildwood\",", "");
        assertFalse(BentoCinemaFetcher.isValidCinema(cinema));
    }

    @Test
    public void oneBadTitleFailsWholeList() {
        String bad = "{\"title\":\"NoPoster\",\"tmdb_id\":123,\"poster_path\":\"\"}";
        String cinema = "{\"verified_on\":\"2026-11-17\",\"titles\":[" + GOOD_TITLE + "," + bad + "]}";
        assertFalse(BentoCinemaFetcher.isValidCinema(cinema));
    }
}
