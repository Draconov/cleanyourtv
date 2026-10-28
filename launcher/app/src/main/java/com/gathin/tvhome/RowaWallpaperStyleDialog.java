package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.Consumer;

/** Remote-friendly editor for per-photo settings and global defaults. No new launcher overlays. */
final class RowaWallpaperStyleDialog {
    private RowaWallpaperStyleDialog() { }
    private static int dp(Activity c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    private static TextView text(Activity c, String label, int size) {
        TextView view = new TextView(c);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(c, 8), dp(c, 5), dp(c, 8), dp(c, 5));
        return view;
    }
    private static GradientDrawable rowBackground(Activity activity, boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(focused ? 0xFFF1F3F6 : 0xD6262B34);
        shape.setCornerRadius(dp(activity, 9));
        shape.setStroke(dp(activity, 1), focused ? 0xFFFFFFFF : 0x35FFFFFF);
        return shape;
    }
    private static Button button(Activity c, LinearLayout parent, String label, Runnable click) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setBackground(rowBackground(c, false));
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        b.setOnFocusChangeListener((v, focused) -> {
            b.setTextColor(focused ? Color.BLACK : Color.WHITE);
            b.setBackground(rowBackground(c, focused));
        });
        b.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, 46));
        lp.bottomMargin = dp(c, 4);
        parent.addView(b, lp);
        return b;
    }
    private static SeekBar slider(Activity c, LinearLayout layout, String label, int current,
                                  int max, Consumer<Integer> action) {
        TextView caption = text(c, label + ": " + current, 13);
        layout.addView(caption);
        SeekBar slider = new SeekBar(c);
        slider.setMax(max);
        slider.setProgress(current);
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFE9EEF3));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFF1F4F6));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                caption.setText(label + ": " + value);
                action.accept(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        layout.addView(slider, new LinearLayout.LayoutParams(-1, dp(c, 40)));
        return slider;
    }
    /** Same per-image controls embedded in the browser's floating glass panel. */
    static View embedded(Activity activity, RowaWallpaperStyle initial,
                         Consumer<RowaWallpaperStyle> onSave, Runnable onCancel,
                         Consumer<RowaWallpaperStyle> onPreview) {
        RowaWallpaperStyle value = initial.copy();
        LinearLayout host = new LinearLayout(activity);
        host.setOrientation(LinearLayout.VERTICAL);
        Runnable edited = () -> onPreview.accept(value.copy());
        View fields = makeControls(activity, value, edited);
        host.addView(fields, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = button(activity, buttons, activity.getString(android.R.string.cancel), onCancel);
        Button save = button(activity, buttons, activity.getString(R.string.rowa_v156_save),
                () -> onSave.accept(value.copy()));
        // Each occupies half the available space, beneath the scrollable controls.
        cancel.setLayoutParams(new LinearLayout.LayoutParams(0, dp(activity, 46), 1f));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(activity, 46), 1f);
        saveParams.leftMargin = dp(activity, 7);
        save.setLayoutParams(saveParams);
        host.addView(buttons, new LinearLayout.LayoutParams(-1, dp(activity, 48)));
        fields.setFocusableInTouchMode(false);
        host.setTag(cancel);
        return host;
    }

    /** Only display controls that affect the chosen mode and backdrop. Hidden
     * groups retain their values, so switching modes never silently resets a photo. */
    private static ScrollView makeControls(Activity activity, RowaWallpaperStyle v, Runnable edited) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 6), dp(activity, 6), dp(activity, 6), dp(activity, 6));
        scroll.addView(body);

        final String[] modes = {activity.getString(R.string.rowa_v156_mode_crop),
                activity.getString(R.string.rowa_v156_mode_fit),
                activity.getString(R.string.rowa_v156_mode_tile)};
        final String[] backdrops = {activity.getString(R.string.rowa_v156_back_blur),
                activity.getString(R.string.rowa_v156_back_solid),
                activity.getString(R.string.rowa_v156_back_auto)};
        final Runnable[] refreshVisibility = new Runnable[1];

        Button mode = button(activity, body,
                activity.getString(R.string.rowa_v156_display, modes[v.mode]), () -> {});
        java.util.function.IntConsumer advanceMode = direction -> {
            v.mode = (v.mode + direction + modes.length) % modes.length;
            mode.setText(activity.getString(R.string.rowa_v156_display, modes[v.mode]));
            refreshVisibility[0].run();
            edited.run();
        };
        mode.setOnClickListener(view -> advanceMode.accept(1));
        mode.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled())
                advanceMode.accept(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        });

        // Only shown for FIT. Crop does not have a backdrop; Tile repeats the image.
        Button backdrop = button(activity, body,
                activity.getString(R.string.rowa_v156_backdrop, backdrops[v.backdrop]), () -> {});
        java.util.function.IntConsumer advanceBackdrop = direction -> {
            v.backdrop = (v.backdrop + direction + backdrops.length) % backdrops.length;
            backdrop.setText(activity.getString(R.string.rowa_v156_backdrop, backdrops[v.backdrop]));
            refreshVisibility[0].run();
            edited.run();
        };
        backdrop.setOnClickListener(view -> advanceBackdrop.accept(1));
        backdrop.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled())
                advanceBackdrop.accept(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        });

        TextView fitHelp = text(activity, activity.getString(R.string.rowa_v156_fit_help), 12);
        body.addView(fitHelp);

        LinearLayout blurControls = new LinearLayout(activity);
        blurControls.setOrientation(LinearLayout.VERTICAL);
        body.addView(blurControls);
        slider(activity, blurControls, activity.getString(R.string.rowa_v156_blur_strength),
                v.blur, 30, n -> { v.blur = n; edited.run(); });
        slider(activity, blurControls, activity.getString(R.string.rowa_v156_back_darkness),
                v.backdropDim, 90, n -> { v.backdropDim = n; edited.run(); });

        final int[] tileScales = {25, 50, 75, 100, 125, 150, 200, 300, 400};
        Button tile = button(activity, body,
                activity.getString(R.string.rowa_v156_tile_size, v.tilePercent), () -> {});
        java.util.function.IntConsumer advanceTile = direction -> {
            int at = 3;
            for (int i = 0; i < tileScales.length; i++)
                if (tileScales[i] == v.tilePercent) { at = i; break; }
            v.tilePercent = tileScales[(at + direction + tileScales.length) % tileScales.length];
            tile.setText(activity.getString(R.string.rowa_v156_tile_size, v.tilePercent));
            edited.run();
        };
        tile.setOnClickListener(view -> advanceTile.accept(1));
        tile.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled())
                advanceTile.accept(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        });

        // Always available: this alters the image itself in every rendering mode.
        final int[] dims = {-1, 0, 10, 20, 30, 40, 60, 80};
        Button dim = button(activity, body, activity.getString(R.string.rowa_v156_img_darkness,
                v.imageDim == -1 ? activity.getString(R.string.rowa_v156_theme_default)
                        : v.imageDim + "%"), () -> {});
        java.util.function.IntConsumer advanceDim = direction -> {
            int at = 0;
            for (int i = 0; i < dims.length; i++) if (dims[i] == v.imageDim) { at = i; break; }
            v.imageDim = dims[(at + direction + dims.length) % dims.length];
            dim.setText(activity.getString(R.string.rowa_v156_img_darkness,
                    v.imageDim == -1 ? activity.getString(R.string.rowa_v156_theme_default)
                            : v.imageDim + "%"));
            edited.run();
        };
        dim.setOnClickListener(view -> advanceDim.accept(1));
        dim.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled())
                advanceDim.accept(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        });

        LinearLayout colorControls = new LinearLayout(activity);
        colorControls.setOrientation(LinearLayout.VERTICAL);
        body.addView(colorControls);
        colorControls.addView(text(activity, activity.getString(R.string.rowa_v156_rgb_picker), 14));
        View swatch = new View(activity);
        GradientDrawable swatchBg = new GradientDrawable();
        swatchBg.setColor(v.solidColor);
        swatchBg.setCornerRadius(dp(activity, 6));
        swatchBg.setStroke(dp(activity, 1), Color.WHITE);
        swatch.setBackground(swatchBg);
        colorControls.addView(swatch, new LinearLayout.LayoutParams(-1, dp(activity, 28)));
        slider(activity, colorControls, activity.getString(R.string.rowa_v156_red),
                Color.red(v.solidColor), 255, n -> {
                    v.solidColor = Color.rgb(n, Color.green(v.solidColor), Color.blue(v.solidColor));
                    swatchBg.setColor(v.solidColor); swatch.invalidate(); edited.run();
                });
        slider(activity, colorControls, activity.getString(R.string.rowa_v156_green),
                Color.green(v.solidColor), 255, n -> {
                    v.solidColor = Color.rgb(Color.red(v.solidColor), n, Color.blue(v.solidColor));
                    swatchBg.setColor(v.solidColor); swatch.invalidate(); edited.run();
                });
        slider(activity, colorControls, activity.getString(R.string.rowa_v156_blue),
                Color.blue(v.solidColor), 255, n -> {
                    v.solidColor = Color.rgb(Color.red(v.solidColor), Color.green(v.solidColor), n);
                    swatchBg.setColor(v.solidColor); swatch.invalidate(); edited.run();
                });

        refreshVisibility[0] = () -> {
            boolean fit = v.mode == RowaWallpaperStyle.FIT;
            backdrop.setVisibility(fit ? View.VISIBLE : View.GONE);
            fitHelp.setVisibility(fit ? View.VISIBLE : View.GONE);
            blurControls.setVisibility(fit && v.backdrop == RowaWallpaperStyle.BLURRED
                    ? View.VISIBLE : View.GONE);
            colorControls.setVisibility(fit && v.backdrop == RowaWallpaperStyle.SOLID
                    ? View.VISIBLE : View.GONE);
            tile.setVisibility(v.mode == RowaWallpaperStyle.TILE ? View.VISIBLE : View.GONE);
        };
        refreshVisibility[0].run();
        return scroll;
    }

    static void show(Activity activity, String title, RowaWallpaperStyle initial,
                     Consumer<RowaWallpaperStyle> onSave) {
        RowaWallpaperStyle v = initial.copy();
        ScrollView scroll = makeControls(activity, v, () -> {});
        // Keep the same reusable glass-panel as all launcher-owned settings.
        // Constrain scrolling so Save/Cancel remain reachable on compact TVs.
        int h = activity.getResources().getDisplayMetrics().heightPixels;
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1,
                Math.max(dp(activity, 155), h - dp(activity, 195))));
        RowaGlassDialog.show(activity, title, scroll,
                activity.getString(R.string.rowa_v156_save), () -> onSave.accept(v.copy()),
                null, null);
        scroll.requestFocus(View.FOCUS_DOWN);
    }
}
