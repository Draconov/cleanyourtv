package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** ROWA_V15: private, bounded wallpapers and focused-panel opacity. No storage permission needed. */
final class RowaVisualOptions {
    private static final String PREFS = "rowa_visual_v15";
    private static final String DIRECTORY = "rowa_wallpapers";
    static final int MAX_IMAGES = 8;
    private RowaVisualOptions() { }

    static int panelOpacity(Context context) {
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("opacity", 95);
        return Math.max(35, Math.min(95, value));
    }
    static void setPanelOpacity(Context context, int value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("opacity", Math.max(35, Math.min(95, value))).apply();
    }
    static int panelAlpha(Context context) { return Math.round(panelOpacity(context) * 255f / 100f); }

    /** MediaStore RELATIVE_PATH (not an absolute filesystem path). Defaults to Download/. */
    static String sourceFolder(Context context) {
        String chosen = RowaSourceFolder.normalize(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("background_source_folder", "Download/"));
        return chosen == null ? "Download/" : chosen;
    }
    static void setSourceFolder(Context context, String path) {
        String safe = RowaSourceFolder.normalize(path);
        if (safe == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("background_source_folder", safe).apply();
    }

    private static File folder(Context context) {
        return new File(context.getFilesDir(), DIRECTORY);
    }
    static List<File> images(Context context) {
        File[] files = folder(context).listFiles((dir, name) -> name.matches("rowa_user_\\d\\d\\.(jpg|png)"));
        if (files == null || files.length == 0) return new ArrayList<>();
        Arrays.sort(files, Comparator.comparing(File::getName));
        return new ArrayList<>(Arrays.asList(files));
    }
    /**
     * App-specific external Pictures directory is writable by adb shell without asking the
     * launcher for blanket media-storage permission. The importer is invoked only by the user.
     * On Android 11+ this is NOT a public gallery folder: normal third-party file managers
     * cannot freely browse it, but the launcher itself can read it without a storage grant.
     */
    private static File transferFolder(Context context) {
        File pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return pictures == null ? null : new File(pictures, "ROWA-Import");
    }

    private static List<File> transferredImages(Context context) {
        File folder = transferFolder(context);
        if (folder == null) return new ArrayList<>();
        File[] candidates = folder.listFiles(file -> file.isFile()
                && file.length() > 0 && file.length() <= 25L * 1024 * 1024
                && file.getName().toLowerCase(java.util.Locale.ROOT)
                    .matches(".*\\.(jpg|jpeg|png|webp)"));
        if (candidates == null) return new ArrayList<>();
        Arrays.sort(candidates, Comparator.comparing(File::getName));
        return new ArrayList<>(Arrays.asList(candidates));
    }

    /** Is there something transferred by Send-Backgrounds.ps1 to import on the next menu click? */
    static boolean hasTransferredImages(Context context) {
        return !transferredImages(context).isEmpty();
    }

    /**
     * Same bounded decoder used by the picker; no background polling, no extra permission.
     * Successful imports are deleted from staging so a later click doesn't duplicate them.
     */
    static void importTransferred(Activity activity, Consumer<Integer> finished) {
        List<File> transfers = transferredImages(activity);
        if (transfers.isEmpty()) { finished.accept(0); return; }
        Thread worker = new Thread(() -> {
            int imported = 0;
            File target = folder(activity.getApplicationContext());
            if (target.isDirectory() || target.mkdirs()) {
                for (File image : transfers) {
                    if (images(activity).size() >= MAX_IMAGES) break;
                    try {
                        if (saveOne(activity, target, Uri.fromFile(image))) {
                            imported++;
                            if (!image.delete()) Log.w("ROWA_V15", "Staged image could not be deleted: " + image.getName());
                        }
                    } catch (Exception | OutOfMemoryError failure) {
                        Log.w("ROWA_V15", "Transferred wallpaper skipped: " + image.getName(), failure);
                    }
                }
            }
            final int count = imported;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!activity.isDestroyed() && !activity.isFinishing()) finished.accept(count);
            });
        }, "rowa-wallpaper-transfer-import");
        worker.setDaemon(true);
        worker.start();
    }

    static void clearImages(Context context) {
        RowaWallpaperStyle.cleared(context);
        for (File file : images(context)) if (!file.delete())
            Log.w("ROWA_V15", "Unable to delete custom wallpaper: " + file.getName());
    }
    /**
     * Replace wallpapers in one user-confirmed operation. All selected images are first decoded
     * and saved in a staging directory; an invalid/inaccessible image leaves the old set intact.
     * A same-filesystem directory swap keeps the old set as backup until commit succeeds.
     */
    static void replaceSelection(Activity activity, Intent data, Consumer<Integer> finished) {
        final List<Uri> picked = new ArrayList<>();
        final java.util.ArrayList<String> incomingStyles = data == null ? null
                : data.getStringArrayListExtra(RowaWallpaperStyle.EXTRA_IMPORT_STYLES);
        final java.util.ArrayList<String> incomingUris = data == null ? null
                : data.getStringArrayListExtra(RowaWallpaperStyle.EXTRA_IMPORT_URIS);
        // Our internal folder browser can return unindexed, directly readable files. Keep their
        // file:// URIs out of ClipData (Android disallows exposing them to other apps).
        if (incomingUris != null && incomingUris.size() <= MAX_IMAGES) {
            for (String source : incomingUris) {
                if (source == null) break;
                Uri uri = Uri.parse(source);
                if ("content".equals(uri.getScheme()) || "file".equals(uri.getScheme())) picked.add(uri);
            }
        } else if (data != null && data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount() && i < MAX_IMAGES; i++)
                picked.add(data.getClipData().getItemAt(i).getUri());
        } else if (data != null && data.getData() != null) picked.add(data.getData());
        if (picked.isEmpty()) { finished.accept(0); return; }
        Thread worker = new Thread(() -> {
            Context app = activity.getApplicationContext();
            File base = app.getFilesDir();
            File live = new File(base, DIRECTORY);
            File stage = new File(base, DIRECTORY + "_incoming");
            File backup = new File(base, DIRECTORY + "_previous");
            // Recover from a prior interrupted swap, if necessary.
            if (!live.exists() && backup.isDirectory()) backup.renameTo(live);
            removeDirectory(stage);
            int result = 0;
            if (stage.mkdirs()) {
                int saved = 0;
                for (Uri uri : picked) {
                    try {
                        if (saveOne(app, stage, uri)) saved++;
                        else break;
                    } catch (Exception | OutOfMemoryError failure) {
                        Log.w("ROWA_V15", "Wallpaper selection skipped", failure);
                        break;
                    }
                }
                if (saved == picked.size()) {
                    // A previous completed transaction can leave a backup only after process death.
                    if (live.isDirectory()) removeDirectory(backup);
                    boolean savedOld = !live.exists() || live.renameTo(backup);
                    if (savedOld) {
                        if (stage.renameTo(live)) {
                            result = saved;
                            // Only commit per-file settings after the new files are safely live.
                            java.util.ArrayList<String> sources = new java.util.ArrayList<>();
                            for (Uri uri : picked) sources.add(uri.toString());
                            if (incomingUris == null || incomingUris.equals(sources))
                                RowaWallpaperStyle.committed(app, sources, incomingStyles);
                            else
                                RowaWallpaperStyle.committed(app, sources, null);
                            removeDirectory(backup);
                        } else if (backup.exists()) {
                            backup.renameTo(live);
                        }
                    }
                }
            }
            removeDirectory(stage);
            final int imported = result;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!activity.isDestroyed() && !activity.isFinishing()) finished.accept(imported);
            });
        }, "rowa-wallpaper-replace");
        worker.setDaemon(true);
        worker.start();
    }

    private static void removeDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) return;
        File[] contents = directory.listFiles();
        if (contents != null) for (File file : contents) {
            if (file.isDirectory()) removeDirectory(file);
            else if (!file.delete()) Log.w("ROWA_V15", "Could not remove " + file.getName());
        }
        if (!directory.delete()) Log.w("ROWA_V15", "Could not remove " + directory.getName());
    }

    private static boolean saveOne(Context ctx, File directory, Uri uri) throws Exception {
        int slot = 0;
        for (int i=1; i<=MAX_IMAGES; i++) {
            String prefix = String.format(java.util.Locale.ROOT, "rowa_user_%02d", i);
            if (!new File(directory, prefix + ".jpg").exists()
                    && !new File(directory, prefix + ".png").exists()) { slot = i; break; }
        }
        if (slot == 0) return false;
        // Bounds-only decode first. Reject unusual dimensions before any bitmap allocation.
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            BitmapFactory.decodeStream(in, null, options);
        }
        int w = options.outWidth, h = options.outHeight;
        if (w < 64 || h < 64 || w > 12000 || h > 12000) return false;
        // Preserve small PNG patterns losslessly, including alpha, so tiled edges do not
        // gain JPEG artifacts. Large photos still use memory-friendly JPEG transcoding.
        String mime = null;
        try { mime = ctx.getContentResolver().getType(uri); }
        catch (Exception ignored) { /* The URI extension is also checked below. */ }
        String uriText = String.valueOf(uri).toLowerCase(java.util.Locale.ROOT);
        boolean pngSource = "image/png".equalsIgnoreCase(mime) || uriText.endsWith(".png");
        boolean lossless = pngSource && (long) w * h <= 1024L * 1024L;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = lossless ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // Keep decode dimensions near 1920x1080: one 16-bit bitmap is typically about 4 MB.
        options.inSampleSize = 1;
        while (w / options.inSampleSize > 1920 || h / options.inSampleSize > 1080)
            options.inSampleSize *= 2;
        Bitmap decoded;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) return false;
        File dest = new File(directory, String.format(java.util.Locale.ROOT,
                "rowa_user_%02d.%s", slot, lossless ? "png" : "jpg"));
        File temp = new File(directory, dest.getName()+".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                if (!decoded.compress(lossless ? Bitmap.CompressFormat.PNG
                        : Bitmap.CompressFormat.JPEG, 86, out)) return false;
            }
            return temp.renameTo(dest);
        } finally {
            decoded.recycle();
            if (temp.exists()) temp.delete();
        }
    }
}
