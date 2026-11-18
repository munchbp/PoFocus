package com.personal.pofocus.wear;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Keeps the Work/Break minutes the same on the phone and the watch.
 * Every change is sent two ways: as a stored data item (arrives even if the other device
 * is offline right now) and as a direct message (fast). The newest change wins.
 * Also tracks a status line ("Connected to Galaxy Watch · updated 10:42") for the screen.
 */
final class Sync {
    static final String PATH = "/settings";

    // status for the screen
    private static volatile List<Node> nodes;      // null = not checked yet
    private static volatile String lastError;
    private static volatile boolean apiMissing;    // no watch support on this device (e.g. no phone link)

    private Sync() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sync", Context.MODE_PRIVATE);
    }

    // ---------------- sending ----------------

    /** Call after the user changed the minutes on this device. */
    static void publish(Context c) {
        Context app = c.getApplicationContext();
        long ts = System.currentTimeMillis();
        Settings.setSyncTime(app, ts);
        int work = Settings.getMinutes(app, Settings.WORK);
        int brk = Settings.getMinutes(app, Settings.BREAK);
        try {
            PutDataMapRequest r = PutDataMapRequest.create(PATH);
            DataMap m = r.getDataMap();
            m.putInt("work", work);
            m.putInt("break", brk);
            m.putLong("ts", ts);
            Wearable.getDataClient(app).putDataItem(r.asPutDataRequest().setUrgent())
                    .addOnSuccessListener(x -> lastError = null)
                    .addOnFailureListener(Sync::onError);

            byte[] payload = (work + "," + brk + "," + ts).getBytes(StandardCharsets.UTF_8);
            Wearable.getNodeClient(app).getConnectedNodes()
                    .addOnSuccessListener(list -> {
                        nodes = list;
                        for (Node n : list) {
                            Wearable.getMessageClient(app).sendMessage(n.getId(), PATH, payload)
                                    .addOnSuccessListener(x -> prefs(app).edit()
                                            .putLong("last_sync", System.currentTimeMillis()).apply())
                                    .addOnFailureListener(Sync::onError);
                        }
                    })
                    .addOnFailureListener(Sync::onError);
        } catch (Exception e) {
            onError(e);
        }
    }

    // ---------------- receiving ----------------

    /** Fetches the newest minutes from the other device and checks the connection (when the app opens). */
    static void pull(Context c) {
        Context app = c.getApplicationContext();
        try {
            Wearable.getDataClient(app).getDataItems()
                    .addOnSuccessListener(items -> {
                        try {
                            for (DataItem item : items) {
                                if (PATH.equals(item.getUri().getPath())) {
                                    DataMap m = DataMapItem.fromDataItem(item).getDataMap();
                                    apply(app, m.getInt("work", 0), m.getInt("break", 0), m.getLong("ts", 0));
                                }
                            }
                        } finally {
                            items.release();
                        }
                    })
                    .addOnFailureListener(Sync::onError);
            Wearable.getNodeClient(app).getConnectedNodes()
                    .addOnSuccessListener(list -> { nodes = list; apiMissing = false; lastError = null; })
                    .addOnFailureListener(Sync::onError);
        } catch (Exception e) {
            onError(e);
        }
    }

    static void applyDataMap(Context c, DataMap m) {
        apply(c, m.getInt("work", 0), m.getInt("break", 0), m.getLong("ts", 0));
    }

    static void applyMessage(Context c, byte[] data) {
        try {
            String[] parts = new String(data, StandardCharsets.UTF_8).split(",");
            apply(c, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Long.parseLong(parts[2]));
        } catch (Exception ignored) {}
    }

    /** Uses minutes that came from the other device, if they are newer than ours. */
    private static void apply(Context c, int work, int brk, long ts) {
        if (work <= 0 || brk <= 0) return;
        prefs(c).edit().putLong("last_sync", System.currentTimeMillis()).apply();
        if (ts <= Settings.getSyncTime(c)) return;
        Settings.setMinutes(c, Settings.WORK, work);
        Settings.setMinutes(c, Settings.BREAK, brk);
        Settings.setSyncTime(c, ts);
    }

    // ---------------- status line ----------------

    private static void onError(Exception e) {
        if (e instanceof ApiException
                && ((ApiException) e).getStatusCode() == CommonStatusCodes.API_NOT_CONNECTED) {
            apiMissing = true;   // this device has no watch support: not an error worth showing
            return;
        }
        lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Text for the small status line, or null to hide it. */
    static String status(Context c) {
        String version = "v" + BuildConfig.VERSION_NAME;
        if (apiMissing) return null;
        if (lastError != null) return version + " · " + c.getString(R.string.sync_error, lastError);
        List<Node> list = nodes;
        if (list == null) return version;
        if (list.isEmpty()) return version + " · " + c.getString(R.string.sync_none);
        StringBuilder s = new StringBuilder(version + " · " + c.getString(R.string.sync_connected, list.get(0).getDisplayName()));
        long last = prefs(c).getLong("last_sync", 0);
        if (last > 0) {
            s.append(" · ").append(c.getString(R.string.sync_last,
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(last))));
        }
        return s.toString();
    }
}
