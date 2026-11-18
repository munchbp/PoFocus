package com.personal.pofocus.wear;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

/** One scrolling screen: the timer on top, Work/Break minutes below. */
public class MainActivity extends Activity {

    private static final int COLOR_WORK = 0xFFE8553E;
    private static final int COLOR_BREAK = 0xFF3FB28A;

    private ScrollView scroll;
    private TextView phaseText, timeText, sessionText;
    private ImageButton startBtn, skipBtn, stopBtn;
    private final TextView[] minutesText = new TextView[2];
    private final Button[] minusBtn = new Button[2];
    private final Button[] plusBtn = new Button[2];

    private TextView syncStatus;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        syncStatus = findViewById(R.id.syncStatus);
        syncStatus.setOnClickListener(v -> {   // tap = send my minutes to the other device now
            Sync.publish(this);
            android.widget.Toast.makeText(this, R.string.sync_sent, android.widget.Toast.LENGTH_SHORT).show();
        });

        scroll = findViewById(R.id.scroll);
        phaseText = findViewById(R.id.phaseText);
        timeText = findViewById(R.id.timeText);
        sessionText = findViewById(R.id.sessionText);
        startBtn = findViewById(R.id.startBtn);
        skipBtn = findViewById(R.id.skipBtn);
        stopBtn = findViewById(R.id.stopBtn);

        setupRow(Settings.WORK, R.id.workTitle, R.id.workMinus, R.id.workMinutes, R.id.workPlus);
        setupRow(Settings.BREAK, R.id.breakTitle, R.id.breakMinus, R.id.breakMinutes, R.id.breakPlus);

        startBtn.setOnClickListener(v -> {
            if (!WatchTimer.isRunning(this)) WatchTimer.start(this);
            else if (WatchTimer.isPaused(this)) WatchTimer.resume(this);
            else WatchTimer.pause(this);
            render();
        });
        skipBtn.setOnClickListener(v -> { WatchTimer.skip(this); render(); });
        stopBtn.setOnClickListener(v -> { WatchTimer.stop(this); render(); });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void setupRow(int slot, int titleId, int minusId, int minutesId, int plusId) {
        TextView title = findViewById(titleId);
        title.setTextColor(slot == Settings.WORK ? COLOR_WORK : COLOR_BREAK);
        minutesText[slot] = findViewById(minutesId);
        minusBtn[slot] = findViewById(minusId);
        plusBtn[slot] = findViewById(plusId);
        minusBtn[slot].setOnClickListener(v -> changeMinutes(slot, -Settings.STEP));
        plusBtn[slot].setOnClickListener(v -> changeMinutes(slot, Settings.STEP));
    }

    private void changeMinutes(int slot, int delta) {
        int before = Settings.getMinutes(this, slot);
        Settings.setMinutes(this, slot, before + delta);
        int change = Settings.getMinutes(this, slot) - before;
        if (change != 0) {
            WatchTimer.adjust(this, slot, change);
            Sync.publish(this);
        }
        render();
    }

    private void render() {
        String st = Sync.status(this);
        syncStatus.setVisibility(st == null ? View.GONE : View.VISIBLE);
        if (st != null) syncStatus.setText(st);
        boolean running = WatchTimer.isRunning(this);
        boolean paused = WatchTimer.isPaused(this);
        int phase = running ? WatchTimer.phase(this) : Settings.WORK;
        int color = phase == Settings.WORK ? COLOR_WORK : COLOR_BREAK;

        phaseText.setText(phase == Settings.WORK ? R.string.work_caps : R.string.break_caps);
        phaseText.setTextColor(color);
        if (running) {
            timeText.setText(WatchTimer.format(WatchTimer.remaining(this)));
            sessionText.setText(paused ? getString(R.string.paused) : getString(R.string.session, WatchTimer.session(this)));
        } else {
            timeText.setText(WatchTimer.format(Settings.getMinutes(this, Settings.WORK) * 60_000L));
            sessionText.setText(R.string.ready);
        }
        timeText.setTextColor(running && paused ? 0xFF8A8A90 : 0xFFFFFFFF);

        startBtn.setImageResource(running && !paused ? R.drawable.ic_pause : R.drawable.ic_play);
        startBtn.setContentDescription(getString(!running ? R.string.start : paused ? R.string.resume : R.string.pause));
        startBtn.setBackgroundTintList(ColorStateList.valueOf(color));
        skipBtn.setVisibility(running ? View.VISIBLE : View.GONE);
        stopBtn.setVisibility(running ? View.VISIBLE : View.GONE);

        for (int slot = 0; slot < 2; slot++) {
            int min = Settings.getMinutes(this, slot);
            minutesText[slot].setText(getString(R.string.minutes, min));
            minusBtn[slot].setEnabled(min > Settings.MIN);
            minusBtn[slot].setAlpha(min > Settings.MIN ? 1f : 0.3f);
            plusBtn[slot].setEnabled(min < Settings.MAX);
            plusBtn[slot].setAlpha(min < Settings.MAX ? 1f : 0.3f);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Sync.pull(this);
        scroll.requestFocus(); // lets the rotating bezel / crown scroll the screen
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }
}
