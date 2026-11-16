package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bento data: recommendations.json (feed, assets until the daily pipeline lands),
 * mylist.json and skipped.json in getFilesDir(). All writes are atomic:
 * write-temp, fsync, rename, keep a .bak. Dedupe is TMDB ID + media type.
 * A skipped title never re-appears in the For you row until restored.
 */
public final class BentoStore {
    public static final String FILE_RECOMMENDATIONS = "recommendations.json";
    public static final String FILE_MYLIST = "mylist.json";
    public static final String FILE_SKIPPED = "skipped.json";
    private static final int MYLIST_MAX = 100;

    private BentoStore() {}

    // ---------- For you ----------

    public static List<BentoTitle> loadRecommendations(Context ctx) {
        List<BentoTitle> out = new ArrayList<>();
        Set<String> skipped = loadSkippedKeys(ctx);
        Set<String> myList = loadMyListKeys(ctx);
        String json = readInternalOrAsset(ctx, FILE_RECOMMENDATIONS);
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray titles = root.optJSONArray("titles");
            if (titles != null) {
                for (int i = 0; i < titles.length(); i++) {
                    JSONObject item = titles.optJSONObject(i);
                    if (item == null) continue;
                    BentoTitle t = BentoTitle.fromJson(item, false);
                    if (t == null) continue;
                    if (skipped.contains(t.identityKey())) continue;
                    if (myList.contains(t.identityKey())) continue; // in My List: shown there, never twice
                    if (!t.isPlayable()) continue; // never ship a card that cannot play
                    out.add(t.withInMyList(myList.contains(t.identityKey())));
                }
            }
        } catch (Exception ignored) {
        }
        int skippedCount = skipped.size();
        if (skippedCount > 0) out.add(BentoTitle.createSkippedButton(skippedCount));
        return out;
    }

    // ---------- In cinemas ----------

    public static final String FILE_CINEMA = "cinema.json";
    /** A verified cinema list stays live for 7 days ("this week"); older than that, or
     * empty, the section hides rather than showing a stale title. At most two titles. */
    static final int CINEMA_FRESH_DAYS = 7;
    static final int CINEMA_MAX = 2;

    /** Verified RC cinema-only titles screening in Singapore (with their TMDB details
     * for the details dialog), or empty when none is verified. */
    public static List<CinemaTitle> loadCinema(Context ctx) {
        return loadCinemaFromJson(readInternalOrAsset(ctx, FILE_CINEMA), java.time.LocalDate.now());
    }

    static List<CinemaTitle> loadCinemaFromJson(String json, java.time.LocalDate today) {
        List<CinemaTitle> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty() || today == null) return out;
        try {
            JSONObject root = new JSONObject(json);
            String verifiedOn = root.optString("verified_on", null);
            if (verifiedOn == null || verifiedOn.isEmpty()) return out;
            java.time.LocalDate verified = java.time.LocalDate.parse(verifiedOn);
            long age = java.time.temporal.ChronoUnit.DAYS.between(verified, today);
            if (age < 0 || age > CINEMA_FRESH_DAYS) return out;
            JSONArray titles = root.optJSONArray("titles");
            if (titles == null) return out;
            for (int i = 0; i < titles.length() && out.size() < CINEMA_MAX; i++) {
                CinemaTitle ct = CinemaTitle.fromJson(titles.optJSONObject(i));
                if (ct != null) out.add(ct);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** generated_at of the feed currently served (cached copy or bundled seed), or null. */
    public static String feedGeneratedAt(Context ctx) {
        String json = readInternalOrAsset(ctx, FILE_RECOMMENDATIONS);
        if (json == null) return null;
        try {
            return new JSONObject(json).optString("generated_at", null);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---------- My List ----------

    public static List<BentoTitle> loadMyList(Context ctx) {
        List<BentoTitle> out = new ArrayList<>();
        String json = readInternalFile(ctx, FILE_MYLIST);
        if (json == null) {
            json = readAsset(ctx, FILE_MYLIST);
            if (json != null) writeAtomicFile(new File(ctx.getFilesDir(), FILE_MYLIST), json);
        }
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    BentoTitle t = BentoTitle.fromJson(items.optJSONObject(i), true);
                    if (t != null) out.add(t);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static Set<String> loadMyListKeys(Context ctx) {
        Set<String> keys = new HashSet<>();
        for (BentoTitle t : loadMyList(ctx)) keys.add(t.identityKey());
        return keys;
    }

    public static void addToMyList(Context ctx, BentoTitle title) {
        android.util.Log.d("TvHome.Bento", "addToMyList " + title.identityKey());
        List<BentoTitle> list = loadMyList(ctx);
        for (BentoTitle t : list) {
            if (t.identityKey().equals(title.identityKey())) return; // already there
        }
        if (list.size() >= MYLIST_MAX) list.remove(list.size() - 1);
        list.add(0, title);
        saveMyList(ctx, list);
        android.util.Log.d("TvHome.Bento", "addToMyList saved, count=" + list.size());
    }

    public static void removeFromMyList(Context ctx, BentoTitle title) {
        List<BentoTitle> list = loadMyList(ctx);
        List<BentoTitle> kept = new ArrayList<>();
        for (BentoTitle t : list) {
            if (!t.identityKey().equals(title.identityKey())) kept.add(t);
        }
        saveMyList(ctx, kept);
    }

    private static void saveMyList(Context ctx, List<BentoTitle> list) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("updated_at", isoNow());
            root.put("count", list.size());
            JSONArray items = new JSONArray();
            for (BentoTitle t : list) {
                JSONObject o = t.toJson();
                o.put("added_at", isoNow());
                o.put("added_from", "reviewer-collective");
                items.put(o);
            }
            root.put("items", items);
            writeAtomicFile(new File(ctx.getFilesDir(), FILE_MYLIST), root.toString());
        } catch (Exception e) {
            android.util.Log.e("TvHome.Bento", "saveMyList failed", e);
        }
    }

    // ---------- Skipped ----------

    public static Set<String> loadSkippedKeys(Context ctx) {
        Set<String> keys = new HashSet<>();
        String json = readInternalFile(ctx, FILE_SKIPPED);
        if (json == null || json.trim().isEmpty()) return keys;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.optJSONObject(i);
                    if (o == null) continue;
                    String key = o.optString("key", null);
                    if (key == null) {
                        int id = o.optInt("tmdb_id", 0);
                        String type = o.optString("media_type", "movie");
                        key = id > 0 ? (type + "_" + id) : null;
                    }
                    if (key != null) keys.add(key);
                }
            }
        } catch (Exception ignored) {
        }
        return keys;
    }

    public static void skipTitle(Context ctx, BentoTitle title) {
        android.util.Log.d("TvHome.Bento", "skipTitle " + title.identityKey());
        Set<String> keys = loadSkippedKeys(ctx);
        keys.add(title.identityKey());
        saveSkipped(ctx, keys);
        removeFromMyList(ctx, title); // a skipped title leaves My List too
    }

    public static void restoreSkipped(Context ctx) {
        saveSkipped(ctx, new HashSet<String>());
    }

    private static void saveSkipped(Context ctx, Set<String> keys) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("updated_at", isoNow());
            JSONArray items = new JSONArray();
            for (String key : keys) {
                JSONObject o = new JSONObject();
                o.put("key", key);
                String[] parts = key.split("_", 2);
                if (parts.length == 2) {
                    o.put("media_type", parts[0]);
                    try { o.put("tmdb_id", Integer.parseInt(parts[1])); } catch (Exception ignored) {}
                }
                items.put(o);
            }
            root.put("items", items);
            writeAtomicFile(new File(ctx.getFilesDir(), FILE_SKIPPED), root.toString());
        } catch (Exception ignored) {
        }
    }

    // ---------- Play ----------

    /** Fire the titles verbatim provider URL. Pins the intent to the providers package when known. */
    public static void playTitle(Context ctx, BentoTitle title) {
        if (title == null || !title.isPlayable()) {
            Toast.makeText(ctx, "Not streaming on your apps yet", Toast.LENGTH_SHORT).show();
            return;
        }
        if (title.requiresVpn != null && title.vpnLabel != null) {
            Toast.makeText(ctx, title.vpnLabel, Toast.LENGTH_LONG).show();
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(title.providerUrl));
            String pkg = resolvePackage(title.provider, title.providerUrl);
            if (pkg != null) intent.setPackage(pkg);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, "Could not open " + (title.provider != null ? title.provider : "this title"), Toast.LENGTH_SHORT).show();
        }
    }

    static String resolvePackage(String provider, String url) {
        if (provider == null) provider = "";
        String p = provider.toLowerCase();
        if (p.contains("netflix")) return "com.netflix.ninja";
        if (p.contains("prime")) return "com.amazon.amazonvideo.livingroom";
        if (p.contains("youtube")) return "com.google.android.youtube.tv";
        if (p.contains("apple")) return "com.apple.atve.androidtv.appletv";
        if (url != null) {
            if (url.contains("netflix.com")) return "com.netflix.ninja";
            if (url.contains("primevideo.com")) return "com.amazon.amazonvideo.livingroom";
            if (url.contains("youtube.com") || url.contains("youtu.be")) return "com.google.android.youtube.tv";
            if (url.contains("tv.apple.com")) return "com.apple.atve.androidtv.appletv";
        }
        return null;
    }

    // ---------- File IO (atomic) ----------

    static String readInternalOrAsset(Context ctx, String name) {
        String s = readInternalFile(ctx, name);
        return s != null ? s : readAsset(ctx, name);
    }

    static String readInternalFile(Context ctx, String name) {
        File f = new File(ctx.getFilesDir(), name);
        return f.exists() ? readAtomicFile(f) : null;
    }

    static String readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            return readStream(in);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String readAtomicFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            return readStream(in);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Write temp, fsync, rename, keep .bak: a crash mid-write never loses the last good file. */
    static void writeAtomicFile(File target, String content) {
        File tmp = new File(target.getParent(), target.getName() + ".tmp");
        File bak = new File(target.getParent(), target.getName() + ".bak");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            return;
        }
        if (target.exists()) target.renameTo(bak);
        tmp.renameTo(target);
    }

    private static String isoNow() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
                .format(new java.util.Date());
    }
}
