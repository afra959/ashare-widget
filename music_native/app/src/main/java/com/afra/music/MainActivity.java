package com.afra.music;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String TARGET_PACKAGE = "com.netease.cloudmusic";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!isAccessibilityServiceEnabled(this, MusicAccessibilityService.class)) {
            Toast.makeText(
                    this,
                    "首次使用：请开启“音乐快捷播放”无障碍服务",
                    Toast.LENGTH_LONG
            ).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            finish();
            return;
        }

        getSharedPreferences("music", MODE_PRIVATE)
                .edit()
                .putLong("pending_play_until", System.currentTimeMillis() + 15000L)
                .apply();

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
        finish();
    }

    private static boolean isAccessibilityServiceEnabled(
            Context context,
            Class<?> serviceClass
    ) {
        ComponentName expected = new ComponentName(context, serviceClass);

        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (TextUtils.isEmpty(enabledServices)) {
            return false;
        }

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);

        while (splitter.hasNext()) {
            ComponentName actual = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(actual)) {
                return true;
            }
        }

        return false;
    }
}
