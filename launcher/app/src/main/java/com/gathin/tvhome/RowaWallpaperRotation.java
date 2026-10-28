package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/** Local, UI-driven rotation override; no service or alarms. */
final class RowaWallpaperRotation {
    static final int THEME = 0;
    static final int NEVER = 1;
    static final int FIVE_MIN = 2;
    static final int FIFTEEN_MIN = 3;
    static final int THIRTY_MIN = 4;
    static final int SIXTY_MIN = 5;
    static final int TWO_HOURS = 6;
    static final int ON_HOME = 7;
    static final int COUNT = 8;
    private static final String PREFS = "rowa_visual_v15";
    private static final String KEY = "rotation_mode";

    private RowaWallpaperRotation() { }

    static int get(Context context) {
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, THEME);
        return Math.max(THEME, Math.min(ON_HOME, value));
    }

    static int change(Context context, int direction) {
        int next = (get(context) + direction + COUNT) % COUNT;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, next).apply();
        return next;
    }

    static String label(Context context) {
        switch (get(context)) {
            case NEVER: return context.getString(R.string.rowa_rotation_never);
            case FIVE_MIN: return context.getString(R.string.rowa_rotation_5min);
            case FIFTEEN_MIN: return context.getString(R.string.rowa_rotation_15min);
            case THIRTY_MIN: return context.getString(R.string.rowa_rotation_30min);
            case SIXTY_MIN: return context.getString(R.string.rowa_rotation_60min);
            case TWO_HOURS: return context.getString(R.string.rowa_rotation_120min);
            case ON_HOME: return context.getString(R.string.rowa_rotation_home);
            default: return context.getString(R.string.rowa_rotation_theme);
        }
    }

    static long interval(Context context, long themeInterval) {
        switch (get(context)) {
            case NEVER:
            case ON_HOME: return 0L;
            case FIVE_MIN: return 5 * 60_000L;
            case FIFTEEN_MIN: return 15 * 60_000L;
            case THIRTY_MIN: return 30 * 60_000L;
            case SIXTY_MIN: return 60 * 60_000L;
            case TWO_HOURS: return 120 * 60_000L;
            default: return themeInterval;
        }
    }
}
