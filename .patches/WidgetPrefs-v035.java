package com.eternal.asharewidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class WidgetPrefs {
    private static final String PREFS = "widget_prefs";
    private static final String DEFAULT_CODES =
            "603605\n600584\n600141\nsh000688\nhf_GC\ngb_ixic\nfx_scnyrub";
    private static final String DEFAULT_ALERTS =
            "603605 <= 55\n600584 >= 100\n600141 >= 45";

    private WidgetPrefs() {}

    public static void save(Context c, int id, String codes,
                            int chartDays, int refreshMinutes, int opacityPercent,
                            String backgroundHex, String textHex, int textSizeSp,
                            String alertsRaw) {
        String oldAlerts = alertsRaw(c, id);
        String newAlerts = cleanAlerts(alertsRaw);

        prefs(c).edit()
                .putString("codes_" + id, cleanCodes(codes))
                .putInt("chart_days_" + id, normalizeChartDays(chartDays))
                .putInt("refresh_" + id, refreshMinutes)
                .putInt("opacity_" + id, clamp(opacityPercent, 20, 100))
                .putString("bg_" + id, safeHex(backgroundHex, "#242A35"))
                .putString("text_" + id, safeHex(textHex, "#F3F4F6"))
                .putInt("size_" + id, clamp(textSizeSp, 12, 22))
                .putString("alerts_" + id, newAlerts)
                .apply();

        if (!oldAlerts.equals(newAlerts)) {
            PriceAlertManager.clearState(c, id);
        }
    }

    private static String cleanCodes(String codes) {
        return codes == null || codes.trim().isEmpty() ? DEFAULT_CODES : codes.trim();
    }

    private static String cleanAlerts(String alerts) {
        if (alerts == null) return "";
        return alerts.replace("\r", "").trim();
    }

    public static String codesRaw(Context c, int id) {
        return prefs(c).getString("codes_" + id, DEFAULT_CODES);
    }

    public static String alertsRaw(Context c, int id) {
        return prefs(c).getString("alerts_" + id, DEFAULT_ALERTS);
    }

    public static List<PriceAlert> alerts(Context c, int id) {
        try {
            return PriceAlert.parseLines(alertsRaw(c, id));
        } catch (IllegalArgumentException ignored) {
            return Collections.emptyList();
        }
    }

    public static int chartDays(Context c, int id) {
        return normalizeChartDays(prefs(c).getInt("chart_days_" + id, 20));
    }

    public static int refreshMinutes(Context c, int id) {
        return prefs(c).getInt("refresh_" + id, 15);
    }

    public static int opacity(Context c, int id) {
        return prefs(c).getInt("opacity_" + id, 92);
    }

    public static String backgroundHex(Context c, int id) {
        return prefs(c).getString("bg_" + id, "#242A35");
    }

    public static String textHex(Context c, int id) {
        return prefs(c).getString("text_" + id, "#F3F4F6");
    }

    public static int backgroundColor(Context c, int id) {
        return parseColor(backgroundHex(c, id), Color.rgb(36, 42, 53));
    }

    public static int textColor(Context c, int id) {
        return parseColor(textHex(c, id), Color.rgb(243, 244, 246));
    }

    public static int textSizeSp(Context c, int id) {
        return prefs(c).getInt("size_" + id, 16);
    }

    public static List<String> codes(Context c, int id) {
        return parseCodes(codesRaw(c, id));
    }

    public static List<String> parseCodes(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[\\s,;，；]+")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                String n = normalize(s);
                if (!n.isEmpty() && !out.contains(n)) out.add(n);
            }
        }
        return out;
    }

    public static void delete(Context c, int id) {
        prefs(c).edit()
                .remove("title_" + id)
                .remove("codes_" + id)
                .remove("chart_days_" + id)
                .remove("refresh_" + id)
                .remove("opacity_" + id)
                .remove("bg_" + id)
                .remove("text_" + id)
                .remove("size_" + id)
                .remove("alerts_" + id)
                .apply();
        PriceAlertManager.clearState(c, id);
        WidgetQuoteCache.clear(c, id);
    }

    public static String normalize(String input) {
        if (input == null) return "";
        String raw = input.trim();
        if (raw.isEmpty()) return "";
        String lower = raw.toLowerCase(Locale.ROOT);

        if ("黄金".equals(raw) || "gold".equals(lower) || "gc=f".equals(lower)
                || "hf_gc".equals(lower)) return "hf_GC";

        if ("纳指".equals(raw) || "纳斯达克".equals(raw)
                || "nasdaq".equals(lower) || "ixic".equals(lower)
                || "^ixic".equals(lower) || "gb_ixic".equals(lower)) return "gb_ixic";

        if ("人民币/卢布".equals(raw) || "人民币卢布".equals(raw)
                || "cnyrub".equals(lower) || "cnyrub=x".equals(lower)
                || "fx_scnyrub".equals(lower)) return "fx_scnyrub";

        if (lower.matches("^(sh|sz|bj)\\d{6}$")) return lower;

        if (lower.startsWith("hf_") && raw.length() > 3) {
            return "hf_" + raw.substring(3).toUpperCase(Locale.ROOT);
        }
        if (lower.startsWith("gb_") && raw.length() > 3) {
            return "gb_" + raw.substring(3).toLowerCase(Locale.ROOT);
        }
        if (lower.startsWith("fx_") && raw.length() > 3) {
            return lower;
        }

        if (!raw.matches("^\\d{6}$")) return raw;
        if (raw.startsWith("6") || raw.startsWith("5") || raw.startsWith("9")) return "sh" + raw;
        if (raw.startsWith("8") || raw.startsWith("4")) return "bj" + raw;
        return "sz" + raw;
    }

    private static int normalizeChartDays(int days) {
        int[] supported = {5, 20, 60, 120, 250};
        for (int v : supported) if (days == v) return v;
        return 20;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int parseColor(String value, int fallback) {
        try { return Color.parseColor(value); }
        catch (Exception e) { return fallback; }
    }

    private static String safeHex(String value, String fallback) {
        if (value == null) return fallback;
        String s = value.trim();
        if (!s.startsWith("#")) s = "#" + s;
        try { Color.parseColor(s); return s.toUpperCase(Locale.ROOT); }
        catch (Exception e) { return fallback; }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
