package com.afra.music;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String TARGET_PACKAGE = "com.netease.cloudmusic";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        // 网易云从后台恢复和冷启动耗时不同。
        // MEDIA_PLAY 可以重复发送：暂停时会播放，已播放时不会变成暂停。
        handler.postDelayed(this::sendPlay, 700L);
        handler.postDelayed(this::sendPlay, 1400L);
        handler.postDelayed(this::sendPlay, 2400L);
        handler.postDelayed(this::sendPlay, 3600L);

        handler.postDelayed(this::finish, 4300L);
    }

    private void sendPlay() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        if (audioManager == null) {
            return;
        }

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

        try {
            audioManager.dispatchMediaKeyEvent(down);
            audioManager.dispatchMediaKeyEvent(up);
        } catch (Exception ignored) {
        }

        // 再补一个只发给网易云包的显式 MEDIA_BUTTON。
        try {
            Intent downIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            downIntent.setPackage(TARGET_PACKAGE);
            downIntent.putExtra(Intent.EXTRA_KEY_EVENT, down);
            sendBroadcast(downIntent);

            Intent upIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            upIntent.setPackage(TARGET_PACKAGE);
            upIntent.putExtra(Intent.EXTRA_KEY_EVENT, up);
            sendBroadcast(upIntent);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
