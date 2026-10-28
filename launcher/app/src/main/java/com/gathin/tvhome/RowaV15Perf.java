package com.gathin.tvhome;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

/** ROWA_V15: one passive first-frame timer per Activity creation, no scheduled work. */
final class RowaV15Perf {
    private RowaV15Perf() { }
    static void recordFirstFrame(View root) {
        final long start = SystemClock.uptimeMillis();
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (root.getViewTreeObserver().isAlive())
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                Log.i("ROWA_PERF_V15", "onCreate root-ready to first pre-draw="
                        + (SystemClock.uptimeMillis() - start) + "ms");
                return true;
            }
        });
    }
}
