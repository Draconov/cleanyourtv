package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Local launcher-only labels and artwork. No changes to installed applications. */
final class AppAppearance {
    private static final String PREFS = "app_appearance_v1";
    private static final String NAME = "name:";
    private static final String ART = "art:";
    private static final String FILE = "file";
    private static final String PACKAGE = "package:";
    static final int MAX_EDGE = 512;

    private AppAppearance() { }

    static String name(Context ctx, String pkg) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(NAME + pkg, "");
    }

    static String label(Context ctx, String pkg, String defaultLabel) {
        String user = name(ctx, pkg);
        return TextUtils.isEmpty(user) ? defaultLabel : user;
    }

    static String artSource(Context ctx, String pkg) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ART + pkg, "");
    }

    private static File imageFile(Context ctx, String pkg) {
        return new File(new File(ctx.getFilesDir(), "appearance_icons"), pkg + ".png");
    }

    static final class Artwork {
        final Drawable drawable;
        final boolean wide;
        Artwork(Drawable drawable, boolean wide) {
            this.drawable = drawable;
            this.wide = wide;
        }
    }

    static Artwork artwork(Context ctx, String pkg) {
        String source = artSource(ctx, pkg);
        if (source.isEmpty()) return null;
        if (FILE.equals(source)) {
            File file = imageFile(ctx, pkg);
            if (!file.isFile()) return null;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bmp == null) return null;
            return new Artwork(new BitmapDrawable(ctx.getResources(), bmp),
                    bmp.getWidth() > bmp.getHeight() * 1.35f);
        }
        if (source.startsWith(PACKAGE)) {
            String other = source.substring(PACKAGE.length());
            try {
                PackageManager pm = ctx.getPackageManager();
                int flags = Build.VERSION.SDK_INT >= 24 ? PackageManager.MATCH_DISABLED_COMPONENTS : 0;
                ApplicationInfo info = pm.getApplicationInfo(other, flags);
                // Prefer the source app's native TV banner when it offers one: this
                // usually looks better than cropping a square adaptive icon to 16:9.
                Drawable image = info.loadBanner(pm);
                if (image != null && image.getIntrinsicWidth() > 0 && image.getIntrinsicHeight() > 0
                        && image.getIntrinsicWidth() >= image.getIntrinsicHeight() * 1.35f) {
                    return new Artwork(image, true);
                }
                image = info.loadIcon(pm);
                return image == null ? null : new Artwork(image, false);
            } catch (PackageManager.NameNotFoundException ignored) {
                return null;
            }
        }
        return null;
    }

    static void setName(Context ctx, String pkg, String input) {
        String normalized = input == null ? "" : input.trim().replaceAll("\\s+", " ");
        if (normalized.length() > 48) normalized = normalized.substring(0, 48);
        if (normalized.equals(name(ctx, pkg))) return;
        SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (normalized.isEmpty()) editor.remove(NAME + pkg);
        else editor.putString(NAME + pkg, normalized);
        editor.apply();
        changed(ctx);
    }

    static void setAppIcon(Context ctx, String pkg, String sourcePkg) {
        if (TextUtils.isEmpty(sourcePkg) || !sourcePkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
            throw new IllegalArgumentException("Invalid application package");
        if (sourcePkg.equals(pkg)) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(ART + pkg, PACKAGE + sourcePkg).apply();
        imageFile(ctx, pkg).delete();
        changed(ctx);
    }

    /** Copies a user-selected photo into launcher-private storage; never keeps a fragile external URI. */
    static boolean setImage(Context ctx, String pkg, Uri uri) {
        File dest = imageFile(ctx, pkg);
        File dir = dest.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File temp = new File(dir, pkg + ".tmp");
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return false;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_EDGE * 2) sample *= 2;
            opts.inSampleSize = sample;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return false;
                bitmap = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bitmap == null) return false;
            if (Math.max(bitmap.getWidth(), bitmap.getHeight()) > MAX_EDGE) {
                float ratio = MAX_EDGE / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * ratio)),
                        Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
                if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled; }
            }
            try (FileOutputStream out = new FileOutputStream(temp)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return false;
            }
            if (dest.exists() && !dest.delete()) return false;
            if (!temp.renameTo(dest)) return false;
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ART + pkg, FILE).apply();
            changed(ctx);
            return true;
        } catch (Exception | OutOfMemoryError ex) {
            return false;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            temp.delete();
        }
    }

    static boolean hasOverride(Context ctx, String pkg) {
        return !name(ctx, pkg).isEmpty() || !artSource(ctx, pkg).isEmpty();
    }

    static void reset(Context ctx, String pkg) {
        if (!hasOverride(ctx, pkg)) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(NAME + pkg).remove(ART + pkg).apply();
        imageFile(ctx, pkg).delete();
        changed(ctx);
    }

    private static void changed(Context ctx) {
        // MainActivity observes the same generation number used by LauncherSettingsActivity.
        SharedPreferences preferences = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        preferences.edit().putLong(Config.SETTINGS_REVISION_KEY,
                preferences.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L).apply();
    }
}
