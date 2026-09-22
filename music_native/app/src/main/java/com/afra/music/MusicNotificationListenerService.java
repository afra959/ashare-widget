package com.afra.music;

import android.app.Notification;
import android.app.PendingIntent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Locale;

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

    public synchronized String inspectAndTryNotification(String targetPackage) {
        StringBuilder out = new StringBuilder();

        try {
            StatusBarNotification[] items = getActiveNotifications();

            out.append("notification_count=").append(items == null ? 0 : items.length).append("\n");

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
                    CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                    CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                    out.append(" title=").append(title);
                    out.append(" text=").append(text);
                }

                out.append("\n");

                if (!targetPackage.equals(pkg) || n == null) {
                    continue;
                }

                Notification.Action[] actions = n.actions;
                out.append("  actions=").append(actions == null ? 0 : actions.length).append("\n");

                if (actions == null) {
                    continue;
                }

                for (int i = 0; i < actions.length; i++) {
                    Notification.Action action = actions[i];
                    if (action == null) {
                        continue;
                    }

                    String title = String.valueOf(action.title);
                    String low = title.toLowerCase(Locale.ROOT);

                    out.append("  ACTION#").append(i)
                            .append(" title=").append(title)
                            .append(" hasIntent=").append(action.actionIntent != null)
                            .append("\n");

                    boolean looksPlay =
                            low.contains("play")
                                    || low.contains("resume")
                                    || title.contains("播放")
                                    || title.contains("继续");

                    boolean looksPause =
                            low.contains("pause")
                                    || title.contains("暂停");

                    if (looksPause) {
                        out.append("  -> notification indicates already playing\n");
                    }

                    if (looksPlay && action.actionIntent != null) {
                        try {
                            action.actionIntent.send();
                            out.append("  -> SENT notification play action\n");
                        } catch (PendingIntent.CanceledException e) {
                            out.append("  -> send failed: ").append(e).append("\n");
                        }
                    }
                }
            }

        } catch (Exception e) {
            out.append("notification_error=").append(e).append("\n");
        }

        return out.toString();
    }
}
