package com.mlib.io;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny album-art cache: 160px JPEGs on disk, ~4 MB LRU in memory, decoded lazily off the UI thread. */
final class ArtCache {
    private static File dir;
    private static LruCache<String, Bitmap> cache;
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static synchronized void init(Context c) {
        if (dir != null) return;
        dir = new File(c.getApplicationContext().getFilesDir(), "art");
        dir.mkdirs();
        cache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
            @Override
            protected int sizeOf(String k, Bitmap b) {
                return b.getByteCount();
            }
        };
    }

    static File dir() {
        return dir;
    }

    static File file(String key) {
        return new File(dir, key + ".jpg");
    }

    static void clearDisk() {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        cache.evictAll();
    }

    static void load(final ImageView iv, final String key) {
        iv.setTag(key);
        iv.setImageDrawable(null);
        if (key == null || key.isEmpty()) return;
        Bitmap hit = cache.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        EX.execute(new Runnable() {
            @Override
            public void run() {
                if (!key.equals(iv.getTag())) return; // row was recycled, skip
                File f = file(key);
                if (!f.exists()) return;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
                if (bm == null) return;
                cache.put(key, bm);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(iv.getTag())) iv.setImageBitmap(bm);
                    }
                });
            }
        });
    }
}
