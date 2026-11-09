package com.mlib.io;

import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/**
 * Playback queue + controls. Everything here runs on the main thread.
 * The actual audio is done by {@link PlayerService}.
 */
final class Player {
    static final class Track {
        final String uri, title, artist, album, akey;
        final long dur;

        Track(String uri, String title, String artist, String album, String akey, long dur) {
            this.uri = uri;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.akey = akey;
            this.dur = dur;
        }
    }

    static final ArrayList<Track> queue = new ArrayList<>();
    static int index = -1;
    /** true from the moment something is started until stop / end of queue. */
    static boolean active;
    /** true while the user has paused (notification / lock screen / headset). */
    static boolean paused;
    /** UI refresh hook, set by the activity while it is visible. */
    static Runnable listener;

    private Player() {
    }

    static Track cur() {
        return active && index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    static void changed() {
        Runnable l = listener;
        if (l != null) l.run();
    }

    /** Replace the queue and start playing at {@code start}. */
    static void play(Context c, List<Track> t, int start) {
        if (t.isEmpty()) return;
        queue.clear();
        queue.addAll(t);
        index = Math.max(0, Math.min(start, queue.size() - 1));
        active = true;
        paused = false;
        changed();
        Intent i = new Intent(c, PlayerService.class).setAction(PlayerService.A_PLAY);
        c.startForegroundService(i);
    }

    /** Insert right after the current song (starts playing if nothing is playing). */
    static void playNext(Context c, List<Track> t) {
        if (t.isEmpty()) return;
        if (!active) {
            play(c, t, 0);
            return;
        }
        queue.addAll(index + 1, t);
        changed();
    }

    /** Append to the end of the queue (starts playing if nothing is playing). */
    static void add(Context c, List<Track> t) {
        if (t.isEmpty()) return;
        if (!active) {
            play(c, t, 0);
            return;
        }
        queue.addAll(t);
        changed();
    }

    private static void startCur() {
        paused = false;
        changed();
        PlayerService s = PlayerService.inst;
        if (s != null) s.playCurrent(); // otherwise the pending service start plays queue[index]
    }

    static void next() {
        if (!active) return;
        if (index + 1 < queue.size()) {
            index++;
            startCur();
        } else {
            stop();
        }
    }

    static void prev() {
        if (!active) return;
        if (index > 0 && position() <= 3000) index--;
        startCur(); // past 3 s (or first song) = restart the current song
    }

    static void jump(int i) {
        if (!active || i < 0 || i >= queue.size()) return;
        index = i;
        startCur();
    }

    /** Called by the service when a song finished by itself. */
    static void onCompleted() {
        if (!active) return;
        if (index + 1 < queue.size()) {
            index++;
            startCur();
        } else {
            stop();
        }
    }

    static void move(int from, int to) {
        if (from < 0 || to < 0 || from >= queue.size() || to >= queue.size() || from == to) return;
        Track t = queue.remove(from);
        queue.add(to, t);
        if (index == from) index = to;
        else if (from < index && index <= to) index--;
        else if (to <= index && index < from) index++;
        changed();
    }

    static void remove(int i) {
        if (!active || i < 0 || i >= queue.size()) return;
        boolean wasCur = i == index;
        queue.remove(i);
        if (queue.isEmpty()) {
            stop();
            return;
        }
        if (i < index) {
            index--;
        } else if (wasCur) {
            if (index >= queue.size()) {
                stop();
            } else {
                startCur();
            }
            return;
        }
        changed();
    }

    static void pause() {
        setPaused(true);
    }

    static void resume() {
        setPaused(false);
    }

    static void toggle() {
        setPaused(!paused);
    }

    private static void setPaused(boolean p) {
        if (!active || paused == p) return;
        PlayerService s = PlayerService.inst;
        if (s != null) s.setPaused(p);
    }

    static void stop() {
        active = false;
        paused = false;
        queue.clear();
        index = -1;
        PlayerService s = PlayerService.inst;
        if (s != null) s.shutdown();
        changed();
    }

    /** The service died without us asking: just forget the queue. */
    static void reset() {
        active = false;
        paused = false;
        queue.clear();
        index = -1;
        changed();
    }

    static int position() {
        return PlayerService.position();
    }

    static int duration() {
        int d = PlayerService.duration();
        if (d > 0) return d;
        Track t = cur();
        return t == null ? 0 : (int) t.dur;
    }
}
