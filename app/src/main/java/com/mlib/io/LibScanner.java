package com.mlib.io;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 1: walk the picked folder tree recursively using raw DocumentsContract queries (much faster than
 * DocumentFile) and store file names right away, so lists appear in seconds.
 * Phase 2: read tags + embedded cover with a few worker threads, only for new/changed files.
 */
final class LibScanner implements Runnable {
    static final String UNKNOWN = "Unknown Artist";
    private static final int ART = 160;
    private static final int WORKERS = 3;
    private static final Set<String> AUDIO = new HashSet<>(Arrays.asList(
            "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "wma", "ape", "wv", "aif", "aiff", "mka", "amr", "mid"));
    private static final Set<String> NOT_AUDIO = new HashSet<>(Arrays.asList(
            "m3u", "m3u8", "pls", "cue", "log", "txt", "nfo", "sfv", "accurip"));

    static volatile LibScanner cur;

    private final Context ctx;
    private final Uri tree;
    private final SQLiteDatabase db;
    private final boolean wipe;
    private final LibScanner prev;
    private final ConcurrentHashMap<String, AtomicInteger> tried = new ConcurrentHashMap<>();
    private Thread th;

    volatile boolean cancel;
    volatile boolean finished;
    volatile int phase = 1;
    volatile int found;
    volatile int total;
    final AtomicInteger done = new AtomicInteger();

    private LibScanner(Context c, Uri tree, SQLiteDatabase db, boolean wipe, LibScanner prev) {
        this.ctx = c;
        this.tree = tree;
        this.db = db;
        this.wipe = wipe;
        this.prev = prev;
    }

    static boolean running() {
        LibScanner s = cur;
        return s != null && !s.finished;
    }

    static synchronized void start(Context c, Uri tree, SQLiteDatabase db, boolean wipe) {
        if (running() && !wipe) return;
        LibScanner p = cur;
        if (p != null) p.cancel = true;
        LibScanner s = new LibScanner(c.getApplicationContext(), tree, db, wipe, p);
        cur = s;
        s.th = new Thread(s, "scan");
        s.th.start();
    }

    @Override
    public void run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            if (prev != null && prev.th != null) {
                try {
                    prev.th.join();
                } catch (InterruptedException ignored) {
                }
            }
            if (wipe) {
                db.delete("songs", null, null);
                ArtCache.clearDisk();
            }
            db.execSQL("UPDATE songs SET seen=0");
            walk();
            if (cancel) return;
            db.execSQL("DELETE FROM songs WHERE seen=0");
            readTags();
        } catch (Throwable ignored) {
        } finally {
            finished = true;
        }
    }

    // ---------------------------------------------------------------- phase 1

    private static final class F {
        String id, name, ext;
        long size, mtime;
    }

    private void walk() {
        ContentResolver cr = ctx.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        ArrayDeque<String[]> stack = new ArrayDeque<>();
        stack.push(new String[]{rootId, leaf(rootId)});
        SQLiteStatement upd = db.compileStatement(
                "UPDATE songs SET seen=1,cover=?,folder=? WHERE doc=? AND size=? AND mtime=?");
        SQLiteStatement ins = db.compileStatement(
                "INSERT OR REPLACE INTO songs(doc,folder,cover,size,mtime,ext,meta,seen,title,artist,aartist,album,akey,pa)"
                        + " VALUES(?,?,?,?,?,?,0,1,?,?,?,?,?,?)");
        String[] cols = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
        try {
            while (!stack.isEmpty() && !cancel) {
                String[] dir = stack.pop();
                Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir[0]);
                ArrayList<F> files = new ArrayList<>();
                String cover = null;
                Cursor c = null;
                try {
                    c = cr.query(kids, cols, null, null, null);
                    while (c != null && c.moveToNext()) {
                        String id = c.getString(0);
                        String name = c.getString(1);
                        String mime = c.getString(2);
                        if (id == null || name == null) continue;
                        if (Document.MIME_TYPE_DIR.equals(mime)) {
                            if (!name.startsWith(".") && !name.equals("Android")) stack.push(new String[]{id, name});
                            continue;
                        }
                        if (name.startsWith(".")) continue;
                        String ext = extOf(name);
                        if (isAudio(mime, ext)) {
                            F f = new F();
                            f.id = id;
                            f.name = name;
                            f.ext = ext;
                            f.size = c.getLong(3);
                            f.mtime = c.getLong(4);
                            files.add(f);
                        } else if (cover == null && isCoverName(name, ext)) {
                            cover = id;
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.close();
                }
                if (files.isEmpty()) continue;
                String folder = dir[1];
                String unkKey = key(UNKNOWN, folder);
                db.beginTransaction();
                try {
                    for (F f : files) {
                        bind(upd, 1, cover);
                        upd.bindString(2, folder);
                        upd.bindString(3, f.id);
                        upd.bindLong(4, f.size);
                        upd.bindLong(5, f.mtime);
                        if (upd.executeUpdateDelete() == 0) {
                            ins.bindString(1, f.id);
                            ins.bindString(2, folder);
                            bind(ins, 3, cover);
                            ins.bindLong(4, f.size);
                            ins.bindLong(5, f.mtime);
                            ins.bindString(6, f.ext);
                            ins.bindString(7, stem(f.name));
                            ins.bindString(8, UNKNOWN);
                            ins.bindString(9, UNKNOWN);
                            ins.bindString(10, folder);
                            ins.bindString(11, unkKey);
                            ins.bindString(12, UNKNOWN);
                            ins.executeInsert();
                        }
                        found++;
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
        } finally {
            upd.close();
            ins.close();
        }
    }

    // ---------------------------------------------------------------- phase 2

    private void readTags() {
        long[] ids;
        Cursor c = db.rawQuery("SELECT _id FROM songs WHERE meta=0", null);
        try {
            ids = new long[c.getCount()];
            int i = 0;
            while (c.moveToNext()) ids[i++] = c.getLong(0);
        } finally {
            c.close();
        }
        total = ids.length;
        done.set(0);
        phase = 2;
        if (ids.length == 0) return;
        final long[] all = ids;
        Thread[] ws = new Thread[WORKERS];
        for (int w = 0; w < WORKERS; w++) {
            final int start = w;
            ws[w] = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    for (int i = start; i < all.length && !cancel; i += WORKERS) {
                        try {
                            process(all[i]);
                        } catch (Throwable ignored) {
                        }
                        done.incrementAndGet();
                    }
                }
            }, "tags" + w);
            ws[w].start();
        }
        for (Thread t : ws) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void process(long id) {
        String doc = null, name = null, folder = null, cover = null;
        Cursor c = db.rawQuery("SELECT doc,title,folder,cover FROM songs WHERE _id=?", new String[]{Long.toString(id)});
        try {
            if (c.moveToFirst()) {
                doc = c.getString(0);
                name = c.getString(1);
                folder = c.getString(2);
                cover = c.getString(3);
            }
        } finally {
            c.close();
        }
        if (doc == null) return;

        String title = null, artist = null, aartist = null, album = null;
        int year = 0, track = 0, dur = 0, br = 0;
        boolean ok = false;
        byte[] pic = null;
        String akey, pa;
        boolean wantArt;

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            try {
                r.setDataSource(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, doc));
                ok = true;
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                aartist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                year = parseYear(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR));
                if (year == 0) year = parseYear(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));
                track = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));
                dur = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                br = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            } catch (Throwable ignored) {
            }
            title = nz(title);
            artist = nz(artist);
            aartist = nz(aartist);
            album = nz(album);
            if (artist == null) artist = aartist;
            if (aartist == null) aartist = artist;
            if (artist == null) {
                artist = UNKNOWN;
                aartist = UNKNOWN;
            }
            if (album == null) album = folder;
            if (title == null) title = name;
            akey = key(aartist, album);
            pa = primary(artist, aartist);
            wantArt = !ArtCache.file(akey).exists()
                    && tried.computeIfAbsent(akey, k -> new AtomicInteger()).incrementAndGet() <= 3;
            if (ok && wantArt) {
                try {
                    pic = r.getEmbeddedPicture();
                } catch (Throwable ignored) {
                }
            }
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }

        if (wantArt) {
            byte[] data = pic;
            if (data == null && cover != null) data = readBytes(cover);
            if (data != null) saveArt(akey, data);
        }

        db.execSQL("UPDATE songs SET meta=1,pa=?,title=?,artist=?,aartist=?,album=?,akey=?,year=?,track=?,dur=?,bitrate=? WHERE _id=?",
                new Object[]{pa, title, artist, aartist, album, akey, year, track, dur, br, id});
    }

    private byte[] readBytes(String docId) {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId));
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, t = 0;
            while ((n = in.read(buf)) > 0) {
                t += n;
                if (t > 6_000_000) return null;
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void saveArt(String key, byte[] d) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return;
            int s = 1;
            while (o.outWidth / (s * 2) >= ART && o.outHeight / (s * 2) >= ART) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length, o);
            if (b == null) return;
            int w = b.getWidth(), h = b.getHeight();
            float sc = Math.min(1f, (float) ART / Math.min(w, h));
            if (sc < 1f) {
                Bitmap n = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)), true);
                if (n != b) {
                    b.recycle();
                    b = n;
                }
            }
            File tmp = new File(ArtCache.dir(), key + ".tmp" + Thread.currentThread().getId());
            FileOutputStream fo = new FileOutputStream(tmp);
            b.compress(Bitmap.CompressFormat.JPEG, 82, fo);
            fo.close();
            b.recycle();
            if (!tmp.renameTo(ArtCache.file(key))) tmp.delete();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void bind(SQLiteStatement st, int i, String v) {
        if (v == null) st.bindNull(i);
        else st.bindString(i, v);
    }

    private static String leaf(String id) {
        int i = Math.max(id.lastIndexOf('/'), id.lastIndexOf(':'));
        return (i >= 0 && i < id.length() - 1) ? id.substring(i + 1) : "Music";
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static String stem(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static boolean isAudio(String mime, String ext) {
        if (NOT_AUDIO.contains(ext)) return false;
        if (AUDIO.contains(ext)) return true;
        return mime != null && mime.startsWith("audio/");
    }

    private static boolean isCoverName(String name, String ext) {
        if (!(ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png"))) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("cover") || n.startsWith("folder") || n.startsWith("front")
                || n.startsWith("album") || n.startsWith("art");
    }

    private static String nz(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static int parseInt(String s) {
        if (s == null) return 0;
        int i = s.indexOf('/');
        if (i >= 0) s = s.substring(0, i);
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int parseYear(String s) {
        if (s == null || s.length() < 4) return 0;
        try {
            int y = Integer.parseInt(s.substring(0, 4));
            return (y > 1000 && y < 3000) ? y : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Main artist: "X feat. Y", "X & Y" (when X is the album artist), "X; Y" all group under X. */
    static String primary(String artist, String aartist) {
        if (artist == null || artist.equals(UNKNOWN)) return UNKNOWN;
        String a = artist.trim();
        if (aartist != null && !aartist.equals(UNKNOWN) && a.length() > aartist.length()
                && a.regionMatches(true, 0, aartist, 0, aartist.length())
                && !Character.isLetterOrDigit(a.charAt(aartist.length()))) {
            return aartist;
        }
        String[] cut = a.split("(?i)\\s*[(\\[]?\\s*\\b(feat|ft|featuring)\\b\\.?\\s.*$|\\s*;\\s*.*$|\\s+/\\s+.*$", 2);
        String p = cut.length > 0 ? cut[0].trim() : a;
        return p.isEmpty() ? a : p;
    }

    static String key(String a, String b) {
        try {
            MessageDigest m = MessageDigest.getInstance("MD5");
            byte[] h = m.digest((a.toLowerCase(Locale.ROOT) + "\u0001" + b.toLowerCase(Locale.ROOT)).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((a + b).hashCode());
        }
    }
}
