package com.gathin.tvhome;

import org.json.JSONObject;

/** One cinema-only title from the Reviewer Collective pipeline, verified screening
 * in Singapore. Carries the TMDB details the details dialog shows (synopsis, year,
 * runtime, language, rating). Any field may be unknown; the dialog omits what is
 * not known rather than inventing it. */
public final class CinemaTitle {
    public final String title;
    public final int tmdbId;
    public final int year;
    public final int runtimeMinutes;
    public final String language;
    public final double rating;
    public final String overview;
    public final String posterPath;     // TMDB poster path, may be null
    public final String posterUrl;      // full w342 URL, may be null
    public final java.util.List<String> genres; // TMDB genre names, may be empty

    /** Convenience: no poster, no genres. */
    public CinemaTitle(String title, int tmdbId, int year, int runtimeMinutes,
                       String language, double rating, String overview) {
        this(title, tmdbId, year, runtimeMinutes, language, rating, overview, null, null);
    }

    public CinemaTitle(String title, int tmdbId, int year, int runtimeMinutes,
                       String language, double rating, String overview,
                       String posterPath, java.util.List<String> genres) {
        this.title = title != null ? title : "";
        this.tmdbId = tmdbId;
        this.year = year;
        this.runtimeMinutes = runtimeMinutes;
        this.language = language != null ? language : "";
        this.rating = rating;
        this.overview = overview != null ? overview : "";
        this.posterPath = posterPath;
        this.posterUrl = posterPath != null ? "https://image.tmdb.org/t/p/w342" + posterPath : null;
        this.genres = genres != null ? genres : new java.util.ArrayList<>();
    }

    public static CinemaTitle fromJson(JSONObject o) {
        if (o == null) return null;
        String title = o.optString("title", "");
        if (title.isEmpty()) return null;
        String posterPath = o.isNull("poster_path") ? null : o.optString("poster_path", null);
        if (posterPath != null && posterPath.trim().isEmpty()) posterPath = null;
        java.util.List<String> genres = new java.util.ArrayList<>();
        org.json.JSONArray ga = o.optJSONArray("genres");
        if (ga != null) {
            for (int i = 0; i < ga.length(); i++) {
                String g = ga.optString(i, null);
                if (g != null && !g.trim().isEmpty()) genres.add(g.trim());
            }
        }
        return new CinemaTitle(title, o.optInt("tmdb_id", 0), o.optInt("year", 0),
                o.optInt("runtime_minutes", 0), o.optString("language", ""),
                o.optDouble("rating", 0), o.optString("overview", ""), posterPath, genres);
    }

    /** At most two genres for the card label ("Animation · Fantasy"); "" when none known. */
    public String genreText() {
        if (genres == null || genres.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, genres.size()); i++) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(genres.get(i));
        }
        return sb.toString();
    }

    /** "2h 12m" / "3h 15m" / "45 min"; "" when the runtime is unknown. */
    public String runtimeText() {
        if (runtimeMinutes <= 0) return "";
        int h = runtimeMinutes / 60, m = runtimeMinutes % 60;
        if (h <= 0) return m + " min";
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    /** "2026 · Hindi · 3h 15m · ★ 7.5" — only the parts that are known. */
    public String metaLine() {
        StringBuilder sb = new StringBuilder();
        if (year > 0) sb.append(year);
        if (!language.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(language);
        }
        String rt = runtimeText();
        if (!rt.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(rt);
        }
        if (rating > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("★ ").append(String.format(java.util.Locale.US, "%.1f", rating));
        }
        return sb.toString();
    }
}
