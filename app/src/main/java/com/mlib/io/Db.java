package com.mlib.io;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Single SQLite file holds the whole library; lists are read straight from cursors (low RAM). */
final class Db extends SQLiteOpenHelper {
    private static SQLiteDatabase inst;

    static synchronized SQLiteDatabase get(Context c) {
        if (inst == null || !inst.isOpen()) {
            inst = new Db(c.getApplicationContext()).getWritableDatabase();
        }
        return inst;
    }

    private Db(Context c) {
        super(c, "lib.db", null, 2);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE songs(_id INTEGER PRIMARY KEY, doc TEXT UNIQUE, folder TEXT, cover TEXT,"
                + " size INTEGER, mtime INTEGER, ext TEXT, meta INTEGER DEFAULT 0, seen INTEGER DEFAULT 1,"
                + " title TEXT, artist TEXT, pa TEXT, aartist TEXT, album TEXT, akey TEXT,"
                + " year INTEGER DEFAULT 0, track INTEGER DEFAULT 0, dur INTEGER DEFAULT 0, bitrate INTEGER DEFAULT 0)");
        d.execSQL("CREATE INDEX i_ak ON songs(akey)");
        d.execSQL("CREATE INDEX i_ar ON songs(pa)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int o, int n) {
        d.execSQL("DROP TABLE IF EXISTS songs");
        onCreate(d);
    }
}
