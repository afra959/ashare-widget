package com.eternal.asharewidget;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WidgetConfigActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 401;
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private boolean waitingForNotificationPermission = false;

    private static final String[] REFRESH_LABELS = {
            "仅手动刷新", "每 5 分钟", "每 15 分钟", "每 30 分钟", "每 60 分钟", "每 120 分钟"
    };
    private static final int[] REFRESH_VALUES = {0, 5, 15, 30, 60, 120};
    private static final String[] SIZE_LABELS = {"小（13sp）", "中（15sp）", "大（17sp）", "特大（19sp）", "超大（21sp）"};
    private static final int[] SIZE_VALUES = {13, 15, 17, 19, 21};
    private static final String[] ALERT_DIRECTION_LABELS = {"达到或高于（≥）", "达到或低于（≤）"};

    private final List<PriceAlert> alertRules = new ArrayList<>();
    private LinearLayout alertListContainer;
    private EditText alertCodeInput;
    private EditText alertTargetInput;
    private Spinner alertDirectionSpinner;
    private Button alertAddUpdateButton;
    private Button alertCancelEditButton;
    private int editingAlertIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return; }

        NotificationHelper.ensureChannel(this);
        try {
            alertRules.addAll(PriceAlert.parseLines(WidgetPrefs.alertsRaw(this, widgetId)));
        } catch (IllegalArgumentException ignored) {
        }

        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.TOP);
        scroll.addView(root);

        TextView h = new TextView(this);
        h.setText("配置行情小部件");
        h.setTextSize(22);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(h);

        TextView tips = label("数据源：A股/沪深北及大陆指数使用腾讯财经；黄金、纳指、人民币/卢布使用 Yahoo Finance。\n建议按源代码填写：腾讯用 sh603605 / sz000001 / bjxxxxxx；Yahoo 用 GC=F / ^IXIC / CNYRUB=X。\nA股也可只填6位数字，程序会自动补 sh/sz/bj；科创50请写 sh000688。桌面不显示代码。", 13);
        tips.setPadding(0, dp(8), 0, dp(14));
        root.addView(tips);

        root.addView(section("标题"));
        EditText title = new EditText(this);
        title.setSingleLine(true);
        title.setText(WidgetPrefs.title(this, widgetId));
        root.addView(title, matchWrap());

        root.addView(section("行情代码"));
        EditText codes = new EditText(this);
        codes.setGravity(Gravity.TOP);
        codes.setMinLines(8);
        codes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        codes.setText(WidgetPrefs.codesRaw(this, widgetId));
        root.addView(codes, matchWrap());
        root.addView(label("增加或删除标的：直接修改上面的代码列表，每行一个，然后点最下面“保存并应用”。", 12));

        root.addView(section("价格提醒（通知栏）"));
        root.addView(label("这里可以直接新增、修改或删除提醒，不需要手写规则。提醒标的不一定要显示在桌面行情列表里。", 12));

        alertCodeInput = new EditText(this);
        alertCodeInput.setSingleLine(true);
        alertCodeInput.setHint("股票/指数代码，例如 603605、GC=F、^IXIC");
        alertCodeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        root.addView(alertCodeInput, matchWrap());

        LinearLayout alertEditRow = new LinearLayout(this);
        alertEditRow.setOrientation(LinearLayout.HORIZONTAL);
        alertEditRow.setGravity(Gravity.CENTER_VERTICAL);
        alertEditRow.setPadding(0, dp(4), 0, 0);

        alertDirectionSpinner = new Spinner(this);
        alertDirectionSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, ALERT_DIRECTION_LABELS));
        LinearLayout.LayoutParams directionLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f);
        alertEditRow.addView(alertDirectionSpinner, directionLp);

        alertTargetInput = new EditText(this);
        alertTargetInput.setSingleLine(true);
        alertTargetInput.setHint("目标价");
        alertTargetInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams targetLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f);
        targetLp.setMargins(dp(8), 0, 0, 0);
        alertEditRow.addView(alertTargetInput, targetLp);
        root.addView(alertEditRow, matchWrap());

        LinearLayout alertButtons = new LinearLayout(this);
        alertButtons.setOrientation(LinearLayout.HORIZONTAL);
        alertButtons.setGravity(Gravity.CENTER_VERTICAL);
        alertButtons.setPadding(0, dp(4), 0, 0);

        alertAddUpdateButton = new Button(this);
        alertAddUpdateButton.setText("添加提醒");
        alertButtons.addView(alertAddUpdateButton,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        alertCancelEditButton = new Button(this);
        alertCancelEditButton.setText("取消修改");
        alertCancelEditButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.setMargins(dp(8), 0, 0, 0);
        alertButtons.addView(alertCancelEditButton, cancelLp);
        root.addView(alertButtons, matchWrap());

        alertListContainer = new LinearLayout(this);
        alertListContainer.setOrientation(LinearLayout.VERTICAL);
        alertListContainer.setPadding(0, dp(8), 0, 0);
        root.addView(alertListContainer, matchWrap());
        renderAlertList();

        TextView alertTip = label(
                "达到条件时发一次通知；价格回到阈值另一侧后会自动重新等待下一次穿越。\n"
                        + "提醒检查频率与下面的自动刷新相同；若选“仅手动刷新”，则只在你手动刷新时检查。", 12);
        alertTip.setPadding(0, dp(4), 0, 0);
        root.addView(alertTip);

        alertAddUpdateButton.setOnClickListener(v -> addOrUpdateAlert());
        alertCancelEditButton.setOnClickListener(v -> resetAlertEditor());

        root.addView(section("自动刷新"));
        Spinner refresh = new Spinner(this);
        refresh.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, REFRESH_LABELS));
        refresh.setSelection(indexOf(REFRESH_VALUES, WidgetPrefs.refreshMinutes(this, widgetId), 2));
        root.addView(refresh, matchWrap());
        TextView refreshTip = label("Android 可能为了省电延后后台刷新；右上角 ↻ 始终可立即手动刷新。价格提醒也受这一限制。", 12);
        root.addView(refreshTip);

        root.addView(section("背景不透明度"));
        TextView opacityValue = label("", 13);
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(80);
        int initialOpacity = WidgetPrefs.opacity(this, widgetId);
        opacity.setProgress(initialOpacity - 20);
        opacityValue.setText(initialOpacity + "%");
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                opacityValue.setText((progress + 20) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(opacityValue);
        root.addView(opacity, matchWrap());

        root.addView(section("背景颜色"));
        EditText bgColor = colorInput(WidgetPrefs.backgroundHex(this, widgetId));
        root.addView(bgColor, matchWrap());
        root.addView(label("输入 HEX，例如腾讯风格深灰：#242A35；纯黑：#000000。", 12));

        root.addView(section("字体颜色"));
        EditText textColor = colorInput(WidgetPrefs.textHex(this, widgetId));
        root.addView(textColor, matchWrap());
        root.addView(label("名称、价格和标题使用此颜色；涨跌额/涨跌幅固定为红涨绿跌。", 12));

        root.addView(section("字号"));
        Spinner size = new Spinner(this);
        size.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, SIZE_LABELS));
        size.setSelection(indexOf(SIZE_VALUES, WidgetPrefs.textSizeSp(this, widgetId), 2));
        root.addView(size, matchWrap());

        Button save = new Button(this);
        save.setText("保存并应用");
        LinearLayout.LayoutParams bp = matchWrap();
        bp.setMargins(0, dp(18), 0, dp(8));
        root.addView(save, bp);

        save.setOnClickListener(v -> {
            String alertText = serializeAlerts();
            int refreshMinutes = REFRESH_VALUES[refresh.getSelectedItemPosition()];
            int textSize = SIZE_VALUES[size.getSelectedItemPosition()];
            int opacityPercent = opacity.getProgress() + 20;
            WidgetPrefs.save(this, widgetId,
                    title.getText().toString(),
                    codes.getText().toString(),
                    refreshMinutes,
                    opacityPercent,
                    bgColor.getText().toString(),
                    textColor.getText().toString(),
                    textSize,
                    alertText);

            boolean hasAlerts = !alertRules.isEmpty();
            if (hasAlerts && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                waitingForNotificationPermission = true;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
                return;
            }

            applyAndFinish("已应用");
        });

        setContentView(scroll);
    }

    private void addOrUpdateAlert() {
        String rawCode = alertCodeInput.getText().toString().trim();
        if (rawCode.isEmpty()) {
            Toast.makeText(this, "请先输入提醒标的代码", Toast.LENGTH_SHORT).show();
            return;
        }

        String rawTarget = alertTargetInput.getText().toString().trim();
        double target;
        try {
            target = Double.parseDouble(rawTarget);
        } catch (Exception e) {
            Toast.makeText(this, "请输入有效的目标价格", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Double.isFinite(target) || target <= 0) {
            Toast.makeText(this, "目标价格必须大于 0", Toast.LENGTH_SHORT).show();
            return;
        }

        PriceAlert.Direction direction = alertDirectionSpinner.getSelectedItemPosition() == 0
                ? PriceAlert.Direction.ABOVE_OR_EQUAL
                : PriceAlert.Direction.BELOW_OR_EQUAL;
        PriceAlert next = new PriceAlert(rawCode, direction, target);

        if (editingAlertIndex >= 0 && editingAlertIndex < alertRules.size()) {
            alertRules.set(editingAlertIndex, next);
            Toast.makeText(this, "提醒价格已修改", Toast.LENGTH_SHORT).show();
        } else {
            int same = findSameAlert(next.code, next.direction);
            if (same >= 0) {
                alertRules.set(same, next);
                Toast.makeText(this, "已有同方向提醒，已更新目标价", Toast.LENGTH_SHORT).show();
            } else {
                alertRules.add(next);
                Toast.makeText(this, "提醒已添加", Toast.LENGTH_SHORT).show();
            }
        }
        resetAlertEditor();
        renderAlertList();
    }

    private int findSameAlert(String code, PriceAlert.Direction direction) {
        for (int i = 0; i < alertRules.size(); i++) {
            PriceAlert a = alertRules.get(i);
            if (a.code.equals(code) && a.direction == direction) return i;
        }
        return -1;
    }

    private void startEditAlert(int index) {
        if (index < 0 || index >= alertRules.size()) return;
        PriceAlert a = alertRules.get(index);
        editingAlertIndex = index;
        alertCodeInput.setText(displayCode(a.code));
        alertTargetInput.setText(formatTarget(a.target));
        alertDirectionSpinner.setSelection(a.direction == PriceAlert.Direction.ABOVE_OR_EQUAL ? 0 : 1);
        alertAddUpdateButton.setText("保存修改");
        alertCancelEditButton.setVisibility(View.VISIBLE);
        alertTargetInput.requestFocus();
    }

    private void resetAlertEditor() {
        editingAlertIndex = -1;
        alertCodeInput.setText("");
        alertTargetInput.setText("");
        alertDirectionSpinner.setSelection(0);
        alertAddUpdateButton.setText("添加提醒");
        alertCancelEditButton.setVisibility(View.GONE);
    }

    private void renderAlertList() {
        if (alertListContainer == null) return;
        alertListContainer.removeAllViews();

        TextView sub = label("已设置的提醒", 13);
        sub.setTypeface(Typeface.DEFAULT_BOLD);
        alertListContainer.addView(sub);

        if (alertRules.isEmpty()) {
            TextView empty = label("尚未设置价格提醒", 12);
            empty.setPadding(0, dp(6), 0, dp(4));
            alertListContainer.addView(empty);
            return;
        }

        for (int i = 0; i < alertRules.size(); i++) {
            final int index = i;
            PriceAlert a = alertRules.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView summary = label(alertSummary(a), 13);
            summary.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(summary, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button edit = new Button(this);
            edit.setText("修改");
            edit.setMinWidth(0);
            edit.setOnClickListener(v -> startEditAlert(index));
            row.addView(edit, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

            Button delete = new Button(this);
            delete.setText("删除");
            delete.setMinWidth(0);
            delete.setOnClickListener(v -> {
                alertRules.remove(index);
                if (editingAlertIndex == index) resetAlertEditor();
                else if (editingAlertIndex > index) editingAlertIndex--;
                renderAlertList();
            });
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(dp(72),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            delLp.setMargins(dp(4), 0, 0, 0);
            row.addView(delete, delLp);
            alertListContainer.addView(row, matchWrap());
        }
    }

    private String alertSummary(PriceAlert a) {
        return friendlyName(a.code) + "  " + a.operator() + "  " + formatTarget(a.target);
    }

    private String friendlyName(String code) {
        if ("GC=F".equalsIgnoreCase(code)) return "COMEX 黄金";
        if ("^IXIC".equalsIgnoreCase(code)) return "纳斯达克综合指数";
        if ("CNYRUB=X".equalsIgnoreCase(code)) return "人民币/卢布";
        if ("sh000688".equalsIgnoreCase(code)) return "科创50";
        return displayCode(code);
    }

    private String displayCode(String code) {
        if (code == null) return "";
        String lower = code.toLowerCase(Locale.ROOT);
        if (lower.matches("^(sh|sz|bj)\\d{6}$")) return code.substring(2);
        return code;
    }

    private String formatTarget(double value) {
        String s = String.format(Locale.US, "%.8f", value);
        s = s.replaceFirst("0+$", "").replaceFirst("\\.$", "");
        return s;
    }

    private String serializeAlerts() {
        StringBuilder out = new StringBuilder();
        for (PriceAlert a : alertRules) {
            if (out.length() > 0) out.append('\n');
            out.append(a.code)
                    .append(a.direction == PriceAlert.Direction.ABOVE_OR_EQUAL ? " >= " : " <= ")
                    .append(formatTarget(a.target));
        }
        return out.toString();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS || !waitingForNotificationPermission) return;
        waitingForNotificationPermission = false;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        applyAndFinish(granted ? "已应用，价格提醒已开启" : "已应用；通知权限未开启，价格提醒暂不会显示");
    }

    private void applyAndFinish(String message) {
        RefreshScheduler.schedule(this, widgetId, WidgetPrefs.refreshMinutes(this, widgetId));
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        StockWidgetProvider.updateOneAsync(this, manager, widgetId);

        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        setResult(RESULT_OK, result);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finish();
    }

    private TextView section(String text) {
        TextView v = label(text, 14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(16), 0, dp(4));
        return v;
    }

    private TextView label(String text, int size) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        return v;
    }

    private EditText colorInput(String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        e.setText(value);
        e.setHint("#242A35");
        return e;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int indexOf(int[] values, int target, int fallback) {
        for (int i = 0; i < values.length; i++) if (values[i] == target) return i;
        return fallback;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
