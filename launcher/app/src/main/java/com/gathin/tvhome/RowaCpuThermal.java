package com.gathin.tvhome;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

/** ROWA_HEADER_V5: unprivileged, best-effort actual CPU/SoC temperature.
 * No root, no extra service, no Internet, and no battery temperature masquerading as CPU.
 * A missing/blocked sensor returns -1; the Home badge then displays T --.
 */
final class RowaCpuThermal {
    private static final int HOT_C = 80;
    private static String temperaturePath;
    private static long nextDiscoveryAt;

    private RowaCpuThermal() { }

    static boolean isHot(int celsius) { return celsius >= HOT_C; }

    /** Called off the main thread, no more than once every 10 s by the existing stats reader. */
    static synchronized int readCelsius() {
        if (temperaturePath != null) {
            int value = readTemperature(temperaturePath);
            if (value >= 0) return value;
            temperaturePath = null;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < nextDiscoveryAt) return readFromThermalDump();
        nextDiscoveryAt = now + 60_000L; // No repeated sysfs directory walk on blocked firmware.
        int bestScore = 0;
        String bestPath = null;
        int bestValue = -1;
        for (int zone = 0; zone < 64; zone++) {
            String prefix = "/sys/class/thermal/thermal_zone" + zone + "/";
            String kind = readLine(prefix + "type");
            if (kind == null) continue;
            int score = rankSensor(kind);
            if (score <= bestScore) continue;
            int value = readTemperature(prefix + "temp");
            if (value < 0) continue;
            bestScore = score;
            bestPath = prefix + "temp";
            bestValue = value;
        }
        // Some SoCs expose thermal data under hwmon instead of thermal_zone.
        // Accept ONLY a labeled CPU/SoC channel; never use unlabeled board readings.
        if (bestPath == null) {
            for (int h = 0; h < 12; h++) {
                String dir = "/sys/class/hwmon/hwmon" + h + "/";
                for (int c = 1; c <= 8; c++) {
                    String label = readLine(dir + "temp" + c + "_label");
                    if (label == null || rankSensor(label) <= bestScore) continue;
                    String path = dir + "temp" + c + "_input";
                    int temperature = readTemperature(path);
                    if (temperature < 0) continue;
                    bestScore = rankSensor(label);
                    bestPath = path;
                    bestValue = temperature;
                }
            }
        }
        if (bestPath != null) temperaturePath = bestPath;
        return bestValue >= 0 ? bestValue : readFromThermalDump();
    }

    /** ROWA_V10 optional HAL read. The DUMP development permission is NOT granted
     * by the installer. Requires an explicit ADB grant; Android/SELinux may still
     * block this tool in the app sandbox. Never fabricate a numeric reading. */
    private static long rowaHalCheckedAt;
    private static int rowaHalLast = -1;
    private static int readFromThermalDump() {
        long now = SystemClock.elapsedRealtime();
        if (now - rowaHalCheckedAt < 30000L && rowaHalCheckedAt != 0) return rowaHalLast;
        rowaHalCheckedAt = now;
        rowaHalLast = -1;
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "thermalservice")
                    .redirectErrorStream(true).start();
            // Avoid indefinitely tying up the low-RAM TV's stats worker if the service stalls.
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            boolean current = false;
            String line;
            int limit = 500;
            while (limit-- > 0 && (line = reader.readLine()) != null) {
                if (line.contains("Current temperatures from HAL:")) {
                    current = true;
                    continue;
                }
                if (current && line.contains("Current cooling devices")) break;
                if (!current || !line.contains("mType=0") || !line.contains("mName=CPU")) continue;
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("mValue=([0-9]+(?:\\.[0-9]+)?)")
                        .matcher(line);
                if (matcher.find()) {
                    double temperature = Double.parseDouble(matcher.group(1));
                    if (temperature >= 15.0 && temperature <= 120.0) {
                        rowaHalLast = (int) Math.round(temperature);
                        break;
                    }
                }
            }
        } catch (java.io.IOException | SecurityException | NumberFormatException | InterruptedException ignored) {
            // Firmware sandbox denied the detailed HAL read; leave CPU --.
        } finally {
            if (process != null) process.destroy();
        }
        return rowaHalLast;
    }

    private static int rankSensor(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace('-', '_');
        if (n.contains("battery") || n.contains("batt") || n.contains("gpu")
                || n.contains("skin") || n.contains("ambient") || n.contains("board")) return 0;
        if (n.contains("cpu")) return 3;
        if (n.contains("soc") || n.contains("package")
                || n.equals("ap") || n.startsWith("mtktsap")) return 2;
        return 0; // Do not guess that an arbitrary thermal zone is a CPU sensor.
    }

    private static int readTemperature(String path) {
        String line = readLine(path);
        if (line == null) return -1;
        try {
            double raw = Double.parseDouble(line.trim());
            double celsius = Math.abs(raw) >= 1000 ? raw / 1000.0 : raw;
            if (celsius < 15 || celsius > 120) return -1;
            return (int) Math.round(celsius);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String readLine(String path) {
        try {
            File file = new File(path);
            if (!file.isFile()) return null;
            try (BufferedReader in = new BufferedReader(new FileReader(file))) {
                return in.readLine();
            }
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }
}
