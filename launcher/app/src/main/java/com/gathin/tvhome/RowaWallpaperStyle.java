package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.TextUtils;

/** Per-image display choices; stored only for imported ROWA wallpaper slots. */
final class RowaWallpaperStyle {
    static final int CROP = 0, FIT = 1, TILE = 2;
    static final int BLURRED = 0, SOLID = 1, DOMINANT = 2;
    static final String EXTRA_IMPORT_STYLES = "rowa_wallpaper_import_styles_v156";
    static final String EXTRA_IMPORT_URIS = "rowa_wallpaper_import_uris_v156";
    private static final String PREFS = "rowa_wallpaper_styles_v156";

    int mode = CROP;
    int backdrop = BLURRED;
    int blur = 14;  // processed only on a tiny 80x45 scratch image, never as a live effect
    int backdropDim = 45;
    int solidColor = 0xFF1F2733;
    int tilePercent = 100;
    int imageDim = -1; // -1 = existing theme wallpaper_dim, other values override per picture

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static RowaWallpaperStyle defaults(Context c) {
        return parse(prefs(c).getString("default_style", null), new RowaWallpaperStyle());
    }
    static void setDefaults(Context c, RowaWallpaperStyle value) {
        prefs(c).edit().putString("default_style", value.encode()).apply();
    }
    static RowaWallpaperStyle forFile(Context c, String name) {
        if (name == null || !name.matches("rowa_user_\\d\\d\\.(jpg|png)")) return defaults(c);
        return parse(prefs(c).getString("file_" + name, null), defaults(c));
    }
    /** Imported sources are already checked when the gallery opens. Preserve their import order. */
    static java.util.LinkedHashSet<String> installedSources(Context c) {
        java.util.LinkedHashSet<String> sources = new java.util.LinkedHashSet<>();
        SharedPreferences p = prefs(c);
        for (java.io.File file : RowaVisualOptions.images(c)) {
            String source = p.getString("source_" + file.getName(), null);
            if (!TextUtils.isEmpty(source)) sources.add(source);
        }
        return sources;
    }

    static String savedStyleForSource(Context c, String uri) {
        SharedPreferences p = prefs(c);
        for (java.io.File file : RowaVisualOptions.images(c)) {
            String name = file.getName();
            if (uri.equals(p.getString("source_" + name, "")))
                return p.getString("file_" + name, null);
        }
        return null;
    }
    static void setForFile(Context c, String name, RowaWallpaperStyle style) {
        if (name != null && name.matches("rowa_user_\\d\\d\\.(jpg|png)"))
            prefs(c).edit().putString("file_" + name, style.encode()).apply();
    }
    static RowaWallpaperStyle forSource(Context c, String uri) {
        // Existing source associations make options editable after closing and reopening the gallery.
        SharedPreferences p = prefs(c);
        for (java.io.File file : RowaVisualOptions.images(c)) {
            String name = file.getName();
            if (uri.equals(p.getString("source_" + name, ""))) return forFile(c, name);
        }
        return defaults(c);
    }
    static void committed(Context c, java.util.List<String> sources, java.util.List<String> styles) {
        SharedPreferences.Editor edit = prefs(c).edit();
        for (int i = 1; i <= RowaVisualOptions.MAX_IMAGES; ++i) {
            String prefix = String.format(java.util.Locale.ROOT, "rowa_user_%02d", i);
            for (String ext : new String[]{".jpg", ".png"})
                edit.remove("file_" + prefix + ext).remove("source_" + prefix + ext);
        }
        java.util.List<java.io.File> actual = RowaVisualOptions.images(c);
        for (int i = 0; i < sources.size() && i < actual.size() && i < RowaVisualOptions.MAX_IMAGES; i++) {
            String slot = actual.get(i).getName();
            edit.putString("source_" + slot, sources.get(i));
            if (styles != null && i < styles.size() && !TextUtils.isEmpty(styles.get(i)))
                edit.putString("file_" + slot, styles.get(i));
        }
        edit.commit();
    }
    static void cleared(Context c) { committed(c, java.util.Collections.emptyList(), null); }

    RowaWallpaperStyle copy() { return parse(encode(), new RowaWallpaperStyle()); }
    String encode() {
        return mode + "," + backdrop + "," + blur + "," + backdropDim + "," + solidColor
                + "," + tilePercent + "," + imageDim;
    }
    static RowaWallpaperStyle parse(String stored, RowaWallpaperStyle fallback) {
        RowaWallpaperStyle v = new RowaWallpaperStyle();
        v.mode = fallback.mode;
        v.backdrop = fallback.backdrop;
        v.blur = fallback.blur;
        v.backdropDim = fallback.backdropDim;
        v.solidColor = fallback.solidColor;
        v.tilePercent = fallback.tilePercent;
        v.imageDim = fallback.imageDim;
        if (stored != null) try {
            String[] p = stored.split(",", -1);
            if (p.length != 7) return v;
            v.mode = clamp(Integer.parseInt(p[0]), 0, 2);
            v.backdrop = clamp(Integer.parseInt(p[1]), 0, 2);
            v.blur = clamp(Integer.parseInt(p[2]), 0, 30);
            v.backdropDim = clamp(Integer.parseInt(p[3]), 0, 90);
            v.solidColor = Color.rgb(Color.red(Integer.parseInt(p[4])), Color.green(Integer.parseInt(p[4])), Color.blue(Integer.parseInt(p[4])));
            v.tilePercent = clamp(Integer.parseInt(p[5]), 25, 400);
            v.imageDim = Integer.parseInt(p[6]);
            if (v.imageDim < -1 || v.imageDim > 90) v.imageDim = -1;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) { return fallback.copy(); }
        return v;
    }
    static int clamp(int n, int lo, int hi) { return Math.max(lo, Math.min(hi, n)); }
}
