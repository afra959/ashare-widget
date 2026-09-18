package com.eternal.asharewidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StockWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "com.eternal.asharewidget.REFRESH";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final int UP = Color.rgb(255, 77, 79);
    private static final int DOWN = Color.rgb(82, 196, 26);
    private static final int FLAT = Color.rgb(170, 177, 188);

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RefreshScheduler.schedule(context, id, WidgetPrefs.refreshMinutes(context, id));
            updateOneAsync(context, manager, id);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            final PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            final AppWidgetManager manager = AppWidgetManager.getInstance(app);
            final int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            EXECUTOR.execute(() -> {
                try {
                    if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        updateOneBlocking(app, manager, id);
                    } else {
                        int[] ids = manager.getAppWidgetIds(new ComponentName(app, StockWidgetProvider.class));
                        for (int widgetId : ids) updateOneBlocking(app, manager, widgetId);
                    }
                } finally {
                    pending.finish();
                }
            });
            return;
        }

        super.onReceive(context, intent);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, StockWidgetProvider.class));
            for (int id : ids) {
                RefreshScheduler.schedule(context, id, WidgetPrefs.refreshMinutes(context, id));
                updateOneAsync(context, manager, id);
            }
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        updateOneAsync(context, manager, appWidgetId);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            RefreshScheduler.cancel(context, id);
            WidgetPrefs.delete(context, id);
        }
    }

    public static void updateOneAsync(Context context, AppWidgetManager manager, int id) {
        Context app = context.getApplicationContext();
        showLoading(app, manager, id);
        EXECUTOR.execute(() -> updateOneBlocking(app, manager, id));
    }

    private static void updateOneBlocking(Context app, AppWidgetManager manager, int id) {
        try {
            List<String> displayCodes = WidgetPrefs.codes(app, id);
            List<PriceAlert> alerts = WidgetPrefs.alerts(app, id);

            Set<String> fetchSet = new LinkedHashSet<>(displayCodes);
            for (PriceAlert alert : alerts) fetchSet.add(alert.code);

            int chartDays = WidgetPrefs.chartDays(app, id);
            List<Quote> allQuotes = QuoteFetcher.fetch(new ArrayList<>(fetchSet), chartDays);
            PriceAlertManager.evaluate(app, id, allQuotes);
            render(app, manager, id, selectDisplayQuotes(displayCodes, allQuotes), null);
        } catch (Exception e) {
            render(app, manager, id, null, "刷新失败");
        }
    }

    private static List<Quote> selectDisplayQuotes(List<String> displayCodes, List<Quote> allQuotes) {
        Map<String, Quote> byCode = new HashMap<>();
        for (Quote q : allQuotes) byCode.put(WidgetPrefs.normalize(q.code), q);
        List<Quote> out = new ArrayList<>();
        for (String code : displayCodes) {
            Quote q = byCode.get(WidgetPrefs.normalize(code));
            if (q != null) out.add(q);
        }
        return out;
    }

    private static void showLoading(Context c, AppWidgetManager m, int id) {
        RemoteViews rv = baseViews(c, m, id);
        rv.setTextViewText(R.id.widget_updated, "正在刷新…");
        m.updateAppWidget(id, rv);
    }

    private static RemoteViews baseViews(Context c, AppWidgetManager manager, int id) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_layout);
        int textColor = WidgetPrefs.textColor(c, id);
        int size = WidgetPrefs.textSizeSp(c, id);

        // User requested no visible "自选股" title.
        rv.setViewVisibility(R.id.widget_title, View.GONE);
        rv.setTextViewText(R.id.widget_title, "");
        rv.setTextColor(R.id.widget_updated, withAlpha(textColor, 185));
        rv.setTextColor(R.id.widget_settings, withAlpha(textColor, 215));
        rv.setTextColor(R.id.widget_refresh, withAlpha(textColor, 215));
        rv.setTextViewTextSize(R.id.widget_updated, TypedValue.COMPLEX_UNIT_SP, Math.max(10, size - 3));
        rv.setTextViewTextSize(R.id.widget_settings, TypedValue.COMPLEX_UNIT_SP, size + 7);
        rv.setTextViewTextSize(R.id.widget_refresh, TypedValue.COMPLEX_UNIT_SP, size + 8);

        Bundle opts = manager.getAppWidgetOptions(id);
        int width = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300);
        int height = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 360);
        rv.setImageViewBitmap(R.id.widget_background,
                WidgetBackgroundRenderer.render(
                        WidgetPrefs.backgroundColor(c, id),
                        WidgetPrefs.opacity(c, id), width, height));

        Intent refresh = new Intent(c, StockWidgetProvider.class);
        refresh.setAction(ACTION_REFRESH);
        refresh.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        PendingIntent refreshPi = PendingIntent.getBroadcast(c, id, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_refresh, refreshPi);

        Intent settings = new Intent(c, WidgetConfigActivity.class);
        settings.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        settings.setData(Uri.parse("asharewidget://settings/" + id));
        PendingIntent settingsPi = PendingIntent.getActivity(c, 10_000 + id, settings,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_settings, settingsPi);
        return rv;
    }

    private static void render(Context c, AppWidgetManager m, int id,
                               List<Quote> quotes, String error) {
        RemoteViews rv = baseViews(c, m, id);
        rv.removeAllViews(R.id.widget_rows);

        int baseSize = WidgetPrefs.textSizeSp(c, id);
        int textColor = WidgetPrefs.textColor(c, id);
        Bundle opts = m.getAppWidgetOptions(id);
        int height = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 360);
        int maxByHeight = Math.max(1, (height - 48) / 46);

        if (error != null) {
            rv.addView(R.id.widget_rows, messageRow(c, error, "点右上角 ↻ 重试", textColor, baseSize));
        } else if (quotes == null || quotes.isEmpty()) {
            rv.addView(R.id.widget_rows, messageRow(c, "暂无行情", "请点 ⚙ 检查代码", textColor, baseSize));
        } else {
            int maxRows = Math.min(quotes.size(), Math.min(maxByHeight, 10));
            for (int i = 0; i < maxRows; i++) {
                Quote q = quotes.get(i);
                RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_row);
                row.setTextViewText(R.id.row_name, q.name);
                row.setTextViewText(R.id.row_price, formatPrice(q));
                row.setTextViewText(R.id.row_change, formatChange(q));
                row.setTextViewText(R.id.row_percent,
                        String.format(Locale.CHINA, "%+.2f%%", q.percent()));

                int trendColor = q.percent() > 0 ? UP : (q.percent() < 0 ? DOWN : FLAT);
                row.setTextColor(R.id.row_name, textColor);
                row.setTextColor(R.id.row_price, textColor);
                row.setTextColor(R.id.row_change, trendColor);
                row.setTextColor(R.id.row_percent, trendColor);
                row.setTextViewTextSize(R.id.row_name, TypedValue.COMPLEX_UNIT_SP, baseSize);
                row.setTextViewTextSize(R.id.row_price, TypedValue.COMPLEX_UNIT_SP, baseSize);
                row.setTextViewTextSize(R.id.row_change, TypedValue.COMPLEX_UNIT_SP, Math.max(10, baseSize - 1));
                row.setTextViewTextSize(R.id.row_percent, TypedValue.COMPLEX_UNIT_SP, Math.max(10, baseSize - 1));
                row.setImageViewBitmap(R.id.row_spark,
                        SparklineRenderer.render(q.history, 64, 26, trendColor));
                rv.addView(R.id.widget_rows, row);
            }
        }

        rv.setTextViewText(R.id.widget_updated,
                new SimpleDateFormat("MM/dd HH:mm:ss", Locale.CHINA).format(new Date()));
        m.updateAppWidget(id, rv);
    }

    private static RemoteViews messageRow(Context c, String left, String right,
                                          int textColor, int size) {
        RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_row);
        row.setTextViewText(R.id.row_name, left);
        row.setTextViewText(R.id.row_price, "");
        row.setTextViewText(R.id.row_change, "");
        row.setTextViewText(R.id.row_percent, right);
        row.setTextColor(R.id.row_name, textColor);
        row.setTextColor(R.id.row_percent, withAlpha(textColor, 180));
        row.setTextViewTextSize(R.id.row_name, TypedValue.COMPLEX_UNIT_SP, size);
        row.setTextViewTextSize(R.id.row_percent, TypedValue.COMPLEX_UNIT_SP, Math.max(10, size - 2));
        row.setImageViewBitmap(R.id.row_spark,
                SparklineRenderer.render(null, 64, 26, FLAT));
        return row;
    }

    private static String formatPrice(Quote q) {
        String code = q.code == null ? "" : q.code.toLowerCase(Locale.ROOT);
        if (code.startsWith("fx_")) return new DecimalFormat("0.0000").format(q.price);
        return new DecimalFormat("0.00").format(q.price);
    }

    private static String formatChange(Quote q) {
        String code = q.code == null ? "" : q.code.toLowerCase(Locale.ROOT);
        String pattern = code.startsWith("fx_") ? "+0.0000;-0.0000" : "+0.00;-0.00";
        return new DecimalFormat(pattern).format(q.change());
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
