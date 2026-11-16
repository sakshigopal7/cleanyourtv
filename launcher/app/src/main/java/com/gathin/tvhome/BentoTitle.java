package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

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
    public final boolean isInMyList;
    public final boolean isSkippedButton;
    public final int skippedCount;

    BentoTitle(String title, String mediaType, int tmdbId, String tmdbType, String imdbId,
                       String posterPath, String posterUrl, int year, String language,
                       String firstSeen, String lastSeen, String postUrl,
                       String providerUrl, String provider, String urlSource,
                       String requiresVpn, String vpnLabel,
                       boolean isInMyList, boolean isSkippedButton, int skippedCount) {
        this.title = title;
        this.mediaType = mediaType != null ? mediaType : "movie";
        this.tmdbId = tmdbId;
        this.tmdbType = tmdbType != null ? tmdbType : this.mediaType;
        this.imdbId = imdbId;
        this.posterPath = posterPath;
        this.posterUrl = posterUrl;
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
        this.isInMyList = isInMyList;
        this.isSkippedButton = isSkippedButton;
        this.skippedCount = skippedCount;
    }

    /** Identity for dedupe: TMDB ID + media type, falling back to the title. */
    public String identityKey() {
        return tmdbId > 0 ? (mediaType + "_" + tmdbId) : ("title_" + title);
    }

    /** Same title, with the My List flag changed. */
    public BentoTitle withInMyList(boolean inMyList) {
        return new BentoTitle(title, mediaType, tmdbId, tmdbType, imdbId, posterPath, posterUrl,
                year, language, firstSeen, lastSeen, postUrl, providerUrl, provider, urlSource,
                requiresVpn, vpnLabel, inMyList, isSkippedButton, skippedCount);
    }

    public boolean isPlayable() {
        return providerUrl != null && !providerUrl.trim().isEmpty();
    }

    public static BentoTitle createSkippedButton(int count) {
        return new BentoTitle("Skipped (" + count + ")", "action", -999, "action", null,
                null, null, 0, null, null, null, null,
                null, null, null, null, null, false, true, count);
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
        int year = item.optInt("year", 0);
        String language = item.optString("language", "");
        String firstSeen = optNullable(item, "first_seen");
        String lastSeen = optNullable(item, "last_seen");
        String postUrl = optNullable(item, "post_url");

        // Availability: prefer SG, then IN (with its VPN label), then any entry.
        String providerUrl = null, provider = null, urlSource = null, requiresVpn = null, vpnLabel = null;
        JSONArray avail = item.optJSONArray("availability");
        if (avail != null) {
            JSONObject pick = pickAvailability(avail, "SG");
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
                year, language, firstSeen, lastSeen, postUrl,
                providerUrl, provider, urlSource, requiresVpn, vpnLabel,
                inMyList, false, 0);
    }

    private static JSONObject pickAvailability(JSONArray avail, String country) {
        for (int i = 0; i < avail.length(); i++) {
            JSONObject a = avail.optJSONObject(i);
            if (a != null && country.equalsIgnoreCase(a.optString("country", ""))) return a;
        }
        return null;
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
            o.put("year", year);
            if (language != null && !language.isEmpty()) o.put("language", language);
            if (firstSeen != null) o.put("first_seen", firstSeen);
            if (lastSeen != null) o.put("last_seen", lastSeen);
            if (postUrl != null) o.put("post_url", postUrl);
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
