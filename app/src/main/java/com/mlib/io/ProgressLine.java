package com.mlib.io;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Thin read-only progress line. */
final class ProgressLine extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float f;

    ProgressLine(Context c) {
        super(c);
        track.setColor(0xFF37474F);
        fill.setColor(MainActivity.ACC);
    }

    void set(float v) {
        v = Math.max(0f, Math.min(1f, v));
        if (v != f) {
            f = v;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight(), rad = h / 2f;
        r.set(0, 0, w, h);
        cv.drawRoundRect(r, rad, rad, track);
        if (f > 0f) {
            r.set(0, 0, Math.max(h, w * f), h);
            cv.drawRoundRect(r, rad, rad, fill);
        }
    }
}
