package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** ROWA_V11: optional city or one-shot device location; no persistent location tracking. */
final class RowaWeatherSettings {
    static final int LOCATION_REQUEST = 6121;
    private static final String PREFS = "rowa_weather_location_v11";
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile long nextFetchAt;
    private static volatile String cached = "";
    private static volatile String cachedFor = "";
    private static final long SUCCESS_INTERVAL = 45 * 60 * 1000L;
    private static final long RETRY_INTERVAL = 5 * 60 * 1000L;
    private RowaWeatherSettings() {}

    static boolean isDeviceMode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("device", false);
    }

    static String cityName(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("name", "Ashkelon");
    }

    static void selectDevice(Activity activity) {
        if (Build.VERSION.SDK_INT >= 23
                && activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        enableDevice(activity);
    }

    static void onPermissionResult(Activity activity) {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            enableDevice(activity);
        } else {
            Toast.makeText(activity, R.string.rowa_v11_location_denied, Toast.LENGTH_LONG).show();
        }
    }

    private static void enableDevice(Activity activity) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("device", true).apply();
        invalidate();
        TextView badge = activity.findViewById(R.id.rowaWeather);
        update(activity, badge);
        Toast.makeText(activity, R.string.rowa_v11_location_selected, Toast.LENGTH_LONG).show();
    }

    static void selectCity(Activity activity, String city) {
        if (city.isEmpty()) return;
        if (!BUSY.compareAndSet(false, true)) {
            Toast.makeText(activity, R.string.rowa_v11_weather_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                String api = "https://geocoding-api.open-meteo.com/v1/search?name="
                        + URLEncoder.encode(city, "UTF-8") + "&count=1&language=en&format=json";
                JSONObject data = new JSONObject(readUrl(api));
                JSONArray locations = data.optJSONArray("results");
                if (locations == null || locations.length() == 0)
                    throw new IllegalStateException("City not found");
                JSONObject first = locations.getJSONObject(0);
                double lat = first.getDouble("latitude"), lon = first.getDouble("longitude");
                if (!Double.isFinite(lat) || !Double.isFinite(lon)) throw new IllegalStateException();
                String fullName = first.getString("name");
                String region = first.optString("country", "");
                if (!region.isEmpty()) fullName += ", " + region;
                String finalName = fullName;
                saveCity(activity, finalName, lat, lon);
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, finalName, Toast.LENGTH_LONG).show();
                    update(activity, activity.findViewById(R.id.rowaWeather));
                });
            } catch (Exception failed) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        R.string.rowa_v11_city_not_found, Toast.LENGTH_LONG).show());
            } finally {
                BUSY.set(false);
            }
        }, "rowa-geocode");
        worker.setDaemon(true);
        worker.start();
    }

    // ROWA_V12: return up to eight geographically distinct city suggestions.
    static final class CityHit {
        final String display;
        final double lat, lon;
        CityHit(String display, double lat, double lon) {
            this.display = display; this.lat = lat; this.lon = lon;
        }
    }

    static void suggestCities(String query, java.util.function.Consumer<java.util.List<CityHit>> done) {
        Thread worker = new Thread(() -> {
            java.util.ArrayList<CityHit> matches = new java.util.ArrayList<>();
            try {
                if (query.trim().length() >= 2) {
                    String language = Locale.getDefault().getLanguage();
                    String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                            + URLEncoder.encode(query, "UTF-8")
                            + "&count=8&language=" + URLEncoder.encode(language, "UTF-8")
                            + "&format=json";
                    JSONArray array = new JSONObject(readUrl(url)).optJSONArray("results");
                    if (array != null) for (int i = 0; i < array.length(); i++) {
                        JSONObject city = array.getJSONObject(i);
                        double latitude = city.getDouble("latitude");
                        double longitude = city.getDouble("longitude");
                        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) continue;
                        String label = city.optString("name", "");
                        String region = city.optString("admin1", "");
                        String country = city.optString("country", "");
                        if (!region.isEmpty() && !region.equals(label)) label += ", " + region;
                        if (!country.isEmpty()) label += ", " + country;
                        if (!label.isEmpty()) matches.add(new CityHit(label, latitude, longitude));
                    }
                }
            } catch (Exception ignored) { /* keep the keyboard usable offline */ }
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> done.accept(matches));
        }, "rowa-city-suggest");
        worker.setDaemon(true);
        worker.start();
    }

    /** Shared persistence for typed and autocomplete city selection; do not change preference keys. */
    private static void saveCity(Context context, String name, double latitude, double longitude) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("name", name)
                .putString("lat", String.format(Locale.US, "%.5f", latitude))
                .putString("lon", String.format(Locale.US, "%.5f", longitude))
                .putBoolean("device", false).apply();
        invalidate();
    }

    static void selectResolvedCity(Activity activity, CityHit city) {
        saveCity(activity, city.display, city.lat, city.lon);
        update(activity, activity.findViewById(R.id.rowaWeather));
    }

    static void update(Activity activity, TextView badge) {
        if (badge == null) return;
        if (!activity.getResources().getBoolean(R.bool.rowa_weather_enabled)) {
            badge.setVisibility(View.GONE);
            return;
        }
        // ROWA_V15: skip futile network work when offline; never label an old value as live.
        String identity = weatherIdentity(activity);
        if (RowaWeatherOffline.showIfDisconnected(activity, badge, identity)) return;
        if (identity.equals(cachedFor) && !cached.isEmpty()) {
            badge.setText(cached);
            badge.setVisibility(View.VISIBLE);
            if (activity instanceof MainActivity) ((MainActivity) activity).rowaPolishHeader();
        } else {
            // Never show the previous city's temperature after a location switch.
            badge.setVisibility(View.GONE);
        }
        long now = SystemClock.elapsedRealtime();
        if (now < nextFetchAt || !BUSY.compareAndSet(false, true)) return;
        nextFetchAt = now + RETRY_INTERVAL;
        Thread worker = new Thread(() -> {
            try {
                String[] coords = getCoordinates(activity);
                String request = "https://api.open-meteo.com/v1/forecast?latitude=" + coords[0]
                        + "&longitude=" + coords[1]
                        + "&current=temperature_2m,weather_code&temperature_unit=celsius";
                JSONObject current = new JSONObject(readUrl(request)).getJSONObject("current");
                int temp = (int) Math.round(current.getDouble("temperature_2m"));
                String result = icon(current.optInt("weather_code", -1)) + " "
                        + String.format(Locale.US, "%d\u00b0C", temp);
                // A location change in flight must not overwrite the new location's badge.
                if (!weatherIdentity(activity).equals(identity)) return;
                RowaWeatherOffline.remember(activity, identity, result);
                cached = result;
                cachedFor = identity;
                nextFetchAt = SystemClock.elapsedRealtime() + SUCCESS_INTERVAL;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()
                            || !weatherIdentity(activity).equals(identity)) return;
                    badge.setText(result);
                    badge.setVisibility(View.VISIBLE);
                    if (activity instanceof MainActivity)
                        ((MainActivity) activity).rowaPolishHeader();
                });
            } catch (Exception ignored) {
                // ROWA_V15: network timeout: show a clearly marked stored reading (if any).
                activity.runOnUiThread(() -> {
                    if (weatherIdentity(activity).equals(identity))
                        RowaWeatherOffline.showFallback(activity, badge, identity);
                });
                // Keep the existing city fallback/reading without polling rapidly.
            } finally {
                BUSY.set(false);
            }
        }, "rowa-weather");
        worker.setDaemon(true);
        worker.start();
    }

    private static void invalidate() {
        cachedFor = "";
        nextFetchAt = 0;
    }

    private static String weatherIdentity(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return (p.getBoolean("device", false) ? "gps:" : "city:")
                + p.getString("lat", "") + ":" + p.getString("lon", "");
    }

    private static String[] getCoordinates(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String lat = p.getString("lat", ctx.getString(R.string.rowa_weather_latitude));
        String lon = p.getString("lon", ctx.getString(R.string.rowa_weather_longitude));
        if (p.getBoolean("device", false)) {
            Location location = resolveDeviceLocation(ctx);
            if (location != null) {
                lat = String.format(Locale.US, "%.5f", location.getLatitude());
                lon = String.format(Locale.US, "%.5f", location.getLongitude());
            }
            // If this TV has no GPS/network provider, quietly use the saved city instead.
        }
        return new String[]{lat, lon};
    }

    private static Location resolveDeviceLocation(Context ctx) {
        try {
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                    && ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return null;
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            boolean fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            String[] providers = fine ? new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER} : new String[]{LocationManager.NETWORK_PROVIDER};
            Location best = null;
            for (String provider : providers) {
                if (!lm.isProviderEnabled(provider)) continue;
                Location loc = lm.getLastKnownLocation(provider);
                if (loc != null && (best == null || loc.getTime() > best.getTime())) best = loc;
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < 6 * 60 * 60 * 1000L)
                return best;
            for (String provider : providers) {
                if (!lm.isProviderEnabled(provider)) continue;
                AtomicReference<Location> result = new AtomicReference<>();
                CountDownLatch latch = new CountDownLatch(1);
                CancellationSignal cancel = new CancellationSignal();
                try {
                    lm.getCurrentLocation(provider, cancel, Runnable::run, loc -> {
                        result.set(loc);
                        latch.countDown();
                    });
                    latch.await(7, TimeUnit.SECONDS);
                } finally {
                    cancel.cancel();
                }
                if (result.get() != null) return result.get();
            }
            return best;
        } catch (Exception ignored) { return null; }
    }

    private static String readUrl(String request) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(request).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                    if (body.length() > 24000) throw new IllegalStateException("Oversized response");
                }
            }
            return body.toString();
        } finally { c.disconnect(); }
    }

    private static String icon(int code) {
        if (code == 0) return "\u2600";
        if (code == 1 || code == 2) return "\u26C5";
        if (code == 3 || code == 45 || code == 48) return "\u2601";
        if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) return "\u2744";
        if (code >= 51 && code <= 82) return "\u2602";
        if (code >= 95 && code <= 99) return "\u26A1";
        return "\u00b0";
    }
}
