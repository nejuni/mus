package com.mlib.io;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.ListView;

/**
 * ListView with drag-to-reorder. Touching the right-hand strip (where the row's grip icon is)
 * starts a drag; the row under the finger swaps with the dragged one as the finger moves.
 */
final class DragList extends ListView {
    interface Cb {
        void onMove(int from, int to);

        /** pos = item being dragged, or -1 when the drag ended. */
        void onDrag(int pos);
    }

    private Cb cb;
    private int pos = -1;
    private float lastY;
    private final int zone, edge, speed;

    private final Runnable scroller = new Runnable() {
        @Override
        public void run() {
            if (pos < 0) return;
            if (lastY < edge) scrollListBy(-speed);
            else if (lastY > getHeight() - edge) scrollListBy(speed);
            step();
            postOnAnimation(this);
        }
    };

    DragList(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        zone = Math.round(44 * d);
        edge = Math.round(64 * d);
        speed = Math.round(10 * d);
    }

    void setCb(Cb c) {
        cb = c;
    }

    private void step() {
        if (cb == null || pos < 0) return;
        int y = (int) Math.max(0, Math.min(lastY, getHeight() - 1));
        int t = pointToPosition(getWidth() / 2, y);
        if (t != INVALID_POSITION && t != pos) {
            cb.onMove(pos, t);
            pos = t;
            cb.onDrag(pos);
        }
    }

    private void end() {
        removeCallbacks(scroller);
        pos = -1;
        if (cb != null) cb.onDrag(-1);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && cb != null && ev.getX() > getWidth() - zone) {
            int p = pointToPosition((int) ev.getX(), (int) ev.getY());
            if (p != INVALID_POSITION) {
                pos = p;
                lastY = ev.getY();
                cb.onDrag(pos);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }
        return pos >= 0 || super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (pos < 0) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastY = ev.getY();
                postOnAnimation(scroller);
                break;
            case MotionEvent.ACTION_MOVE:
                lastY = ev.getY();
                step();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                end();
                break;
            default:
                break;
        }
        return true;
    }
}
