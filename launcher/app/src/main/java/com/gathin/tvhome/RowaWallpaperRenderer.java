package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.util.Log;
import java.io.InputStream;

/**
 * Renders one final TV-sized RGB_565 wallpaper once per selection/rotation.
 * Blurring uses a tiny 80x45 scratch bitmap; it never runs as an animated GPU filter.
 */
final class RowaWallpaperRenderer {
    private RowaWallpaperRenderer() { }
    static Bitmap render(Context context, Uri uri, int width, int height,
                         RowaWallpaperStyle style, int themeDim) {
        if (width <= 0 || height <= 0) return null;
        // A bad DisplayMetrics value should never allocate a 4K+ framebuffer on this TV.
        width = Math.min(1920, width);
        height = Math.min(1080, height);
        Bitmap input = null, output = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) return null;
                BitmapFactory.decodeStream(stream, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > 12000 || o.outHeight > 12000)
                return null;
            int sample = 1;
            while (o.outWidth / sample > 1920 || o.outHeight / sample > 1080) sample <<= 1;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inScaled = false;
            String mime = null;
        try { mime = context.getContentResolver().getType(uri); }
        catch (Exception ignored) { /* The URI extension is also checked below. */ }
            boolean tiledPng = style.mode == RowaWallpaperStyle.TILE &&
                    ("image/png".equalsIgnoreCase(mime) ||
                            String.valueOf(uri).toLowerCase(java.util.Locale.ROOT).endsWith(".png"));
            o.inPreferredConfig = tiledPng ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) return null;
                input = BitmapFactory.decodeStream(stream, null, o);
            }
            if (input == null) return null;
            output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(output);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            if (style.mode == RowaWallpaperStyle.FIT) {
                drawBackdrop(canvas, input, width, height, style, p);
                float scale = Math.min(width / (float) input.getWidth(), height / (float) input.getHeight());
                float w = input.getWidth() * scale, h = input.getHeight() * scale;
                canvas.drawBitmap(input, null, new RectF((width - w) / 2, (height - h) / 2,
                        (width + w) / 2, (height + h) / 2), p);
            } else if (style.mode == RowaWallpaperStyle.TILE) {
                // A solid underlay also works for transparent PNG tile patterns.
                canvas.drawColor(style.solidColor);
                // BitmapShader performs hardware-friendly repeating without hundreds of Java draw calls.
                BitmapShader shader = new BitmapShader(input, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                Matrix transform = new Matrix();
                transform.setScale(style.tilePercent / 100f, style.tilePercent / 100f);
                shader.setLocalMatrix(transform);
                p.setShader(shader);
                canvas.drawRect(0, 0, width, height, p);
                p.setShader(null);
            } else {
                // Fill/Crop: same center-cropped behavior as v15.5, without stretching.
                drawCrop(canvas, input, width, height, p);
            }
            int dim = style.imageDim >= 0 ? style.imageDim : themeDim;
            dim = RowaWallpaperStyle.clamp(dim, 0, 90);
            if (dim > 0) canvas.drawColor(Color.argb(dim * 255 / 100, 0, 0, 0));
            return output;
        } catch (Exception | OutOfMemoryError problem) {
            Log.w("ROWA_WALLPAPER", "Unable to compose wallpaper", problem);
            if (output != null) output.recycle();
            return null;
        } finally {
            if (input != null) input.recycle();
        }
    }

    private static void drawCrop(Canvas canvas, Bitmap input, int width, int height, Paint p) {
        float scale = Math.max(width / (float) input.getWidth(), height / (float) input.getHeight());
        float sourceW = width / scale, sourceH = height / scale;
        float left = (input.getWidth() - sourceW) * 0.5f;
        float top = (input.getHeight() - sourceH) * 0.5f;
        canvas.drawBitmap(input, new Rect(Math.round(left), Math.round(top),
                Math.round(left + sourceW), Math.round(top + sourceH)),
                new Rect(0, 0, width, height), p);
    }

    private static void drawBackdrop(Canvas canvas, Bitmap source, int w, int h,
                                     RowaWallpaperStyle style, Paint p) {
        if (style.backdrop == RowaWallpaperStyle.SOLID) {
            canvas.drawColor(style.solidColor);
        } else if (style.backdrop == RowaWallpaperStyle.DOMINANT) {
            canvas.drawColor(dominantColor(source));
        } else if (style.blur <= 0) {
            // Blur Off really means sharp center crop, not a pixelated 80x45 background.
            drawCrop(canvas, source, w, h, p);
        } else {
            Bitmap small = Bitmap.createBitmap(80, 45, Bitmap.Config.ARGB_8888);
            new Canvas(small).drawBitmap(source, null, new Rect(0, 0, 80, 45), p);
            try {
                // A small multi-pass box blur is fast even on an older 1 GiB TV.
                blurTiny(small, RowaWallpaperStyle.clamp(style.blur, 1, 30));
                canvas.drawBitmap(small, null, new Rect(0, 0, w, h), p);
            } finally { small.recycle(); }
        }
        if (style.backdropDim > 0)
            canvas.drawColor(Color.argb(style.backdropDim * 255 / 100, 0, 0, 0));
    }

    /** Dominant quantized color instead of a flat average, keeping image backgrounds coherent. */
    private static int dominantColor(Bitmap source) {
        Bitmap tiny = Bitmap.createScaledBitmap(source, 32, 32, true);
        try {
            int[] pixel = new int[32 * 32];
            tiny.getPixels(pixel, 0, 32, 0, 0, 32, 32);
            int[] counts = new int[4096];
            long[] rs = new long[4096], gs = new long[4096], bs = new long[4096];
            int best = 0, bestCount = 0;
            for (int color : pixel) {
                int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
                int bin = (r >> 4) << 8 | (g >> 4) << 4 | (b >> 4);
                ++counts[bin]; rs[bin] += r; gs[bin] += g; bs[bin] += b;
                if (counts[bin] > bestCount) { best = bin; bestCount = counts[bin]; }
            }
            return bestCount == 0 ? Color.DKGRAY : Color.rgb((int) (rs[best] / bestCount),
                    (int) (gs[best] / bestCount), (int) (bs[best] / bestCount));
        } finally { if (tiny != source) tiny.recycle(); }
    }

    /** Separable box blur over only 3,600 pixels; 2 passes avoid blocking the preview. */
    private static void blurTiny(Bitmap b, int radius) {
        int w = b.getWidth(), h = b.getHeight();
        int[] pixels = new int[w * h], next = new int[pixels.length];
        b.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; ++pass) {
            // Use explicit clipped neighborhoods, bounded to 30*3600*4 operations.
            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w; ++x) {
                    int r = 0, g = 0, bl = 0, n = 0;
                    for (int t = Math.max(0, x - radius); t <= Math.min(w - 1, x + radius); ++t) {
                        int color = pixels[y * w + t];
                        r += Color.red(color); g += Color.green(color); bl += Color.blue(color); ++n;
                    }
                    next[y * w + x] = Color.rgb(r / n, g / n, bl / n);
                }
            }
            int[] swap = pixels; pixels = next; next = swap;
            for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
                int r = 0, g = 0, bl = 0, n = 0;
                for (int t = Math.max(0, y - radius); t <= Math.min(h - 1, y + radius); ++t) {
                    int color = pixels[t * w + x];
                    r += Color.red(color); g += Color.green(color); bl += Color.blue(color); ++n;
                }
                next[y * w + x] = Color.rgb(r / n, g / n, bl / n);
            }
            swap = pixels; pixels = next; next = swap;
        }
        b.setPixels(pixels, 0, w, 0, 0, w, h);
    }
}
