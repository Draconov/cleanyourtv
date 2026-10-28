package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import java.io.InputStream;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.media.MediaScannerConnection;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in TV image browser: choose a public MediaStore folder and preview its
 * photos in a vertically scrolling single-column glass dropdown. The focused
 * image is previewed behind the menu (not behind the launcher's home controls).
 * The filename and Image settings action sit at the screen's left edge.
 * OK toggles a photo; Apply replaces the
 * current private set of up to eight wallpapers. There is no external picker,
 * permanent scanner, raw filesystem access, or MANAGE_EXTERNAL_STORAGE grant.
 */
public final class RowaDownloadsBrowserActivity extends Activity {
    public static final String EXTRA_PICK_FOLDER = "rowa_pick_background_folder";
    private static final int PERMISSION_REQUEST = 731;
    private static final long PREVIEW_DWELL_MS = 450L;
    private static final int MAX_VISIBLE = 400;
    private static final int MAX_FOLDERS = 200;
    private static final int THUMB_KIB = 6 * 1024;

    private final ArrayList<Photo> photos = new ArrayList<>();
    private final ArrayList<Folder> folders = new ArrayList<>();
    private final LinkedHashSet<String> chosen = new LinkedHashSet<>();
    private final java.util.HashMap<String, RowaWallpaperStyle> photoStyles = new java.util.HashMap<>();
    private final LruCache<Long, Bitmap> thumbnails = new LruCache<Long, Bitmap>(THUMB_KIB) {
        @Override protected int sizeOf(Long key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };
    private final ExecutorService decoder = Executors.newFixedThreadPool(2);
    private final Set<Long> loading = Collections.synchronizedSet(new HashSet<>());
    private GridView grid;
    private ImageView fullScreenPreview;
    private Bitmap previewBitmap;
    private boolean previewOpen;
    /** Back/OK returns to the grid without immediately reopening the same picture. */
    private boolean previewSuppressed;
    private int previewPosition = -1;
    private volatile int previewGeneration;
    private int previewDim;
    private TextView filenameBanner;
    /** Infrequent, focus-driven filename marquee; no continuously running animation. */
    private final Handler filenameHandler = new Handler(Looper.getMainLooper());
    private int filenameGeneration;
    private int filenameMaxWidth;
    private LinearLayout galleryPane, optionsPane;
    private Button galleryBackButton;
    private boolean settingsMode;
    private int settingsPhotoPosition = -1;
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private Runnable queuedPreview;
    private ListView folderList;
    private TextView status, title, help;
    private Button sourceButton, scanButton, applyButton, cancelButton, imageOptionsButton;
    private LinearLayout sourceRow;
    private PhotoAdapter photoAdapter;
    private FolderAdapter folderAdapter;
    private boolean folderMode, folderOnly;
    private int thumbHeight, thumbWidth;
    private int requestGeneration;
    private String currentFolder;

    private static final class Photo {
        final long id;
        final String name;
        final Uri uri;
        Photo(long id, String name, Uri uri) {
            this.id = id; this.name = name; this.uri = uri;
        }
    }
    private static final class Folder {
        final String relativePath;
        final int count;
        Folder(String path, int count) { relativePath = path; this.count = count; }
    }

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        folderOnly = getIntent().getBooleanExtra(EXTRA_PICK_FOLDER, false);
        currentFolder = RowaVisualOptions.sourceFolder(this);
        previewDim = Math.max(0, Math.min(100, Config.load(this).wallpaperDim));
        // Existing installed wallpapers remain selected until the user unchecks them.
        // Closing without Apply discards only pending edits, not the installed collection.
        chosen.addAll(RowaWallpaperStyle.installedSources(this));
        buildUi();
        title.setText(folderOnly ? R.string.rowa_browser_choose_folder : R.string.rowa_browser_title);
        help.setText(folderOnly ? R.string.rowa_browser_folder_hint : R.string.rowa_browser_hint);
        if (hasMediaPermission()) openInitialScreen();
        else askMediaPermission();
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    private GradientDrawable rounded(int fill, int stroke, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }
    private Button action(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setBackground(RowaMenuStyle.row(this, false));
        button.setPadding(dp(9), dp(3), dp(9), dp(3));
        button.setOnFocusChangeListener((v, focused) -> {
            button.setBackground(RowaMenuStyle.row(this, focused));
            button.setTextColor(focused ? Color.BLACK : Color.WHITE);
        });
        RowaMenuMarquee.bind(button, button);
        return button;
    }

    /** The browser is a floating glass menu, not an opaque full-screen settings page. */
    private void buildUi() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);
        // Actual composed wallpaper image behind the menu, with no launcher controls.
        fullScreenPreview = new ImageView(this);
        fullScreenPreview.setScaleType(ImageView.ScaleType.FIT_XY);
        fullScreenPreview.setBackgroundColor(Color.BLACK);
        fullScreenPreview.setFocusable(false);
        fullScreenPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        screen.addView(fullScreenPreview, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        // In 720p this leaves most of the wallpaper visible to the left.
        int panelWidth = Math.min(dp(350), Math.round(screenWidth * 0.34f));
        int panelHeight = Math.min(dp(690), screenHeight - dp(44));
        FrameLayout panelHost = new FrameLayout(this);
        panelHost.setBackgroundResource(R.drawable.glass_panel);
        // Match the launcher menu's glass intensity setting rather than introducing a second style.
        panelHost.getBackground().mutate().setAlpha(RowaVisualOptions.panelAlpha(this));
        panelHost.setPadding(dp(11), dp(10), dp(11), dp(10));
        FrameLayout.LayoutParams hostParams = new FrameLayout.LayoutParams(
                panelWidth, panelHeight, Gravity.TOP | Gravity.RIGHT);
        hostParams.topMargin = dp(18);
        hostParams.rightMargin = dp(26);
        screen.addView(panelHost, hostParams);

        filenameBanner = label("", 17, Color.WHITE);
        filenameBanner.setSingleLine(true);
        filenameBanner.setEllipsize(TextUtils.TruncateAt.END);
        filenameBanner.setMarqueeRepeatLimit(1);
        filenameBanner.setPadding(dp(14), dp(5), dp(14), dp(5));
        filenameBanner.setBackground(rounded(0xB8101218, 0x35FFFFFF, 9));
        filenameMaxWidth = Math.max(dp(180), screenWidth - panelWidth - dp(90));
        filenameBanner.setMaxWidth(filenameMaxWidth);
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                -2, dp(42), Gravity.TOP | Gravity.LEFT);
        nameParams.topMargin = dp(34);
        nameParams.leftMargin = dp(27);
        screen.addView(filenameBanner, nameParams);

        galleryPane = new LinearLayout(this);
        galleryPane.setOrientation(LinearLayout.VERTICAL);
        panelHost.addView(galleryPane, new FrameLayout.LayoutParams(-1, -1));
        title = label("", 20, Color.WHITE);
        galleryPane.addView(title, new LinearLayout.LayoutParams(-1, dp(34)));
        help = label("", 11, 0xFFCAD2DD);
        help.setSingleLine(true);
        help.setEllipsize(TextUtils.TruncateAt.END);
        galleryPane.addView(help, new LinearLayout.LayoutParams(-1, dp(25)));

        sourceButton = action("");
        sourceButton.setSingleLine(true);
        sourceButton.setEllipsize(TextUtils.TruncateAt.END);
        sourceButton.setOnClickListener(v -> showFolders());
        sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        sourceRow.addView(sourceButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        scanButton = action(getString(R.string.rowa_browser_rescan));
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(-2, dp(42));
        scanParams.leftMargin = dp(6);
        sourceRow.addView(scanButton, scanParams);
        scanButton.setOnClickListener(v -> rescanCurrentFolder());
        galleryPane.addView(sourceRow, new LinearLayout.LayoutParams(-1, dp(44)));

        FrameLayout content = new FrameLayout(this);
        galleryPane.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        grid = new GridView(this);
        grid.setId(View.generateViewId());
        grid.setNumColumns(1);
        grid.setHorizontalSpacing(0);
        grid.setVerticalSpacing(dp(8));
        grid.setPadding(dp(3), dp(3), dp(3), dp(3));
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setDrawSelectorOnTop(true);
        GradientDrawable focus = new GradientDrawable();
        focus.setColor(0x66F1F4F6);
        focus.setCornerRadius(dp(RowaMenuStyle.ROW_RADIUS_DP));
        focus.setStroke(dp(3), Color.WHITE);
        grid.setSelector(focus);
        photoAdapter = new PhotoAdapter();
        grid.setAdapter(photoAdapter);
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < photos.size())
                    setFilename(photos.get(position).name);
                schedulePreview(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { cancelPendingPreview(); }
        });
        grid.setOnFocusChangeListener((view, focused) -> {
            if (!focused) cancelPendingPreview();
            else schedulePreview(grid.getSelectedItemPosition());
        });
        // The gallery lives on the RIGHT of the TV: press LEFT to switch its
        // glass panel from thumbnails to the selected image's settings.
        // OK still selects/deselects a wallpaper; Back restores the gallery.
        grid.setOnKeyListener((view, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) showImageOptions();
                return true;
            }
            return false;
        });
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= photos.size()) return;
            Photo photo = photos.get(position);
            String key = photo.uri.toString();
            if (chosen.contains(key)) chosen.remove(key);
            else if (chosen.size() < RowaVisualOptions.MAX_IMAGES) chosen.add(key);
            else { message(getString(R.string.rowa_browser_limit, RowaVisualOptions.MAX_IMAGES)); return; }
            photoAdapter.notifyDataSetChanged();
            updateStatus();
            // Do not remove the preview or cover it with the launcher UI on OK.
        });
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        folderList = new ListView(this);
        folderList.setId(View.generateViewId());
        folderList.setDivider(new ColorDrawable(0xFF2A3039));
        folderList.setDividerHeight(dp(2));
        GradientDrawable folderFocus = new GradientDrawable();
        folderFocus.setColor(0xFF35383E);
        folderFocus.setCornerRadius(dp(RowaMenuStyle.ROW_RADIUS_DP));
        folderFocus.setStroke(dp(2), Color.WHITE);
        folderList.setSelector(folderFocus);
        folderList.setDrawSelectorOnTop(true);
        folderAdapter = new FolderAdapter();
        folderList.setAdapter(folderAdapter);
        folderList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= folders.size()) return;
            currentFolder = folders.get(position).relativePath;
            RowaVisualOptions.setSourceFolder(this, currentFolder);
            if (folderOnly) { setResult(RESULT_OK); finish(); }
            else showImages();
        });
        content.addView(folderList, new FrameLayout.LayoutParams(-1, -1));
        folderList.setVisibility(View.GONE);

        status = label(getString(R.string.rowa_browser_loading), 12, 0xFFD3D9E1);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        galleryPane.addView(status, new LinearLayout.LayoutParams(-1, dp(27)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        cancelButton = action(getString(android.R.string.cancel));
        cancelButton.setId(View.generateViewId());
        cancelButton.setOnClickListener(v -> onBackPressed());
        applyButton = action(getString(R.string.rowa_browser_import));
        applyButton.setId(View.generateViewId());
        applyButton.setOnClickListener(v -> {
            if (folderMode) askMediaPermission();
            else finishWithSelection();
        });
        for (Button b : new Button[]{cancelButton,applyButton}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(43), 1);
            lp.leftMargin = dp(6);
            actions.addView(b, lp);
        }
        galleryPane.addView(actions, new LinearLayout.LayoutParams(-1, dp(45)));

        // The options panel replaces (never overlays) the image grid inside the
        // exact same glass dropdown frame, rather than opening an AlertDialog.
        optionsPane = new LinearLayout(this);
        optionsPane.setOrientation(LinearLayout.VERTICAL);
        optionsPane.setVisibility(View.GONE);
        panelHost.addView(optionsPane, new FrameLayout.LayoutParams(-1, -1));

        imageOptionsButton = action(getString(R.string.rowa_v156_image_options));
        imageOptionsButton.setId(View.generateViewId());
        imageOptionsButton.setOnClickListener(v -> {
            if (settingsMode) returnToGallery();
            else if (folderMode) enterFolderPath();
            else showImageOptions();
        });
        FrameLayout.LayoutParams optionsParams = new FrameLayout.LayoutParams(
                Math.min(dp(230), Math.max(dp(140), screenWidth - panelWidth - dp(100))),
                dp(49), Gravity.LEFT | Gravity.BOTTOM);
        optionsParams.leftMargin = dp(27);
        optionsParams.bottomMargin = dp(20);
        screen.addView(imageOptionsButton, optionsParams);

        grid.setNextFocusDownId(imageOptionsButton.getId());
        folderList.setNextFocusDownId(cancelButton.getId());
        // Compact single-column photographs with no duplicated filename text.
        // Keep the whole image visible in each tile, leaving ample space for the preview.
        thumbWidth = Math.min(dp(182), panelWidth - dp(35));
        thumbHeight = Math.min(dp(110), Math.round(thumbWidth * 9f / 16f));
    }

    /** The top-left filename replaces the redundant labels beneath the thumbnail images.
     *  Long names scroll for a single pass and rest for five seconds, like the
     *  existing launcher dropdown marquee. The animation stops when the TV leaves
     *  this activity or a different image is selected. */
    private void setFilename(String name) {
        ++filenameGeneration;
        filenameHandler.removeCallbacksAndMessages(null);
        filenameBanner.setSelected(false);
        filenameBanner.setEllipsize(TextUtils.TruncateAt.END);
        filenameBanner.setText(name);
        int count = name.codePointCount(0, name.length());
        // Long names display approximately their first 20 characters at once,
        // instead of stretching the entire way towards the glass panel. This
        // guarantees a meaningful marquee even on a very wide television.
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) filenameBanner.getLayoutParams();
        int targetWidth = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (count > 20) {
            int first20 = name.offsetByCodePoints(0, 20);
            float visible = filenameBanner.getPaint().measureText(name, 0, first20);
            targetWidth = Math.min(filenameMaxWidth,
                    Math.max(dp(130), Math.round(visible) + filenameBanner.getPaddingLeft()
                            + filenameBanner.getPaddingRight() + dp(8)));
        }
        if (params.width != targetWidth) {
            params.width = targetWidth;
            filenameBanner.setLayoutParams(params);
        }
        filenameBanner.setVisibility(name.isEmpty() ? View.GONE : View.VISIBLE);
        if (count <= 20) return;
        int expected = filenameGeneration;
        filenameBanner.post(() -> runFilenameMarquee(expected));
    }

    private void runFilenameMarquee(int expected) {
        if (expected != filenameGeneration || isFinishing() || isDestroyed()
                || filenameBanner.getVisibility() != View.VISIBLE || !hasWindowFocus()) return;
        int room = filenameBanner.getWidth() - filenameBanner.getPaddingLeft()
                - filenameBanner.getPaddingRight();
        float width = filenameBanner.getPaint().measureText(filenameBanner.getText().toString());
        if (room <= 0 || width <= room + dp(2)) return;
        filenameBanner.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        filenameBanner.setSelected(true);
        float pxPerSecond = 32f * getResources().getDisplayMetrics().density;
        long pass = 1200L + (long) (1000f * (width + room) / pxPerSecond);
        filenameHandler.postDelayed(() -> {
            if (expected != filenameGeneration || isFinishing() || isDestroyed()) return;
            filenameBanner.setSelected(false);
            filenameBanner.setEllipsize(TextUtils.TruncateAt.END);
            filenameHandler.postDelayed(() -> runFilenameMarquee(expected), 5000L);
        }, Math.min(45000L, Math.max(2600L, pass)));
    }

    private void message(String text) { status.setText(text); }
    private void openInitialScreen() { if (folderOnly) showFolders(); else showImages(); }

    private void showFolders() {
        folderMode = true;
        settingsMode = false;
        galleryPane.setVisibility(View.VISIBLE);
        optionsPane.setVisibility(View.GONE);
        setFilename("");
        hidePreview(false);
        ++requestGeneration;
        title.setText(R.string.rowa_browser_choose_folder);
        help.setText(R.string.rowa_browser_folder_hint);
        sourceRow.setVisibility(View.GONE);
        grid.setVisibility(View.GONE);
        folderList.setVisibility(View.VISIBLE);
        applyButton.setText(R.string.rowa_browser_grant_photos);
        applyButton.setVisibility(hasLimitedMediaPermission() ? View.VISIBLE : View.GONE);
        imageOptionsButton.setText(R.string.rowa_browser_enter_path);
        imageOptionsButton.setVisibility(View.VISIBLE);
        cancelButton.setText(folderOnly ? android.R.string.cancel : R.string.rowa_browser_back);
        loadFolders();
    }

    private void showImages() {
        folderMode = false;
        settingsMode = false;
        galleryPane.setVisibility(View.VISIBLE);
        optionsPane.setVisibility(View.GONE);
        hidePreview(false);
        previewPosition = -1;
        previewSuppressed = false;
        title.setText(R.string.rowa_browser_title);
        help.setText(R.string.rowa_browser_hint);
        sourceRow.setVisibility(View.VISIBLE);
        sourceButton.setText(getString(R.string.rowa_browser_source, currentFolder));
        grid.setVisibility(View.VISIBLE);
        folderList.setVisibility(View.GONE);
        applyButton.setText(R.string.rowa_browser_import);
        imageOptionsButton.setText(R.string.rowa_v156_image_options);
        applyButton.setVisibility(View.VISIBLE);
        imageOptionsButton.setVisibility(View.VISIBLE);
        cancelButton.setText(android.R.string.cancel);
        // Keep checkmarks and unsaved per-image edits when switching source folders.
        // Import replaces the installed set only after Apply succeeds.
        loadPhotos();
    }

    private Uri imageCollection() {
        return Build.VERSION.SDK_INT >= 29 ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }
    private boolean supportedMime(String mime) {
        return "image/jpeg".equalsIgnoreCase(mime) || "image/png".equalsIgnoreCase(mime)
                || "image/webp".equalsIgnoreCase(mime);
    }

    /** Public shared media folders only; never traverse app-private Android/data folders. */
    private File publicDirectory(String relativePath) {
        String normalized = RowaSourceFolder.normalize(relativePath);
        if (normalized == null) return null;
        return new File(Environment.getExternalStorageDirectory(), normalized);
    }

    private boolean supportedFile(File file) {
        if (file == null || !file.isFile() || file.length() == 0 || file.length() > 25L * 1024L * 1024L)
            return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".webp");
    }

    /** Only list user-selected public folders and small, common subfolder trees. */
    private File[] mediaFiles(String relativePath) {
        if (!hasFullMediaPermission()) return new File[0];
        File folder = publicDirectory(relativePath);
        if (folder == null || !folder.isDirectory()) return new File[0];
        try {
            File[] files = folder.listFiles(this::supportedFile);
            return files == null ? new File[0] : files;
        } catch (SecurityException problem) {
            android.util.Log.w("ROWA_BROWSER", "Direct folder listing not permitted", problem);
            return new File[0];
        }
    }

    private void addPublicSubfolders(LinkedHashMap<String, Integer> counts, String parent) {
        File parentFolder = publicDirectory(parent);
        if (parentFolder == null || !parentFolder.isDirectory()) return;
        File[] children;
        try { children = parentFolder.listFiles(File::isDirectory); }
        catch (SecurityException problem) { return; }
        if (children == null) return;
        int checked = 0;
        for (File child : children) {
            if (++checked > 100) break;
            if (child.getName().startsWith(".")) continue;
            String path = RowaSourceFolder.normalize(parent + child.getName());
            if (path == null) continue;
            counts.putIfAbsent(path, 0);
            // One additional level supports e.g. Pictures/Art/Wallpapers without a full-disk walk.
            if (path.split("/").length <= 3) {
                File[] grandChildren;
                try { grandChildren = child.listFiles(File::isDirectory); }
                catch (SecurityException problem) { continue; }
                if (grandChildren == null) continue;
                int grandCount = 0;
                for (File grandChild : grandChildren) {
                    if (++grandCount > 50) break;
                    if (grandChild.getName().startsWith(".")) continue;
                    String grandPath = RowaSourceFolder.normalize(path + grandChild.getName());
                    if (grandPath != null) counts.putIfAbsent(grandPath, 0);
                }
            }
        }
    }

    /** Handles paths typed with the TV keyboard, including /sdcard/Pictures/Wallpapers. */
    private void enterFolderPath() {
        EditText path = new EditText(this);
        path.setSingleLine(true);
        path.setText(currentFolder);
        path.setSelectAllOnFocus(true);
        path.setHint("Pictures/Wallpapers/");
        RowaGlassDialog.input(this, getString(R.string.rowa_browser_enter_path), path,
                null, () -> {
                    String folder = RowaSourceFolder.normalize(path.getText().toString());
                    if (folder == null) {
                        android.widget.Toast.makeText(this, R.string.rowa_browser_bad_path,
                                android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    currentFolder = folder;
                    RowaVisualOptions.setSourceFolder(this, currentFolder);
                    if (folderOnly) { setResult(RESULT_OK); finish(); }
                    else showImages();
                }, null, null);
    }

    /** Explicit, one-shot rescan: no background polling or full-storage permission. */
    private void rescanCurrentFolder() {
        File[] files = mediaFiles(currentFolder);
        if (files.length == 0) {
            message(getString(hasLimitedMediaPermission() ? R.string.rowa_browser_partial
                    : R.string.rowa_browser_rescan_no_files));
            return;
        }
        int count = Math.min(files.length, 250);
        String[] paths = new String[count];
        for (int i = 0; i < count; i++) paths[i] = files[i].getAbsolutePath();
        message(getString(R.string.rowa_browser_scanning, count));
        final java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        MediaScannerConnection.scanFile(this, paths, null, (scannedPath, scannedUri) -> {
            if (completed.incrementAndGet() == count) runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && !folderMode) loadPhotos();
            });
        });
    }

    private void loadFolders() {
        final int generation = ++requestGeneration;
        message(getString(R.string.rowa_browser_loading_folders));
        decoder.execute(() -> {
            LinkedHashMap<String,Integer> counts = new LinkedHashMap<>();
            // MediaStore has no folder records for unindexed files. Always show common
            // public locations; additionally list real subdirectories if Android allows it.
            for (String path : new String[]{"Download/", "Pictures/", "Pictures/Wallpapers/",
                    "DCIM/", "DCIM/Camera/"}) counts.put(path, 0);
            if (hasFullMediaPermission()) {
                addPublicSubfolders(counts, "Pictures/");
                addPublicSubfolders(counts, "Download/");
                addPublicSubfolders(counts, "DCIM/");
            }
            try (Cursor cursor = getContentResolver().query(imageCollection(),
                    new String[]{MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.MIME_TYPE},
                    null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (cursor != null) {
                    int pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH);
                    int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE);
                    int seen = 0;
                    while (cursor.moveToNext() && seen++ < 12000) {
                        if (!supportedMime(cursor.getString(mimeCol))) continue;
                        String path = cursor.getString(pathCol);
                        path = RowaSourceFolder.normalize(path);
                        if (path == null) continue;
                        counts.put(path, counts.getOrDefault(path, 0) + 1);
                    }
                }
            } catch (Exception problem) {
                android.util.Log.w("ROWA_BROWSER", "Could not enumerate media folders", problem);
            }
            counts.putIfAbsent(currentFolder, 0);
            if (hasFullMediaPermission()) {
                for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                    if (entry.getValue() == 0) {
                        int directlyReadable = Math.min(400, mediaFiles(entry.getKey()).length);
                        entry.setValue(directlyReadable);
                    }
                }
            }
            ArrayList<Folder> found = new ArrayList<>();
            for (Map.Entry<String,Integer> entry : counts.entrySet()) {
                found.add(new Folder(entry.getKey(), entry.getValue()));
            }
            found.sort((a, b) -> {
                if (a.relativePath.equals(currentFolder)) return -1;
                if (b.relativePath.equals(currentFolder)) return 1;
                return a.relativePath.compareToIgnoreCase(b.relativePath);
            });
            if (found.size() > MAX_FOLDERS) found.subList(MAX_FOLDERS, found.size()).clear();
            runOnUiThread(() -> {
                if (generation != requestGeneration || isFinishing() || isDestroyed()) return;
                folders.clear(); folders.addAll(found);
                folderAdapter.notifyDataSetChanged();
                message(hasLimitedMediaPermission()
                        ? getString(R.string.rowa_browser_partial)
                        : getString(R.string.rowa_browser_folder_count, found.size()));
                if (!found.isEmpty()) folderList.requestFocus();
            });
        });
    }

    private void loadPhotos() {
        final int generation = ++requestGeneration;
        final String folder = currentFolder;
        message(getString(R.string.rowa_browser_loading));
        decoder.execute(() -> {
            ArrayList<Photo> found = new ArrayList<>();
            HashSet<String> indexedNames = new HashSet<>();
            try {
                Uri collection = imageCollection();
                String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.MIME_TYPE};
                String selection = MediaStore.Images.Media.RELATIVE_PATH + " = ?";
                try (Cursor cursor = getContentResolver().query(collection, projection, selection,
                        new String[]{folder}, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                    if (cursor != null) {
                        int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                        int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                        int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE);
                        while (cursor.moveToNext() && found.size() < MAX_VISIBLE) {
                            if (!supportedMime(cursor.getString(mimeCol))) continue;
                            long id = cursor.getLong(idCol);
                            String name = cursor.getString(nameCol);
                            if (TextUtils.isEmpty(name)) name = "Image " + id;
                            found.add(new Photo(id, name, ContentUris.withAppendedId(collection, id)));
                            indexedNames.add(name);
                        }
                    }
                }
            } catch (Exception problem) {
                android.util.Log.w("ROWA_BROWSER", "Cannot enumerate selected folder", problem);
            }
            // Android sometimes has not indexed images copied by X-plore or ADB. If full
            // media access permits direct reads, include those files without a broad grant.
            // Keep file URIs inside our own non-exported activity and import via extras.
            for (File image : mediaFiles(folder)) {
                if (found.size() >= MAX_VISIBLE) break;
                if (indexedNames.contains(image.getName())) continue;
                long id = -1L - (image.getAbsolutePath().hashCode() & 0xffffffffL);
                found.add(new Photo(id, image.getName(), Uri.fromFile(image)));
                indexedNames.add(image.getName());
            }
            runOnUiThread(() -> {
                if (generation != requestGeneration || isFinishing() || isDestroyed()) return;
                photos.clear(); photos.addAll(found);
                photoAdapter.notifyDataSetChanged();
                if (found.isEmpty()) message(hasLimitedMediaPermission()
                        ? getString(R.string.rowa_browser_partial)
                        : getString(R.string.rowa_browser_empty_folder, folder));
                else {
                    updateStatus();
                    grid.setSelection(0);
                    grid.requestFocus();
                    schedulePreview(0);
                }
                updateButtons();
            });
        });
    }

    private void updateStatus() {
        if (photos.isEmpty()) return;
        int count = chosen.size();
        message(getString(R.string.rowa_browser_count, count, RowaVisualOptions.MAX_IMAGES, photos.size()));
        updateButtons();
    }
    private void updateButtons() {
        applyButton.setEnabled(!chosen.isEmpty());
        applyButton.setAlpha(chosen.isEmpty() ? 0.45f : 1f);
        imageOptionsButton.setEnabled(!photos.isEmpty());
        imageOptionsButton.setAlpha(photos.isEmpty() ? 0.45f : 1f);
        grid.setNextFocusDownId(imageOptionsButton.getId());
    }
    /**
     * After a short D-pad dwell, show the focused image using its saved wallpaper style.
     * Debouncing is essential: scrolling through a folder must not
     * allocate full-resolution bitmaps for every briefly focused image.
     */
    private void cancelPendingPreview() {
        ++previewGeneration;
        if (queuedPreview != null) previewHandler.removeCallbacks(queuedPreview);
        queuedPreview = null;
    }

    private void schedulePreview(int position) {
        if (position < 0 || position >= photos.size() || folderMode || settingsMode || !grid.hasFocus())
            return;
        if (previewSuppressed && previewPosition == position) return;
        previewSuppressed = false;
        if (previewOpen && previewPosition == position) return;
        previewPosition = position;
        String key = photos.get(position).uri.toString();
        RowaWallpaperStyle style = photoStyles.containsKey(key) ? photoStyles.get(key).copy()
                : RowaWallpaperStyle.forSource(this, key);
        requestPreview(photos.get(position).uri, style, PREVIEW_DWELL_MS);
    }

    /** Debounced composition: at most one pending wallpaper render for remote focus or slider edits. */
    private void requestPreview(Uri uri, RowaWallpaperStyle style, long debounceMs) {
        cancelPendingPreview();
        final int generation = previewGeneration;
        queuedPreview = () -> {
            if (generation != previewGeneration || isFinishing() || isDestroyed() || folderMode) return;
            decoder.execute(() -> {
                if (generation != previewGeneration) return;
                Bitmap bitmap = decodePreview(uri, style);
                runOnUiThread(() -> {
                    if (generation != previewGeneration || isFinishing() || isDestroyed() || folderMode) {
                        dispose(bitmap);
                        return;
                    }
                    if (bitmap == null) return;
                    Bitmap old = previewBitmap;
                    previewBitmap = bitmap;
                    fullScreenPreview.setImageBitmap(bitmap);
                    previewOpen = true;
                    disposeLater(old);
                });
            });
        };
        previewHandler.postDelayed(queuedPreview, debounceMs);
    }

    /** Preview uses the exact same single-bitmap composer as the launcher itself. */
    private Bitmap decodePreview(Uri uri, RowaWallpaperStyle style) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        return RowaWallpaperRenderer.render(this, uri, w, h, style, previewDim);
    }

    private static void dispose(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
    private void disposeLater(Bitmap bitmap) {
        if (bitmap != null) previewHandler.postDelayed(() -> dispose(bitmap), 450L);
    }
    private void hidePreview(boolean suppressCurrent) {
        cancelPendingPreview();
        if (suppressCurrent) previewSuppressed = true;
        if (fullScreenPreview != null) {
            fullScreenPreview.setImageDrawable(null);
        }
        previewOpen = false;
        Bitmap old = previewBitmap;
        previewBitmap = null;
        disposeLater(old);
    }

    private boolean hasFullMediaPermission() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasLimitedMediaPermission() {
        return Build.VERSION.SDK_INT >= 34 && !hasFullMediaPermission()
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                == PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasMediaPermission() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true;
        return Build.VERSION.SDK_INT >= 34
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                == PackageManager.PERMISSION_GRANTED;
    }
    private void askMediaPermission() {
        if (Build.VERSION.SDK_INT >= 34) requestPermissions(new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, PERMISSION_REQUEST);
        else requestPermissions(new String[]{Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != PERMISSION_REQUEST) return;
        if (hasMediaPermission()) openInitialScreen();
        else message(getString(R.string.rowa_browser_permission));
    }

    private void showImageOptions() {
        if (folderMode || settingsMode || photos.isEmpty()) return;
        int position = grid.getSelectedItemPosition();
        if (position < 0 || position >= photos.size()) position = previewPosition;
        if (position < 0 || position >= photos.size()) position = 0;
        settingsPhotoPosition = position;
        Photo photo = photos.get(position);
        setFilename(photo.name);
        String key = photo.uri.toString();
        RowaWallpaperStyle existing = photoStyles.containsKey(key) ? photoStyles.get(key).copy()
                : RowaWallpaperStyle.forSource(this, key);
        settingsMode = true;
        cancelPendingPreview();
        galleryPane.setVisibility(View.GONE);
        optionsPane.removeAllViews();
        optionsPane.setVisibility(View.VISIBLE);
        // The active filename is already shown at top-left, outside the glass panel.
        galleryBackButton = action(getString(R.string.rowa_browser_back));
        galleryBackButton.setOnClickListener(v -> returnToGallery());
        galleryBackButton.setOnKeyListener((view, code, event) -> {
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) returnToGallery();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(-1, dp(46));
        // Match the same 6dp horizontal inset as the other settings rows.
        backParams.leftMargin = dp(6);
        backParams.rightMargin = dp(6);
        backParams.bottomMargin = dp(4);
        optionsPane.addView(galleryBackButton, backParams);
        View editor = RowaWallpaperStyleDialog.embedded(this, existing, updated -> {
            photoStyles.put(key, updated);
            returnToGallery();
        }, this::returnToGallery, updated -> {
            // Preview temporary edits in the background; write them only on Save.
            requestPreview(photo.uri, updated, 260L);
        });
        optionsPane.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1f));
        // No extra bottom-left action in editor mode; use the full-width
        // Back to photos row inside the same right-side glass menu.
        imageOptionsButton.setVisibility(View.GONE);
        galleryBackButton.requestFocus();
        // This full-screen background remains visible while the dropdown contents change.
        requestPreview(photo.uri, existing, 0L);
    }

    private void returnToGallery() {
        if (!settingsMode) return;
        settingsMode = false;
        cancelPendingPreview();
        optionsPane.setVisibility(View.GONE);
        optionsPane.removeAllViews();
        galleryPane.setVisibility(View.VISIBLE);
        imageOptionsButton.setText(R.string.rowa_v156_image_options);
        imageOptionsButton.setVisibility(View.VISIBLE);
        int pos = Math.max(0, settingsPhotoPosition);
        grid.setSelection(pos);
        grid.requestFocus();
        previewPosition = -1;
        previewSuppressed = false;
        grid.post(() -> schedulePreview(grid.getSelectedItemPosition()));
    }

    private void finishWithSelection() {
        if (chosen.isEmpty()) return;
        ClipData clip = null;
        // Keep selection order exactly as the remote OK presses, including across pages.
        for (String uri : chosen) {
            Uri selected = Uri.parse(uri);
            if (!"content".equals(selected.getScheme())) continue; // Never expose file:// in ClipData
            if (clip == null) clip = ClipData.newUri(getContentResolver(), "ROWA backgrounds", selected);
            else clip.addItem(new ClipData.Item(selected));
        }
        Intent answer = new Intent();
        java.util.ArrayList<String> sources = new java.util.ArrayList<>();
        java.util.ArrayList<String> styles = new java.util.ArrayList<>();
        for (String source : chosen) {
            sources.add(source);
            RowaWallpaperStyle style = photoStyles.containsKey(source) ? photoStyles.get(source)
                    : RowaWallpaperStyle.forSource(this, source);
            // Unmodified images inherit global defaults; customized ones retain their own style.
            styles.add(photoStyles.containsKey(source) ? style.encode() :
                    RowaWallpaperStyle.savedStyleForSource(this, source));
        }
        answer.putStringArrayListExtra(RowaWallpaperStyle.EXTRA_IMPORT_STYLES, styles);
        answer.putStringArrayListExtra(RowaWallpaperStyle.EXTRA_IMPORT_URIS, sources);
        if (clip != null) {
            answer.setClipData(clip);
            answer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        setResult(RESULT_OK, answer);
        finish();
    }

    @Override public void onBackPressed() {
        if (settingsMode) { returnToGallery(); return; }
        if (folderMode && !folderOnly) showImages();
        else finish();
    }

    @Override protected void onPause() {
        ++filenameGeneration;
        filenameHandler.removeCallbacksAndMessages(null);
        filenameBanner.setSelected(false);
        filenameBanner.setEllipsize(TextUtils.TruncateAt.END);
        hidePreview(false);
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (filenameBanner != null && filenameBanner.getVisibility() == View.VISIBLE
                && filenameBanner.getText().length() > 20) {
            int expected = ++filenameGeneration;
            filenameBanner.post(() -> runFilenameMarquee(expected));
        }
    }

    private void updateVisibleThumbnail(String uri, Bitmap image) {
        if (isFinishing() || isDestroyed() || folderMode) return;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View tile = grid.getChildAt(i);
            if (!(tile instanceof FrameLayout)) continue;
            FrameLayout holder = (FrameLayout) ((FrameLayout) tile).getChildAt(0);
            ImageView thumbnail = (ImageView) holder.getChildAt(0);
            if (uri.equals(thumbnail.getTag())) thumbnail.setImageBitmap(image);
        }
    }

    private final class FolderAdapter extends BaseAdapter {
        @Override public int getCount() { return folders.size(); }
        @Override public Object getItem(int position) { return folders.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reused, ViewGroup parent) {
            TextView text = reused instanceof TextView ? (TextView) reused : label("", 15, Color.WHITE);
            Folder folder = folders.get(position);
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setText((folder.relativePath.equals(currentFolder) ? "✓  " : "   ")
                    + folder.relativePath + "    (" + folder.count + ")");
            text.setPadding(dp(20), dp(6), dp(12), dp(6));
            text.setMinHeight(dp(RowaMenuStyle.ROW_DP));
            text.setBackground(RowaMenuStyle.row(RowaDownloadsBrowserActivity.this, false));
            text.setTextColor(0xFFFFFFFF);
            return text;
        }
    }

    private Bitmap loadFileThumbnail(Uri uri) {
        File file = new File(uri.getPath() == null ? "" : uri.getPath());
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int sample = 1;
            while (options.outWidth / sample > thumbWidth * 2 || options.outHeight / sample > thumbHeight * 2)
                sample <<= 1;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError problem) { return null; }
    }

    private final class PhotoAdapter extends BaseAdapter {
        @Override public int getCount() { return photos.size(); }
        @Override public Object getItem(int position) { return photos.get(position); }
        @Override public long getItemId(int position) { return ((Photo) getItem(position)).id; }
        @Override public View getView(int position, View reused, ViewGroup parent) {
            FrameLayout tile;
            FrameLayout holder;
            ImageView image;
            TextView check;
            if (reused instanceof FrameLayout) {
                tile = (FrameLayout) reused;
                holder = (FrameLayout) tile.getChildAt(0);
                image = (ImageView) holder.getChildAt(0);
                check = (TextView) holder.getChildAt(1);
            } else {
                tile = new FrameLayout(RowaDownloadsBrowserActivity.this);
                tile.setPadding(dp(5), dp(5), dp(5), dp(5));
                tile.setBackground(rounded(0x98232832, 0x22FFFFFF, 9));
                holder = new FrameLayout(RowaDownloadsBrowserActivity.this);
                FrameLayout.LayoutParams holderParams = new FrameLayout.LayoutParams(
                        thumbWidth, thumbHeight, Gravity.CENTER);
                tile.addView(holder, holderParams);
                image = new ImageView(RowaDownloadsBrowserActivity.this);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setBackgroundColor(0xFF15191F);
                holder.addView(image, new FrameLayout.LayoutParams(-1, -1));
                check = label("✓", 16, Color.WHITE);
                check.setGravity(Gravity.CENTER);
                check.setBackground(rounded(0xD91A6E62, 0xAAFFFFFF, 14));
                FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(
                        dp(28), dp(28), Gravity.TOP | Gravity.RIGHT);
                checkParams.topMargin = dp(4);
                checkParams.rightMargin = dp(4);
                holder.addView(check, checkParams);
            }
            Photo photo = (Photo) getItem(position);
            boolean selected = chosen.contains(photo.uri.toString());
            check.setVisibility(selected ? View.VISIBLE : View.GONE);
            tile.setBackground(rounded(selected ? 0xD0355557 : 0x98232832,
                    selected ? 0xAA7AE5CB : 0x22FFFFFF, 9));
            // Filename lives only in the banner on the left; each item exposes it
            // to screen readers even without a visible label.
            tile.setContentDescription(photo.name + (selected ? ", selected" : ""));
            image.setTag(photo.uri.toString());
            Bitmap cached = thumbnails.get(photo.id);
            if (cached != null) image.setImageBitmap(cached);
            else {
                image.setImageDrawable(new ColorDrawable(0xFF1A1E25));
                if (loading.add(photo.id)) decoder.execute(() -> {
                    Bitmap bitmap = null;
                    try {
                        if ("file".equals(photo.uri.getScheme())) bitmap = loadFileThumbnail(photo.uri);
                        else if (Build.VERSION.SDK_INT >= 29) bitmap = getContentResolver().loadThumbnail(
                                photo.uri, new Size(thumbWidth, thumbHeight), null);
                        else bitmap = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(),
                                photo.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                    } catch (Exception | OutOfMemoryError ignored) { /* Placeholder until the next load. */ }
                    finally { loading.remove(photo.id); }
                    if (bitmap != null) {
                        thumbnails.put(photo.id, bitmap);
                        Bitmap ready = bitmap;
                        grid.post(() -> updateVisibleThumbnail(photo.uri.toString(), ready));
                    }
                });
            }
            return tile;
        }
    }

    @Override protected void onDestroy() {
        ++filenameGeneration;
        filenameHandler.removeCallbacksAndMessages(null);
        ++requestGeneration;
        hidePreview(false);
        decoder.shutdownNow();
        thumbnails.evictAll();
        super.onDestroy();
    }
}
