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
                        int[] ids = manager.getAppWidgetIds(
                                new ComponentName(app, StockWidgetProvider.class));
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
            int[] ids = manager.getAppWidgetIds(
                    new ComponentName(context, StockWidgetProvider.class));
            for (int id : ids) {
                RefreshScheduler.schedule(context, id, WidgetPrefs.refreshMinutes(context, id));
                updateOneAsync(context, manager, id);
            }
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        updateShell(context, manager, appWidgetId, false);
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_rows);
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
        updateShell(app, manager, id, true);
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

            List<Quote> displayQuotes = selectDisplayQuotes(displayCodes, allQuotes);
            WidgetQuoteCache.save(app, id, displayQuotes, null);

            updateShell(app, manager, id, false);
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_rows);
        } catch (Exception e) {
            WidgetQuoteCache.save(app, id, new ArrayList<>(), "刷新失败");
            updateShell(app, manager, id, false);
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_rows);
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

    private static void updateShell(Context c, AppWidgetManager manager, int id, boolean loading) {
        RemoteViews rv = baseViews(c, manager, id);
        rv.setTextViewText(R.id.widget_updated,
                loading ? "正在刷新…" :
                        new SimpleDateFormat("MM/dd HH:mm:ss", Locale.CHINA).format(new Date()));
        manager.updateAppWidget(id, rv);
    }

    private static RemoteViews baseViews(Context c, AppWidgetManager manager, int id) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_layout);
        int textColor = WidgetPrefs.textColor(c, id);
        int size = WidgetPrefs.textSizeSp(c, id);

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

        Intent service = new Intent(c, QuoteListRemoteViewsService.class);
        service.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        service.setData(Uri.parse("asharewidget://rows/" + id));
        rv.setRemoteAdapter(R.id.widget_rows, service);

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

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
