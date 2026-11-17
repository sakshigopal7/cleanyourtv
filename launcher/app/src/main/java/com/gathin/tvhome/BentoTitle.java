package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One title in the Reviewer Collective feed or My List, in the full design-doc schema.
 * Every field the pipeline publishes is kept: nothing is stripped to fit an arbitrary size gate.
 * A title with no playable provider_url is never offered as a playable card; callers check
 * {@link #isPlayable()} before wiring short-OK to it.
 */
public final class BentoTitle {
    public final String title;
    public final String mediaType;      // "movie" | "tv"
    public final int tmdbId;
    public final String tmdbType;       // "movie" | "tv"
    public final String imdbId;         // may be null when unknown
    public final String posterPath;     // TMDB poster path, e.g. "/abc.jpg"
    public final String posterUrl;      // full w342 URL, may be null
    public final String backdropPath;   // TMDB backdrop path, may be null (feed may not carry it yet)
    public final String backdropUrl;    // full w780 URL, may be null
    public final int year;
    public final String language;
    public final String firstSeen;
    public final String lastSeen;
    public final String postUrl;        // Reviewer Collective post, may be null
    public final String providerUrl;    // verbatim URL the launcher fires, may be null
    public final String provider;       // e.g. "Netflix", "Prime Video"
    public final String urlSource;      // watchmode_web_url | justwatch_standardWebURL
    public final String requiresVpn;    // null | "IN" | ...
    public final String vpnLabel;       // e.g. "ExpressVPN to India", may be null
    public final List<String> genres;   // TMDB genre names, may be empty; never invented
    public final String overview;       // TMDB synopsis, may be null (search results carry it)
    public final boolean isInMyList;
    public final boolean isSkippedButton;
    public final int skippedCount;
    public final boolean isSearchTile;  // synthetic "Search" tile at the head of the For you row

    BentoTitle(String title, String mediaType, int tmdbId, String tmdbType, String imdbId,
                       String posterPath, String posterUrl, String backdropPath, String backdropUrl,
                       int year, String language,
                       String firstSeen, String lastSeen, String postUrl,
                       String providerUrl, String provider, String urlSource,
                       String requiresVpn, String vpnLabel, List<String> genres,
                       boolean isInMyList, boolean isSkippedButton, int skippedCount,
                       String overview, boolean isSearchTile) {
        this.title = title;
        this.mediaType = mediaType != null ? mediaType : "movie";
        this.tmdbId = tmdbId;
        this.tmdbType = tmdbType != null ? tmdbType : this.mediaType;
        this.imdbId = imdbId;
        this.posterPath = posterPath;
        this.posterUrl = posterUrl;
        this.backdropPath = backdropPath;
        this.backdropUrl = backdropUrl;
        this.year = year;
        this.language = language;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.postUrl = postUrl;
        this.providerUrl = providerUrl;
        this.provider = provider;
        this.urlSource = urlSource;
        this.requiresVpn = requiresVpn;
        this.vpnLabel = vpnLabel;
        this.genres = genres != null ? genres : new ArrayList<>();
        this.overview = overview;
        this.isInMyList = isInMyList;
        this.isSkippedButton = isSkippedButton;
        this.skippedCount = skippedCount;
        this.isSearchTile = isSearchTile;
    }

    /** Identity for dedupe: TMDB ID + media type, falling back to the title. */
    public String identityKey() {
        return tmdbId > 0 ? (mediaType + "_" + tmdbId) : ("title_" + title);
    }

    /** Same title, with the My List flag changed. */
    public BentoTitle withInMyList(boolean inMyList) {
        return new BentoTitle(title, mediaType, tmdbId, tmdbType, imdbId, posterPath, posterUrl,
                backdropPath, backdropUrl,
                year, language, firstSeen, lastSeen, postUrl, providerUrl, provider, urlSource,
                requiresVpn, vpnLabel, genres, inMyList, isSkippedButton, skippedCount,
                overview, isSearchTile);
    }

    public boolean isPlayable() {
        return providerUrl != null && !providerUrl.trim().isEmpty();
    }

    /** True when the feed first saw this title today (Singapore date): the "New" treatment. */
    public boolean isNewToday() {
        if (firstSeen == null || firstSeen.length() < 10) return false;
        return firstSeen.substring(0, 10).equals(todaySgt());
    }

    private static String todaySgt() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Singapore"));
        return f.format(new java.util.Date());
    }

    public static BentoTitle createSkippedButton(int count) {
        return new BentoTitle("Removed (" + count + ")", "action", -999, "action", null,
                null, null, null, null, 0, null, null, null, null,
                null, null, null, null, null, null, false, true, count, null, false);
    }

    /** Synthetic tile at the head of the For you row: OK opens the TMDB search dialog. */
    public static BentoTitle createSearchTile() {
        return new BentoTitle("Search", "action", -998, "action", null,
                null, null, null, null, 0, null, null, null, null,
                null, null, null, null, null, null, false, false, 0, null, true);
    }

    /** At most two genres for the card label ("Drama · Comedy"); "" when none are known. */
    public String genreText() {
        if (genres == null || genres.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, genres.size()); i++) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(genres.get(i));
        }
        return sb.toString();
    }

    /** Parse one title object from recommendations.json / mylist.json (full schema). */
    public static BentoTitle fromJson(JSONObject item, boolean inMyList) {
        if (item == null) return null;
        String title = item.optString("title", "");
        String mediaType = item.optString("media_type", "tv");
        int tmdbId = item.optInt("tmdb_id", 0);
        String tmdbType = item.optString("tmdb_type", mediaType);
        String imdbId = optNullable(item, "imdb_id");
        String posterPath = optNullable(item, "poster_path");
        String posterUrl = optNullable(item, "poster_url");
        if (posterUrl == null && posterPath != null) {
            posterUrl = "https://image.tmdb.org/t/p/w342" + posterPath;
        }
        String backdropPath = optNullable(item, "backdrop_path");
        String backdropUrl = backdropPath != null ? "https://image.tmdb.org/t/p/w780" + backdropPath : null;
        int year = item.optInt("year", 0);
        String language = item.optString("language", "");
        String firstSeen = optNullable(item, "first_seen");
        String lastSeen = optNullable(item, "last_seen");
        String postUrl = optNullable(item, "post_url");
        String overview = optNullable(item, "overview");
        List<String> genres = new ArrayList<>();
        JSONArray ga = item.optJSONArray("genres");
        if (ga != null) {
            for (int i = 0; i < ga.length(); i++) {
                String g = ga.optString(i, null);
                if (g != null && !g.trim().isEmpty()) genres.add(g.trim());
            }
        }

        // Availability: prefer SG, then IN (with its VPN label), then any entry.
        // Within a country, prefer an entry with a playable URL; and if the SG
        // pick has no verified link but the IN pick does, take the IN link — a
        // title must not read "not streaming" while a playable IN source exists.
        String providerUrl = null, provider = null, urlSource = null, requiresVpn = null, vpnLabel = null;
        JSONArray avail = item.optJSONArray("availability");
        if (avail != null) {
            JSONObject pick = pickAvailability(avail, "SG");
            if (pick != null && optNullable(pick, "provider_url") == null) {
                JSONObject inPick = pickAvailability(avail, "IN");
                if (inPick != null && optNullable(inPick, "provider_url") != null) pick = inPick;
            }
            if (pick == null) pick = pickAvailability(avail, "IN");
            if (pick == null && avail.length() > 0) pick = avail.optJSONObject(0);
            if (pick != null) {
                providerUrl = optNullable(pick, "provider_url");
                provider = optNullable(pick, "provider");
                urlSource = optNullable(pick, "url_source");
                requiresVpn = optNullable(pick, "requires_vpn");
                vpnLabel = optNullable(pick, "vpn_label");
            }
        }
        return new BentoTitle(title, mediaType, tmdbId, tmdbType, imdbId, posterPath, posterUrl,
                backdropPath, backdropUrl,
                year, language, firstSeen, lastSeen, postUrl,
                providerUrl, provider, urlSource, requiresVpn, vpnLabel, genres,
                inMyList, false, 0, overview, false);
    }

    /**
     * First entry for the country, preferring one with a playable provider URL:
     * a search result whose SG entry has no verified link must not shadow an IN
     * entry that does (and vice versa). Feed entries always carry a URL, so the
     * feed is unaffected.
     */
    private static JSONObject pickAvailability(JSONArray avail, String country) {
        JSONObject fallback = null;
        for (int i = 0; i < avail.length(); i++) {
            JSONObject a = avail.optJSONObject(i);
            if (a != null && country.equalsIgnoreCase(a.optString("country", ""))) {
                if (fallback == null) fallback = a;
                if (optNullable(a, "provider_url") != null) return a;
            }
        }
        return fallback;
    }

    static String optNullable(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String v = o.optString(key, null);
        return (v == null || v.trim().isEmpty()) ? null : v;
    }

    /** Serialise back to the full schema (used when My List is saved). */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("title", title);
            o.put("media_type", mediaType);
            o.put("tmdb_id", tmdbId);
            o.put("tmdb_type", tmdbType);
            if (imdbId != null) o.put("imdb_id", imdbId);
            if (posterPath != null) o.put("poster_path", posterPath);
            if (posterUrl != null) o.put("poster_url", posterUrl);
            if (backdropPath != null) o.put("backdrop_path", backdropPath);
            if (genres != null && !genres.isEmpty()) {
                JSONArray g = new JSONArray();
                for (String s : genres) g.put(s);
                o.put("genres", g);
            }
            o.put("year", year);
            if (language != null && !language.isEmpty()) o.put("language", language);
            if (firstSeen != null) o.put("first_seen", firstSeen);
            if (lastSeen != null) o.put("last_seen", lastSeen);
            if (postUrl != null) o.put("post_url", postUrl);
            if (overview != null) o.put("overview", overview);
            JSONArray avail = new JSONArray();
            if (providerUrl != null) {
                JSONObject a = new JSONObject();
                a.put("country", requiresVpn != null ? requiresVpn : "SG");
                if (provider != null) a.put("provider", provider);
                a.put("provider_url", providerUrl);
                if (urlSource != null) a.put("url_source", urlSource);
                if (requiresVpn != null) a.put("requires_vpn", requiresVpn);
                if (vpnLabel != null) a.put("vpn_label", vpnLabel);
                avail.put(a);
            }
            o.put("availability", avail);
        } catch (Exception ignored) {
        }
        return o;
    }
}
