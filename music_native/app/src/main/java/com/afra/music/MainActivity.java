package com.afra.music;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
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
                    "首次使用：请开启“音乐媒体控制”的通知使用权",
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

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);

        if (launchIntent == null) {
            Toast.makeText(this, "未找到网易云音乐", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        );

        startActivity(launchIntent);

        // 网易云可能需要一点时间重新建立 MediaSession。
        handler.postDelayed(this::playTargetSession, 600L);
        handler.postDelayed(this::playTargetSession, 1200L);
        handler.postDelayed(this::playTargetSession, 2200L);
        handler.postDelayed(this::playTargetSession, 3500L);
        handler.postDelayed(this::playTargetSession, 5000L);

        handler.postDelayed(this::finish, 5600L);
    }

    private void playTargetSession() {
        boolean controlled = false;

        try {
            MediaSessionManager manager =
                    (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);

            if (manager != null) {
                List<MediaController> controllers =
                        manager.getActiveSessions(listenerComponent);

                for (MediaController controller : controllers) {
                    if (controller == null) {
                        continue;
                    }

                    if (TARGET_PACKAGE.equals(controller.getPackageName())) {
                        MediaController.TransportControls controls =
                                controller.getTransportControls();

                        if (controls != null) {
                            controls.play();
                            controlled = true;
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }

        if (!controlled) {
            // 最后的兼容兜底：若网易云刚启动但 session 尚未进入列表，
            // 补发显式 PLAY 媒体键；不会把已播放状态切成暂停。
            sendPlayKey();
        }
    }

    private void sendPlayKey() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        if (audioManager == null) {
            return;
        }

        long now = android.os.SystemClock.uptimeMillis();

        KeyEvent down = new KeyEvent(
                now, now,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                0
        );

        KeyEvent up = new KeyEvent(
                now, now,
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                0
        );

        try {
            audioManager.dispatchMediaKeyEvent(down);
            audioManager.dispatchMediaKeyEvent(up);
        } catch (Exception ignored) {
        }
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

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
