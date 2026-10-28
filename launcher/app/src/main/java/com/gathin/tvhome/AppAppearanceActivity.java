package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV-remote editor for launcher-only app labels and artwork. Uses the same glass-panel style
 * as the launcher menus. Original APKs, app identities and launch intents are never changed.
 */
public final class AppAppearanceActivity extends Activity {
    private static final int IMAGE_PERMISSION = 852;
    private static final int MAX_IMAGES = 160;
    private static final int THUMB_KIB = 3 * 1024;

    private static final class Item {
        final String pkg, label;
        Item(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }
    private static final class Photo {
        final long id;
        final String name;
        final Uri uri;
        Photo(long id, String name, Uri uri) { this.id = id; this.name = name; this.uri = uri; }
    }

    private final List<Item> apps = new ArrayList<>();
    private final List<Photo> photos = new ArrayList<>();
    private final ExecutorService decoder = Executors.newFixedThreadPool(2);
    private final LruCache<Long, Bitmap> thumbnails = new LruCache<Long, Bitmap>(THUMB_KIB) {
        @Override protected int sizeOf(Long key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };
    private LinearLayout editorPane;
    private LinearLayout editorControls;
    private ScrollView editorScroll;
    private ListView appList;
    private TextView editorTitle;
    private ImageView previewIcon;
    private Button nameButton, imageButton, appIconButton, sourcePackageButton, resetButton, backButton;
    private ListView choiceList;
    private TextView choiceTitle;
    private String editingPackage = "";
    private boolean choosingImage;
    private List<Item> sourceSelection = new ArrayList<>();
    private int listPosition;
    private BaseAdapter appAdapter;
    private int selectionGeneration;

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(boolean focused) {
        return RowaMenuStyle.row(this, focused);
    }

    /** List items keep white text, so their selection uses a dark focus fill and white rim. */
    private GradientDrawable listSelection() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(RowaMenuStyle.ROW_RADIUS_DP));
        bg.setColor(0xFF35383E);
        bg.setStroke(dp(2), Color.WHITE);
        return bg;
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private Button button(int label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        b.setMarqueeRepeatLimit(1);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, RowaMenuStyle.TEXT_SP);
        b.setBackground(rounded(false));
        b.setTextColor(Color.WHITE);
        b.setOnFocusChangeListener((v, focused) -> {
            b.setBackground(rounded(focused));
            b.setTextColor(focused ? Color.BLACK : Color.WHITE);
            // Remote focus must never leave the bottom of a settings row clipped.
            if (focused && editorScroll != null && editorScroll.getVisibility() == View.VISIBLE) {
                editorScroll.post(() -> {
                    if (b.hasFocus() && b.getWidth() > 0) {
                        Rect row = new Rect(0, 0, b.getWidth(), b.getHeight() + dp(7));
                        editorScroll.requestChildRectangleOnScreen(b, row, false);
                    }
                });
            }
        });
        b.setFocusable(true);
        RowaMenuMarquee.bind(b, b);
        return b;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadApps();
        LinearLayout screen = vertical();
        screen.setBackgroundColor(0xFF101419);
        screen.setPadding(dp(24), dp(18), dp(24), dp(18));
        setContentView(screen);
        TextView title = text(getString(R.string.app_appearance_title), 20, Color.WHITE);
        screen.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        screen.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout left = vertical();
        left.setBackgroundResource(R.drawable.glass_panel);
        RowaMenuStyle.glass(left, this);
        left.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -1, 0.42f);
        llp.setMarginEnd(dp(12));
        columns.addView(left, llp);
        left.addView(text(getString(R.string.app_appearance_select), 17, 0xFFCCD6E1),
                new LinearLayout.LayoutParams(-1, dp(35)));
        appList = new ListView(this);
        appList.setDividerHeight(0);
        appList.setSelector(listSelection());
        appList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        appAdapter = new AppListAdapter(apps, false);
        appList.setAdapter(appAdapter);
        left.addView(appList, new LinearLayout.LayoutParams(-1, 0, 1f));
        appList.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos < 0 || pos >= apps.size()) return;
            listPosition = pos;
            editingPackage = apps.get(pos).pkg;
            showEditor();
        });

        editorPane = vertical();
        editorPane.setBackgroundResource(R.drawable.glass_panel);
        RowaMenuStyle.glass(editorPane, this);
        editorPane.setPadding(dp(19), dp(13), dp(19), dp(13));
        columns.addView(editorPane, new LinearLayout.LayoutParams(0, -1, 0.58f));

        // Only the editor controls scroll. Source-app and photo choice lists retain
        // their own native ListView scrolling, so D-pad navigation remains predictable.
        editorScroll = new ScrollView(this);
        editorScroll.setFillViewport(false);
        editorScroll.setVerticalScrollBarEnabled(true);
        editorScroll.setScrollbarFadingEnabled(true);
        editorScroll.setSmoothScrollingEnabled(true);
        editorScroll.setFocusable(false);
        editorControls = vertical();
        editorControls.setPadding(0, 0, dp(5), 0); // Clearance for the scrollbar.
        editorScroll.addView(editorControls, new android.widget.FrameLayout.LayoutParams(-1, -2));
        editorPane.addView(editorScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        editorTitle = text("", 17, Color.WHITE);
        editorTitle.setSingleLine(true);
        editorTitle.setEllipsize(TextUtils.TruncateAt.END);
        editorControls.addView(editorTitle, new LinearLayout.LayoutParams(-1, dp(38)));
        previewIcon = new ImageView(this);
        // Match the real 16:9 application tile rather than previewing a square icon.
        previewIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewIcon.setBackgroundResource(R.drawable.tile_bg);
        previewIcon.setClipToOutline(true);
        LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(dp(156), dp(88));
        imageLp.gravity = Gravity.CENTER_HORIZONTAL;
        imageLp.topMargin = dp(2);
        imageLp.bottomMargin = dp(9);
        editorControls.addView(previewIcon, imageLp);
        nameButton = button(R.string.app_appearance_name);
        imageButton = button(R.string.app_appearance_from_image);
        appIconButton = button(R.string.app_appearance_from_app);
        sourcePackageButton = button(R.string.app_appearance_source_package);
        resetButton = button(R.string.app_appearance_reset);
        backButton = button(R.string.app_appearance_back);
        // Explicit IDs and vertical navigation keep all six actions reachable via
        // the TV remote, even when the panel is shorter than its contents.
        appList.setId(View.generateViewId());
        Button[] actions = {nameButton, imageButton, appIconButton, sourcePackageButton, resetButton, backButton};
        for (int i = 0; i < actions.length; i++) {
            Button b = actions[i];
            b.setId(View.generateViewId());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(RowaMenuStyle.ROW_DP));
            lp.bottomMargin = dp(6);
            editorControls.addView(b, lp);
            b.setNextFocusLeftId(appList.getId());
            if (i > 0) b.setNextFocusUpId(actions[i - 1].getId());
        }
        for (int i = 0; i + 1 < actions.length; i++) {
            actions[i].setNextFocusDownId(actions[i + 1].getId());
        }
        appList.setNextFocusRightId(nameButton.getId());
        nameButton.setOnClickListener(v -> rename());
        imageButton.setOnClickListener(v -> listPictures());
        appIconButton.setOnClickListener(v -> listSourceApps());
        sourcePackageButton.setOnClickListener(v -> askSourcePackage());
        resetButton.setOnClickListener(v -> reset());
        backButton.setOnClickListener(v -> focusAppList());

        choiceTitle = text("", 19, Color.WHITE);
        choiceTitle.setVisibility(View.GONE);
        choiceList = new ListView(this);
        choiceList.setId(View.generateViewId());
        choiceList.setDividerHeight(0);
        choiceList.setSelector(listSelection());
        choiceList.setVisibility(View.GONE);
        editorPane.addView(choiceTitle, new LinearLayout.LayoutParams(-1, dp(38)));
        editorPane.addView(choiceList, new LinearLayout.LayoutParams(-1, 0, 1f));
        choiceList.setOnItemClickListener((parent, view, position, id) -> selectOption(position));
        if (!apps.isEmpty()) {
            editingPackage = apps.get(0).pkg;
            updateEditor();
        } else editorTitle.setText(R.string.app_appearance_empty);
        appList.requestFocus();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> seen = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent request = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(request, 0)) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                apps.add(new Item(pkg, String.valueOf(info.loadLabel(pm))));
            }
        }
        apps.sort(Comparator.comparing(item -> item.label.toLowerCase(java.util.Locale.ROOT)));
    }

    private Drawable iconFor(String pkg) {
        AppAppearance.Artwork custom = AppAppearance.artwork(this, pkg);
        return custom == null ? originalIconFor(pkg) : custom.drawable;
    }

    private Drawable originalIconFor(String pkg) {
        try { return getPackageManager().getApplicationIcon(pkg); }
        catch (PackageManager.NameNotFoundException ignored) { return getDrawable(R.mipmap.ic_launcher); }
    }

    private void updateEditor() {
        if (editingPackage.isEmpty()) return;
        String original = editingPackage;
        for (Item item : apps) if (item.pkg.equals(editingPackage)) { original = item.label; break; }
        editorTitle.setText(AppAppearance.label(this, editingPackage, original));
        AppAppearance.Artwork override = AppAppearance.artwork(this, editingPackage);
        // CENTER_CROP is the exact banner mode used on Home for custom artwork;
        // a default square icon is still shown in full rather than cropped.
        previewIcon.setScaleType(override != null ? ImageView.ScaleType.CENTER_CROP
                : ImageView.ScaleType.FIT_CENTER);
        previewIcon.setImageDrawable(override != null ? override.drawable : originalIconFor(editingPackage));
        resetButton.setEnabled(AppAppearance.hasOverride(this, editingPackage));
        resetButton.setAlpha(resetButton.isEnabled() ? 1f : 0.5f);
        // Skip the disabled Reset action instead of trapping D-pad navigation.
        sourcePackageButton.setNextFocusDownId(resetButton.isEnabled() ? resetButton.getId() : backButton.getId());
        backButton.setNextFocusUpId(resetButton.isEnabled() ? resetButton.getId() : sourcePackageButton.getId());
        appAdapter.notifyDataSetChanged();
    }

    private void showEditor() {
        editorPane.setVisibility(View.VISIBLE);
        choiceList.setVisibility(View.GONE);
        choiceTitle.setVisibility(View.GONE);
        editorScroll.setVisibility(View.VISIBLE);
        updateEditor();
        editorScroll.scrollTo(0, 0);
        nameButton.requestFocus();
    }

    private void rename() {
        if (editingPackage.isEmpty()) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(48)});
        input.setText(AppAppearance.name(this, editingPackage));
        input.setHint(R.string.app_appearance_default);
        RowaGlassDialog.input(this, getString(R.string.app_appearance_name), input,
                null, () -> {
                    AppAppearance.setName(this, editingPackage, input.getText().toString());
                    updateEditor();
                }, null, null);
    }

    private void askSourcePackage() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.app_appearance_package_hint);
        RowaGlassDialog.input(this, getString(R.string.app_appearance_source_package), input,
                null, () -> chooseSource(input.getText().toString().trim()), null, null);
    }

    private void chooseSource(String pkg) {
        try {
            int flags = Build.VERSION.SDK_INT >= 24 ? PackageManager.MATCH_DISABLED_COMPONENTS : 0;
            ApplicationInfo application = getPackageManager().getApplicationInfo(pkg, flags);
            if (application == null) throw new PackageManager.NameNotFoundException();
            AppAppearance.setAppIcon(this, editingPackage, pkg);
            showEditor();
            toast(R.string.app_appearance_saved);
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException ex) {
            toast(R.string.app_appearance_invalid);
        }
    }

    private void reset() {
        if (editingPackage.isEmpty()) return;
        RowaGlassDialog.show(this, getString(R.string.app_appearance_reset),
                RowaGlassDialog.caption(this, getString(R.string.app_appearance_reset_confirm)),
                getString(android.R.string.ok), () -> {
                    AppAppearance.reset(this, editingPackage);
                    updateEditor();
                }, null, null);
    }

    private void listSourceApps() {
        choosingImage = false;
        List<Item> sources = new ArrayList<>(apps);
        // A system app that the owner disabled remains an icon source without being
        // added to the editable launcher-app list or re-enabled on the TV.
        String originalYouTube = "com.google.android.youtube.tv";
        boolean listed = false;
        for (Item item : sources) if (item.pkg.equals(originalYouTube)) { listed = true; break; }
        if (!listed) {
            try {
                ApplicationInfo info = getPackageManager().getApplicationInfo(originalYouTube,
                        PackageManager.MATCH_DISABLED_COMPONENTS);
                sources.add(new Item(originalYouTube,
                        String.valueOf(info.loadLabel(getPackageManager()))));
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        sources.sort(Comparator.comparing(item -> item.label.toLowerCase(java.util.Locale.ROOT)));
        showOptions(R.string.app_appearance_from_app, new AppListAdapter(sources, true));
        sourceSelection = sources;
    }

    private boolean hasImagePermission() {
        if (Build.VERSION.SDK_INT >= 33)
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                    (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED);
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void listPictures() {
        if (!hasImagePermission()) {
            if (Build.VERSION.SDK_INT >= 34)
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, IMAGE_PERMISSION);
            else if (Build.VERSION.SDK_INT >= 33)
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, IMAGE_PERMISSION);
            else if (Build.VERSION.SDK_INT >= 23)
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, IMAGE_PERMISSION);
            return;
        }
        photos.clear();
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME};
        try (Cursor cursor = getContentResolver().query(base, cols, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                while (cursor.moveToNext() && photos.size() < MAX_IMAGES) {
                    long id = cursor.getLong(idIndex);
                    photos.add(new Photo(id, cursor.getString(nameIndex), ContentUris.withAppendedId(base, id)));
                }
            }
        } catch (SecurityException ignored) { }
        if (photos.isEmpty()) {
            Toast.makeText(this, R.string.app_appearance_no_images, Toast.LENGTH_LONG).show();
            return;
        }
        choosingImage = true;
        showOptions(R.string.app_appearance_images, new PhotoListAdapter());
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == IMAGE_PERMISSION) {
            if (hasImagePermission()) listPictures();
            else toast(R.string.app_appearance_no_images);
        }
    }

    private void showOptions(int title, BaseAdapter adapter) {
        ++selectionGeneration;
        editorScroll.setVisibility(View.GONE);
        choiceTitle.setText(title);
        choiceTitle.setVisibility(View.VISIBLE);
        choiceList.setAdapter(adapter);
        choiceList.setVisibility(View.VISIBLE);
        choiceList.requestFocus();
    }

    private void selectOption(int position) {
        if (choosingImage) {
            if (position < 0 || position >= photos.size()) return;
            Photo selected = photos.get(position);
            if (AppAppearance.setImage(this, editingPackage, selected.uri)) {
                showEditor();
                toast(R.string.app_appearance_saved);
            } else toast(R.string.app_appearance_failed);
        } else if (position >= 0 && position < sourceSelection.size()) {
            chooseSource(sourceSelection.get(position).pkg);
        }
    }

    private void focusAppList() {
        appList.requestFocus();
        appList.setSelection(listPosition);
    }

    @Override public void onBackPressed() {
        if (choiceList.getVisibility() == View.VISIBLE) showEditor();
        else if (nameButton.hasFocus() || imageButton.hasFocus() || appIconButton.hasFocus()
                || sourcePackageButton.hasFocus() || resetButton.hasFocus() || backButton.hasFocus()) focusAppList();
        else super.onBackPressed();
    }

    private void toast(int id) { Toast.makeText(this, id, Toast.LENGTH_SHORT).show(); }

    private class AppListAdapter extends BaseAdapter {
        private final List<Item> source;
        private final boolean sourceIcons;
        AppListAdapter(List<Item> source, boolean sourceIcons) {
            this.source = source;
            this.sourceIcons = sourceIcons;
        }
        @Override public int getCount() { return source.size(); }
        @Override public Object getItem(int position) { return source.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView image;
            TextView title;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                image = (ImageView) row.getChildAt(0);
                title = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(AppAppearanceActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(4), dp(8), dp(4));
                image = new ImageView(AppAppearanceActivity.this);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                row.addView(image, new LinearLayout.LayoutParams(dp(39), dp(39)));
                title = text("", 16, Color.WHITE);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
                lp.leftMargin = dp(12);
                row.addView(title, lp);
            }
            Item entry = source.get(position);
            image.setImageDrawable(sourceIcons ? originalIconFor(entry.pkg) : iconFor(entry.pkg));
            title.setText(sourceIcons ? entry.label : AppAppearance.label(
                    AppAppearanceActivity.this, entry.pkg, entry.label));
            return row;
        }
    }

    private Bitmap readThumb(Photo photo) {
        try {
            if (Build.VERSION.SDK_INT >= 29)
                return getContentResolver().loadThumbnail(photo.uri, new Size(dp(85), dp(60)), null);
            try (InputStream input = getContentResolver().openInputStream(photo.uri)) {
                if (input == null) return null;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 8;
                return BitmapFactory.decodeStream(input, null, opts);
            }
        } catch (Exception | OutOfMemoryError ex) { return null; }
    }

    private class PhotoListAdapter extends BaseAdapter {
        @Override public int getCount() { return photos.size(); }
        @Override public Object getItem(int position) { return photos.get(position); }
        @Override public long getItemId(int position) { return photos.get(position).id; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView title;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                icon = (ImageView) row.getChildAt(0);
                title = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(AppAppearanceActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(6), dp(4), dp(6), dp(4));
                icon = new ImageView(AppAppearanceActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setBackgroundColor(0x55202128);
                row.addView(icon, new LinearLayout.LayoutParams(dp(87), dp(60)));
                title = text("", 15, Color.WHITE);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(66), 1);
                lp.leftMargin = dp(10);
                row.addView(title, lp);
            }
            Photo photo = photos.get(position);
            title.setText(photo.name);
            icon.setTag(photo.id);
            Bitmap cached = thumbnails.get(photo.id);
            icon.setImageBitmap(cached);
            if (cached == null) {
                final int generation = selectionGeneration;
                decoder.execute(() -> {
                    Bitmap bmp = readThumb(photo);
                    if (bmp != null) thumbnails.put(photo.id, bmp);
                    icon.post(() -> {
                        if (generation == selectionGeneration && photo.id == (long) icon.getTag() && bmp != null)
                            icon.setImageBitmap(bmp);
                    });
                });
            }
            return row;
        }
    }

    @Override protected void onDestroy() {
        ++selectionGeneration;
        decoder.shutdownNow();
        super.onDestroy();
    }
}
