package com.afra.music;

import android.service.notification.NotificationListenerService;

public class MusicNotificationListenerService extends NotificationListenerService {

    public static volatile MusicNotificationListenerService instance;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        super.onListenerDisconnected();
    }
}
