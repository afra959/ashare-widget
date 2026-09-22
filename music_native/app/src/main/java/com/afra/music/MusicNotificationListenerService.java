package com.afra.music;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

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

    public synchronized String inspectNotifications(String targetPackage) {
        StringBuilder out = new StringBuilder();

        try {
            StatusBarNotification[] items = getActiveNotifications();

            out.append("notification_count=")
                    .append(items == null ? 0 : items.length)
                    .append("\n");

            if (items == null) {
                return out.toString();
            }

            for (StatusBarNotification sbn : items) {
                if (sbn == null) {
                    continue;
                }

                String pkg = sbn.getPackageName();
                Notification n = sbn.getNotification();

                out.append("NOTIF pkg=").append(pkg);

                if (n != null && n.extras != null) {
                    CharSequence title =
                            n.extras.getCharSequence(Notification.EXTRA_TITLE);
                    CharSequence body =
                            n.extras.getCharSequence(Notification.EXTRA_TEXT);

                    out.append(" title=").append(title)
                            .append(" text=").append(body);
                }

                out.append("\n");

                if (!targetPackage.equals(pkg) || n == null) {
                    continue;
                }

                Notification.Action[] actions = n.actions;

                out.append("  target_actions=")
                        .append(actions == null ? 0 : actions.length)
                        .append("\n");

                if (actions != null) {
                    for (int i = 0; i < actions.length; i++) {
                        Notification.Action a = actions[i];

                        if (a == null) {
                            continue;
                        }

                        out.append("  ACTION#")
                                .append(i)
                                .append(" title=")
                                .append(a.title)
                                .append(" hasIntent=")
                                .append(a.actionIntent != null)
                                .append("\n");
                    }
                }
            }

        } catch (Exception e) {
            out.append("notification_error=").append(e).append("\n");
        }

        return out.toString();
    }
}
