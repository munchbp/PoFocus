package com.personal.studytimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.List;

/**
 * Runs the Work/Break cycle in the foreground so it keeps going with the screen off.
 * Work -> beep -> Break -> beep -> Work ... until the user presses Stop.
 */
public class TimerService extends Service {

    static final String ACTION_START = "start";
    static final String ACTION_PAUSE = "pause";
    static final String ACTION_RESUME = "resume";
    static final String ACTION_SKIP = "skip";
    static final String ACTION_STOP = "stop";
    static final String ACTION_TOGGLE_MEDIA = "media";
    static final String ACTION_NEXT_TRACK = "next_track";

    private static final String CHANNEL = "timer";
    private static final int NOTIF_ID = 1;

    private static TimerService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private boolean paused;
    private int phase = Settings.WORK;
    private int session = 1;
    private long endAt;          // elapsedRealtime when the current phase ends
    private long pausedRemaining;
    private boolean mediaMuted;  // user pressed "stop music" for this phase
    private MediaPlayer player;
    private List<Uri> tracks;     // songs for the current phase
    private int trackIdx;
    private ToneGenerator tone;
    private PowerManager.WakeLock wakeLock;

    // ---- read-only state for the screen ----
    static boolean isActive() { return instance != null && instance.running; }
    static boolean isPaused() { return instance != null && instance.paused; }
    static int getPhase() { return instance != null ? instance.phase : Settings.WORK; }
    static int getSession() { return instance != null ? instance.session : 1; }
    static long getRemainingMs() { return instance != null ? instance.remaining() : 0; }
    static boolean isMediaPlaying() {
        if (instance == null || instance.player == null) return false;
        try { return instance.player.isPlaying(); } catch (Exception e) { return false; }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (running && !paused && SystemClock.elapsedRealtime() >= endAt) {
                nextPhase();
            }
            handler.postDelayed(this, 250);
        }
    };

    private final Runnable startMediaLater = this::startMedia;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Settings.localized(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (RuntimeException e) {
            tone = null;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StudyTimer:timer");
        wakeLock.setReferenceCounted(false);

        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);

        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_START.equals(action)) {
            goForeground();
            if (!running) {
                running = true;
                session = 1;
                startPhase(Settings.WORK, false);
            }
            return START_NOT_STICKY;
        }

        if (!running) { // nothing to control
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_PAUSE.equals(action)) pause();
        else if (ACTION_RESUME.equals(action)) resume();
        else if (ACTION_SKIP.equals(action)) nextPhase();
        else if (ACTION_STOP.equals(action)) stopAll();
        else if (ACTION_TOGGLE_MEDIA.equals(action)) toggleMedia();
        else if (ACTION_NEXT_TRACK.equals(action)) skipTrack();
        return START_NOT_STICKY;
    }

    // ---------------- timer logic ----------------

    private long remaining() {
        if (!running) return 0;
        if (paused) return pausedRemaining;
        return Math.max(0, endAt - SystemClock.elapsedRealtime());
    }

    private void startPhase(int newPhase, boolean withBeep) {
        phase = newPhase;
        paused = false;
        mediaMuted = false;
        handler.removeCallbacks(startMediaLater);
        releasePlayer();
        tracks = null;
        long duration = Settings.getMinutes(this, phase) * 60_000L;
        endAt = SystemClock.elapsedRealtime() + duration;
        holdWakeLock();
        if (withBeep) {
            beep();
            handler.postDelayed(startMediaLater, 1600); // let the beep be heard first
        } else {
            startMedia();
        }
        updateNotification();
    }

    private void nextPhase() {
        if (phase == Settings.BREAK) session++;
        startPhase(phase == Settings.WORK ? Settings.BREAK : Settings.WORK, true);
    }

    private void pause() {
        if (paused) return;
        pausedRemaining = remaining();
        paused = true;
        handler.removeCallbacks(startMediaLater);
        if (player != null) {
            try { if (player.isPlaying()) player.pause(); } catch (Exception ignored) {}
        }
        releaseWakeLock();
        updateNotification();
    }

    private void resume() {
        if (!paused) return;
        paused = false;
        endAt = SystemClock.elapsedRealtime() + pausedRemaining;
        holdWakeLock();
        if (!mediaMuted) {
            if (player != null) {
                try { player.start(); } catch (Exception ignored) {}
            } else {
                startMedia();
            }
        }
        updateNotification();
    }

    private void stopAll() {
        running = false;
        paused = false;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ---------------- sound ----------------

    private void beep() {
        if (tone == null) return;
        for (int i = 0; i < 3; i++) {
            handler.postDelayed(() -> {
                try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 250); } catch (Exception ignored) {}
            }, i * 450L);
        }
    }

    /** Plays the music chosen for the current phase. If none was chosen, stays silent. */
    private void startMedia() {
        if (!running || paused || mediaMuted || player != null) return;
        if (!loadTracks()) return;
        playTrack(0);
    }

    private boolean loadTracks() {
        if (tracks == null) {
            tracks = Settings.getTracks(this, phase);
            if (!tracks.isEmpty()) trackIdx = Settings.getTrackIndex(this, phase) % tracks.size();
        }
        return !tracks.isEmpty();
    }

    /** Plays tracks[trackIdx]; songs that can't be opened (deleted/moved) are skipped. */
    private void playTrack(int failures) {
        releasePlayer();
        if (tracks == null || tracks.isEmpty() || failures >= tracks.size()) return;
        Settings.setTrackIndex(this, phase, trackIdx);
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(this, tracks.get(trackIdx));
            mp.setLooping(tracks.size() == 1);
            mp.setOnCompletionListener(m -> advanceTrack());
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            releasePlayer();
            trackIdx = (trackIdx + 1) % tracks.size();
            playTrack(failures + 1);
        }
    }

    /** Song finished: go to the next one (back to the first after the last). */
    private void advanceTrack() {
        if (tracks == null || tracks.isEmpty()) return;
        trackIdx = (trackIdx + 1) % tracks.size();
        if (running && !paused && !mediaMuted) {
            playTrack(0);
        } else {
            releasePlayer();
            Settings.setTrackIndex(this, phase, trackIdx);
        }
    }

    /** "Next song" button. */
    private void skipTrack() {
        if (!loadTracks()) return;
        handler.removeCallbacks(startMediaLater);
        mediaMuted = false;
        advanceTrack();
    }

    private void toggleMedia() {
        if (isMediaPlaying()) {
            try { player.pause(); } catch (Exception ignored) {}
            mediaMuted = true;
        } else {
            mediaMuted = false;
            if (paused) return;
            if (player != null) {
                try { player.start(); } catch (Exception ignored) {}
            } else {
                handler.removeCallbacks(startMediaLater);
                startMedia();
            }
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
    }

    private void holdWakeLock() {
        // Re-acquired every phase; the timeout is just a safety net.
        wakeLock.acquire(2 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock.isHeld()) wakeLock.release();
    }

    // ---------------- notification ----------------

    private void goForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification() {
        if (!running) return;
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, TimerService.class).setAction(a);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Icon icon = Icon.createWithResource(this, R.drawable.ic_notif);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(phase == Settings.WORK ? R.string.work : R.string.brk))
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(phase == Settings.WORK ? 0xFFE8553E : 0xFF3FB28A);

        long rem = remaining();
        if (paused) {
            b.setContentText(getString(R.string.paused_left, MainActivity.format(rem)));
            b.setShowWhen(false);
            b.addAction(new Notification.Action.Builder(icon, getString(R.string.resume), action(ACTION_RESUME, 1)).build());
        } else {
            b.setContentText(getString(R.string.session, session));
            b.setShowWhen(true);
            b.setUsesChronometer(true);
            b.setChronometerCountDown(true);
            b.setWhen(System.currentTimeMillis() + rem);
            b.addAction(new Notification.Action.Builder(icon, getString(R.string.pause), action(ACTION_PAUSE, 2)).build());
        }
        b.addAction(new Notification.Action.Builder(icon, getString(R.string.stop), action(ACTION_STOP, 3)).build());
        return b.build();
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        releaseWakeLock();
        if (tone != null) tone.release();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
