package com.afra.music;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private static final String TARGET_PACKAGE = "com.netease.cloudmusic";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ComponentName listenerComponent;
    private boolean finished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        listenerComponent = new ComponentName(
                this,
                MusicNotificationListenerService.class
        );

        if (!isNotificationAccessEnabled()) {
            Toast.makeText(
                    this,
                    "请开启“音乐媒体控制”的通知使用权",
                    Toast.LENGTH_LONG
            ).show();

            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }

            finish();
            return;
        }

        startDirectPlayback();
    }

    private void startDirectPlayback() {
        MediaController controller = findNetEaseController();

        if (controller == null) {
            launchNetEaseWithoutSession();
            return;
        }

        PlaybackState state = controller.getPlaybackState();

        if (isAlreadyProgressing(state)) {
            finishSafely();
            return;
        }

        try {
            MediaController.TransportControls controls =
                    controller.getTransportControls();

            if (controls != null) {
                controls.play();
            }
        } catch (Exception ignored) {
        }

        // 给网易云足够时间更新 MediaSession，避免过早兜底导致反向暂停。
        handler.postDelayed(this::verifyAfterTransportPlay, 900L);
    }

    private void verifyAfterTransportPlay() {
        MediaController controller = findNetEaseController();

        if (controller == null) {
            finishWithFailure("播放失败：网易云 MediaSession 消失");
            return;
        }

        PlaybackState state = controller.getPlaybackState();

        if (isAlreadyProgressing(state)) {
            finishSafely();
            return;
        }

        // 只发送定向 MEDIA_PLAY，不使用 PLAY_PAUSE。
        // 即使网易云状态更新稍慢，也不会把已经播放的音乐切回暂停。
        sendTargetedMediaPlay(controller);

        handler.postDelayed(this::verifyAfterMediaPlay, 900L);
    }

    private void verifyAfterMediaPlay() {
        MediaController controller = findNetEaseController();

        if (controller == null) {
            finishWithFailure("播放失败：未读取到网易云 MediaSession");
            return;
        }

        PlaybackState state = controller.getPlaybackState();

        if (isAlreadyProgressing(state)) {
            finishSafely();
            return;
        }

        // 最后只在状态仍明确不是播放时，尝试网易云自己的通知 toggle。
        if (tryNotificationToggle()) {
            handler.postDelayed(this::finalVerification, 800L);
        } else {
            finalVerification();
        }
    }

    private void finalVerification() {
        MediaController controller = findNetEaseController();
        PlaybackState state =
                controller == null ? null : controller.getPlaybackState();

        if (isAlreadyProgressing(state)) {
            finishSafely();
            return;
        }

        if (state == null) {
            finishWithFailure("播放失败：网易云状态为空");
        } else {
            finishWithFailure(
                    "播放失败（state=" + state.getState()
                            + ", actions=" + state.getActions() + "）"
            );
        }
    }

    private boolean isAlreadyProgressing(PlaybackState state) {
        if (state == null) {
            return false;
        }

        int s = state.getState();

        return s == PlaybackState.STATE_PLAYING
                || s == PlaybackState.STATE_BUFFERING
                || s == PlaybackState.STATE_CONNECTING;
    }

    private void sendTargetedMediaPlay(MediaController controller) {
        try {
            long now = android.os.SystemClock.uptimeMillis();

            KeyEvent down = new KeyEvent(
                    now,
                    now,
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    0
            );

            KeyEvent up = new KeyEvent(
                    now,
                    now,
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    0
            );

            controller.dispatchMediaButtonEvent(down);
            controller.dispatchMediaButtonEvent(up);

        } catch (Exception ignored) {
        }
    }

    private boolean tryNotificationToggle() {
        MusicNotificationListenerService service =
                MusicNotificationListenerService.instance;

        if (service == null) {
            return false;
        }

        try {
            android.service.notification.StatusBarNotification[] items =
                    service.getActiveNotifications();

            if (items == null) {
                return false;
            }

            for (android.service.notification.StatusBarNotification sbn : items) {
                if (sbn == null
                        || !TARGET_PACKAGE.equals(sbn.getPackageName())) {
                    continue;
                }

                Notification n = sbn.getNotification();

                if (n == null || n.actions == null) {
                    continue;
                }

                for (Notification.Action action : n.actions) {
                    if (action == null || action.actionIntent == null) {
                        continue;
                    }

                    String title = String.valueOf(action.title);

                    if ("toggle".equalsIgnoreCase(title)
                            || "播放".equals(title)
                            || "继续播放".equals(title)) {
                        try {
                            action.actionIntent.send();
                            return true;
                        } catch (PendingIntent.CanceledException ignored) {
                        }
                    }
                }
            }

        } catch (Exception ignored) {
        }

        return false;
    }

    private MediaController findNetEaseController() {
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);

            if (manager == null) {
                return null;
            }

            List<MediaController> controllers =
                    manager.getActiveSessions(listenerComponent);

            if (controllers == null) {
                return null;
            }

            for (MediaController controller : controllers) {
                if (controller != null
                        && TARGET_PACKAGE.equals(controller.getPackageName())) {
                    return controller;
                }
            }

        } catch (Exception ignored) {
        }

        return null;
    }

    private void launchNetEaseWithoutSession() {
        Intent launchIntent =
                getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);

        if (launchIntent != null) {
            launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            );

            startActivity(launchIntent);

            Toast.makeText(
                    this,
                    "网易云当前没有可恢复的媒体会话，已打开网易云",
                    Toast.LENGTH_SHORT
            ).show();

        } else {
            Toast.makeText(this, "未找到网易云音乐", Toast.LENGTH_SHORT).show();
        }

        finishSafely();
    }

    private void finishWithFailure(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finishSafely();
    }

    private boolean isNotificationAccessEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                "enabled_notification_listeners"
        );

        if (TextUtils.isEmpty(enabled)) {
            return false;
        }

        TextUtils.SimpleStringSplitter splitter =
                new TextUtils.SimpleStringSplitter(':');

        splitter.setString(enabled);

        while (splitter.hasNext()) {
            ComponentName component =
                    ComponentName.unflattenFromString(splitter.next());

            if (listenerComponent.equals(component)) {
                return true;
            }
        }

        return false;
    }

    private void finishSafely() {
        if (finished) {
            return;
        }

        finished = true;
        handler.removeCallbacksAndMessages(null);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
