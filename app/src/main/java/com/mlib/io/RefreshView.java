package com.mlib.io;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Minimal refresh icon: thin circular arrow when idle, spinning arc while listing files,
 * progress arc + percent while reading tags.
 */
final class RefreshView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path head = new Path();
    private final float d;
    private boolean busy;
    private float progress = -1f;
    private float spin;

    RefreshView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[]{line, track, arc}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.8f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        line.setColor(IconView.ICON);
        track.setColor(0xFF37474F);
        arc.setColor(MainActivity.ACC);
        txt.setColor(MainActivity.ACC);
        txt.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
        setContentDescription("Refresh");
        setBackground(IconView.ripple());
    }

    void update(boolean busy, float progress) {
        this.busy = busy;
        this.progress = progress;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = 8.5f * d;
        rect.set(cx - r, cy - r, cx + r, cy + r);
        if (!busy) {
            float start = -50f, sweep = 280f;
            cv.drawArc(rect, start, sweep, false, line);
            // arrow head at the arc end, pointing along the (clockwise) direction of travel
            double a = Math.toRadians(start + sweep);
            float px = cx + r * (float) Math.cos(a), py = cy + r * (float) Math.sin(a);
            float tx = -(float) Math.sin(a), ty = (float) Math.cos(a); // tangent
            float nx = (float) Math.cos(a), ny = (float) Math.sin(a);  // radial
            float s = 3.8f * d;
            float tipx = px + tx * s * 0.35f, tipy = py + ty * s * 0.35f;
            head.reset();
            head.moveTo(tipx - tx * s + nx * s, tipy - ty * s + ny * s);
            head.lineTo(tipx, tipy);
            head.lineTo(tipx - tx * s - nx * s, tipy - ty * s - ny * s);
            cv.drawPath(head, line);
            return;
        }
        cv.drawOval(rect, track);
        String label;
        if (progress >= 0) {
            cv.drawArc(rect, -90, 360f * progress, false, arc);
            txt.setTextSize(8 * d);
            label = (int) (progress * 100) + "";
        } else {
            cv.drawArc(rect, spin, 100, false, arc);
            spin = (spin + 7) % 360;
            postInvalidateOnAnimation();
            label = null;
        }
        if (label != null) {
            float y = cy - (txt.descent() + txt.ascent()) / 2;
            cv.drawText(label, cx, y, txt);
        }
    }
}
