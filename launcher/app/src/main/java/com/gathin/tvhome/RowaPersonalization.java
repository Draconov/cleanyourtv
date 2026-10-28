package com.gathin.tvhome;

import android.content.Context;

/** ROWA_V12: opt-in greeting name, local to launcher preferences. */
final class RowaPersonalization {
    private static final String PREFS = "rowa_personalization";
    private RowaPersonalization() { }

    static String savedName(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("name", "");
    }

    static String name(Context ctx, String themeDefault) {
        String saved = savedName(ctx).trim();
        return saved.isEmpty() ? themeDefault : saved;
    }

    static void save(Context ctx, String name) {
        String sanitized = name == null ? "" : name.trim();
        sanitized = sanitized.replaceAll("\\s+", " ").trim();
        if (sanitized.length() > 40) sanitized = sanitized.substring(0, 40);
        if (savedName(ctx).equals(sanitized)) return; // no unnecessary prefs write
        // No network requests: the name stays in app-local preferences.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("name", sanitized).apply();
    }
}
