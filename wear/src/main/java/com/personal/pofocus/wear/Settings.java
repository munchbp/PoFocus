package com.personal.pofocus.wear;

import android.content.Context;
import android.content.SharedPreferences;

/** Work/Break minutes on the watch. Slot 0 = Work, 1 = Break. */
final class Settings {
    static final int WORK = 0;
    static final int BREAK = 1;
    static final int MIN = 5, MAX = 60, STEP = 5;

    private Settings() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static int getMinutes(Context c, int slot) {
        return p(c).getInt(slot == WORK ? "work_min" : "break_min", slot == WORK ? 25 : 5);
    }

    static void setMinutes(Context c, int slot, int minutes) {
        minutes = Math.max(MIN, Math.min(MAX, minutes));
        p(c).edit().putInt(slot == WORK ? "work_min" : "break_min", minutes).apply();
    }

    static long getSyncTime(Context c) {
        return p(c).getLong("sync_ts", 0);
    }

    static void setSyncTime(Context c, long ts) {
        p(c).edit().putLong("sync_ts", ts).apply();
    }
}
