package com.afra.music;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private static final String TARGET_PACKAGE = "com.netease.cloudmusic";
    private static final String PREFS = "music_diag";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_STARTED = "started";

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

        boolean pending = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_PENDING, false);

        long started = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(KEY_STARTED, 0L);

        long age = System.currentTimeMillis() - started;

        if (pending) {
            if (age < 1500L) {
                showWaitPage(age);
                return;
            }

            if (age <= 180000L) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_PENDING, false)
                        .apply();

                showCollectingPage();

                handler.postDelayed(() -> {
                    String report = buildReport(age);
                    copyReport(report);
                    showReport(report);
                }, 800L);

                return;
            }

            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_PENDING, false)
                    .apply();
        }

        startFirstStage();
    }

    private void startFirstStage() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING, true)
                .putLong(KEY_STARTED, System.currentTimeMillis())
                .apply();

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);

        if (launchIntent == null) {
            Toast.makeText(this, "未找到网易云音乐", Toast.LENGTH_SHORT).show();

            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_PENDING, false)
                    .apply();

            finish();
            return;
        }

        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        );

        Toast.makeText(
                this,
                "第一步完成：约 3 秒后回桌面，再点一次“音乐”",
                Toast.LENGTH_LONG
        ).show();

        startActivity(launchIntent);
        finish();
    }

    private void showWaitPage(long age) {
        LinearLayout root = baseLayout();

        TextView title = titleView("还需要稍等");
        root.addView(title);

        TextView body = new TextView(this);
        body.setText(
                "第一次启动距现在只有 " + age + " ms。\n\n" +
                "请再等约 2 秒，然后回桌面重新点击“音乐”。\n" +
                "本次不会重置诊断流程，也不会重新打开网易云。"
        );
        body.setTextSize(17f);
        root.addView(body);

        setContentView(root);
    }

    private void showCollectingPage() {
        LinearLayout root = baseLayout();

        TextView title = titleView("正在收集诊断信息");
        root.addView(title);

        TextView body = new TextView(this);
        body.setText(
                "正在等待通知监听服务连接，并读取网易云的 MediaSession 和媒体通知。\n\n" +
                "约 1 秒后会自动显示结果。"
        );
        body.setTextSize(17f);
        root.addView(body);

        setContentView(root);
    }

    private String buildReport(long ageMs) {
        StringBuilder log = new StringBuilder();

        log.append("========== 音乐 v1.8 诊断 ==========\n");
        log.append("second_launch_age_ms=").append(ageMs).append("\n");
        log.append("notification_access=")
                .append(isNotificationAccessEnabled())
                .append("\n");

        MusicNotificationListenerService service =
                MusicNotificationListenerService.instance;

        log.append("listener_instance=")
                .append(service != null)
                .append("\n");

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);

        log.append("isMusicActive=")
                .append(am != null && am.isMusicActive())
                .append("\n");

        log.append("\n--- MediaSession ---\n");
        inspectSessions(log);

        log.append("\n--- Notifications ---\n");

        if (service != null) {
            log.append(service.inspectNotifications(TARGET_PACKAGE));
        } else {
            log.append("listener_instance=null\n");
        }

        log.append("========== 诊断结束 ==========\n");

        return log.toString();
    }

    private void inspectSessions(StringBuilder log) {
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);

            if (manager == null) {
                log.append("session_manager=null\n");
                return;
            }

            List<MediaController> controllers =
                    manager.getActiveSessions(listenerComponent);

            log.append("session_count=")
                    .append(controllers == null ? 0 : controllers.size())
                    .append("\n");

            if (controllers == null) {
                return;
            }

            for (int i = 0; i < controllers.size(); i++) {
                MediaController c = controllers.get(i);

                if (c == null) {
                    continue;
                }

                PlaybackState state = c.getPlaybackState();
                MediaMetadata meta = c.getMetadata();

                log.append("SESSION#")
                        .append(i)
                        .append(" pkg=")
                        .append(c.getPackageName());

                if (state != null) {
                    log.append(" state=")
                            .append(state.getState())
                            .append(" actions=")
                            .append(state.getActions())
                            .append(" position=")
                            .append(state.getPosition());
                } else {
                    log.append(" state=null");
                }

                if (meta != null) {
                    log.append(" title=")
                            .append(meta.getText(MediaMetadata.METADATA_KEY_TITLE))
                            .append(" artist=")
                            .append(meta.getText(MediaMetadata.METADATA_KEY_ARTIST));
                }

                log.append("\n");
            }

        } catch (SecurityException e) {
            log.append("session_security_error=").append(e).append("\n");
        } catch (Exception e) {
            log.append("session_error=").append(e).append("\n");
        }
    }

    private void showReport(final String report) {
        LinearLayout root = baseLayout();

        TextView title = titleView("音乐诊断结果");
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("结果已经成功生成，并已自动复制到剪贴板。");
        tip.setTextSize(15f);
        tip.setPadding(0, 0, 0, 18);
        root.addView(tip);

        Button copy = new Button(this);
        copy.setText("复制全部日志");
        copy.setOnClickListener(v -> {
            copyReport(report);
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        root.addView(copy);

        TextView body = new TextView(this);
        body.setText(report);
        body.setTextSize(13f);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(0, 20, 0, 40);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        setContentView(root);
    }

    private LinearLayout baseLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 36, 36, 36);
        return root;
    }

    private TextView titleView(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, 22);
        return title;
    }

    private void copyReport(String report) {
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

            if (cm != null) {
                cm.setPrimaryClip(
                        ClipData.newPlainText("音乐诊断日志", report)
                );
            }
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
