package com.mlib.io;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import java.io.File;

/** Foreground media-playback service: one MediaPlayer, audio focus, lock-screen / notification controls. */
public final class PlayerService extends Service {
    static final String A_PLAY = "com.mlib.io.PLAY";
    static final String A_NEXT = "com.mlib.io.NEXT";
    static final String A_PREV = "com.mlib.io.PREV";
    static final String A_STOP = "com.mlib.io.STOP";
    private static final String CH = "playback";
    private static final int NID = 1;

    static volatile PlayerService inst;

    private MediaPlayer mp;
    private boolean prepared, pausedByFocus, fg, noisyReg, stopping;
    private int errStreak, lastStartId;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focus;
    private NotificationManager nm;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final AudioManager.OnAudioFocusChangeListener focusL = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            MediaPlayer m = mp;
            switch (change) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    Player.stop();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    if (m != null && prepared && m.isPlaying()) {
                        m.pause();
                        pausedByFocus = true;
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (m != null) m.setVolume(0.25f, 0.25f);
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (m != null) {
                        m.setVolume(1f, 1f);
                        if (pausedByFocus && prepared) {
                            m.start();
                            pausedByFocus = false;
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    };

    /** Headphones unplugged / bluetooth gone: stop, don't blast the speaker. */
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) Player.stop();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        nm = getSystemService(NotificationManager.class);
        am = getSystemService(AudioManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        session = new MediaSession(this, "mlib");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onSkipToNext() {
                Player.next();
            }

            @Override
            public void onSkipToPrevious() {
                Player.prev();
            }

            @Override
            public void onStop() {
                Player.stop();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        stopping = false;
        ensureForeground(Player.cur()); // must happen quickly after startForegroundService()
        String a = intent == null ? null : intent.getAction();
        if (A_NEXT.equals(a)) {
            Player.next();
        } else if (A_PREV.equals(a)) {
            Player.prev();
        } else if (A_STOP.equals(a)) {
            Player.stop();
        } else if (A_PLAY.equals(a)) {
            playCurrent();
        }
        if (!Player.active) shutdown();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        releasePlayer();
        unregisterNoisy();
        if (focus != null) am.abandonAudioFocusRequest(focus);
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        if (inst == this) {
            inst = null;
            if (!stopping && Player.active) Player.reset(); // killed by the system, not by us
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ playback

    void playCurrent() {
        final Player.Track t = Player.cur();
        if (t == null) {
            Player.stop();
            return;
        }
        stopping = false;
        registerNoisy();
        session.setActive(true);
        requestFocus();
        releasePlayer();
        pausedByFocus = false;
        final MediaPlayer m = new MediaPlayer();
        mp = m;
        m.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        m.setAudioAttributes(attrs());
        m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer p) {
                if (mp != p) return;
                prepared = true;
                errStreak = 0;
                p.start();
                updateSession(t, true);
                Player.changed();
            }
        });
        m.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer p) {
                if (mp == p) Player.onCompleted();
            }
        });
        m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer p, int what, int extra) {
                if (mp == p) postError();
                return true;
            }
        });
        try {
            m.setDataSource(this, Uri.parse(t.uri));
            m.prepareAsync();
        } catch (Exception e) {
            postError();
            return;
        }
        ensureForeground(t);
        updateSession(t, false);
        Player.changed();
    }

    /** Unplayable file: skip it; give up when the rest of the queue is not playable either. */
    private void postError() {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (!Player.active) return;
                errStreak++;
                if (errStreak >= Player.queue.size() || Player.index + 1 >= Player.queue.size()) {
                    Toast.makeText(PlayerService.this, "Can't play this file", Toast.LENGTH_SHORT).show();
                    Player.stop();
                } else {
                    Player.next();
                }
            }
        });
    }

    void shutdown() {
        stopping = true;
        releasePlayer();
        unregisterNoisy();
        if (focus != null) am.abandonAudioFocusRequest(focus);
        if (session != null) session.setActive(false);
        if (fg) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            fg = false;
        }
        nm.cancel(NID);
        stopSelf(lastStartId);
    }

    private void releasePlayer() {
        MediaPlayer m = mp;
        mp = null;
        prepared = false;
        if (m != null) {
            m.setOnPreparedListener(null);
            m.setOnCompletionListener(null);
            m.setOnErrorListener(null);
            try {
                m.release();
            } catch (Exception ignored) {
            }
        }
    }

    static int position() {
        PlayerService s = inst;
        try {
            return s != null && s.mp != null && s.prepared ? s.mp.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    static int duration() {
        PlayerService s = inst;
        try {
            return s != null && s.mp != null && s.prepared ? s.mp.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ focus / noisy

    private static AudioAttributes attrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    private void requestFocus() {
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs())
                    .setOnAudioFocusChangeListener(focusL, h).build();
        }
        am.requestAudioFocus(focus);
    }

    private void registerNoisy() {
        if (noisyReg) return;
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisy, f);
        }
        noisyReg = true;
    }

    private void unregisterNoisy() {
        if (!noisyReg) return;
        noisyReg = false;
        try {
            unregisterReceiver(noisy);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ session + notification

    private static Bitmap art(Player.Track t) {
        try {
            if (t.akey == null) return null;
            File f = ArtCache.file(t.akey);
            return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void updateSession(Player.Track t, boolean playing) {
        int dur = duration();
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur > 0 ? dur : t.dur);
        Bitmap b = art(t);
        if (b != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, b);
        session.setMetadata(mb.build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_BUFFERING, position(), 1f)
                .build());
    }

    private PendingIntent act(String action, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action action(int icon, String label, String action, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, act(action, code)).build();
    }

    private Notification build(Player.Track t) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(t == null ? "mlib" : t.title)
                .setContentText(t == null ? "" : t.artist)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(action(R.drawable.ic_prev, "Previous", A_PREV, 1))
                .addAction(action(R.drawable.ic_stop, "Stop", A_STOP, 2))
                .addAction(action(R.drawable.ic_next, "Next", A_NEXT, 3))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        Bitmap art = t == null ? null : art(t);
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    private void ensureForeground(Player.Track t) {
        Notification n = build(t);
        if (!fg) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NID, n);
            }
            fg = true;
        } else {
            nm.notify(NID, n);
        }
    }
}
