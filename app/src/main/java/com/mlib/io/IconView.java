package com.mlib.io;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.view.View;

/** Minimal thin-line icon button. Same stroke weight/colour as RefreshView. */
final class IconView extends View {
    static final int ICON = 0xFFBDBDBD;
    static final int FOLDER = 0, PREV = 1, NEXT = 2, STOP = 3, BACK = 4, CLOSE = 5, DRAG = 6;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int type;
    private final float d;
    private float scale = 1f;

    IconView(Context c, String description, int type) {
        super(c);
        this.type = type;
        d = c.getResources().getDisplayMetrics().density;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.8f * d);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(ICON);
        setClickable(true);
        setContentDescription(description);
        setBackground(ripple());
    }

    IconView setScale(float s) {
        scale = s;
        invalidate();
        return this;
    }

    IconView setColor(int c) {
        p.setColor(c);
        invalidate();
        return this;
    }

    /** Pure decoration (e.g. a drag grip): no ripple, no click handling. */
    IconView decorative() {
        setClickable(false);
        setBackground(null);
        return this;
    }

    static RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new ShapeDrawable(new OvalShape()));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float u = d * scale; // 1 unit = 1dp on a 24 unit grid
        float ox = (getWidth() - 24 * u) / 2f, oy = (getHeight() - 24 * u) / 2f;
        p.setPathEffect(new CornerPathEffect(2f * u));
        Path a = path;
        a.reset();
        switch (type) {
            case FOLDER:
                a.moveTo(ox + 3 * u, oy + 6 * u);
                a.lineTo(ox + 9.5f * u, oy + 6 * u);
                a.lineTo(ox + 11.5f * u, oy + 8.5f * u);
                a.lineTo(ox + 21 * u, oy + 8.5f * u);
                a.lineTo(ox + 21 * u, oy + 19 * u);
                a.lineTo(ox + 3 * u, oy + 19 * u);
                a.close();
                break;
            case PREV:
                a.moveTo(ox + 6 * u, oy + 6 * u);
                a.lineTo(ox + 6 * u, oy + 18 * u);
                a.moveTo(ox + 18 * u, oy + 6 * u);
                a.lineTo(ox + 9 * u, oy + 12 * u);
                a.lineTo(ox + 18 * u, oy + 18 * u);
                a.close();
                break;
            case NEXT:
                a.moveTo(ox + 18 * u, oy + 6 * u);
                a.lineTo(ox + 18 * u, oy + 18 * u);
                a.moveTo(ox + 6 * u, oy + 6 * u);
                a.lineTo(ox + 15 * u, oy + 12 * u);
                a.lineTo(ox + 6 * u, oy + 18 * u);
                a.close();
                break;
            case STOP:
                a.moveTo(ox + 6.5f * u, oy + 6.5f * u);
                a.lineTo(ox + 17.5f * u, oy + 6.5f * u);
                a.lineTo(ox + 17.5f * u, oy + 17.5f * u);
                a.lineTo(ox + 6.5f * u, oy + 17.5f * u);
                a.close();
                break;
            case BACK:
                a.moveTo(ox + 15 * u, oy + 5 * u);
                a.lineTo(ox + 8 * u, oy + 12 * u);
                a.lineTo(ox + 15 * u, oy + 19 * u);
                break;
            case CLOSE:
                a.moveTo(ox + 7 * u, oy + 7 * u);
                a.lineTo(ox + 17 * u, oy + 17 * u);
                a.moveTo(ox + 17 * u, oy + 7 * u);
                a.lineTo(ox + 7 * u, oy + 17 * u);
                break;
            default: // DRAG grip
                for (int i = 0; i < 3; i++) {
                    a.moveTo(ox + 6 * u, oy + (8 + 4 * i) * u);
                    a.lineTo(ox + 18 * u, oy + (8 + 4 * i) * u);
                }
                break;
        }
        cv.drawPath(a, p);
    }
}
