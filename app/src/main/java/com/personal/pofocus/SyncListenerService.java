package com.personal.pofocus;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

/** Receives minute changes made on the watch, even when the app is closed. */
public class SyncListenerService extends WearableListenerService {
    @Override
    public void onDataChanged(DataEventBuffer events) {
        for (DataEvent e : events) {
            if (e.getType() == DataEvent.TYPE_CHANGED
                    && Sync.PATH.equals(e.getDataItem().getUri().getPath())) {
                Sync.apply(this, DataMapItem.fromDataItem(e.getDataItem()).getDataMap());
            }
        }
    }
}
