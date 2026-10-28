package com.gathin.tvhome;

import android.app.Activity;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional, lightweight weather badge for the CleanYourTV Home screen only.
 *  Coordinates are explicitly configured by the owner; no device location is read.
 */
final class RowaWeather {
    private static final long SUCCESS_INTERVAL = 45 * 60 * 1000L;
    private static final long RETRY_INTERVAL = 5 * 60 * 1000L;
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static volatile long nextFetchMs;
    private static volatile String cached = "";

    private RowaWeather() { }

    // ROWA_V11: mode, city and device location are managed without an extra Android app.
    static void update(Activity activity, TextView badge) {
        RowaWeatherSettings.update(activity, badge);
    }

    private static String fetch(String latitude, String longitude) throws Exception {
        String request = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude
                + "&longitude=" + longitude
                + "&current=temperature_2m,weather_code&temperature_unit=celsius";
        HttpURLConnection connection = (HttpURLConnection) new URL(request).openConnection();
        connection.setConnectTimeout(3500);
        connection.setReadTimeout(3500);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("Weather unavailable");
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                    if (body.length() > 16384) throw new IllegalStateException("Unexpected response size");
                }
            }
            JSONObject current = new JSONObject(body.toString()).getJSONObject("current");
            int temp = (int) Math.round(current.getDouble("temperature_2m"));
            int code = current.optInt("weather_code", -1);
            return symbol(code) + " " + String.format(Locale.US, "%d\u00b0C", temp);
        } finally {
            connection.disconnect();
        }
    }

    private static String symbol(int code) {
        if (code == 0) return "\u2600"; // clear
        if (code == 1 || code == 2) return "\u26c5"; // partly cloudy
        if (code == 3 || code == 45 || code == 48) return "\u2601"; // cloudy / fog
        if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) return "\u2744"; // snow
        if (code >= 51 && code <= 82) return "\u2602"; // drizzle / rain
        if (code >= 95 && code <= 99) return "\u26a1"; // thunder
        return "\u00b0";
    }
}
