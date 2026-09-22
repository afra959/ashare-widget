package com.afra.music;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
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

        try {
            NotificationListenerService.requestRebind(listenerComponent);
        } catch (Exception ignored) {
        }

        // 关键变化：不先打开网易云。
        // 在本 Activity 仍位于前台时，直接控制网易云现有 MediaSession。
        attemptDirectPlay(0);
    }

    private void attemptDirectPlay(int stage) {
        MediaController controller = findNetEaseController();

        if (controller == null) {
            // 通知监听刚重连时，给系统极短的时间建立访问。
            if (stage < 2) {
                handler.postDelayed(() -> attemptDirectPlay(stage + 1), 250L);
                return;
            }

            // 没有可恢复的网易云媒体会话时，才退回到打开网易云。
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
                        "网易云当前没有可恢复的播放会话，已打开网易云",
                        Toast.LENGTH_SHORT
                ).show();
            } else {
                Toast.makeText(this, "未找到网易云音乐", Toast.LENGTH_SHORT).show();
            }

            finishSafely();
            return;
        }

        PlaybackState state = controller.getPlaybackState();

        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
            // 已播放，不做任何操作，避免误暂停。
            finishSafely();
            return;
        }

        long actions = state == null ? 0L : state.getActions();

        // 日志已确认网易云 actions=822，包含 ACTION_PLAY。
        if (state == null || (actions & PlaybackState.ACTION_PLAY) != 0L) {
            try {
                controller.getTransportControls().play();
            } catch (Exception ignored) {
            }
        }

        // 在前台短暂等待后，重新读取这个“指定网易云 Session”的真实状态。
        handler.postDelayed(() -> verifyAfterPlay(controller), 280L);
    }

    private void verifyAfterPlay(MediaController originalController) {
        MediaController freshController = findNetEaseController();

        if (freshController == null) {
            freshController = originalController;
        }

        PlaybackState state =
                freshController == null ? null : freshController.getPlaybackState();

        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
            finishSafely();
            return;
        }

        if (freshController != null) {
            // play() 若被网易云忽略，使用“指定 MediaController”的媒体键兜底。
            // 这不是全局媒体键，不会送给正在播放的小宇宙。
            try {
                long now = android.os.SystemClock.uptimeMillis();

                KeyEvent down = new KeyEvent(
                        now,
                        now,
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        0
                );

                KeyEvent up = new KeyEvent(
                        now,
                        now,
                        KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        0
                );

                freshController.dispatchMediaButtonEvent(down);
                freshController.dispatchMediaButtonEvent(up);

            } catch (Exception ignored) {
            }
        }

        handler.postDelayed(this::finalVerification, 350L);
    }

    private void finalVerification() {
        MediaController controller = findNetEaseController();
        PlaybackState state =
                controller == null ? null : controller.getPlaybackState();

        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
            finishSafely();
            return;
        }

        String detail = "播放失败";

        if (state != null) {
            detail += "（state=" + state.getState()
                    + ", actions=" + state.getActions() + "）";
        } else {
            detail += "（未读取到网易云 MediaSession）";
        }

        Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
        finishSafely();
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
