package com.eternal.asharewidget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.TypedValue;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class QuoteListRemoteViewsService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        return new Factory(getApplicationContext(), id);
    }

    private static final class Factory implements RemoteViewsFactory {
        private static final int UP = Color.rgb(255, 77, 79);
        private static final int DOWN = Color.rgb(82, 196, 26);
        private static final int FLAT = Color.rgb(170, 177, 188);

        private final Context context;
        private final int widgetId;
        private List<Quote> quotes = new ArrayList<>();
        private String error = "";

        Factory(Context context, int widgetId) {
            this.context = context;
            this.widgetId = widgetId;
        }

        @Override public void onCreate() { reload(); }
        @Override public void onDataSetChanged() { reload(); }
        @Override public void onDestroy() { quotes.clear(); }

        private void reload() {
            quotes = WidgetQuoteCache.load(context, widgetId);
            error = WidgetQuoteCache.error(context, widgetId);
        }

        @Override
        public int getCount() {
            if (!error.isEmpty() && quotes.isEmpty()) return 1;
            if (quotes.isEmpty()) return 1;
            return quotes.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            int size = WidgetPrefs.textSizeSp(context, widgetId);
            int textColor = WidgetPrefs.textColor(context, widgetId);

            if (!error.isEmpty() && quotes.isEmpty()) {
                return messageRow("刷新失败", "点右上角 ↻ 重试", textColor, size);
            }
            if (quotes.isEmpty()) {
                return messageRow("暂无行情", "请点 ⚙ 检查代码", textColor, size);
            }

            Quote q = quotes.get(position);
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_row);
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
            row.setTextViewTextSize(R.id.row_name, TypedValue.COMPLEX_UNIT_SP, size);
            row.setTextViewTextSize(R.id.row_price, TypedValue.COMPLEX_UNIT_SP, size);
            row.setTextViewTextSize(R.id.row_change, TypedValue.COMPLEX_UNIT_SP, Math.max(10, size - 1));
            row.setTextViewTextSize(R.id.row_percent, TypedValue.COMPLEX_UNIT_SP, Math.max(10, size - 1));
            row.setImageViewBitmap(R.id.row_spark,
                    SparklineRenderer.render(q.history, 64, 26, trendColor));
            return row;
        }

        private RemoteViews messageRow(String left, String right, int color, int size) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_row);
            row.setTextViewText(R.id.row_name, left);
            row.setTextViewText(R.id.row_price, "");
            row.setTextViewText(R.id.row_change, "");
            row.setTextViewText(R.id.row_percent, right);
            row.setTextColor(R.id.row_name, color);
            row.setTextColor(R.id.row_percent, withAlpha(color, 180));
            row.setTextViewTextSize(R.id.row_name, TypedValue.COMPLEX_UNIT_SP, size);
            row.setTextViewTextSize(R.id.row_percent, TypedValue.COMPLEX_UNIT_SP, Math.max(10, size - 2));
            row.setImageViewBitmap(R.id.row_spark,
                    SparklineRenderer.render(null, 64, 26, FLAT));
            return row;
        }

        @Override public RemoteViews getLoadingView() {
            return messageRow("正在加载…", "", WidgetPrefs.textColor(context, widgetId),
                    WidgetPrefs.textSizeSp(context, widgetId));
        }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean hasStableIds() { return true; }

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
}
