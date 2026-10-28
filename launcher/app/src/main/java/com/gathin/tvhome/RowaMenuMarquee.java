package com.gathin.tvhome;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

/** ROWA_UI_V7: focus-only, one-pass marquee with a five-second pause between passes.
 * Only runs while the popup row is focused; no permanent animation or new services.
 */
final class RowaMenuMarquee {
    private RowaMenuMarquee() { }

    static void bind(View row, TextView title, TextView... secondary) {
        if (row == null || title == null) return;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable[] pending = {null};
        final int normalText = Color.WHITE;
        final int dimText = 0xDDFFFFFF;
        final int focusText = 0xFF15191F;
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setHorizontalFadingEdgeEnabled(true);
        title.setMarqueeRepeatLimit(1);

        Runnable cancel = () -> {
            if (pending[0] != null) handler.removeCallbacks(pending[0]);
            pending[0] = null;
            title.setSelected(false);
            title.setEllipsize(TextUtils.TruncateAt.END);
        };
        Runnable[] start = {null};
        start[0] = () -> {
            if (!row.hasFocus() || !row.isAttachedToWindow()) return;
            int room = title.getWidth() - title.getPaddingLeft() - title.getPaddingRight();
            float textWidth = title.getPaint().measureText(title.getText().toString());
            if (room <= 0 || textWidth <= room + 2f) return;
            title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            title.setSelected(true);
            // TextView's native, GPU-friendly marquee does one pass, then pauses ~5 seconds.
            float pxPerSecond = 32f * title.getResources().getDisplayMetrics().density;
            long passMs = 1200L + (long) (1000f * (textWidth + room) / pxPerSecond);
            Runnable stop = () -> {
                if (!row.hasFocus()) return;
                title.setSelected(false);
                title.setEllipsize(TextUtils.TruncateAt.END);
                pending[0] = start[0];
                handler.postDelayed(pending[0], 5000L);
            };
            pending[0] = stop;
            handler.postDelayed(stop, Math.min(45000L, Math.max(2600L, passMs)));
        };

        // A button may already own the launcher's glass focus/contrast treatment.
        // Chain it rather than replacing the original listener with marquee behavior.
        View.OnFocusChangeListener originalFocus = row.getOnFocusChangeListener();
        row.setOnFocusChangeListener((v, focused) -> {
            if (originalFocus != null) originalFocus.onFocusChange(v, focused);
            cancel.run();
            title.setTextColor(focused ? focusText : normalText);
            for (TextView peer : secondary) {
                if (peer != null) peer.setTextColor(focused ? focusText : dimText);
            }
            if (focused) title.post(start[0]);
        });
        row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) { cancel.run(); }
        });
        // A popup may focus its first row before binding finishes.
        if (row.hasFocus()) row.post(start[0]);
    }
}
