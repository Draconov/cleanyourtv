package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A wallpaper scrim directly behind the visible app-card rows.
 *
 * One-row mode gets one continuous horizontal band; grid and default modes
 * get a band per visible card row. The gradient lives in the RecyclerView
 * decoration layer, not a full-screen overlay, and moves with scrolling.
 * No bitmaps, animations, or per-frame shader allocations are required.
 */
final class RowaCardBackdropDecoration extends RecyclerView.ItemDecoration {
    private static final int INITIAL_ROWS = 8;
    private final float density;
    private final boolean singleRow;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearGradient gradient = new LinearGradient(0f, 0f, 1f, 0f,
            new int[]{0x10000000, 0xAE000000, 0xBC000000, 0xAE000000, 0x10000000},
            new float[]{0f, 0.17f, 0.5f, 0.83f, 1f}, Shader.TileMode.CLAMP);
    private final Matrix shaderMatrix = new Matrix();
    private final RectF band = new RectF();
    private float[] left = new float[INITIAL_ROWS];
    private float[] top = new float[INITIAL_ROWS];
    private float[] right = new float[INITIAL_ROWS];
    private float[] bottom = new float[INITIAL_ROWS];
    private int rows;

    RowaCardBackdropDecoration(float density, boolean singleRow) {
        this.density = density;
        this.singleRow = singleRow;
        paint.setShader(gradient);
    }

    private void expand() {
        int capacity = left.length * 2;
        left = java.util.Arrays.copyOf(left, capacity);
        top = java.util.Arrays.copyOf(top, capacity);
        right = java.util.Arrays.copyOf(right, capacity);
        bottom = java.util.Arrays.copyOf(bottom, capacity);
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
                       @NonNull RecyclerView.State state) {
        rows = 0;
        // A tiny, reused row-bounds array avoids allocating a collection each
        // frame and avoids decoding/scaling any wallpaper or application assets.
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (parent.getChildAdapterPosition(child) == RecyclerView.NO_POSITION) continue;
            View card = child.findViewById(R.id.card);
            if (card == null || card.getVisibility() != View.VISIBLE
                    || card.getWidth() <= 0 || card.getHeight() <= 0) continue;
            float l = child.getLeft() + card.getLeft() + child.getTranslationX();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (r <= 0 || l >= parent.getWidth() || b <= 0 || t >= parent.getHeight()) continue;

            int row = -1;
            if (singleRow && rows > 0) {
                row = 0;
            } else {
                for (int j = 0; j < rows; j++) {
                    // Cells in a grid row share their card's vertical center.
                    // Use a tolerance for fractional sizing or focus lift.
                    if (Math.abs((top[j] + bottom[j] - t - b) * 0.5f)
                            < Math.min(b - t, bottom[j] - top[j]) * 0.36f) {
                        row = j;
                        break;
                    }
                }
            }
            if (row < 0) {
                if (rows == left.length) expand();
                row = rows++;
                left[row] = l;
                top[row] = t;
                right[row] = r;
                bottom[row] = b;
            } else {
                left[row] = Math.min(left[row], l);
                top[row] = Math.min(top[row], t);
                right[row] = Math.max(right[row], r);
                bottom[row] = Math.max(bottom[row], b);
            }
        }

        final float sidePad = 24f * density;
        final float verticalPad = 18f * density;
        final float corner = 25f * density;
        final float maxX = parent.getWidth();
        final float maxY = parent.getHeight();
        for (int i = 0; i < rows; i++) {
            // Keep the scrim localized to the visible app cards, leaving the
            // greeting, clock, and wallpaper outside these bands untouched.
            float l = singleRow ? 0f : Math.max(0f, left[i] - sidePad);
            float r = singleRow ? maxX : Math.min(maxX, right[i] + sidePad);
            float t = Math.max(0f, top[i] - verticalPad);
            float b = Math.min(maxY, bottom[i] + verticalPad);
            if (r - l < 2f || b - t < 2f) continue;
            band.set(l, t, r, b);
            shaderMatrix.reset();
            shaderMatrix.setScale(r - l, 1f);
            shaderMatrix.postTranslate(l, 0f);
            gradient.setLocalMatrix(shaderMatrix);
            canvas.drawRoundRect(band, Math.min(corner, (b - t) / 2f),
                    Math.min(corner, (b - t) / 2f), paint);
        }
    }
}
