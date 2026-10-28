package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** One reusable, remote-friendly glass dropdown for launcher-owned dialogs.
 * System permission dialogs and the IME remain managed by Android. */
final class RowaGlassDialog {
    private RowaGlassDialog() { }

    static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    static TextView row(Activity a, CharSequence label) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(RowaMenuStyle.TEXT_SP);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        t.setMarqueeRepeatLimit(2);
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        t.setPadding(dp(a, 14), 0, dp(a, 14), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(RowaMenuStyle.row(a, false));
        t.setOnFocusChangeListener((v, focused) -> {
            t.setBackground(RowaMenuStyle.row(a, focused));
            t.setTextColor(focused ? Color.BLACK : Color.WHITE);
        });
        // The same focus-only marquee/pause used by the launcher's other dropdowns.
        RowaMenuMarquee.bind(t, t);
        return t;
    }

    static void addRow(Activity a, LinearLayout parent, CharSequence label, Runnable action) {
        TextView item = row(a, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(a, RowaMenuStyle.ROW_DP));
        lp.bottomMargin = dp(a, 5);
        parent.addView(item, lp);
        item.setOnClickListener(v -> action.run());
    }

    static TextView caption(Activity a, CharSequence label) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextColor(0xFFE4E9EF);
        t.setTextSize(15);
        t.setPadding(dp(a, 8), dp(a, 5), dp(a, 8), dp(a, 9));
        return t;
    }

    static Dialog show(Activity a, CharSequence title, View content,
                       CharSequence positive, Runnable accept,
                       CharSequence neutral, Runnable alternative) {
        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(a, 15), dp(a, 14), dp(a, 15), dp(a, 14));
        panel.setBackgroundResource(R.drawable.glass_panel);
        RowaMenuStyle.glass(panel, a);

        if (!TextUtils.isEmpty(title)) {
            TextView heading = new TextView(a);
            heading.setText(title);
            heading.setTextSize(20);
            heading.setTextColor(Color.WHITE);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.setSingleLine(true);
            heading.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            heading.setMarqueeRepeatLimit(2);
            heading.setSelected(true);
            heading.setPadding(dp(a, 9), 0, dp(a, 9), dp(a, 9));
            panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(a, 42)));
        }
        if (content != null) {
            if (content.getParent() instanceof ViewGroup)
                ((ViewGroup) content.getParent()).removeView(content);
            // The content itself may be a scroll view; avoid nesting scroll views.
            ViewGroup.LayoutParams original = content.getLayoutParams();
            int contentHeight = original != null && original.height > 0 ? original.height : -2;
            int screenHeight = a.getResources().getDisplayMetrics().heightPixels;
            int reserved = dp(a, 160 + (title == null ? 0 : 42)
                    + (positive == null ? 0 : RowaMenuStyle.ROW_DP)
                    + (neutral == null ? 0 : RowaMenuStyle.ROW_DP));
            int limit = Math.max(dp(a, 85), screenHeight - reserved);
            if (contentHeight > limit) contentHeight = limit;
            if (contentHeight < 0 && !(content instanceof ScrollView)) {
                ScrollView scrollingBody = new ScrollView(a) {
                    @Override protected void onMeasure(int widthSpec, int heightSpec) {
                        super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(limit,
                                View.MeasureSpec.AT_MOST));
                    }
                };
                scrollingBody.setFillViewport(false);
                scrollingBody.setVerticalScrollBarEnabled(false);
                scrollingBody.setFocusable(false);
                scrollingBody.addView(content);
                panel.addView(scrollingBody, new LinearLayout.LayoutParams(-1, -2));
            } else {
                // Embedded wallpaper controls are already a ScrollView; don't nest them,
                // but still bound their height so buttons remain visible on 480p TVs.
                if (content instanceof ScrollView && contentHeight < 0)
                    contentHeight = limit;
                panel.addView(content, new LinearLayout.LayoutParams(-1, contentHeight));
            }
        }
        if (positive != null) addRow(a, panel, positive, () -> {
            dialog.dismiss();
            if (accept != null) accept.run();
        });
        if (neutral != null) addRow(a, panel, neutral, () -> {
            dialog.dismiss();
            if (alternative != null) alternative.run();
        });
        addRow(a, panel, a.getString(android.R.string.cancel), dialog::dismiss);

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.50f;
            attrs.gravity = Gravity.CENTER;
            window.setAttributes(attrs);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        if (window != null) {
            int width = a.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min(dp(a, 440), width - dp(a, 32)),
                    WindowManager.LayoutParams.WRAP_CONTENT);
            // Each scrollable body is capped before being added to the panel;
            // the Save / Cancel buttons remain below it and inside the viewport.
        }
        if (content instanceof EditText) content.requestFocus();
        else if (positive != null) {
            // Keep the user's D-pad focus on the primary action when there is no list.
            View target = panel.getChildAt(panel.getChildCount() - (neutral == null ? 2 : 3));
            if (target != null) target.requestFocus();
        }
        return dialog;
    }

    static Dialog input(Activity a, CharSequence title, EditText edit, CharSequence description,
                        Runnable accept, CharSequence neutral, Runnable alternative) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        if (description != null) body.addView(caption(a, description));
        edit.setSingleLine(true);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(0xFFA9B9C9);
        edit.setTextSize(17);
        edit.setSelectAllOnFocus(true);
        edit.setPadding(dp(a, 13), dp(a, 10), dp(a, 13), dp(a, 10));
        edit.setBackground(RowaMenuStyle.row(a, false));
        body.addView(edit, new LinearLayout.LayoutParams(-1, dp(a, 52)));
        Dialog dialog = show(a, title, body, a.getString(android.R.string.ok), accept,
                neutral, alternative);
        edit.requestFocus();
        return dialog;
    }

    static Dialog confirm(Activity a, CharSequence title, CharSequence message, Runnable accept) {
        return show(a, title, caption(a, message), a.getString(R.string.enable), accept,
                null, null);
    }

    static Dialog choices(Activity a, CharSequence title, String[] labels, int selected,
                          IntConsumer pick, CharSequence neutral, Runnable other) {
        LinearLayout entries = new LinearLayout(a);
        entries.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            addRow(a, entries, (selected == i ? "✓  " : "    ") + labels[i], () -> { });
        }
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(entries);
        int h = a.getResources().getDisplayMetrics().heightPixels;
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1,
                Math.min(dp(a, 51) * Math.max(1, labels.length),
                        Math.max(dp(a, 140), h - dp(a, 180)))));
        // No generic OK: a choice is applied on click and dismisses itself.
        final Dialog[] dialog = new Dialog[1];
        for (int i = 0; i < entries.getChildCount(); i++) {
            View child = entries.getChildAt(i);
            final int which = i;
            child.setOnClickListener(v -> {
                dialog[0].dismiss();
                pick.accept(which);
            });
        }
        dialog[0] = show(a, title, scroll, null, null, neutral, other);
        if (entries.getChildCount() > 0) {
            int at = Math.max(0, Math.min(selected, entries.getChildCount() - 1));
            entries.getChildAt(at).requestFocus();
        }
        return dialog[0];
    }
}
