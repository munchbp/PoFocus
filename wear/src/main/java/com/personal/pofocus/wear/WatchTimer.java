package com.personal.pofocus.wear;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.Locale;

/**
 * The watch timer. To save battery nothing runs in the background: the state is stored,
 * and an exact alarm wakes the watch when a Work/Break period ends.
 */
final class WatchTimer {
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL = "timer";

    private WatchTimer() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("timer", Context.MODE_PRIVATE);
    }

    private static long now() { return SystemClock.elapsedRealtime(); }

    // ---------------- state ----------------
    static boolean isRunning(Context c) { return p(c).getBoolean("running", false); }
    static boolean isPaused(Context c) { return p(c).getBoolean("paused", false); }
    static int phase(Context c) { return p(c).getInt("phase", Settings.WORK); }
    static int session(Context c) { return p(c).getInt("session", 1); }

    static long remaining(Context c) {
        if (!isRunning(c)) return 0;
        if (isPaused(c)) return p(c).getLong("remaining", 0);
        return Math.max(0, p(c).getLong("endAt", 0) - now());
    }

    static String format(long ms) {
        long s = (ms + 999) / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    // ---------------- controls ----------------
    static void start(Context c) {
        p(c).edit().putBoolean("running", true).putInt("session", 1).commit();
        beginPhase(c, Settings.WORK);
    }

    static void pause(Context c) {
        if (!isRunning(c) || isPaused(c)) return;
        long rem = remaining(c);
        p(c).edit().putBoolean("paused", true).putLong("remaining", rem).commit();
        cancelAlarm(c);
        showNotification(c);
    }

    static void resume(Context c) {
        if (!isRunning(c) || !isPaused(c)) return;
        long end = now() + p(c).getLong("remaining", 0);
        p(c).edit().putBoolean("paused", false).putLong("endAt", end).commit();
        schedule(c, end);
        showNotification(c);
    }

    static void skip(Context c) {
        if (isRunning(c)) next(c, null);
    }

    static void stop(Context c) {
        p(c).edit().putBoolean("running", false).putBoolean("paused", false).commit();
        cancelAlarm(c);
        c.getSystemService(NotificationManager.class).cancel(NOTIF_ID);
    }

    /** − / + on the running timer: make it shorter/longer right away. */
    static void adjust(Context c, int slot, int deltaMinutes) {
        if (!isRunning(c) || slot != phase(c) || deltaMinutes == 0) return;
        long d = deltaMinutes * 60_000L;
        if (isPaused(c)) {
            p(c).edit().putLong("remaining", Math.max(0, p(c).getLong("remaining", 0) + d)).commit();
        } else {
            long end = p(c).getLong("endAt", 0) + d;
            p(c).edit().putLong("endAt", end).commit();
            if (end <= now()) { next(c, null); return; }
            schedule(c, end);
        }
        showNotification(c);
    }

    /** The alarm went off. {@code done} must be called when finished (keeps the receiver alive). */
    static void onAlarm(Context c, Runnable done) {
        if (!isRunning(c) || isPaused(c)) { done.run(); return; }
        long end = p(c).getLong("endAt", 0);
        if (now() < end - 1500) {    // fired early (e.g. the time was made longer): wait again
            schedule(c, end);
            done.run();
            return;
        }
        next(c, done);
    }

    private static void next(Context c, Runnable done) {
        int ph = phase(c);
        if (ph == Settings.BREAK) p(c).edit().putInt("session", session(c) + 1).commit();
        alert(c, done);
        beginPhase(c, ph == Settings.WORK ? Settings.BREAK : Settings.WORK);
    }

    private static void beginPhase(Context c, int ph) {
        long end = now() + Settings.getMinutes(c, ph) * 60_000L;
        p(c).edit().putInt("phase", ph).putBoolean("paused", false).putLong("endAt", end).commit();
        schedule(c, end);
        showNotification(c);
    }

    // ---------------- alarm ----------------
    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, TimerReceiver.class).setAction(TimerReceiver.ACTION_ALARM);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void schedule(Context c, long endElapsed) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = alarmIntent(c);
        boolean exactAllowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exactAllowed) {
            // An "alarm clock" alarm is exact and not delayed by battery saving.
            long wall = System.currentTimeMillis() + (endElapsed - now());
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(wall, openAppIntent(c)), pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, endElapsed, pi);
        }
    }

    private static void cancelAlarm(Context c) {
        c.getSystemService(AlarmManager.class).cancel(alarmIntent(c));
    }

    // ---------------- vibration + beep ----------------
    private static void alert(Context c, Runnable done) {
        try {
            Vibrator v = c.getSystemService(Vibrator.class);
            VibrationEffect e = VibrationEffect.createWaveform(new long[]{0, 400, 200, 400, 200, 400}, -1);
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
            } else {
                v.vibrate(e, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            }
        } catch (Exception ignored) {}

        Handler h = new Handler(Looper.getMainLooper());
        ToneGenerator tone = null;
        try { tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100); } catch (RuntimeException ignored) {}
        final ToneGenerator t = tone;
        if (t != null) {
            for (int i = 0; i < 3; i++) {
                h.postDelayed(() -> {
                    try { t.startTone(ToneGenerator.TONE_PROP_BEEP, 250); } catch (Exception ignored) {}
                }, i * 450L);
            }
        }
        h.postDelayed(() -> {
            if (t != null) t.release();
            if (done != null) done.run();
        }, 1700);
    }

    // ---------------- notification ----------------
    static PendingIntent openAppIntent(Context c) {
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent action(Context c, String a, int code) {
        Intent i = new Intent(c, TimerReceiver.class).setAction(a);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void showNotification(Context c) {
        if (!isRunning(c)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);

        int ph = phase(c);
        boolean paused = isPaused(c);
        long rem = remaining(c);
        Icon icon = Icon.createWithResource(c, R.drawable.ic_timer);
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle(c.getString(ph == Settings.WORK ? R.string.work : R.string.brk))
                .setContentIntent(openAppIntent(c))
                .setOnlyAlertOnce(true)
                .setColor(ph == Settings.WORK ? 0xFFE8553E : 0xFF3FB28A);
        if (paused) {
            b.setContentText(c.getString(R.string.paused_left, format(rem)));
            b.addAction(new Notification.Action.Builder(icon, c.getString(R.string.resume),
                    action(c, TimerReceiver.ACTION_RESUME, 1)).build());
        } else {
            b.setContentText(c.getString(R.string.session, session(c)));
            b.setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                    .setWhen(System.currentTimeMillis() + rem);
            b.addAction(new Notification.Action.Builder(icon, c.getString(R.string.pause),
                    action(c, TimerReceiver.ACTION_PAUSE, 2)).build());
        }
        b.addAction(new Notification.Action.Builder(icon, c.getString(R.string.stop),
                action(c, TimerReceiver.ACTION_STOP, 3)).build());
        nm.notify(NOTIF_ID, b.build());
    }
}
