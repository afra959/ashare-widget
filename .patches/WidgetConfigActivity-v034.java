package com.eternal.asharewidget;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WidgetConfigActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 401;
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private boolean waitingForNotificationPermission = false;

    private static final String[] REFRESH_LABELS = {
            "仅手动刷新", "每 5 分钟", "每 15 分钟", "每 30 分钟", "每 60 分钟", "每 120 分钟"
    };
    private static final int[] REFRESH_VALUES = {0, 5, 15, 30, 60, 120};

    private static final String[] CHART_LABELS = {
            "5日（约1周）", "20日（约1月）", "60日（约1季）", "120日（约半年）", "250日（约1年）"
    };
    private static final int[] CHART_VALUES = {5, 20, 60, 120, 250};

    private static final String[] SIZE_LABELS = {
            "小（13sp）", "中（15sp）", "大（17sp）", "特大（19sp）", "超大（21sp）"
    };
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

    private final Handler nameHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService nameExecutor = Executors.newSingleThreadExecutor();
    private Runnable pendingNameLookup;
    private int nameLookupGeneration = 0;
    private TextView codeNamePreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

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
        h.setText("配置轻行情");
        h.setTextSize(22);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(h);

        TextView tips = label(
                "数据源：沪深北股票和大陆指数使用腾讯财经；COMEX期货、纳指和外汇使用新浪财经。\n"
                        + "推荐直接使用源代码：sh603605、sh000688、hf_GC、gb_ixic、fx_scnyrub。\n"
                        + "A股也可以只填6位数字，程序会自动补 sh / sz / bj。旧代码 GC=F、^IXIC、CNYRUB=X 也会自动转换。", 13);
        tips.setPadding(0, dp(8), 0, dp(14));
        root.addView(tips);

        root.addView(section("行情代码"));
        EditText codes = new EditText(this);
        codes.setGravity(Gravity.TOP);
        codes.setMinLines(8);
        codes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        codes.setText(WidgetPrefs.codesRaw(this, widgetId));
        root.addView(codes, matchWrap());

        TextView codeHint = label(
                "每行一个。新增或修改代码后，下方会自动识别名称；桌面组件只显示名称，不显示代码。", 12);
        root.addView(codeHint);

        codeNamePreview = label("代码名称识别：", 12);
        codeNamePreview.setPadding(0, dp(8), 0, dp(4));
        root.addView(codeNamePreview, matchWrap());

        codes.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                scheduleNamePreview(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        scheduleNamePreview(codes.getText().toString());

        root.addView(section("折线图周期"));
        Spinner chartDays = new Spinner(this);
        chartDays.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, CHART_LABELS));
        chartDays.setSelection(indexOf(CHART_VALUES, WidgetPrefs.chartDays(this, widgetId), 1));
        root.addView(chartDays, matchWrap());
        root.addView(label(
                "建议默认 20 日：5日看短线波动，20日看约一个月趋势，60日看中期，120/250日适合观察半年到一年大方向。"
                        + "小组件折线较小，20日和60日通常最容易读。", 12));

        root.addView(section("价格提醒（通知栏）"));
        root.addView(label(
                "可直接新增、修改或删除提醒；提醒标的不一定要出现在桌面行情列表。", 12));

        alertCodeInput = new EditText(this);
        alertCodeInput.setSingleLine(true);
        alertCodeInput.setHint("代码，例如 603605、hf_GC、gb_ixic");
        alertCodeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        root.addView(alertCodeInput, matchWrap());

        LinearLayout alertEditRow = new LinearLayout(this);
        alertEditRow.setOrientation(LinearLayout.HORIZONTAL);
        alertEditRow.setGravity(Gravity.CENTER_VERTICAL);
        alertEditRow.setPadding(0, dp(4), 0, 0);

        alertDirectionSpinner = new Spinner(this);
        alertDirectionSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, ALERT_DIRECTION_LABELS));
        LinearLayout.LayoutParams directionLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f);
        alertEditRow.addView(alertDirectionSpinner, directionLp);

        alertTargetInput = new EditText(this);
        alertTargetInput.setSingleLine(true);
        alertTargetInput.setHint("目标价");
        alertTargetInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams targetLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f);
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
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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
                        + "提醒检查频率与下面的自动刷新相同；若选“仅手动刷新”，只在手动刷新时检查。", 12);
        alertTip.setPadding(0, dp(4), 0, 0);
        root.addView(alertTip);

        alertAddUpdateButton.setOnClickListener(v -> addOrUpdateAlert());
        alertCancelEditButton.setOnClickListener(v -> resetAlertEditor());

        root.addView(section("自动刷新"));
        Spinner refresh = new Spinner(this);
        refresh.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, REFRESH_LABELS));
        refresh.setSelection(indexOf(REFRESH_VALUES, WidgetPrefs.refreshMinutes(this, widgetId), 2));
        root.addView(refresh, matchWrap());
        root.addView(label(
                "Android / HyperOS 可能为了省电延后后台刷新；右上角 ↻ 始终可立即手动刷新。价格提醒也受这一限制。", 12));

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
        root.addView(label("输入 HEX，例如 #242A35；纯黑为 #000000。", 12));

        root.addView(section("字体颜色"));
        EditText textColor = colorInput(WidgetPrefs.textHex(this, widgetId));
        root.addView(textColor, matchWrap());
        root.addView(label("名称、价格和更新时间使用此颜色；涨跌额/涨跌幅固定红涨绿跌。", 12));

        root.addView(section("字号"));
        Spinner size = new Spinner(this);
        size.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, SIZE_LABELS));
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
            int selectedChartDays = CHART_VALUES[chartDays.getSelectedItemPosition()];
            int textSize = SIZE_VALUES[size.getSelectedItemPosition()];
            int opacityPercent = opacity.getProgress() + 20;

            WidgetPrefs.save(this, widgetId,
                    codes.getText().toString(),
                    selectedChartDays,
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
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS);
                return;
            }
            applyAndFinish("已应用");
        });

        setContentView(scroll);
    }

    private void scheduleNamePreview(String raw) {
        final int generation = ++nameLookupGeneration;
        if (pendingNameLookup != null) nameHandler.removeCallbacks(pendingNameLookup);

        List<String> now = WidgetPrefs.parseCodes(raw);
        renderCodeNamePreview(now, null, true);

        pendingNameLookup = () -> {
            final List<String> codes = WidgetPrefs.parseCodes(raw);
            if (codes.isEmpty()) {
                renderCodeNamePreview(codes, null, false);
                return;
            }
            nameExecutor.execute(() -> {
                Map<String, String> names = QuoteFetcher.fetchNames(codes);
                nameHandler.post(() -> {
                    if (generation != nameLookupGeneration || isFinishing()) return;
                    renderCodeNamePreview(codes, names, false);
                });
            });
        };
        nameHandler.postDelayed(pendingNameLookup, 550);
    }

    private void renderCodeNamePreview(List<String> codes, Map<String, String> names, boolean loading) {
        if (codeNamePreview == null) return;
        if (codes == null || codes.isEmpty()) {
            codeNamePreview.setText("代码名称识别：尚未填写代码");
            return;
        }

        StringBuilder sb = new StringBuilder("代码名称识别：\n");
        int shown = 0;
        for (String code : codes) {
            if (shown >= 12) {
                sb.append("…其余 ").append(codes.size() - shown).append(" 个代码保存后仍会正常使用");
                break;
            }
            String name = names == null ? null : names.get(code);
            if (name == null) name = QuoteFetcher.knownName(code);
            sb.append(code).append("  →  ");
            if (name != null && !name.trim().isEmpty()) sb.append(name);
            else sb.append(loading ? "识别中…" : "未识别到名称");
            sb.append('\n');
            shown++;
        }
        codeNamePreview.setText(sb.toString().trim());
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
        alertCodeInput.setText(a.code);
        alertTargetInput.setText(formatTarget(a.target));
        alertDirectionSpinner.setSelection(
                a.direction == PriceAlert.Direction.ABOVE_OR_EQUAL ? 0 : 1);
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
            row.addView(summary, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button edit = new Button(this);
            edit.setText("修改");
            edit.setMinWidth(0);
            edit.setOnClickListener(v -> startEditAlert(index));
            row.addView(edit, new LinearLayout.LayoutParams(
                    dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

            Button delete = new Button(this);
            delete.setText("删除");
            delete.setMinWidth(0);
            delete.setOnClickListener(v -> {
                alertRules.remove(index);
                if (editingAlertIndex == index) resetAlertEditor();
                else if (editingAlertIndex > index) editingAlertIndex--;
                renderAlertList();
            });
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                    dp(72), ViewGroup.LayoutParams.WRAP_CONTENT);
            delLp.setMargins(dp(4), 0, 0, 0);
            row.addView(delete, delLp);
            alertListContainer.addView(row, matchWrap());
        }
    }

    private String alertSummary(PriceAlert a) {
        return friendlyName(a.code) + "  " + a.operator() + "  " + formatTarget(a.target);
    }

    private String friendlyName(String code) {
        String known = QuoteFetcher.knownName(code);
        if (known != null) return known;
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
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        applyAndFinish(granted
                ? "已应用，价格提醒已开启"
                : "已应用；通知权限未开启，价格提醒暂不会显示");
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

    @Override
    protected void onDestroy() {
        if (pendingNameLookup != null) nameHandler.removeCallbacks(pendingNameLookup);
        nameLookupGeneration++;
        nameExecutor.shutdownNow();
        super.onDestroy();
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
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int indexOf(int[] values, int target, int fallback) {
        for (int i = 0; i < values.length; i++) if (values[i] == target) return i;
        return fallback;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
