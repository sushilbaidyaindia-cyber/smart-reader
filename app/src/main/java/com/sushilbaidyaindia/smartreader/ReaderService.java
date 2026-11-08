package com.sushilbaidyaindia.smartreader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/** Keeps the reading going with the screen off, with a notification and lock-screen buttons. */
public class ReaderService extends Service {

    static final String A_TOGGLE = "com.sushilbaidyaindia.smartreader.TOGGLE";
    static final String A_NEXT = "com.sushilbaidyaindia.smartreader.NEXT";
    static final String A_PREV = "com.sushilbaidyaindia.smartreader.PREV";
    static final String A_STOP = "com.sushilbaidyaindia.smartreader.STOP";
    private static final String CHANNEL = "reader_playback";
    private static final int ID = 11;

    private static ReaderService inst;
    private MediaSession session;
    private boolean foreground = false;

    static void start(Context c) {
        Intent i = new Intent(c, ReaderService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) { }
    }

    static void refresh() {
        ReaderService s = inst;
        if (s != null) s.update();
    }

    static void stop() {
        ReaderService s = inst;
        if (s != null) {
            s.foreground = false;
            s.stopForeground(true);
            s.stopSelf();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "পড়া চলছে", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("পড়ার সময় প্লে, পজ আর পরের-আগের বাটন");
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        session = new MediaSession(this, "SmartReader");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { Player.I.play(); }

            @Override public void onPause() { Player.I.pause(); }

            @Override public void onSkipToNext() { Player.I.nextSentence(); }

            @Override public void onSkipToPrevious() { Player.I.prevSentence(); }

            @Override public void onStop() {
                Player.I.pause();
                ReaderService.stop();
            }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        Player.I.init(this);
        if (A_TOGGLE.equals(a)) Player.I.toggle();
        else if (A_NEXT.equals(a)) Player.I.nextSentence();
        else if (A_PREV.equals(a)) Player.I.prevSentence();
        else if (A_STOP.equals(a)) {
            Player.I.pause();
            stop();
            return START_NOT_STICKY;
        }
        goForeground();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        inst = null;
        try { session.setActive(false); session.release(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, ReaderService.class).setAction(a);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification build() {
        Player p = Player.I;
        boolean playing = p.playing;
        String title = p.doc == null ? "স্মার্ট রিডার" : p.doc.title;
        String sub = p.doc == null ? "" : "পেজ " + BanglaText.digits(p.page) + "/" + BanglaText.digits(p.doc.pages)
                + " · বাক্য " + BanglaText.digits(p.sents.isEmpty() ? 0 : p.idx + 1) + "/" + BanglaText.digits(p.sents.size());

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentTitle(title)
                .setContentText(sub)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "আগের বাক্য", action(A_PREV, 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "পজ" : "প্লে", action(A_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, "পরের বাক্য", action(A_NEXT, 3))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "বন্ধ", action(A_STOP, 4))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private void goForeground() {
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ID, n);
            foreground = true;
        } catch (Throwable ignored) { }
        syncSession();
    }

    private void syncSession() {
        Player p = Player.I;
        try {
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, p.doc == null ? "স্মার্ট রিডার" : p.doc.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, p.doc == null ? "" : "পেজ " + BanglaText.digits(p.page))
                    .build());
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                    .setState(p.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, p.playing ? 1f : 0f)
                    .build());
        } catch (Throwable ignored) { }
    }

    private void update() {
        Player p = Player.I;
        if (p.playing && !foreground) {
            goForeground();
            return;
        }
        if (!p.playing && foreground) {
            foreground = false;
            stopForeground(false);      // the notification stays, so it can be used to start again
        }
        try {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(ID, build());
        } catch (Throwable ignored) { }
        syncSession();
    }
}
