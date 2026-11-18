package com.personal.pofocus;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stores the minutes and chosen music for each timer. Slot 0 = Work, 1 = Break.
 * Music is a list of tracks (one song, several songs, or an imported playlist).
 */
final class Settings {
    static final int WORK = 0;
    static final int BREAK = 1;
    static final int MIN = 5, MAX = 60, STEP = 5;

    private Settings() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static String k(int slot, String name) {
        return (slot == WORK ? "work_" : "break_") + name;
    }

    static int getMinutes(Context c, int slot) {
        return p(c).getInt(k(slot, "min"), slot == WORK ? 25 : 5);
    }

    static void setMinutes(Context c, int slot, int minutes) {
        minutes = Math.max(MIN, Math.min(MAX, minutes));
        p(c).edit().putInt(k(slot, "min"), minutes).apply();
    }

    // ---------------- language ----------------

    /** "" = follow the phone's language, otherwise "en" or "de". */
    static String getLanguage(Context c) {
        return p(c).getString("lang", "");
    }

    static void setLanguage(Context c, String lang) {
        p(c).edit().putString("lang", lang).commit();
    }

    /** Returns a context that uses the language chosen in the app. */
    static Context localized(Context base) {
        String lang = getLanguage(base);
        if (lang.isEmpty()) return base;
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocale(new Locale(lang));
        return base.createConfigurationContext(conf);
    }

    // ---------------- music ----------------

    static List<Uri> getTracks(Context c, int slot) {
        List<Uri> out = new ArrayList<>();
        String s = p(c).getString(k(slot, "tracks"), null);
        if (s == null) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) out.add(Uri.parse(a.getString(i)));
        } catch (Exception ignored) {}
        return out;
    }

    static int getTrackCount(Context c, int slot) {
        return p(c).getInt(k(slot, "count"), 0);
    }

    static boolean hasMedia(Context c, int slot) {
        return getTrackCount(c, slot) > 0;
    }

    static String getMediaName(Context c, int slot) {
        return p(c).getString(k(slot, "name"), null);
    }

    static void setMedia(Context c, int slot, List<Uri> tracks, String name) {
        JSONArray a = new JSONArray();
        for (Uri u : tracks) a.put(u.toString());
        p(c).edit()
                .putString(k(slot, "tracks"), a.toString())
                .putInt(k(slot, "count"), tracks.size())
                .putString(k(slot, "name"), name)
                .putInt(k(slot, "index"), 0)
                .apply();
    }

    static void clearMedia(Context c, int slot) {
        p(c).edit().remove(k(slot, "tracks")).remove(k(slot, "count"))
                .remove(k(slot, "name")).remove(k(slot, "index")).apply();
    }

    /** Which song of the list to play next time, so sessions continue through the playlist. */
    static int getTrackIndex(Context c, int slot) {
        return p(c).getInt(k(slot, "index"), 0);
    }

    static void setTrackIndex(Context c, int slot, int index) {
        p(c).edit().putInt(k(slot, "index"), index).apply();
    }
}
