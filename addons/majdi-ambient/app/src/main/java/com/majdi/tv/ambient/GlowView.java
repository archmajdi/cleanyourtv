package com.majdi.tv.ambient;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Three large, soft colour glows drifting very slowly behind the text, in colours that follow the
 * time of day, with faint twinkling stars at night. Drawn at about 20 frames a second: no blur, no
 * bitmaps, so it stays light on a 1 GB TV.
 */
final class GlowView extends View {
    private static final long FRAME_MS = 50;

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[][] stars = new float[70][4]; // x, y, phase, radius
    private final long t0 = SystemClock.uptimeMillis();
    private final float density;

    private int[] colors = {0xFF1E2A6E, 0xFF4A2A7A, 0xFF0F4C5C};
    private int[] target = colors;
    private float starLevel = 1f;
    private float starTarget = 1f;
    private boolean running;
    private boolean painted;

    private final Runnable frame = this::invalidate;

    GlowView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        Random r = new Random(7);
        for (float[] s : stars) {
            s[0] = r.nextFloat();
            s[1] = r.nextFloat() * 0.8f;
            s[2] = r.nextFloat() * 6.28f;
            s[3] = (0.6f + r.nextFloat() * 1.1f) * density;
        }
    }

    /** Colours for the time of day; the change fades in over a minute or so. */
    void setPalette(int[] palette, boolean showStars) {
        target = palette;
        starTarget = showStars ? 1f : 0f;
        if (!painted || colors.length != target.length) {
            colors = target.clone();
            starLevel = starTarget;
            painted = true;
        }
    }

    void setRunning(boolean on) {
        running = on;
        removeCallbacks(frame);
        if (on) invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        double t = (SystemClock.uptimeMillis() - t0) / 1000.0;

        // Ease the colours and the stars toward the time-of-day target.
        int[] eased = new int[target.length];
        for (int i = 0; i < target.length; i++) eased[i] = mix(colors[i], target[i], 0.004f);
        colors = eased;
        starLevel += (starTarget - starLevel) * 0.004f;

        float big = Math.max(w, h);
        for (int i = 0; i < colors.length; i++) {
            float cx = (float) (w * (0.5 + 0.40 * Math.sin(t * (0.019 + 0.006 * i) + i * 2.1)));
            float cy = (float) (h * (0.5 + 0.34 * Math.cos(t * (0.015 + 0.005 * i) + i * 1.3)));
            float r = (float) (big * (0.50 + 0.08 * Math.sin(t * 0.04 + i * 1.7)));
            int c = colors[i];
            glow.setShader(new RadialGradient(cx, cy, r,
                    new int[]{alpha(c, 120), alpha(c, 45), alpha(c, 0)},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, glow);
        }

        if (starLevel > 0.02f) {
            for (float[] s : stars) {
                double tw = 0.5 + 0.5 * Math.sin(t * 0.7 + s[2]);
                int a = (int) (starLevel * (40 + 150 * tw));
                star.setColor(Color.argb(a, 255, 255, 255));
                canvas.drawCircle(s[0] * w, s[1] * h, s[3], star);
            }
        }

        if (running) postDelayed(frame, FRAME_MS);
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static int mix(int from, int to, float f) {
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * f + Math.signum(Color.red(to) - Color.red(from)));
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * f + Math.signum(Color.green(to) - Color.green(from)));
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f + Math.signum(Color.blue(to) - Color.blue(from)));
        return Color.rgb(clamp(r, from, to, Color.red(from), Color.red(to)),
                clamp(g, from, to, Color.green(from), Color.green(to)),
                clamp(b, from, to, Color.blue(from), Color.blue(to)));
    }

    private static int clamp(int v, int fromColor, int toColor, int lo, int hi) {
        int min = Math.min(lo, hi), max = Math.max(lo, hi);
        return Math.max(min, Math.min(max, v));
    }
}
