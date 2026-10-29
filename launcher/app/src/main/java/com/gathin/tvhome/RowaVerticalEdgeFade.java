package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Fade clipped vertical-rail tiles at both edges, never the whole launcher. */
final class RowaVerticalEdgeFade extends RecyclerView.ItemDecoration {
    private final float length;
    private final Paint top = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bottom = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastHeight = -1f;

    private RowaVerticalEdgeFade(float length) {
        this.length = length;
        top.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        bottom.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    static void attach(RecyclerView grid, float length) {
        // The mask only requires a layer for the narrow RecyclerView, never the full wallpaper.
        grid.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        grid.addItemDecoration(new RowaVerticalEdgeFade(length));
    }

    @Override public void onDrawOver(@NonNull Canvas canvas, @NonNull RecyclerView parent,
                                     @NonNull RecyclerView.State state) {
        float height = parent.getHeight();
        if (height <= 0f) return;
        float fade = Math.min(length, height * 0.25f);
        if (height != lastHeight) {
            lastHeight = height;
            top.setShader(new LinearGradient(0f, 0f, 0f, fade,
                    0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
            bottom.setShader(new LinearGradient(0f, height - fade, 0f, height,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        }
        if (parent.canScrollVertically(-1))
            canvas.drawRect(0f, 0f, parent.getWidth(), fade, top);
        if (parent.canScrollVertically(1))
            canvas.drawRect(0f, height - fade, parent.getWidth(), height, bottom);
    }

}
