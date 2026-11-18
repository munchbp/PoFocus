package com.personal.pofocus;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int COLOR_WORK = 0xFFE8553E;
    private static final int COLOR_BREAK = 0xFF3FB28A;
    // request codes: +slot (0 = Work, 1 = Break)
    private static final int REQ_SONGS = 10, REQ_M3U = 20, REQ_PERMISSION = 30, REQ_NOTIF = 40;

    private TextView phaseText, timeText, sessionText, nowPlaying, notifWarning;
    private Button startBtn, skipBtn, stopBtn, musicBtn, nextSongBtn, langBtn;
    private View musicRow;
    private final Card[] cards = new Card[2];

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable afterPermission;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 250);
        }
    };

    /** Views of one timer card (Work or Break). */
    private static class Card {
        View root;
        TextView minutes, mediaName;
        Button minus, plus, pick, clear, play;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Settings.localized(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        phaseText = findViewById(R.id.phaseText);
        timeText = findViewById(R.id.timeText);
        sessionText = findViewById(R.id.sessionText);
        startBtn = findViewById(R.id.startBtn);
        skipBtn = findViewById(R.id.skipBtn);
        stopBtn = findViewById(R.id.stopBtn);
        musicBtn = findViewById(R.id.musicBtn);
        nextSongBtn = findViewById(R.id.nextSongBtn);
        musicRow = findViewById(R.id.musicRow);
        nowPlaying = findViewById(R.id.nowPlaying);
        notifWarning = findViewById(R.id.notifWarning);
        langBtn = findViewById(R.id.langBtn);

        skipBtn.setText(R.string.skip);
        stopBtn.setText(R.string.stop);
        notifWarning.setText(R.string.notif_off);
        langBtn.setText("🌐 " + getString(R.string.lang_code));
        langBtn.setOnClickListener(v -> chooseLanguage());
        notifWarning.setOnClickListener(v -> openNotificationSettings());

        cards[Settings.WORK] = setupCard(R.id.workCard, Settings.WORK, getString(R.string.work));
        cards[Settings.BREAK] = setupCard(R.id.breakCard, Settings.BREAK, getString(R.string.brk));

        startBtn.setOnClickListener(v -> {
            if (!TimerService.isActive()) {
                Intent i = new Intent(this, TimerService.class).setAction(TimerService.ACTION_START);
                startForegroundService(i);
            } else {
                send(TimerService.isPaused() ? TimerService.ACTION_RESUME : TimerService.ACTION_PAUSE);
            }
            handler.postDelayed(this::render, 100);
        });
        skipBtn.setOnClickListener(v -> send(TimerService.ACTION_SKIP));
        stopBtn.setOnClickListener(v -> { send(TimerService.ACTION_STOP); handler.postDelayed(this::render, 100); });
        musicBtn.setOnClickListener(v -> send(TimerService.ACTION_MUSIC_TOGGLE));
        nextSongBtn.setOnClickListener(v -> send(TimerService.ACTION_MUSIC_NEXT));

        // Keep the layout a comfortable width on the tablet.
        View content = findViewById(R.id.content);
        int maxPx = (int) (560 * getResources().getDisplayMetrics().density);
        ViewGroup.LayoutParams lp = content.getLayoutParams();
        if (getResources().getDisplayMetrics().widthPixels > maxPx) {
            lp.width = maxPx;
            content.setLayoutParams(lp);
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private Card setupCard(int id, int slot, String title) {
        Card c = new Card();
        c.root = findViewById(id);
        TextView t = c.root.findViewById(R.id.cardTitle);
        t.setText(title);
        t.setTextColor(slot == Settings.WORK ? COLOR_WORK : COLOR_BREAK);
        c.minutes = c.root.findViewById(R.id.minutes);
        c.mediaName = c.root.findViewById(R.id.mediaName);
        c.minus = c.root.findViewById(R.id.minus);
        c.plus = c.root.findViewById(R.id.plus);
        c.pick = c.root.findViewById(R.id.pick);
        c.clear = c.root.findViewById(R.id.clear);
        c.play = c.root.findViewById(R.id.play);

        c.minus.setOnClickListener(v -> changeMinutes(slot, -Settings.STEP));
        c.plus.setOnClickListener(v -> changeMinutes(slot, Settings.STEP));
        c.pick.setOnClickListener(v -> chooseMusic(slot));
        c.clear.setOnClickListener(v -> {
            Settings.clearMedia(this, slot);
            musicChanged(slot);
        });
        c.play.setOnClickListener(v -> send(TimerService.ACTION_PREVIEW, slot, 0));
        return c;
    }

    /** Works before and during a session: a running timer gets longer/shorter right away. */
    private void changeMinutes(int slot, int delta) {
        int before = Settings.getMinutes(this, slot);
        Settings.setMinutes(this, slot, before + delta);
        int change = Settings.getMinutes(this, slot) - before;
        if (change != 0 && TimerService.isActive()) send(TimerService.ACTION_ADJUST, slot, change);
        render();
    }

    private void musicChanged(int slot) {
        if (TimerService.isActive() || TimerService.getMusicSlot() == slot) {
            send(TimerService.ACTION_MUSIC_CHANGED, slot, 0);
        }
        render();
    }

    private void send(String action) {
        send(action, -1, 0);
    }

    private void send(String action, int slot, int delta) {
        Intent i = new Intent(this, TimerService.class).setAction(action)
                .putExtra(TimerService.EXTRA_SLOT, slot)
                .putExtra(TimerService.EXTRA_DELTA, delta);
        try {
            startService(i);
        } catch (Exception ignored) {}
        handler.postDelayed(this::render, 150);
    }

    // ---------------- drawing the screen ----------------

    static String format(long ms) {
        long s = (ms + 999) / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private void render() {
        boolean active = TimerService.isActive();
        boolean paused = TimerService.isPaused();
        boolean musicPlaying = TimerService.isMusicPlaying();
        int phase = active ? TimerService.getPhase() : Settings.WORK;
        int color = phase == Settings.WORK ? COLOR_WORK : COLOR_BREAK;

        phaseText.setText(phase == Settings.WORK ? R.string.work_caps : R.string.break_caps);
        phaseText.setTextColor(color);
        timeText.setTextColor(active && paused ? 0xFF8A8A90 : 0xFFFFFFFF);

        if (active) {
            timeText.setText(format(TimerService.getRemainingMs()));
            sessionText.setText(paused ? getString(R.string.paused) : getString(R.string.session, TimerService.getSession()));
            startBtn.setText(paused ? R.string.resume : R.string.pause);
        } else {
            timeText.setText(format(Settings.getMinutes(this, Settings.WORK) * 60_000L));
            sessionText.setText(R.string.ready);
            startBtn.setText(R.string.start);
        }
        startBtn.setBackgroundTintList(ColorStateList.valueOf(color));
        skipBtn.setVisibility(active ? View.VISIBLE : View.GONE);
        stopBtn.setVisibility(active ? View.VISIBLE : View.GONE);

        if (active && Settings.hasMedia(this, phase)) {
            musicRow.setVisibility(View.VISIBLE);
            musicBtn.setText(musicPlaying ? R.string.stop_music : R.string.play_music);
            nextSongBtn.setVisibility(Settings.getTrackCount(this, phase) > 1 ? View.VISIBLE : View.GONE);
        } else {
            musicRow.setVisibility(View.GONE);
        }
        String title = TimerService.getTrackTitle();
        if (title != null) {
            nowPlaying.setVisibility(View.VISIBLE);
            nowPlaying.setText("♪  " + title);
            nowPlaying.setAlpha(musicPlaying ? 1f : 0.5f);
        } else {
            nowPlaying.setVisibility(View.GONE);
        }

        int previewSlot = !active && musicPlaying ? TimerService.getMusicSlot() : -1;
        for (int slot = 0; slot < 2; slot++) {
            Card c = cards[slot];
            int min = Settings.getMinutes(this, slot);
            c.minutes.setText(getString(R.string.minutes, min));
            c.minus.setEnabled(min > Settings.MIN);
            c.minus.setAlpha(min > Settings.MIN ? 1f : 0.3f);
            c.plus.setEnabled(min < Settings.MAX);
            c.plus.setAlpha(min < Settings.MAX ? 1f : 0.3f);

            String name = Settings.getMediaName(this, slot);
            boolean has = Settings.hasMedia(this, slot);
            int count = Settings.getTrackCount(this, slot);
            String songs = getString(R.string.songs_count, count);
            c.mediaName.setText(!has ? getString(R.string.no_music)
                    : count > 1 ? "♪  " + (name == null || name.isEmpty() ? "" : name + "  ·  ") + songs
                    : "♪  " + name);
            c.mediaName.setAlpha(has ? 1f : 0.5f);
            c.pick.setText(has ? R.string.change : R.string.choose_music);
            c.clear.setVisibility(has ? View.VISIBLE : View.GONE);
            c.play.setVisibility(has && !active ? View.VISIBLE : View.GONE);
            c.play.setText(previewSlot == slot ? "■" : "▶");

            // Highlight the card of the timer that is running.
            c.root.setAlpha(!active || slot == phase ? 1f : 0.55f);
        }
    }

    // ---------------- choosing music ----------------

    /** Small menu: songs, a playlist file, or a playlist saved on the phone. */
    private void chooseMusic(int slot) {
        String[] options = {
                getString(R.string.opt_songs),
                getString(R.string.opt_m3u),
                getString(R.string.opt_phone),
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(slot == Settings.WORK ? R.string.music_for_work : R.string.music_for_break)
                .setItems(options, (d, which) -> {
                    if (which == 0) pickSongs(slot);
                    else if (which == 1) withAudioPermission(() -> pickM3u(slot));
                    else withAudioPermission(() -> pickPhonePlaylist(slot));
                })
                .show();
    }

    private void pickSongs(int slot) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        launch(i, REQ_SONGS + slot);
    }

    private void pickM3u(int slot) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*"); // playlist files have inconsistent types, so show all files
        launch(i, REQ_M3U + slot);
    }

    private void launch(Intent i, int code) {
        try {
            startActivityForResult(i, code);
        } catch (Exception e) {
            toast(getString(R.string.no_picker));
        }
    }

    private void pickPhonePlaylist(int slot) {
        List<Playlists.PhonePlaylist> lists = Playlists.phonePlaylists(this);
        if (lists.isEmpty()) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.no_playlists_title)
                    .setMessage(R.string.no_playlists_msg)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        String[] names = new String[lists.size()];
        for (int n = 0; n < names.length; n++) names[n] = lists.get(n).name;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.choose_playlist)
                .setItems(names, (d, which) -> {
                    Playlists.PhonePlaylist pl = lists.get(which);
                    Playlists.Result r = Playlists.phonePlaylistTracks(this, pl.id);
                    savePlaylist(slot, r, pl.name);
                })
                .show();
    }

    private void savePlaylist(int slot, Playlists.Result r, String name) {
        if (r.tracks.isEmpty()) {
            toast(getString(R.string.none_found));
            return;
        }
        Settings.setMedia(this, slot, r.tracks, name);
        if (r.listed > r.tracks.size()) {
            toast(getString(R.string.added_some, r.tracks.size(), r.listed));
        } else {
            toast(getString(R.string.added_all, r.tracks.size()));
        }
        musicChanged(slot);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_SONGS || requestCode == REQ_SONGS + 1) {
            int slot = requestCode - REQ_SONGS;
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int n = 0; n < data.getClipData().getItemCount(); n++) {
                    uris.add(data.getClipData().getItemAt(n).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            for (Uri u : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
            }
            String name = uris.size() == 1
                    ? Playlists.displayName(this, uris.get(0), getString(R.string.selected_audio)) : "";
            Settings.setMedia(this, slot, uris, name);
            musicChanged(slot);

        } else if ((requestCode == REQ_M3U || requestCode == REQ_M3U + 1) && data.getData() != null) {
            int slot = requestCode - REQ_M3U;
            Uri file = data.getData();
            String name = Playlists.displayName(this, file, getString(R.string.playlist));
            new Thread(() -> {
                Playlists.Result r;
                try {
                    r = Playlists.readM3u(this, file);
                } catch (Exception e) {
                    r = null;
                }
                Playlists.Result result = r;
                runOnUiThread(() -> {
                    if (result == null || result.listed == 0) {
                        toast(getString(R.string.not_playlist));
                    } else {
                        savePlaylist(slot, result, name);
                    }
                });
            }).start();
        }
    }

    // Reading playlists needs the "Music and audio" permission to find the songs.
    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void withAudioPermission(Runnable action) {
        if (checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED) {
            action.run();
        } else {
            afterPermission = action;
            requestPermissions(new String[]{audioPermission()}, REQ_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIF) { updateNotifWarning(); return; }
        if (requestCode != REQ_PERMISSION) return;
        Runnable action = afterPermission;
        afterPermission = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (action != null) action.run();
        } else {
            toast(getString(R.string.need_permission));
        }
    }

    // ---------------- notifications ----------------

    private void updateNotifWarning() {
        boolean on = getSystemService(NotificationManager.class).areNotificationsEnabled();
        notifWarning.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private void openNotificationSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (Exception ignored) {}
    }

    // ---------------- language ----------------

    /** Language menu: phone language, English or German. The screen reloads in the new language. */
    private void chooseLanguage() {
        String[] codes = {"", "en", "de"};
        String[] names = {getString(R.string.lang_system), "English", "Deutsch"};
        int current = Arrays.asList(codes).indexOf(Settings.getLanguage(this));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.language)
                .setSingleChoiceItems(names, current, (d, which) -> {
                    d.dismiss();
                    if (codes[which].equals(Settings.getLanguage(this))) return;
                    Settings.setLanguage(this, codes[which]);
                    recreate();
                })
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ---------------- lifecycle ----------------

    @Override
    protected void onResume() {
        super.onResume();
        updateNotifWarning();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the app (or opening the file picker) ends a music preview.
        // A running timer and its music keep going.
        TimerService.stopPreviewIfIdle();
    }
}
