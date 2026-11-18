package com.personal.pofocus;

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
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The heart of the app. It runs the Work/Break cycle in the foreground (so it keeps going
 * with the screen off) and owns the one and only music player.
 *
 * Music is connected to Android's media system through a MediaSession. That gives:
 * media controls in the notification shade / Samsung media panel, the lock screen,
 * and Bluetooth/headphone buttons.
 */
public class TimerService extends Service {

    private static final String TAG = "PoFocus";

    // ---- commands (sent by the screen and by notification buttons) ----
    static final String ACTION_START = "start";
    static final String ACTION_PAUSE = "pause";
    static final String ACTION_RESUME = "resume";
    static final String ACTION_SKIP = "skip";
    static final String ACTION_STOP = "stop";
    static final String ACTION_ADJUST = "adjust";               // extras: slot, delta (minutes)
    static final String ACTION_MUSIC_TOGGLE = "music_toggle";
    static final String ACTION_MUSIC_NEXT = "music_next";
    static final String ACTION_MUSIC_PREV = "music_prev";
    static final String ACTION_MUSIC_CHANGED = "music_changed"; // extra: slot
    static final String ACTION_PREVIEW = "preview";             // extra: slot
    static final String EXTRA_SLOT = "slot";
    static final String EXTRA_DELTA = "delta";

    private static final String CUSTOM_STOP_TIMER = "stop_timer";
    private static final String CH_TIMER = "timer";
    private static final String CH_MEDIA = "media";
    private static final int ID_TIMER = 1;
    private static final int ID_MEDIA = 2;

    private static final AudioAttributes MUSIC_ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    private static final long SESSION_ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
            | PlaybackState.ACTION_STOP;

    private static TimerService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationManager nm;

    // ---- timer state ----
    private boolean running;
    private boolean paused;
    private int phase = Settings.WORK;
    private int session = 1;
    private long endAt;            // elapsedRealtime when the current phase ends
    private long pausedRemaining;

    // ---- music state ----
    private int musicSlot = -1;    // whose music is loaded (Work/Break), -1 = none
    private List<Uri> tracks = new ArrayList<>();
    private int trackIdx;
    private MediaPlayer player;
    private boolean musicOn;       // music is wanted (false after the user pauses it)
    private boolean resumeAfterFocus;
    private String trackTitle = "";
    private String trackArtist = "";
    private Bitmap trackArt;

    private MediaSession mediaSession;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus;
    private boolean noisyRegistered;

    private ToneGenerator tone;
    private PowerManager.WakeLock wakeLock;

    // =====================================================================
    // Read-only state for the screen
    // =====================================================================
    static boolean isActive() { return instance != null && instance.running; }
    static boolean isPaused() { return instance != null && instance.paused; }
    static int getPhase() { return instance != null ? instance.phase : Settings.WORK; }
    static int getSession() { return instance != null ? instance.session : 1; }
    static long getRemainingMs() { return instance != null ? instance.remaining() : 0; }
    static boolean isMusicPlaying() { return instance != null && instance.playing(); }
    /** Whose music is loaded right now (also during a preview), or -1. */
    static int getMusicSlot() { return instance != null && instance.player != null ? instance.musicSlot : -1; }
    static String getTrackTitle() {
        return instance != null && instance.player != null ? instance.trackTitle : null;
    }

    /** Called when the app leaves the screen: a preview should not keep playing. */
    static void stopPreviewIfIdle() {
        if (instance != null && !instance.running) instance.shutdown();
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Settings.localized(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = getSystemService(NotificationManager.class);
        audio = getSystemService(AudioManager.class);

        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (RuntimeException e) {
            tone = null;
        }
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PoFocus:timer");
        wakeLock.setReferenceCounted(false);

        NotificationChannel timerCh = new NotificationChannel(CH_TIMER,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        timerCh.setShowBadge(false);
        timerCh.setSound(null, null);
        NotificationChannel mediaCh = new NotificationChannel(CH_MEDIA,
                getString(R.string.channel_media), NotificationManager.IMPORTANCE_LOW);
        mediaCh.setShowBadge(false);
        mediaCh.setSound(null, null);
        nm.createNotificationChannel(timerCh);
        nm.createNotificationChannel(mediaCh);

        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(MUSIC_ATTRS)
                .setOnAudioFocusChangeListener(this::onFocusChange, handler)
                .build();

        mediaSession = new MediaSession(this, "PoFocus");
        mediaSession.setSessionActivity(openAppIntent());
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (!playing()) toggleMusic(); }
            @Override public void onPause() { if (playing()) toggleMusic(); }
            @Override public void onSkipToNext() { nextTrack(); }
            @Override public void onSkipToPrevious() { prevTrack(); }
            @Override public void onSeekTo(long pos) {
                if (player != null) {
                    try { player.seekTo((int) pos); } catch (Exception ignored) {}
                    publishMusicState();
                }
            }
            @Override public void onStop() {
                if (running) { if (playing()) toggleMusic(); } else shutdown();
            }
            @Override public void onCustomAction(String action, Bundle extras) {
                if (CUSTOM_STOP_TIMER.equals(action)) shutdown();
            }
        }, handler);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        int slot = intent != null ? intent.getIntExtra(EXTRA_SLOT, -1) : -1;
        int delta = intent != null ? intent.getIntExtra(EXTRA_DELTA, 0) : 0;

        if (ACTION_START.equals(a)) startTimer();
        else if (ACTION_STOP.equals(a)) shutdown();
        else if (ACTION_PREVIEW.equals(a)) preview(slot);
        else if (ACTION_MUSIC_TOGGLE.equals(a)) toggleMusic();
        else if (ACTION_MUSIC_NEXT.equals(a)) nextTrack();
        else if (ACTION_MUSIC_PREV.equals(a)) prevTrack();
        else if (ACTION_MUSIC_CHANGED.equals(a)) musicChanged(slot);
        else if (running) {
            if (ACTION_PAUSE.equals(a)) pauseTimer();
            else if (ACTION_RESUME.equals(a)) resumeTimer();
            else if (ACTION_SKIP.equals(a)) nextPhase();
            else if (ACTION_ADJUST.equals(a)) adjust(slot, delta);
        }

        if (!running && player == null) stopSelf(); // nothing left to do
        return START_NOT_STICKY;
    }

    /** Swiping the app away from "recent apps" stops everything. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        abandonFocus();
        unregisterNoisy();
        releaseWakeLock();
        nm.cancel(ID_MEDIA);
        nm.cancel(ID_TIMER);
        mediaSession.release();
        if (tone != null) tone.release();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // =====================================================================
    // Timer
    // =====================================================================
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (running && !paused && SystemClock.elapsedRealtime() >= endAt) nextPhase();
            if (running) handler.postDelayed(this, 250);
        }
    };

    private final Runnable startMusicLater = () -> {
        if (running && !paused && musicOn) playTrack(0);
    };

    private long remaining() {
        if (!running) return 0;
        if (paused) return pausedRemaining;
        return Math.max(0, endAt - SystemClock.elapsedRealtime());
    }

    private void startTimer() {
        goForeground();
        if (running) return;
        stopMusic(); // ends a preview, if any
        running = true;
        session = 1;
        handler.removeCallbacks(tick);
        handler.post(tick);
        startPhase(Settings.WORK, false);
    }

    private void startPhase(int newPhase, boolean withBeep) {
        phase = newPhase;
        paused = false;
        stopMusic();
        loadMusic(phase);
        musicOn = !tracks.isEmpty();
        endAt = SystemClock.elapsedRealtime() + Settings.getMinutes(this, phase) * 60_000L;
        holdWakeLock();
        if (withBeep) {
            beep();
            handler.postDelayed(startMusicLater, 1600); // let the beep be heard first
        } else {
            startMusicLater.run();
        }
        updateTimerNotification();
    }

    private void nextPhase() {
        if (phase == Settings.BREAK) session++;
        startPhase(phase == Settings.WORK ? Settings.BREAK : Settings.WORK, true);
    }

    private void pauseTimer() {
        if (paused) return;
        pausedRemaining = remaining();
        paused = true;
        handler.removeCallbacks(startMusicLater);
        if (playing()) {
            player.pause();      // musicOn stays true, so music comes back on Resume
            publishMusicState();
        }
        releaseWakeLock();
        updateTimerNotification();
    }

    private void resumeTimer() {
        if (!paused) return;
        paused = false;
        endAt = SystemClock.elapsedRealtime() + pausedRemaining;
        holdWakeLock();
        if (musicOn && !playing()) resumeMusic();
        updateTimerNotification();
    }

    /** The user changed the minutes of a timer while it runs: move its end by the same amount. */
    private void adjust(int slot, int deltaMinutes) {
        if (slot != phase || deltaMinutes == 0) return;
        long d = deltaMinutes * 60_000L;
        if (paused) pausedRemaining = Math.max(0, pausedRemaining + d);
        else endAt += d;     // if this makes it run out, the tick moves on to the next timer
        updateTimerNotification();
    }

    /** Stop button, "Stop timer" in the media controls, or closing the app. */
    private void shutdown() {
        running = false;
        paused = false;
        handler.removeCallbacksAndMessages(null);
        stopMusic();
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        nm.cancel(ID_TIMER);
        stopSelf();
    }

    private void beep() {
        if (tone == null) return;
        for (int i = 0; i < 3; i++) {
            handler.postDelayed(() -> {
                try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 250); } catch (Exception ignored) {}
            }, i * 450L);
        }
    }

    private void holdWakeLock() { wakeLock.acquire(2 * 60 * 60 * 1000L); }

    private void releaseWakeLock() { if (wakeLock.isHeld()) wakeLock.release(); }

    // =====================================================================
    // Music
    // =====================================================================
    private boolean playing() {
        if (player == null) return false;
        try { return player.isPlaying(); } catch (Exception e) { return false; }
    }

    private void loadMusic(int slot) {
        musicSlot = slot;
        tracks = Settings.getTracks(this, slot);
        trackIdx = tracks.isEmpty() ? 0 : Settings.getTrackIndex(this, slot) % tracks.size();
    }

    /** The user chose new music (or removed it). Use it right away if it belongs to what's playing. */
    private void musicChanged(int slot) {
        if (running) {
            if (slot != phase) return;
            stopMusic();
            loadMusic(phase);
            musicOn = !tracks.isEmpty();
            if (musicOn && !paused) playTrack(0);
        } else if (slot == musicSlot) {
            stopMusic(); // a preview of the old music: just stop it
        }
    }

    /** ▶ on a card while no timer runs: try out the music. Pressing it again stops it. */
    private void preview(int slot) {
        if (running) return;
        if (player != null && musicSlot == slot) { stopMusic(); return; }
        stopMusic();
        loadMusic(slot);
        if (tracks.isEmpty()) return;
        musicOn = true;
        playTrack(0);
    }

    /** Play/pause for the music only (the timer keeps going). */
    private void toggleMusic() {
        if (playing()) {
            musicOn = false;
            player.pause();
            publishMusicState();
            return;
        }
        if (tracks.isEmpty() && running) loadMusic(phase);
        if (tracks.isEmpty()) return;
        musicOn = true;
        resumeMusic();
    }

    private void resumeMusic() {
        if (player == null) { playTrack(0); return; }
        if (!requestFocus()) return;
        try { player.start(); } catch (Exception e) { playTrack(0); return; }
        publishMusicState();
    }

    private void nextTrack() {
        if (tracks.isEmpty() && running) loadMusic(phase);
        if (tracks.isEmpty()) return;
        trackIdx = (trackIdx + 1) % tracks.size();
        musicOn = true;
        playTrack(0);
    }

    private void prevTrack() {
        if (tracks.isEmpty() && running) loadMusic(phase);
        if (tracks.isEmpty()) return;
        musicOn = true;
        if (player != null && safePosition() > 3000) {   // like other players: first back to the start
            player.seekTo(0);
            if (!playing()) resumeMusic(); else publishMusicState();
            return;
        }
        trackIdx = (trackIdx - 1 + tracks.size()) % tracks.size();
        playTrack(0);
    }

    /** Plays tracks[trackIdx]. Songs that can't be opened (deleted/moved) are skipped. */
    private void playTrack(int failures) {
        releasePlayer();
        if (tracks.isEmpty()) return;
        if (failures >= tracks.size()) {
            toast(R.string.cant_play_any);
            stopMusic();
            return;
        }
        if (!requestFocus()) return;
        Uri uri = tracks.get(trackIdx);
        Settings.setTrackIndex(this, musicSlot, trackIdx);
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(MUSIC_ATTRS);
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(this, uri);
            mp.setLooping(tracks.size() == 1);
            mp.setOnCompletionListener(m -> {
                trackIdx = (trackIdx + 1) % tracks.size();
                playTrack(0);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                Log.w(TAG, "Playback error " + what + "/" + extra + " for " + uri);
                handler.post(() -> {
                    trackIdx = (trackIdx + 1) % Math.max(1, tracks.size());
                    playTrack(1);
                });
                return true;
            });
            mp.prepare();
            player = mp;
            readTrackInfo(uri);
            mp.start();
            publishMusicState();
        } catch (Exception e) {
            Log.w(TAG, "Can't play " + uri, e);
            mp.release();
            player = null;
            if (failures == 0) toast(R.string.cant_play_skip);
            trackIdx = (trackIdx + 1) % tracks.size();
            playTrack(failures + 1);
        }
    }

    private long safePosition() {
        try { return player != null ? player.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
    }

    /** Music fully off: player gone, media controls removed. */
    private void stopMusic() {
        handler.removeCallbacks(startMusicLater);
        releasePlayer();
        abandonFocus();
        unregisterNoisy();
        resumeAfterFocus = false;
        musicSlot = -1;
        tracks = new ArrayList<>();
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_STOPPED, 0, 0f).build());
        mediaSession.setActive(false);
        nm.cancel(ID_MEDIA);
    }

    // ---- song title, artist and cover for the media controls ----
    private void readTrackInfo(Uri uri) {
        String title = null, artist = null;
        Bitmap art = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            byte[] pic = r.getEmbeddedPicture();
            if (pic != null) art = decodeScaled(pic, 512);
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        if (title == null || title.trim().isEmpty()) title = Playlists.displayName(this, uri, getString(R.string.selected_audio));
        if (artist == null || artist.trim().isEmpty()) artist = getString(R.string.app_name);
        trackTitle = title;
        trackArtist = artist;
        trackArt = art;

        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
        long dur = player != null ? player.getDuration() : 0;
        if (dur > 0) m.putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
        if (art != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        mediaSession.setMetadata(m.build());
    }

    private static Bitmap decodeScaled(byte[] data, int maxSize) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxSize && o.outHeight / (sample * 2) >= maxSize) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    /** Tells Android (media panel, lock screen, headphones) what the music is doing. */
    private void publishMusicState() {
        if (player == null) return;
        boolean isPlaying = playing();
        PlaybackState.Builder s = new PlaybackState.Builder()
                .setActions(SESSION_ACTIONS)
                .setState(isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        safePosition(), isPlaying ? 1f : 0f);
        if (running) {
            s.addCustomAction(new PlaybackState.CustomAction.Builder(
                    CUSTOM_STOP_TIMER, getString(R.string.stop_timer), R.drawable.ic_stop).build());
        }
        mediaSession.setPlaybackState(s.build());
        mediaSession.setActive(true);
        if (isPlaying) registerNoisy(); else unregisterNoisy();
        postMediaNotification(isPlaying);
    }

    private void postMediaNotification(boolean isPlaying) {
        Notification.Builder b = new Notification.Builder(this, CH_MEDIA)
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle(trackTitle)
                .setContentText(trackArtist)
                .setLargeIcon(trackArt)
                .setContentIntent(openAppIntent())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_prev),
                        getString(R.string.previous), action(ACTION_MUSIC_PREV, 10)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, isPlaying ? R.drawable.ic_pause : R.drawable.ic_play),
                        getString(isPlaying ? R.string.pause : R.string.play), action(ACTION_MUSIC_TOGGLE, 11)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_next),
                        getString(R.string.next), action(ACTION_MUSIC_NEXT, 12)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        nm.notify(ID_MEDIA, b.build());
    }

    // ---- audio focus: other apps, calls, alarms ----
    private boolean requestFocus() {
        if (hasFocus) return true;
        int r = audio.requestAudioFocus(focusRequest);
        hasFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (hasFocus) return true;
        // Only hold back during a phone/video call; otherwise play anyway.
        int mode = audio.getMode();
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
            toast(R.string.audio_busy);
            return false;
        }
        Log.w(TAG, "Audio focus not granted (" + r + "), playing anyway");
        return true;
    }

    private void abandonFocus() {
        if (hasFocus) audio.abandonAudioFocusRequest(focusRequest);
        hasFocus = false;
    }

    private void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:           // another app started playing
                hasFocus = false;
                if (playing()) toggleMusic();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: // e.g. a call
                if (playing()) {
                    player.pause();
                    resumeAfterFocus = true;
                    publishMusicState();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (player != null) player.setVolume(0.3f, 0.3f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                if (player != null) {
                    player.setVolume(1f, 1f);
                    if (resumeAfterFocus && musicOn && !(running && paused)) {
                        try { player.start(); } catch (Exception ignored) {}
                        publishMusicState();
                    }
                }
                resumeAfterFocus = false;
                break;
        }
    }

    // ---- headphones unplugged / Bluetooth disconnected: pause ----
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (playing()) toggleMusic();
        }
    };

    private void registerNoisy() {
        if (noisyRegistered) return;
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, f);
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        noisyRegistered = false;
    }

    // =====================================================================
    // Timer notification
    // =====================================================================
    private void goForeground() {
        Notification n = buildTimerNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_TIMER, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID_TIMER, n);
        }
    }

    private void updateTimerNotification() {
        if (running) nm.notify(ID_TIMER, buildTimerNotification());
        if (player != null) publishMusicState(); // keeps the "Stop timer" button in sync
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, TimerService.class).setAction(a);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildTimerNotification() {
        Icon icon = Icon.createWithResource(this, R.drawable.ic_notif);
        Notification.Builder b = new Notification.Builder(this, CH_TIMER)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(phase == Settings.WORK ? R.string.work : R.string.brk))
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
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

    private void toast(int resId) {
        handler.post(() -> Toast.makeText(this, resId, Toast.LENGTH_LONG).show());
    }
}
