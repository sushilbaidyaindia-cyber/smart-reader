package com.sushilbaidyaindia.smartreader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Runs the PDF reading in the background, so it goes on when the screen is off or the app is left. */
public class ImportService extends Service {

    static final String A_CANCEL = "com.sushilbaidyaindia.smartreader.IMPORT_CANCEL";
    private static final String CHANNEL = "reader_import";
    private static final int ID = 21;
    private static final int ID_DONE = 22;

    private PowerManager.WakeLock wake;
    private volatile boolean alive = false;

    static void start(Context c) {
        Intent i = new Intent(c, ImportService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "PDF পড়া", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification build(String text, int max, int now, boolean ongoing) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("PDF পড়া হচ্ছে")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (ongoing) {
            b.setProgress(Math.max(1, max), now, false);
            Intent cancel = new Intent(this, ImportService.class).setAction(A_CANCEL);
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "বন্ধ করুন",
                    PendingIntent.getService(this, 5, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && A_CANCEL.equals(intent.getAction())) {
            Importer.cancel = true;
            return START_NOT_STICKY;
        }
        if (alive) return START_NOT_STICKY;
        alive = true;
        Notification n = build("শুরু হচ্ছে…", 1, 0, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID, n);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smartreader:import");
            wake.acquire(4 * 60 * 60 * 1000L);
        } catch (Throwable ignored) { }
        final Context app = getApplicationContext();
        final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        new Thread(() -> {
            Thread ticker = new Thread(() -> {
                while (alive) {
                    try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                    if (Importer.running) nm.notify(ID, build(Importer.status, Importer.total, Importer.done, true));
                }
            });
            ticker.start();
            try {
                Importer.run(app);
            } finally {
                alive = false;
                ticker.interrupt();
                try { if (wake != null && wake.isHeld()) wake.release(); } catch (Throwable ignored) { }
                stopForeground(true);
                nm.notify(ID_DONE, build(Importer.status, 1, 1, false));
                stopSelf();
            }
        }).start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
