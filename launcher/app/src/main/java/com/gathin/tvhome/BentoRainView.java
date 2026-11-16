package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Slice 6: a subtle animated rain/drizzle overlay for the wallpaper, shown only while the live
 * Open-Meteo weather says it is actually raining or drizzling in Singapore. Thin, sparse,
 * slow-falling streaks on a transparent full-screen view: no new libraries, no bitmaps, no
 * per-frame allocation. The view takes no focus, clicks or accessibility, and stops drawing
 * entirely while hidden.
 */
public final class BentoRainView extends View {
    private static final int DROPS = 70;
    private final float density;
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] x = new float[DROPS];
    private final float[] y = new float[DROPS];
    private final float[] speed = new float[DROPS]; // px per second
    private final float[] len = new float[DROPS];   // px
    private final float[] slant = new float[DROPS]; // horizontal drift per px fallen
    private boolean raining;
    private long lastFrameMs;

    public BentoRainView(Context context) {
        this(context, null);
    }

    public BentoRainView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BentoRainView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(64);
        paint.setStrokeWidth(1.5f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < DROPS; i++) resetDrop(i, true);
    }

    public void setRaining(boolean raining) {
        if (this.raining == raining) return;
        this.raining = raining;
        if (raining) {
            setVisibility(VISIBLE);
            lastFrameMs = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        } else {
            setVisibility(GONE);
        }
    }

    private void resetDrop(int i, boolean scatterY) {
        x[i] = rnd.nextFloat();
        y[i] = scatterY ? rnd.nextFloat() : -0.05f;
        speed[i] = (520 + rnd.nextFloat() * 420) * density;
        len[i] = (16 + rnd.nextFloat() * 12) * density;
        slant[i] = -0.06f - rnd.nextFloat() * 0.06f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!raining || getVisibility() != VISIBLE) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.max(0f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            postInvalidateOnAnimation();
            return;
        }
        for (int i = 0; i < DROPS; i++) {
            y[i] += (speed[i] * dt) / h;
            if (y[i] > 1.05f) resetDrop(i, false);
            float px = x[i] * w + slant[i] * y[i] * h;
            float py = y[i] * h;
            canvas.drawLine(px, py, px + slant[i] * len[i], py + len[i], paint);
        }
        postInvalidateOnAnimation();
    }
}
