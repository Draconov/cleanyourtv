package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.widget.TextView;

/** Keeps weather typography independent from clock size, and clamps the weather symbol's ink
 *  to the temperature digit height. The span does not alter TextView's font metrics or line box. */
final class RowaWeatherVisuals {
    private RowaWeatherVisuals() { }

    static void matchTemperatureHeight(Activity activity, TextView weather, TextView clock) {
        if (weather == null || clock == null) return;
        // E.g. Small clock + Normal weather now yields a 44sp clock and 52sp weather;
        // Small/Normal/Large on both use the same reference sizes independently.
        if (weather.getTypeface() != clock.getTypeface())
            weather.setTypeface(clock.getTypeface());
        float requested = 52f * RowaAppearance.scale(activity, "weather")
                * activity.getResources().getDisplayMetrics().scaledDensity;
        if (Math.abs(weather.getTextSize() - requested) > 0.5f)
            weather.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, requested);
        if (weather.getTextScaleX() != clock.getTextScaleX())
            weather.setTextScaleX(clock.getTextScaleX());
        if (weather.getLetterSpacing() != clock.getLetterSpacing())
            weather.setLetterSpacing(clock.getLetterSpacing());
        if (weather.getIncludeFontPadding() != clock.getIncludeFontPadding())
            weather.setIncludeFontPadding(clock.getIncludeFontPadding());
        if (weather.getCurrentTextColor() != clock.getCurrentTextColor())
            weather.setTextColor(clock.getCurrentTextColor());
        applyIconSpan(weather);
    }

    private static void applyIconSpan(TextView weather) {
        CharSequence value = weather.getText();
        if (value == null || value.length() < 3 || value.charAt(1) != ' ') return;
        char symbol = value.charAt(0);
        // The only symbols produced by the weather provider (live or cached). Do not
        // touch status-only messages such as 'Offline' or 'Unavailable'.
        if ("☀⛅☁❄☂⚡°".indexOf(symbol) < 0) return;
        if (value instanceof Spanned && ((Spanned) value).getSpans(0, 1, IconSpan.class).length > 0)
            return;
        SpannableString styled = new SpannableString(value);
        styled.setSpan(new IconSpan(String.valueOf(symbol)), 0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        weather.setText(styled, TextView.BufferType.SPANNABLE);
    }

    /** An in-line weather icon without emoji fallback ascent/descent increasing row height. */
    private static final class IconSpan extends ReplacementSpan {
        private final String symbol;
        private final Rect glyph = new Rect();
        private final Rect digit = new Rect();
        IconSpan(String symbol) { this.symbol = symbol; }

        private float iconScale(Paint paint) {
            paint.getTextBounds(symbol, 0, symbol.length(), glyph);
            paint.getTextBounds("0", 0, 1, digit);
            if (glyph.height() <= 0 || digit.height() <= 0) return 1f;
            return digit.height() / (float) glyph.height();
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end,
                                     Paint.FontMetricsInt fm) {
            // Deliberately leave fm unchanged: the icon must not protrude above/below
            // the temperature text, including with a color-emoji fallback font.
            float scale = iconScale(paint);
            return Math.max(1, Math.round(glyph.width() * scale + paint.measureText(" ")));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            float scale = iconScale(paint);
            float digitCenter = (digit.top + digit.bottom) * 0.5f;
            float glyphCenter = (glyph.top + glyph.bottom) * 0.5f;
            canvas.save();
            canvas.translate(x - glyph.left * scale, y + digitCenter - glyphCenter * scale);
            canvas.scale(scale, scale);
            canvas.drawText(symbol, 0f, 0f, paint);
            canvas.restore();
        }
    }
}
