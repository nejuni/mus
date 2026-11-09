package com.mlib.io;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final int REQ_TREE = 1;
    static final int BG = 0xFF121212, FG = 0xFFEEEEEE, DIM = 0xFF9E9E9E, ACC = 0xFF4FC3F7, CARD = 0xFF1E1E1E;
    static final String[] TABS = {"ALBUMS", "ARTISTS", "SONGS"};
    static final String[] UNIT = {"albums", "artists", "songs"};

    static final String[][] SORT_NAMES = {
            {"Name A\u2013Z", "Artist A\u2013Z", "Year (newest)", "Year (oldest)", "Most songs", "Longest", "Recently added"},
            {"Name A\u2013Z", "Most songs", "Most albums", "Longest total"},
            {"Title A\u2013Z", "Artist A\u2013Z", "Album (track order)", "Longest", "Shortest", "Largest file", "Recently added", "Year (newest)"}};
    static final String[][] SORT_SQL = {
            {"album COLLATE NOCASE",
                    "aartist COLLATE NOCASE, album COLLATE NOCASE",
                    "MAX(year) DESC, album COLLATE NOCASE",
                    "COALESCE(MIN(NULLIF(year,0)),9999), album COLLATE NOCASE",
                    "COUNT(*) DESC, album COLLATE NOCASE",
                    "SUM(dur) DESC",
                    "MAX(mtime) DESC"},
            {"pa COLLATE NOCASE",
                    "COUNT(*) DESC, pa COLLATE NOCASE",
                    "COUNT(DISTINCT akey) DESC, pa COLLATE NOCASE",
                    "SUM(dur) DESC"},
            {"title COLLATE NOCASE",
                    "artist COLLATE NOCASE, album COLLATE NOCASE, track",
                    "album COLLATE NOCASE, akey, track, title COLLATE NOCASE",
                    "dur DESC",
                    "dur ASC",
                    "size DESC",
                    "mtime DESC",
                    "year DESC, album COLLATE NOCASE, track"}};

    SQLiteDatabase db;
    SharedPreferences prefs;
    Uri tree;
    int mode = 0;
    final int[] sortIdx = new int[3];
    String fArtist, fAlbum, fAlbumName, query = "";
    int seq;
    // navigation back-stack: remembers where we were (tab, filters, sort, scroll) before drilling in
    static final class Nav {
        int mode, pos, off;
        int[] sorts;
        String artist, album, albumName;
    }
    final ArrayList<Nav> navStack = new ArrayList<>();
    int pendPos = -1, pendOff;
    BackHelper backHelper;

    // player UI
    static final String GROUP_ORDER = "album COLLATE NOCASE, akey, track, title COLLATE NOCASE";
    boolean started, queueOpen, progTicking;
    int dragPos = -1;
    String miniKey;
    LinearLayout mini, queueView;
    ImageView miniArt;
    TextView miniTitle, miniSub, playTv, qCount, qTime;
    ProgressLine miniProg, qProg;
    DragList qList;
    QAdapter qAdapter;
    boolean ticking, wasRunning;

    ListView list;
    Adapter adapter;
    TextView statsTv, countTv, sortTv, chip, empty, clearBtn;
    final TextView[] tabs = new TextView[3];
    EditText search;
    RefreshView refresh;

    final Handler h = new Handler(Looper.getMainLooper());
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final NumberFormat nf = NumberFormat.getInstance();

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ArtCache.init(this);
        db = Db.get(this);
        prefs = getSharedPreferences("p", MODE_PRIVATE);
        String s = prefs.getString("tree", null);
        if (s != null) {
            Uri u = Uri.parse(s);
            if (hasPerm(u)) tree = u;
        }
        buildUi();
        refreshUi();
        reload();
        loadStats();
        if (tree == null) {
            pickFolder();
        } else if (!LibScanner.running()
                && (q1("SELECT COUNT(*) FROM songs") == 0 || q1("SELECT COUNT(*) FROM songs WHERE meta=0") > 0)) {
            // first scan, or an unfinished first scan: continue it. Otherwise refresh is manual only.
            startScan();
        }
        if (LibScanner.running()) startTick();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (LibScanner.running()) startTick();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        Player.listener = uiRun;
        updatePlayerUi();
    }

    @Override
    protected void onStop() {
        started = false;
        Player.listener = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Player.listener = null;
        h.removeCallbacksAndMessages(null);
        ticking = false;
        if (adapter != null) adapter.swap(null, mode);
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!goBack()) super.onBackPressed();
    }

    /** Pops one level and restores tab, filters, sort and exact scroll position. */
    boolean goBack() {
        if (queueOpen) {
            closeQueue();
            return true;
        }
        if (!navStack.isEmpty()) {
            restore(navStack.remove(navStack.size() - 1));
            updateBack();
            return true;
        }
        if (fArtist != null || fAlbum != null) {
            clearFilters();
            return true;
        }
        return false;
    }

    void clearFilters() {
        fArtist = null;
        fAlbum = null;
        fAlbumName = null;
        navStack.clear();
        updateBack();
        pendPos = 0;
        pendOff = 0;
        refreshUi();
        reload();
    }

    Nav snapshot() {
        Nav n = new Nav();
        n.mode = mode;
        n.artist = fArtist;
        n.album = fAlbum;
        n.albumName = fAlbumName;
        n.sorts = sortIdx.clone();
        n.pos = list.getFirstVisiblePosition();
        View v = list.getChildAt(0);
        n.off = v == null ? 0 : v.getTop() - list.getPaddingTop();
        return n;
    }

    void restore(Nav n) {
        mode = n.mode;
        fArtist = n.artist;
        fAlbum = n.album;
        fAlbumName = n.albumName;
        System.arraycopy(n.sorts, 0, sortIdx, 0, 3);
        pendPos = n.pos;
        pendOff = n.off;
        refreshUi();
        reload();
    }

    void updateBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (backHelper == null) backHelper = new BackHelper();
            backHelper.set(this, queueOpen || !navStack.isEmpty() || fArtist != null || fAlbum != null, new Runnable() {
                @Override
                public void run() {
                    goBack();
                }
            });
        }
    }

    boolean hasPerm(Uri u) {
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isReadPermission()) return true;
        }
        return false;
    }

    void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (tree != null) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, tree);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            Toast.makeText(this, "No folder picker available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_TREE || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        boolean changed = tree == null || !tree.equals(u);
        tree = u;
        prefs.edit().putString("tree", u.toString()).apply();
        fArtist = null;
        fAlbum = null;
        fAlbumName = null;
        navStack.clear();
        updateBack();
        pendPos = 0;
        pendOff = 0;
        refreshUi();
        LibScanner.start(this, tree, db, changed);
        startTick();
        reload();
        loadStats();
    }

    void startScan() {
        if (tree == null) {
            pickFolder();
            return;
        }
        if (LibScanner.running()) return;
        LibScanner.start(this, tree, db, false);
        startTick();
    }

    // ------------------------------------------------------------------ progress ticker

    final Runnable tick = new Runnable() {
        int n;

        @Override
        public void run() {
            LibScanner s = LibScanner.cur;
            boolean run = LibScanner.running();
            if (run && s != null) {
                float p = (s.phase == 2 && s.total > 0) ? s.done.get() / (float) s.total : -1f;
                refresh.update(true, p);
                wasRunning = true;
                if ((n++ % 4) == 0) {
                    reload();
                    loadStats();
                }
                h.postDelayed(this, 500);
            } else {
                refresh.update(false, -1f);
                ticking = false;
                n = 0;
                if (wasRunning) {
                    wasRunning = false;
                    reload();
                    loadStats();
                }
            }
        }
    };

    void startTick() {
        if (!ticking) {
            ticking = true;
            h.post(tick);
        }
    }

    // ------------------------------------------------------------------ data

    long q1(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    String where(ArrayList<String> a, String artist, String album, String q) {
        StringBuilder w = new StringBuilder("1=1");
        if (artist != null) {
            w.append(" AND pa=? COLLATE NOCASE");
            a.add(artist);
        }
        if (album != null) {
            w.append(" AND akey=?");
            a.add(album);
        }
        for (String t : q.trim().split("\\s+")) {
            if (t.isEmpty()) continue;
            w.append(" AND (title LIKE ? OR artist LIKE ? OR album LIKE ? OR aartist LIKE ?)");
            String p = "%" + t + "%";
            a.add(p);
            a.add(p);
            a.add(p);
            a.add(p);
        }
        return w.toString();
    }

    void reload() {
        final int s = ++seq;
        final int m = mode;
        final int sort = sortIdx[m];
        final String qa = fArtist, qb = fAlbum, qq = query;
        ex.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ArrayList<String> a = new ArrayList<>();
                    String w = where(a, qa, qb, qq);
                    String sql;
                    if (m == 0) {
                        sql = "SELECT akey,album,aartist,COUNT(*),MAX(year),SUM(dur) FROM songs WHERE " + w
                                + " GROUP BY akey ORDER BY " + SORT_SQL[0][sort];
                    } else if (m == 1) {
                        sql = "SELECT pa,COUNT(*),COUNT(DISTINCT akey),SUM(dur),MIN(akey) FROM songs WHERE " + w
                                + " GROUP BY pa COLLATE NOCASE ORDER BY " + SORT_SQL[1][sort];
                    } else {
                        sql = "SELECT _id,title,artist,album,dur,akey,track,year FROM songs WHERE " + w
                                + " ORDER BY " + SORT_SQL[2][sort];
                    }
                    final Cursor c = db.rawQuery(sql, a.toArray(new String[0]));
                    c.getCount(); // fill the first window off the UI thread
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (s != seq || isDestroyed()) {
                                c.close();
                                return;
                            }
                            adapter.swap(c, m);
                            updateInfo();
                            if (pendPos >= 0) {
                                list.setSelectionFromTop(pendPos, pendOff);
                                pendPos = -1;
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    void loadStats() {
        ex.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    long songs = q1("SELECT COUNT(*) FROM songs");
                    long albums = q1("SELECT COUNT(DISTINCT akey) FROM songs");
                    long artists = q1("SELECT COUNT(*) FROM (SELECT 1 FROM songs GROUP BY pa COLLATE NOCASE)");
                    final String t = nf.format(songs) + " songs \u00B7 " + nf.format(albums) + " albums \u00B7 "
                            + nf.format(artists) + " artists";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statsTv.setText(t);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    void showStats() {
        ex.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder sb = new StringBuilder();
                    Cursor c = db.rawQuery("SELECT COUNT(*),COUNT(DISTINCT akey),SUM(dur),SUM(size),AVG(dur),"
                            + "MIN(NULLIF(year,0)),MAX(year),SUM(CASE WHEN meta=0 THEN 1 ELSE 0 END) FROM songs", null);
                    long songs = 0, albums = 0;
                    try {
                        if (c.moveToFirst()) {
                            songs = c.getLong(0);
                            albums = c.getLong(1);
                            sb.append("Songs: ").append(nf.format(songs)).append('\n');
                            sb.append("Albums: ").append(nf.format(albums)).append('\n');
                            long artists = q1("SELECT COUNT(*) FROM (SELECT 1 FROM songs GROUP BY pa COLLATE NOCASE)");
                            sb.append("Artists: ").append(nf.format(artists)).append('\n');
                            sb.append("Total time: ").append(fmtTotal(c.getLong(2))).append('\n');
                            sb.append("Total size: ").append(fmtSize(c.getLong(3))).append('\n');
                            sb.append("Average song: ").append(fmtDur(c.getLong(4))).append('\n');
                            if (albums > 0) {
                                sb.append("Songs per album: ").append(String.format(Locale.US, "%.1f", songs / (double) albums)).append('\n');
                            }
                            if (c.getLong(6) > 0) {
                                sb.append("Years: ").append(c.getLong(5)).append("\u2013").append(c.getLong(6)).append('\n');
                            }
                            if (c.getLong(7) > 0) {
                                sb.append("Tags still loading: ").append(nf.format(c.getLong(7))).append('\n');
                            }
                        }
                    } finally {
                        c.close();
                    }
                    sb.append("Single-song albums: ")
                            .append(nf.format(q1("SELECT COUNT(*) FROM (SELECT 1 FROM songs GROUP BY akey HAVING COUNT(*)=1)")))
                            .append('\n');
                    sb.append("Unknown artist songs: ")
                            .append(nf.format(q1("SELECT COUNT(*) FROM songs WHERE artist='" + LibScanner.UNKNOWN + "'")))
                            .append('\n');

                    sb.append("\nTop artists\n");
                    c = db.rawQuery("SELECT pa,COUNT(*) n FROM songs GROUP BY pa COLLATE NOCASE ORDER BY n DESC LIMIT 5", null);
                    try {
                        while (c.moveToNext()) {
                            sb.append("  ").append(c.getString(0)).append(" \u2013 ").append(c.getLong(1)).append('\n');
                        }
                    } finally {
                        c.close();
                    }
                    sb.append("\nBiggest albums\n");
                    c = db.rawQuery("SELECT album,aartist,COUNT(*) n FROM songs GROUP BY akey ORDER BY n DESC LIMIT 5", null);
                    try {
                        while (c.moveToNext()) {
                            sb.append("  ").append(c.getString(0)).append(" (").append(c.getString(1)).append(") \u2013 ")
                                    .append(c.getLong(2)).append('\n');
                        }
                    } finally {
                        c.close();
                    }
                    sb.append("\nFormats\n");
                    c = db.rawQuery("SELECT ext,COUNT(*) n FROM songs GROUP BY ext ORDER BY n DESC LIMIT 8", null);
                    try {
                        while (c.moveToNext()) {
                            sb.append("  ").append(c.getString(0)).append(" \u2013 ").append(c.getLong(1)).append('\n');
                        }
                    } finally {
                        c.close();
                    }
                    final String msg = sb.toString();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isDestroyed()) return;
                            new AlertDialog.Builder(MainActivity.this).setTitle("Library stats")
                                    .setMessage(msg).setPositiveButton("OK", null).show();
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    void showSong(long id) {
        Cursor c = db.rawQuery("SELECT title,artist,aartist,album,year,track,dur,size,bitrate,ext,folder FROM songs WHERE _id=?",
                new String[]{Long.toString(id)});
        try {
            if (!c.moveToFirst()) return;
            StringBuilder sb = new StringBuilder();
            sb.append("Artist: ").append(c.getString(1)).append('\n');
            sb.append("Album artist: ").append(c.getString(2)).append('\n');
            sb.append("Album: ").append(c.getString(3)).append('\n');
            if (c.getInt(4) > 0) sb.append("Year: ").append(c.getInt(4)).append('\n');
            if (c.getInt(5) > 0) sb.append("Track: ").append(c.getInt(5)).append('\n');
            sb.append("Length: ").append(fmtDur(c.getLong(6))).append('\n');
            sb.append("Size: ").append(fmtSize(c.getLong(7))).append('\n');
            if (c.getInt(8) > 0) sb.append("Bitrate: ").append(c.getInt(8) / 1000).append(" kbps\n");
            sb.append("Format: ").append(c.getString(9)).append('\n');
            sb.append("Folder: ").append(c.getString(10));
            new AlertDialog.Builder(this).setTitle(c.getString(0)).setMessage(sb.toString())
                    .setPositiveButton("OK", null).show();
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------------ player

    static String nz(String s) {
        return s == null ? "" : s;
    }

    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** Loads songs matching a WHERE clause off the UI thread, then plays / queues them. how: 0 play, 1 next, 2 add. */
    void playSql(final String w, final String[] args, final String order, final int how) {
        final Uri tr = tree;
        if (tr == null) return;
        ex.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final ArrayList<Player.Track> out = new ArrayList<>();
                    Cursor c = db.rawQuery("SELECT doc,title,artist,album,akey,dur FROM songs WHERE " + w
                            + " ORDER BY " + order, args);
                    try {
                        while (c.moveToNext()) {
                            String doc = c.getString(0);
                            if (doc == null) continue;
                            out.add(new Player.Track(DocumentsContract.buildDocumentUriUsingTree(tr, doc).toString(),
                                    nz(c.getString(1)), nz(c.getString(2)), nz(c.getString(3)), c.getString(4), c.getLong(5)));
                        }
                    } finally {
                        c.close();
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            deliver(out, how);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    void deliver(ArrayList<Player.Track> l, int how) {
        if (isDestroyed()) return;
        if (l.isEmpty()) {
            Toast.makeText(this, "Nothing to play", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean was = Player.active;
        if (how == 0) {
            Player.play(this, l, 0);
        } else if (how == 1) {
            Player.playNext(this, l);
            if (was) Toast.makeText(this, "Playing next", Toast.LENGTH_SHORT).show();
        } else {
            Player.add(this, l);
            if (was) Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show();
        }
        askNotifications();
    }

    /** Android 13+: the playback notification needs this permission to be visible (playback works without it). */
    void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notifAsked", false)
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("notifAsked", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }
    }

    /** "Play all": everything the current list shows (tab, artist/album filter, search), in list order. */
    void playShown() {
        if (adapter.getCount() == 0) return;
        ArrayList<String> a = new ArrayList<>();
        String w = where(a, fArtist, fAlbum, query);
        String order = mode == 2 ? SORT_SQL[2][sortIdx[2]] : GROUP_ORDER;
        playSql(w, a.toArray(new String[0]), order, 0);
    }

    void showPlayMenu(final int m, final String key, final String name) {
        String[] items = m == 2 ? new String[]{"Play", "Play next", "Add to queue", "Details"}
                : new String[]{m == 0 ? "Play album" : "Play all songs", "Play next", "Add to queue"};
        new AlertDialog.Builder(this).setTitle(name).setItems(items, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                if (m == 2 && which == 3) {
                    showSong(Long.parseLong(key));
                    return;
                }
                if (m == 0) playSql("akey=?", new String[]{key}, GROUP_ORDER, which);
                else if (m == 1) playSql("pa=? COLLATE NOCASE", new String[]{key}, GROUP_ORDER, which);
                else playSql("_id=?", new String[]{key}, "_id", which);
            }
        }).show();
    }

    final Runnable uiRun = new Runnable() {
        @Override
        public void run() {
            updatePlayerUi();
        }
    };

    void updatePlayerUi() {
        if (mini == null) return;
        Player.Track t = Player.cur();
        boolean on = t != null;
        mini.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            miniTitle.setText(t.title);
            miniSub.setText(t.artist);
            String k = t.akey == null ? "" : t.akey;
            if (!k.equals(miniKey)) {
                miniKey = k;
                ArtCache.load(miniArt, t.akey);
            }
            if (!progTicking) {
                progTicking = true;
                h.post(progRun);
            }
        } else {
            miniKey = null;
        }
        if (queueOpen) {
            if (!on) {
                closeQueue();
            } else {
                qCount.setText(plural(Player.queue.size(), "song", "songs"));
                qAdapter.notifyDataSetChanged();
            }
        }
    }

    final Runnable progRun = new Runnable() {
        @Override
        public void run() {
            if (Player.cur() == null || !started) {
                progTicking = false;
                return;
            }
            int p = Player.position(), d = Player.duration();
            float f = d > 0 ? p / (float) d : 0f;
            miniProg.set(f);
            if (queueOpen) {
                qProg.set(f);
                qTime.setText(clock(p) + " / " + clock(d));
            }
            h.postDelayed(this, 500);
        }
    };

    void openQueue() {
        if (Player.cur() == null) return;
        queueOpen = true;
        queueView.setVisibility(View.VISIBLE);
        updatePlayerUi();
        qList.setSelection(Math.max(0, Player.index - 1));
        updateBack();
    }

    void closeQueue() {
        queueOpen = false;
        queueView.setVisibility(View.GONE);
        updateBack();
    }

    IconView ctl(String desc, int type, float scale, int sizeDp, View.OnClickListener l) {
        IconView v = new IconView(this, desc, type).setScale(scale);
        v.setOnClickListener(l);
        return v;
    }

    final View.OnClickListener onPrev = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Player.prev();
        }
    };
    final View.OnClickListener onStop = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Player.stop();
        }
    };
    final View.OnClickListener onNext = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Player.next();
        }
    };

    LinearLayout buildMini() {
        mini = new LinearLayout(this);
        mini.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable mg = new GradientDrawable();
        mg.setColor(CARD);
        mg.setCornerRadius(dp(14));
        mini.setBackground(mg);
        mini.setVisibility(View.GONE);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(4), dp(4));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openQueue();
            }
        });
        miniArt = new ImageView(this);
        miniArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniArt.setBackgroundColor(0xFF2A2A2A);
        row.addView(miniArt, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        miniTitle = tv(14, FG, false);
        miniSub = tv(12, DIM, false);
        for (TextView t : new TextView[]{miniTitle, miniSub}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(t);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cl.leftMargin = dp(10);
        row.addView(col, cl);
        row.addView(ctl("Previous", IconView.PREV, 1f, 40, onPrev), new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.addView(ctl("Stop", IconView.STOP, 1f, 40, onStop), new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.addView(ctl("Next", IconView.NEXT, 1f, 40, onNext), new LinearLayout.LayoutParams(dp(40), dp(40)));
        mini.addView(row);

        miniProg = new ProgressLine(this);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        pl.setMargins(dp(14), 0, dp(14), dp(7));
        mini.addView(miniProg, pl);
        return mini;
    }

    LinearLayout buildQueue() {
        queueView = new LinearLayout(this);
        queueView.setOrientation(LinearLayout.VERTICAL);
        queueView.setBackgroundColor(BG);
        queueView.setClickable(true);
        queueView.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(8), dp(16), dp(4));
        head.addView(ctl("Close queue", IconView.BACK, 1f, 44, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeQueue();
            }
        }), new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = tv(20, FG, true);
        title.setText("Queue");
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tl.leftMargin = dp(4);
        head.addView(title, tl);
        qCount = tv(12, DIM, false);
        head.addView(qCount);
        queueView.addView(head);

        qList = new DragList(this);
        qList.setDivider(null);
        qList.setDividerHeight(0);
        qAdapter = new QAdapter();
        qList.setAdapter(qAdapter);
        qList.setCb(new DragList.Cb() {
            @Override
            public void onMove(int from, int to) {
                Player.move(from, to);
            }

            @Override
            public void onDrag(int pos) {
                dragPos = pos;
                qAdapter.notifyDataSetChanged();
            }
        });
        qList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Player.jump(pos);
            }
        });
        queueView.addView(qList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(0, dp(8), 0, dp(12));
        qProg = new ProgressLine(this);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        pl.setMargins(dp(20), 0, dp(20), 0);
        bottom.addView(qProg, pl);
        qTime = tv(12, DIM, false);
        qTime.setPadding(0, dp(6), 0, dp(2));
        bottom.addView(qTime);
        LinearLayout ctr = new LinearLayout(this);
        ctr.setOrientation(LinearLayout.HORIZONTAL);
        ctr.setGravity(Gravity.CENTER);
        ctr.addView(ctl("Previous", IconView.PREV, 1.4f, 60, onPrev), new LinearLayout.LayoutParams(dp(64), dp(56)));
        ctr.addView(ctl("Stop", IconView.STOP, 1.4f, 60, onStop), new LinearLayout.LayoutParams(dp(64), dp(56)));
        ctr.addView(ctl("Next", IconView.NEXT, 1.4f, 60, onNext), new LinearLayout.LayoutParams(dp(64), dp(56)));
        bottom.addView(ctr);
        queueView.addView(bottom);
        return queueView;
    }

    // ------------------------------------------------------------------ formatting

    static String fmtDur(long ms) {
        if (ms <= 0) return "";
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    static String fmtTotal(long ms) {
        long mins = ms / 60000, hrs = mins / 60, days = hrs / 24;
        if (days > 0) return days + "d " + (hrs % 24) + "h";
        if (hrs > 0) return hrs + "h " + String.format(Locale.US, "%02d", mins % 60) + "m";
        return mins + "m";
    }

    static String fmtSize(long b) {
        if (b >= (1L << 30)) return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
        if (b >= (1L << 20)) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return (b / 1024) + " KB";
    }

    static String plural(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    // ------------------------------------------------------------------ UI

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    TextView tv(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        // targetSdk 35+ is edge-to-edge: keep content clear of status/nav bars, cutout and keyboard
        final FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                    return WindowInsets.CONSUMED;
                }
                v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                        in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
                return in.consumeSystemWindowInsets();
            }
        });

        // top bar: title + stats | folder | refresh icon
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(8), dp(12), dp(4));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = tv(20, FG, true);
        title.setText("Music Library");
        statsTv = tv(12, DIM, false);
        statsTv.setText(" ");
        statsTv.setPadding(0, dp(2), 0, dp(2));
        statsTv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStats();
            }
        });
        titles.addView(title);
        titles.addView(statsTv);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        IconView folderBtn = new IconView(this, "Choose music folder", IconView.FOLDER);
        folderBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFolder();
            }
        });
        top.addView(folderBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        refresh = new RefreshView(this);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        top.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(top);

        // tabs
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = tv(13, DIM, true);
            t.setText(TABS[i]);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(10), 0, dp(10));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mode = idx;
                    pendPos = 0;
                    pendOff = 0;
                    refreshUi();
                    reload();
                }
            });
            tabs[i] = t;
            tabRow.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(tabRow);

        // active filter chip
        chip = tv(13, FG, false);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(0xFF263238);
        cg.setCornerRadius(dp(16));
        chip.setBackground(cg);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setVisibility(View.GONE);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!navStack.isEmpty()) {
                    Nav first = navStack.get(0);
                    navStack.clear();
                    restore(first);
                    updateBack();
                } else {
                    clearFilters();
                }
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dp(16), dp(6), dp(16), 0);
        root.addView(chip, clp);

        // count + sort row
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(16), 0, dp(8), 0);
        countTv = tv(12, DIM, false);
        info.addView(countTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        playTv = tv(13, ACC, false);
        playTv.setText("Play all");
        playTv.setPadding(dp(8), dp(10), dp(8), dp(10));
        playTv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playShown();
            }
        });
        info.addView(playTv);
        sortTv = tv(13, ACC, false);
        sortTv.setPadding(dp(8), dp(10), dp(8), dp(10));
        sortTv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Sort by")
                        .setSingleChoiceItems(SORT_NAMES[mode], sortIdx[mode], new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                sortIdx[mode] = which;
                                d.dismiss();
                                pendPos = 0;
                                pendOff = 0;
                                updateInfo();
                                reload();
                            }
                        }).show();
            }
        });
        info.addView(sortTv);
        root.addView(info);

        // list
        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setFastScrollEnabled(true);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Cursor c = adapter.c;
                if (c == null || !c.moveToPosition(pos)) return;
                if (mode == 0) {
                    navStack.add(snapshot());
                    fAlbum = c.getString(0);
                    fAlbumName = c.getString(1);
                    mode = 2;
                    sortIdx[2] = 2; // album track order
                    pendPos = 0;
                    pendOff = 0;
                    updateBack();
                    refreshUi();
                    reload();
                } else if (mode == 1) {
                    navStack.add(snapshot());
                    fArtist = c.getString(0);
                    fAlbum = null;
                    fAlbumName = null;
                    mode = 0;
                    pendPos = 0;
                    pendOff = 0;
                    updateBack();
                    refreshUi();
                    reload();
                } else {
                    playSql("_id=?", new String[]{Long.toString(c.getLong(0))}, "_id", 0);
                }
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Cursor c = adapter.c;
                if (c == null || !c.moveToPosition(pos)) return true;
                if (mode == 0) showPlayMenu(0, c.getString(0), c.getString(1));
                else if (mode == 1) showPlayMenu(1, c.getString(0), c.getString(0));
                else showPlayMenu(2, Long.toString(c.getLong(0)), c.getString(1));
                return true;
            }
        });
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        empty = tv(15, DIM, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), dp(32), dp(32), dp(32));
        empty.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tree == null) pickFolder();
            }
        });
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.setMargins(dp(12), dp(6), dp(12), 0);
        root.addView(buildMini(), mlp);

        // search bar at the bottom
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(CARD);
        sg.setCornerRadius(dp(24));
        bar.setBackground(sg);
        search = new EditText(this);
        search.setHint("Search songs, artists, albums");
        search.setHintTextColor(DIM);
        search.setTextColor(FG);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setPadding(dp(18), dp(12), dp(8), dp(12));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                pendPos = 0;
                pendOff = 0;
                clearBtn.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                h.removeCallbacks(reloadRun);
                h.postDelayed(reloadRun, 180);
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
                return true;
            }
        });
        bar.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        clearBtn = tv(18, DIM, false);
        clearBtn.setText("\u2715");
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setVisibility(View.GONE);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search.setText("");
            }
        });
        bar.addView(clearBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(12), dp(6), dp(12), dp(10));
        root.addView(bar, blp);

        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(buildQueue(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
    }

    final Runnable reloadRun = new Runnable() {
        @Override
        public void run() {
            reload();
        }
    };

    /** Tabs, chip and sort label follow the current state. */
    void refreshUi() {
        for (int i = 0; i < 3; i++) {
            tabs[i].setTextColor(i == mode ? ACC : DIM);
            tabs[i].setPaintFlags(i == mode ? tabs[i].getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    : tabs[i].getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        }
        updateInfo();
    }

    void updateInfo() {
        sortTv.setText("Sort: " + SORT_NAMES[mode][sortIdx[mode]] + " \u25BE");
        if (fArtist != null || fAlbum != null) {
            String t = "";
            if (fArtist != null) t += fArtist;
            if (fAlbum != null) t += (t.isEmpty() ? "" : "  \u203A  ") + fAlbumName;
            chip.setText(t + "  \u2715");
            chip.setVisibility(View.VISIBLE);
        } else {
            chip.setVisibility(View.GONE);
        }
        int n = adapter.getCount();
        countTv.setText(nf.format(n) + " " + UNIT[mode]);
        if (tree == null) {
            empty.setText("Tap here (or the folder icon) to choose your music folder");
        } else if (LibScanner.running() && n == 0) {
            LibScanner s = LibScanner.cur;
            empty.setText("Scanning\u2026 " + (s == null ? 0 : s.found) + " files found");
        } else if (!query.trim().isEmpty() || fArtist != null || fAlbum != null) {
            empty.setText("No results");
        } else {
            empty.setText("No music found.\nTap the refresh icon to scan again.");
        }
    }

    // ------------------------------------------------------------------ adapter

    static final class Holder {
        ImageView img;
        TextView t1, t2, r;
    }

    final class Adapter extends BaseAdapter {
        Cursor c;
        int m;

        void swap(Cursor n, int mode) {
            Cursor o = c;
            c = n;
            m = mode;
            notifyDataSetChanged();
            if (o != null) o.close();
        }

        @Override
        public int getCount() {
            return c == null ? 0 : c.getCount();
        }

        @Override
        public Object getItem(int p) {
            return null;
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            Holder hd;
            if (v == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(6), dp(16), dp(6));
                hd = new Holder();
                hd.img = new ImageView(MainActivity.this);
                hd.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                hd.img.setBackgroundColor(0xFF2A2A2A);
                row.addView(hd.img, new LinearLayout.LayoutParams(dp(52), dp(52)));
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                hd.t1 = tv(16, FG, false);
                hd.t2 = tv(13, DIM, false);
                for (TextView t : new TextView[]{hd.t1, hd.t2}) {
                    t.setSingleLine(true);
                    t.setEllipsize(TextUtils.TruncateAt.END);
                    col.addView(t);
                }
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                cl.leftMargin = dp(12);
                row.addView(col, cl);
                hd.r = tv(12, DIM, false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rl.leftMargin = dp(8);
                row.addView(hd.r, rl);
                row.setTag(hd);
                v = row;
            } else {
                hd = (Holder) v.getTag();
            }
            if (c == null || !c.moveToPosition(p)) return v;
            if (m == 0) {
                hd.t1.setText(c.getString(1));
                String y = c.getInt(4) > 0 ? " \u00B7 " + c.getInt(4) : "";
                hd.t2.setText(c.getString(2) + " \u00B7 " + plural(c.getLong(3), "song", "songs") + y);
                hd.r.setText(fmtTotal(c.getLong(5)));
                ArtCache.load(hd.img, c.getString(0));
            } else if (m == 1) {
                hd.t1.setText(c.getString(0));
                hd.t2.setText(plural(c.getLong(2), "album", "albums") + " \u00B7 " + plural(c.getLong(1), "song", "songs"));
                hd.r.setText(fmtTotal(c.getLong(3)));
                ArtCache.load(hd.img, c.getString(4));
            } else {
                int tr = c.getInt(6);
                hd.t1.setText((fAlbum != null && tr > 0 ? tr + ". " : "") + c.getString(1));
                hd.t2.setText(c.getString(2) + " \u00B7 " + c.getString(3));
                hd.r.setText(fmtDur(c.getLong(4)));
                ArtCache.load(hd.img, c.getString(5));
            }
            return v;
        }
    }

    // ------------------------------------------------------------------ queue adapter

    static final class QHolder {
        ImageView img;
        TextView t1, t2;
        IconView rm;
    }

    final class QAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return Player.queue.size();
        }

        @Override
        public Object getItem(int p) {
            return null;
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(final int p, View v, ViewGroup parent) {
            QHolder hd;
            if (v == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(6), dp(4), dp(6));
                hd = new QHolder();
                hd.img = new ImageView(MainActivity.this);
                hd.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                hd.img.setBackgroundColor(0xFF2A2A2A);
                row.addView(hd.img, new LinearLayout.LayoutParams(dp(44), dp(44)));
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                hd.t1 = tv(15, FG, false);
                hd.t2 = tv(12, DIM, false);
                for (TextView t : new TextView[]{hd.t1, hd.t2}) {
                    t.setSingleLine(true);
                    t.setEllipsize(TextUtils.TruncateAt.END);
                    col.addView(t);
                }
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                cl.leftMargin = dp(12);
                row.addView(col, cl);
                hd.rm = new IconView(MainActivity.this, "Remove from queue", IconView.CLOSE).setScale(0.8f);
                hd.rm.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View b) {
                        Object t = b.getTag();
                        if (t instanceof Integer) Player.remove((Integer) t);
                    }
                });
                row.addView(hd.rm, new LinearLayout.LayoutParams(dp(36), dp(40)));
                // grip: touches on this strip are picked up by DragList
                row.addView(new IconView(MainActivity.this, "Drag to reorder", IconView.DRAG).decorative(),
                        new LinearLayout.LayoutParams(dp(40), dp(40)));
                row.setTag(hd);
                v = row;
            } else {
                hd = (QHolder) v.getTag();
            }
            Player.Track t = p < Player.queue.size() ? Player.queue.get(p) : null;
            if (t == null) return v;
            boolean now = p == Player.index;
            hd.t1.setText(t.title);
            hd.t1.setTextColor(now ? ACC : FG);
            hd.t2.setText(t.artist + (t.album.isEmpty() ? "" : " \u00B7 " + t.album));
            hd.rm.setTag(p);
            v.setBackgroundColor(p == dragPos ? 0xFF2A2A2A : 0);
            ArtCache.load(hd.img, t.akey);
            return v;
        }
    }
}
