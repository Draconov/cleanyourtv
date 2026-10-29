package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Lightweight, local-only sidebar backdrop for vertical Home (no bitmap cache). */
final class RowaVerticalRailView extends View {
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float focusY = -1f;
    private int focusColor;
    private float haloRadius;

    RowaVerticalRailView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        haloRadius = 145f * density;
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    void setFocusedTile(float centerY, int color) {
        if (Math.abs(focusY - centerY) < 1f && focusColor == color) return;
        focusY = centerY;
        focusColor = color;
        rebuildFocusShaders();
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) return;
        base.setShader(new LinearGradient(0f, 0f, width, 0f,
                new int[]{0xE6000000, 0xCA000000, 0x78000000, Color.TRANSPARENT},
                new float[]{0f, 0.38f, 0.74f, 1f}, Shader.TileMode.CLAMP));
        rebuildFocusShaders();
    }

    private void rebuildFocusShaders() {
        if (focusY < 0f || getWidth() <= 0) return;
        float x = 115f * density;
        shadow.setShader(new RadialGradient(x, focusY, haloRadius,
                new int[]{0xCC000000, 0x80000000, Color.TRANSPARENT},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
        int color = focusColor == 0 ? 0xFF7099C2 : focusColor;
        accent.setShader(new RadialGradient(x, focusY, haloRadius,
                new int[]{Color.argb(105, Color.red(color), Color.green(color), Color.blue(color)),
                        Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)),
                        Color.TRANSPARENT},
                new float[]{0f, 0.44f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), base);
        if (focusY >= 0f) {
            float cx = 115f * density;
            canvas.drawCircle(cx, focusY, haloRadius, shadow);
            canvas.drawCircle(cx, focusY, haloRadius, accent);
        }
    }
}
