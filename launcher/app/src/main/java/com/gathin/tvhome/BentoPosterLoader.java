package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TMDB w342 poster loader. No Glide/Coil (each ~0.5-1 MB): BitmapFactory + inSampleSize,
 * a small memory cache and a disk cache under getCacheDir()/posters. Posters are 115x172dp
 * on screen, so decode to ~230x344px and never more.
 */
public final class BentoPosterLoader {
    private static final int TARGET_W = 230;
    private static final int TARGET_H = 344;
    private static final int BACKDROP_W = 960;
    private static final int BACKDROP_H = 540;
    private static BentoPosterLoader instance;
    private final LruCache<String, Bitmap> memCache;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final File cacheDir;
    private final File backdropDir;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    public static synchronized BentoPosterLoader get(Context ctx) {
        if (instance == null) instance = new BentoPosterLoader(ctx.getApplicationContext());
        return instance;
    }

    private BentoPosterLoader(Context ctx) {
        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        memCache = new LruCache<String, Bitmap>(Math.max(2048, maxMemoryKb / 8)) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        cacheDir = new File(ctx.getCacheDir(), "posters");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        backdropDir = new File(ctx.getCacheDir(), "backdrops");
        if (!backdropDir.exists()) backdropDir.mkdirs();
    }

    public void loadPoster(String url, ImageView imageView) {
        load(url, imageView, TARGET_W, TARGET_H, cacheDir, null);
    }

    /**
     * Backdrop art (w780): decoded to ~960x540, so upscaling it to full screen reads soft,
     * like a blur, without the cost of one. onReady fires on the main thread once the bitmap
     * is actually on the view (immediately when memory-cached); it never fires on a failed load,
     * so callers keep the previous backdrop instead of fading to black.
     */
    public void loadBackdrop(String url, ImageView imageView, Runnable onReady) {
        load(url, imageView, BACKDROP_W, BACKDROP_H, backdropDir, onReady);
    }

    private void load(String url, ImageView imageView, int targetW, int targetH, File dir, Runnable onReady) {
        if (imageView == null) return;
        if (url == null || url.trim().isEmpty()) {
            // Clear the tag too: a pending load for a previous binding must not
            // land on this view after it was recycled to an empty slot.
            imageView.setTag(null);
            imageView.setImageDrawable(null);
            return;
        }
        Bitmap cached = memCache.get(url);
        if (cached != null) {
            // Tag FIRST: without this, a stale in-flight load for the view's
            // previous binding passes the tag check below and overwrites the
            // correct bitmap (seen live: The Gentlemen art on the Daayra card).
            imageView.setTag(url);
            imageView.setImageBitmap(cached);
            if (onReady != null) onReady.run();
            return;
        }
        imageView.setImageDrawable(null);
        imageView.setTag(url);
        pool.execute(() -> {
            Bitmap bmp = loadFromDiskOrNetwork(url, targetW, targetH, dir);
            if (bmp != null) {
                memCache.put(url, bmp);
                mainHandler.post(() -> {
                    if (url.equals(imageView.getTag())) {
                        imageView.setImageBitmap(bmp);
                        if (onReady != null) onReady.run();
                    }
                });
            }
        });
    }

    private Bitmap loadFromDiskOrNetwork(String urlStr, int targetW, int targetH, File dir) {
        try {
            File diskFile = new File(dir, Integer.toHexString(urlStr.hashCode()) + ".jpg");
            if (!diskFile.exists() || diskFile.length() == 0) {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(5000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(diskFile)) {
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                        out.flush();
                    }
                }
                conn.disconnect();
            }
            if (diskFile.exists() && diskFile.length() > 0) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(diskFile.getAbsolutePath(), opts);
                int inSampleSize = 1;
                while (opts.outWidth / inSampleSize > targetW || opts.outHeight / inSampleSize > targetH) {
                    inSampleSize *= 2;
                }
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = Math.max(1, inSampleSize);
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeFile(diskFile.getAbsolutePath(), opts);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
