package com.afra.music;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private static final String TARGET_PACKAGE = "com.netease.cloudmusic";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private ComponentName listenerComponent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        listenerComponent = new ComponentName(
                this,
                MusicNotificationListenerService.class
        );

        log.append("========== 音乐 v1.6 诊断 ==========\n");
        log.append("notification_access=").append(isNotificationAccessEnabled()).append("\n");
        log.append("listener_instance=")
                .append(MusicNotificationListenerService.instance != null)
                .append("\n");

        if (!isNotificationAccessEnabled()) {
            Toast.makeText(
                    this,
                    "请先开启“音乐媒体控制”的通知使用权",
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

        log.append("launchIntent=").append(launchIntent != null).append("\n");

        if (launchIntent == null) {
            copyAndFinish();
            return;
        }

        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        );

        startActivity(launchIntent);

        handler.postDelayed(() -> inspectRound("R1-800ms"), 800L);
        handler.postDelayed(() -> inspectRound("R2-1800ms"), 1800L);
        handler.postDelayed(() -> inspectRound("R3-3200ms"), 3200L);
        handler.postDelayed(() -> inspectRound("R4-5000ms"), 5000L);

        handler.postDelayed(this::copyAndFinish, 5800L);
    }

    private void inspectRound(String label) {
        log.append("\n--- ").append(label).append(" ---\n");

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);

        if (am != null) {
            log.append("isMusicActive=").append(am.isMusicActive()).append("\n");
        }

        inspectSessions();

        MusicNotificationListenerService service =
                MusicNotificationListenerService.instance;

        log.append("listener_instance_now=").append(service != null).append("\n");

        if (service != null) {
            String notifResult = service.inspectAndTryNotification(TARGET_PACKAGE);
            log.append(notifResult);
        }
    }

    private void inspectSessions() {
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);

            if (manager == null) {
                log.append("session_manager=null\n");
                return;
            }

            List<MediaController> controllers =
                    manager.getActiveSessions(listenerComponent);

            log.append("session_count=").append(controllers.size()).append("\n");

            for (int i = 0; i < controllers.size(); i++) {
                MediaController c = controllers.get(i);

                if (c == null) {
                    continue;
                }

                String pkg = c.getPackageName();
                PlaybackState state = c.getPlaybackState();
                MediaMetadata meta = c.getMetadata();

                log.append("SESSION#").append(i)
                        .append(" pkg=").append(pkg);

                if (state != null) {
                    log.append(" state=").append(state.getState())
                            .append(" actions=").append(state.getActions());
                } else {
                    log.append(" state=null");
                }

                if (meta != null) {
                    CharSequence title =
                            meta.getText(MediaMetadata.METADATA_KEY_TITLE);
                    CharSequence artist =
                            meta.getText(MediaMetadata.METADATA_KEY_ARTIST);

                    log.append(" title=").append(title)
                            .append(" artist=").append(artist);
                }

                log.append("\n");

                if (TARGET_PACKAGE.equals(pkg)) {
                    try {
                        MediaController.TransportControls controls =
                                c.getTransportControls();

                        if (controls != null) {
                            controls.play();
                            log.append("  -> controls.play() CALLED\n");
                        }
                    } catch (Exception e) {
                        log.append("  -> controls.play() ERROR ").append(e).append("\n");
                    }
                }
            }

        } catch (SecurityException e) {
            log.append("session_security_error=").append(e).append("\n");
        } catch (Exception e) {
            log.append("session_error=").append(e).append("\n");
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

    private void copyAndFinish() {
        log.append("\n========== 诊断结束 ==========\n");

        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

            if (cm != null) {
                cm.setPrimaryClip(
                        ClipData.newPlainText("音乐诊断日志", log.toString())
                );
            }
        } catch (Exception ignored) {
        }

        Toast.makeText(
                this,
                "诊断完成，日志已复制到剪贴板",
                Toast.LENGTH_LONG
        ).show();

        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
