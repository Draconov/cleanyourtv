package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ScrollView;

/** Consistent ROWA glass menu styling; all values are Android dp/sp, never screen pixels. */
final class RowaMenuStyle {
    static final int ROW_DP = 42;
    static final int TEXT_SP = 15;
    static final int ROW_RADIUS_DP = 13;
    static final int PANEL_RADIUS_DP = 20;
    static final int FOCUS_TEXT = 0xFF15191F;
    private RowaMenuStyle() { }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable row(Context context, boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focused ? 0xFFF1F4F6 : 0xD6262B34);
        drawable.setCornerRadius(dp(context, ROW_RADIUS_DP));
        drawable.setStroke(dp(context, focused ? 2 : 1),
                focused ? Color.WHITE : 0x35FFFFFF);
        return drawable;
    }

    static void glass(View panel, Context context) {
        if (panel != null && panel.getBackground() != null)
            panel.getBackground().mutate().setAlpha(RowaVisualOptions.panelAlpha(context));
    }

    /** Let the D-pad reveal the full focused row without moving scroll focus to the parent. */
    static void revealOnFocus(View row, ScrollView scroll) {
        if (row == null || scroll == null) return;
        View.OnFocusChangeListener prior = row.getOnFocusChangeListener();
        row.setOnFocusChangeListener((v, focused) -> {
            if (prior != null) prior.onFocusChange(v, focused);
            if (focused) scroll.post(() -> {
                if (!v.hasFocus() || !v.isAttachedToWindow()) return;
                Rect bounds = new Rect(0, 0, v.getWidth(), v.getHeight() + dp(v.getContext(), 6));
                scroll.requestChildRectangleOnScreen(v, bounds, false);
            });
        });
    }

}
