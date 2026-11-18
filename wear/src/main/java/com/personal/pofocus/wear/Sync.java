package com.personal.pofocus.wear;

import android.content.Context;
import android.net.Uri;

import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

/**
 * Keeps the Work/Break minutes the same on the watch and the phone.
 * Each side publishes its minutes with a timestamp; the newest change wins.
 * Without a connected phone these calls simply do nothing.
 */
final class Sync {
    static final String PATH = "/settings";

    private Sync() {}

    /** Call after the user changed the minutes on this device. */
    static void publish(Context c) {
        try {
            long ts = System.currentTimeMillis();
            Settings.setSyncTime(c, ts);
            PutDataMapRequest r = PutDataMapRequest.create(PATH);
            DataMap m = r.getDataMap();
            m.putInt("work", Settings.getMinutes(c, Settings.WORK));
            m.putInt("break", Settings.getMinutes(c, Settings.BREAK));
            m.putLong("ts", ts);
            Wearable.getDataClient(c).putDataItem(r.asPutDataRequest().setUrgent());
        } catch (Exception ignored) {}
    }

    /** Fetches the newest minutes from the phone (e.g. when the app opens). */
    static void pull(Context c) {
        try {
            Context app = c.getApplicationContext();
            Wearable.getDataClient(app).getDataItems(Uri.parse("wear://*" + PATH))
                    .addOnSuccessListener(items -> {
                        try {
                            for (DataItem item : items) {
                                if (PATH.equals(item.getUri().getPath())) {
                                    apply(app, DataMapItem.fromDataItem(item).getDataMap());
                                }
                            }
                        } finally {
                            items.release();
                        }
                    });
        } catch (Exception ignored) {}
    }

    /** Uses minutes that came from the other device, if they are newer than ours. */
    static void apply(Context c, DataMap m) {
        long ts = m.getLong("ts", 0);
        if (ts <= Settings.getSyncTime(c)) return;
        Settings.setMinutes(c, Settings.WORK, m.getInt("work", Settings.getMinutes(c, Settings.WORK)));
        Settings.setMinutes(c, Settings.BREAK, m.getInt("break", Settings.getMinutes(c, Settings.BREAK)));
        Settings.setSyncTime(c, ts);
    }
}
