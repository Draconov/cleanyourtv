package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.view.View;
import android.widget.TextView;

/** ROWA_V15: last-known weather, clearly marked stale when disconnected. */
final class RowaWeatherOffline {
    private static final String PREFS = "rowa_weather_last_v15";
    private RowaWeatherOffline() { }

    static void remember(Context ctx, String identity, String weather) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("identity", identity).putString("reading", weather)
                .putLong("time", System.currentTimeMillis()).apply();
    }

    static boolean offline(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false; // unknown: allow an ordinary fetch
            Network network = cm.getActiveNetwork();
            if (network == null) return true;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException ignored) { return false; }
    }

    static boolean showIfDisconnected(Activity activity, TextView badge, String identity) {
        if (!offline(activity)) return false;
        showFallback(activity, badge, identity);
        return true;
    }

    static void showFallback(Activity activity, TextView badge, String identity) {
        if (badge == null || activity.isDestroyed() || activity.isFinishing()) return;
        android.content.SharedPreferences p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = identity.equals(p.getString("identity", "")) ? p.getString("reading", "") : "";
        boolean disconnected = offline(activity);
        // Keep a last-known reading visible after TV/launcher restart without
        // showing an intrusive "saved/cached" suffix while weather refreshes.
        // Still identify a genuinely disconnected TV as offline.
        badge.setText(value.isEmpty() ? (disconnected
                        ? activity.getString(R.string.rowa_v15_offline_empty)
                        : activity.getString(R.string.rowa_v15_unavailable))
                : (disconnected ? value + " " + activity.getString(R.string.rowa_v15_offline) : value));
        badge.setVisibility(View.VISIBLE);
        if (activity instanceof MainActivity) ((MainActivity) activity).rowaPolishHeader();
    }
}
