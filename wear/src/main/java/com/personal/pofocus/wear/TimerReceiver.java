package com.personal.pofocus.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the end-of-period alarm and the notification buttons. */
public class TimerReceiver extends BroadcastReceiver {
    static final String ACTION_ALARM = "com.personal.pofocus.wear.ALARM";
    static final String ACTION_PAUSE = "com.personal.pofocus.wear.PAUSE";
    static final String ACTION_RESUME = "com.personal.pofocus.wear.RESUME";
    static final String ACTION_STOP = "com.personal.pofocus.wear.STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync(); // keep running until the vibration/beep is done
        Runnable done = pending::finish;
        String a = intent.getAction();
        if (ACTION_ALARM.equals(a)) {
            WatchTimer.onAlarm(context, done);
            return;
        }
        if (ACTION_PAUSE.equals(a)) WatchTimer.pause(context);
        else if (ACTION_RESUME.equals(a)) WatchTimer.resume(context);
        else if (ACTION_STOP.equals(a)) WatchTimer.stop(context);
        done.run();
    }
}
