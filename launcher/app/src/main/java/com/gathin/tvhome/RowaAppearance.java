package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.WeakHashMap;

/** ROWA_APPEARANCE_V4: on-device appearance overrides only; no service or extra APK. */
final class RowaAppearance {
    private static final String PREFS = "rowa_appearance_v4";
    private RowaAppearance() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ROWA_APPEARANCE_V6: 0/1/2 sizing, 0..3 focus-glow intensity.
    // For columns/layout, 0 means use the theme; other choices are explicit overrides.
    static int get(Context ctx, String key) {
        if ("margins".equals(key)) return prefs(ctx).getInt(key, 0);
        int fallback = ("layout".equals(key) || "columns".equals(key) || "glow".equals(key)) ? 0 : 1;
        return prefs(ctx).getInt(key, fallback);
    }

    static void set(Context ctx, String key, int index) {
        prefs(ctx).edit().putInt(key, index).apply();
    }

    static void advance(Context ctx, String key, int choices) {
        int index = get(ctx, key);
        prefs(ctx).edit().putInt(key, (index + 1) % choices).apply();
    }

    static void reset(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    static float scale(Context ctx, String key) {
        switch (get(ctx, key)) {
            case 0: return 0.85f;
            case 2: return 1.12f;
            default: return 1.0f;
        }
    }

    /** ROWA_V10: Keep the ORIGINAL outer content margins, or reduce them by half,
     * or use a literal 5 physical pixels at the outside edges. Applies while the menu stays open. */
    private static final WeakHashMap<View, int[]> ROWA_V10_MARGINS = new WeakHashMap<>();

    // ROWA_V11: 0..100%, where 100 is the theme's original padding.
    static int marginPercent(Context ctx) {
        SharedPreferences p = prefs(ctx);
        int saved = p.getInt("margins_pct", -1);
        if (saved >= 0) return Math.max(0, Math.min(100, saved));
        // Migrate the previous three-position preference without changing the user's choice.
        switch (p.getInt("margins", 0)) {
            case 1: return 50;
            case 2: return 10; // old 5px physical setting; use slider to refine
            default: return 100;
        }
    }

    static void setMarginPercent(Context ctx, int percentage) {
        prefs(ctx).edit().putInt("margins_pct", Math.max(0, Math.min(100, percentage))).apply();
    }

    static String marginsName(Context ctx) {
        switch (get(ctx, "margins")) {
            case 1: return ctx.getString(R.string.rowa_v10_margin_half);
            case 2: return ctx.getString(R.string.rowa_v10_margin_5px);
            default: return ctx.getString(R.string.rowa_v10_margin_original);
        }
    }

    // ROWA_V11: outer top/left/right padding only, never multiply an already scaled value.
    private static void applyMargins(MainActivity activity) {
        View leftBlock = activity.findViewById(R.id.leftBlock);
        if (leftBlock == null || !(leftBlock.getParent() instanceof ViewGroup)) return;
        View header = (View) leftBlock.getParent();
        if (!(header.getParent() instanceof ViewGroup)) return;
        View outer = (View) header.getParent();
        if (!(outer instanceof android.widget.LinearLayout)) return;
        int[] baseline = ROWA_V10_MARGINS.get(outer);
        if (baseline == null) {
            baseline = new int[]{outer.getPaddingStart(), outer.getPaddingTop(),
                    outer.getPaddingEnd(), outer.getPaddingBottom()};
            ROWA_V10_MARGINS.put(outer, baseline);
        }
        int amount = marginPercent(activity);
        int start = Math.round(baseline[0] * amount / 100f);
        int top = Math.round(baseline[1] * amount / 100f);
        int end = Math.round(baseline[2] * amount / 100f);
        if (outer.getPaddingStart() != start || outer.getPaddingTop() != top
                || outer.getPaddingEnd() != end) {
            outer.setPaddingRelative(start, top, end, baseline[3]);
            View grid = activity.findViewById(R.id.grid);
            if (grid != null) grid.requestLayout();
        }
    }

    static String sizeName(Context ctx, String key) {
        int value = get(ctx, key);
        return ctx.getString(value == 0 ? R.string.rowa_size_small
                : value == 2 ? R.string.rowa_size_large : R.string.rowa_size_normal);
    }

    static String layoutName(Context ctx) {
        int index = get(ctx, "layout");
        switch (index) {
            case 1: return ctx.getString(R.string.rowa_layout_grid);
            case 2: return ctx.getString(R.string.rowa_layout_dock);
            case 3: return ctx.getString(R.string.rowa_layout_single);
            default: return ctx.getString(R.string.rowa_layout_default);
        }
    }

    static String columnsName(Context ctx) {
        int value = get(ctx, "columns");
        return value == 0 ? ctx.getString(R.string.rowa_layout_default) : String.valueOf(3 + value);
    }

    /** Remember actual resource-derived defaults so repeated Left/Right changes never accumulate padding. */
    private static final WeakHashMap<View, Baseline> BASES = new WeakHashMap<>();
    private static final class Baseline {
        final int height, width, start, end, top, bottom;
        final float fontPx;
        Baseline(TextView view) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            height = lp != null ? lp.height : -2;
            width = lp != null ? lp.width : -2;
            start = view.getPaddingStart(); end = view.getPaddingEnd();
            top = view.getPaddingTop(); bottom = view.getPaddingBottom();
            fontPx = view.getTextSize();
        }
    }

    /** Update top-bar/clock/weather/glow in place; do not restart the activity or dismiss the menu. */
    static void applyHeader(MainActivity activity) {
        applyMargins(activity);
        TextView clock = activity.findViewById(R.id.clock);
        float clockScale = scale(activity, "clock");
        if (clock != null) clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f * clockScale);
        TextView weather = activity.findViewById(R.id.rowaWeather);
        if (weather != null) weather.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                52f * scale(activity, "weather"));

        GlassView glass = activity.findViewById(R.id.glass);
        if (glass != null) { glass.setGlowLevel(get(activity, "glow")); glass.setSurfaceOpacity(RowaVisualOptions.panelOpacity(activity)); }

        ViewGroup bar = activity.findViewById(R.id.topButtons);
        if (bar == null) return;
        float factor = scale(activity, "buttons");
        float density = activity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView text = (TextView) child;
            Baseline base = BASES.get(child);
            if (base == null) {
                base = new Baseline(text);
                BASES.put(child, base);
            }
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp != null) {
                if (base.height > 0) lp.height = Math.round(base.height * factor);
                if (base.width > 0 && base.height > 0
                        && Math.abs(base.width - base.height) <= Math.round(3 * density)) {
                    lp.width = Math.round(base.width * factor);
                }
                child.setLayoutParams(lp);
            }
            child.setPaddingRelative(
                    Math.round(base.start * factor), base.top,
                    Math.round(base.end * factor), base.bottom);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, base.fontPx * factor);
        }
        activity.rowaPolishHeader();
    }
}
