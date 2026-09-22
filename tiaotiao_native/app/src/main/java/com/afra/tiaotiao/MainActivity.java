package com.afra.tiaotiao;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String PREFS = "tiaotiao";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_LOG = "last_log";

    private TextView statusView;
    private TextView logView;
    private Switch masterSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 54, 48, 54);
        root.setBackgroundColor(Color.rgb(248, 248, 246));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("跳跳");
        title.setTextSize(34f);
        title.setTextColor(Color.rgb(32, 33, 36));
        title.setPadding(0, 0, 0, 10);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("只在指定 App 刚打开后的 8 秒内检查开屏广告。");
        sub.setTextSize(16f);
        sub.setTextColor(Color.DKGRAY);
        sub.setPadding(0, 0, 0, 32);
        root.addView(sub);

        statusView = new TextView(this);
        statusView.setTextSize(17f);
        statusView.setTextColor(Color.rgb(32, 33, 36));
        statusView.setPadding(0, 0, 0, 18);
        root.addView(statusView);

        Button accessibilityButton = new Button(this);
        accessibilityButton.setText("打开无障碍设置");
        accessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );
        root.addView(accessibilityButton);

        masterSwitch = new Switch(this);
        masterSwitch.setText("启用自动跳过");
        masterSwitch.setTextSize(17f);
        masterSwitch.setPadding(0, 28, 0, 24);
        masterSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;

            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ENABLED, isChecked)
                    .apply();

            refreshUi();
        });
        root.addView(masterSwitch);

        TextView listTitle = new TextView(this);
        listTitle.setText("白名单");
        listTitle.setTextSize(20f);
        listTitle.setTextColor(Color.rgb(32, 33, 36));
        listTitle.setPadding(0, 20, 0, 12);
        root.addView(listTitle);

        TextView apps = new TextView(this);
        apps.setText(
                "豆瓣\n" +
                "小红书\n" +
                "淘宝\n" +
                "微博\n" +
                "小宇宙\n" +
                "网易云音乐"
        );
        apps.setTextSize(17f);
        apps.setTextColor(Color.DKGRAY);
        apps.setLineSpacing(8f, 1f);
        root.addView(apps);

        TextView ruleTitle = new TextView(this);
        ruleTitle.setText("第一版规则");
        ruleTitle.setTextSize(20f);
        ruleTitle.setTextColor(Color.rgb(32, 33, 36));
        ruleTitle.setPadding(0, 34, 0, 12);
        root.addView(ruleTitle);

        TextView rule = new TextView(this);
        rule.setText(
                "• 只监听 App 切换，不持续扫描所有界面\n" +
                "• 进入白名单 App 后最多工作 8 秒\n" +
                "• 只点明确的“跳过 / Skip / 关闭广告”语义\n" +
                "• 不截图、不 OCR、不做纯坐标猜测\n" +
                "• 成功点击后立即结束本次检测"
        );
        rule.setTextSize(15f);
        rule.setTextColor(Color.DKGRAY);
        rule.setLineSpacing(6f, 1f);
        root.addView(rule);

        TextView logTitle = new TextView(this);
        logTitle.setText("最近记录");
        logTitle.setTextSize(20f);
        logTitle.setTextColor(Color.rgb(32, 33, 36));
        logTitle.setPadding(0, 34, 0, 12);
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(14f);
        logView.setTextColor(Color.DKGRAY);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        setContentView(scroll);
    }

    private void refreshUi() {
        boolean enabled = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);

        masterSwitch.setChecked(enabled);

        boolean accessibility = isAccessibilityServiceEnabled(
                this,
                SkipAccessibilityService.class
        );

        if (accessibility && enabled) {
            statusView.setText("状态：运行中");
        } else if (!accessibility) {
            statusView.setText("状态：需要开启“跳跳自动跳过”无障碍服务");
        } else {
            statusView.setText("状态：已暂停");
        }

        String log = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_LAST_LOG, "暂无记录");

        logView.setText(log == null || log.isEmpty() ? "暂无记录" : log);
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

        TextUtils.SimpleStringSplitter splitter =
                new TextUtils.SimpleStringSplitter(':');

        splitter.setString(enabledServices);

        while (splitter.hasNext()) {
            ComponentName actual =
                    ComponentName.unflattenFromString(splitter.next());

            if (expected.equals(actual)) {
                return true;
            }
        }

        return false;
    }
}
