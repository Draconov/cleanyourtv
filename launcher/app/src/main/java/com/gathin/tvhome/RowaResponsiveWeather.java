package com.gathin.tvhome;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.WeakHashMap;

/** ROWA_V12: fold just weather onto a new line when the clock row is crowded. */
final class RowaResponsiveWeather {
    private RowaResponsiveWeather() { }
    private static final WeakHashMap<TextView, State> STATES = new WeakHashMap<>();

    private static final class State {
        final LinearLayout trailing, clockRow, left;
        final LinearLayout lower;
        final int index;
        boolean stacked;
        State(Activity activity, TextView weather, LinearLayout trailing,
              LinearLayout clockRow, LinearLayout left) {
            this.trailing = trailing; this.clockRow = clockRow; this.left = left;
            index = trailing.indexOfChild(weather);
            lower = new LinearLayout(activity);
            lower.setOrientation(LinearLayout.HORIZONTAL);
            lower.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 0;
            lower.setLayoutParams(lp);
        }
    }

    /** True means the weather is in its own row and the old layout must not touch it. */
    static boolean update(Activity activity, TextView weather, TextView time,
                          TextView date, View divider, View leftView) {
        if (!(leftView instanceof LinearLayout) || weather == null || time == null || date == null)
            return false;
        State s = STATES.get(weather);
        if (s == null) {
            if (!(weather.getParent() instanceof LinearLayout)) return false;
            LinearLayout trailing = (LinearLayout) weather.getParent();
            if (!(trailing.getParent() instanceof LinearLayout)) return false;
            LinearLayout row = (LinearLayout) trailing.getParent();
            s = new State(activity, weather, trailing, row, (LinearLayout) leftView);
            STATES.put(weather, s);
            final State watcher = s;
            // Re-evaluate after the width changes due to margins, font sizes or the TV display size.
            leftView.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,oright,ob) -> {
                if (r-l != oright-ol && weather.isAttachedToWindow())
                    weather.post(() -> update(activity, weather, time, date, divider, leftView));
            });
        }
        int available = s.left.getWidth();
        if (available <= 0 || time.getWidth() <= 0) return s.stacked;
        // Reserve the full date even when the current line was squeezed by the layout.
        float dateWidth = 0;
        String[] dateLines = date.getText().toString().split("\\n", -1);
        for (String line : dateLines)
            dateWidth = Math.max(dateWidth, date.getPaint().measureText(line));
        // We only need the date's longest rendered line, not an imaginary single-line date.
        float measuredWeather = weather.getPaint().measureText(weather.getText().toString())
                + 12f * activity.getResources().getDisplayMetrics().density;
        float dp = activity.getResources().getDisplayMetrics().density;
        float required = time.getWidth() + dateWidth + measuredWeather + (39f * dp);
        // No hysteresis: both directions switch at the same 2dp clearance.
        // The smaller guard lets weather stay beside the clock longer.
        boolean shouldStack = required > available - 2f * dp;
        if (shouldStack && !s.stacked) {
            s.trailing.removeView(weather);
            s.lower.addView(weather, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            int position = s.left.indexOfChild(s.clockRow);
            s.left.addView(s.lower, Math.max(0, position + 1));
            s.stacked = true;
        } else if (!shouldStack && s.stacked) {
            s.lower.removeView(weather);
            s.left.removeView(s.lower);
            s.trailing.addView(weather, Math.min(s.index, s.trailing.getChildCount()));
            s.stacked = false;
        }
        if (s.stacked) {
            // Match the very same clock font and respect the user's weather size setting.
            weather.setTypeface(time.getTypeface());
            weather.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    52f * activity.getResources().getDisplayMetrics().scaledDensity
                            * RowaAppearance.scale(activity, "weather"));
            weather.setTextColor(time.getCurrentTextColor());
            weather.setBackground(null);
            weather.setPadding(0,0,0,0);
            weather.setTranslationY(0);
            if (divider != null) divider.setVisibility(View.GONE);
        } else if (divider != null) {
            divider.setVisibility(weather.getVisibility() == View.VISIBLE
                    ? View.VISIBLE : View.GONE);
        }
        return s.stacked;
    }
}
