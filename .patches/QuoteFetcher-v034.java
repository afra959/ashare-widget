package com.eternal.asharewidget;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Market data without Yahoo:
 * - A shares / mainland indices: Tencent Finance
 * - COMEX futures / US indices / FX: Sina Finance
 */
public final class QuoteFetcher {
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final String SINA_REFERER = "https://finance.sina.com.cn/";

    private QuoteFetcher() {}

    public static List<Quote> fetch(List<String> codes) throws Exception {
        return fetch(codes, 20);
    }

    public static List<Quote> fetch(List<String> codes, int historyDays) throws Exception {
        List<Quote> out = new ArrayList<>();
        if (codes == null || codes.isEmpty()) return out;
        int days = Math.max(2, Math.min(250, historyDays));

        List<String> normalized = new ArrayList<>();
        List<String> cn = new ArrayList<>();
        List<String> sina = new ArrayList<>();
        for (String raw : codes) {
            String code = WidgetPrefs.normalize(raw);
            if (code.isEmpty()) continue;
            normalized.add(code);
            if (isChina(code)) cn.add(code);
            else if (isSina(code)) sina.add(code);
        }

        Map<String, Quote> basics = new HashMap<>();
        basics.putAll(fetchTencentQuotes(cn));
        basics.putAll(fetchSinaQuotes(sina));

        for (String code : normalized) {
            Quote base = basics.get(code);
            if (base == null) continue;
            List<Double> history = new ArrayList<>();
            try {
                history = isChina(code)
                        ? fetchTencentHistory(code, days)
                        : fetchSinaHistory(code, days);
            } catch (Exception ignored) {
                // Keep realtime quote even when the chart endpoint is unavailable.
            }
            out.add(new Quote(base.code, base.name, base.price, base.previousClose, history));
        }
        return out;
    }

    /**
     * Lightweight name lookup for the configuration screen.
     * Does not request K-line history.
     */
    public static Map<String, String> fetchNames(List<String> codes) {
        Map<String, String> names = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) return names;

        List<String> cn = new ArrayList<>();
        List<String> sina = new ArrayList<>();
        for (String raw : codes) {
            String code = WidgetPrefs.normalize(raw);
            if (code.isEmpty()) continue;
            String known = knownName(code);
            if (known != null) names.put(code, known);
            if (isChina(code)) cn.add(code);
            else if (isSina(code)) sina.add(code);
        }

        try {
            for (Quote q : fetchTencentQuotes(cn).values()) names.put(q.code, q.name);
        } catch (Exception ignored) {}
        try {
            for (Quote q : fetchSinaQuotes(sina).values()) names.put(q.code, q.name);
        } catch (Exception ignored) {}
        return names;
    }

    public static String knownName(String code) {
        String c = WidgetPrefs.normalize(code);
        if ("hf_GC".equals(c)) return "COMEX黄金";
        if ("gb_ixic".equals(c)) return "纳斯达克综合指数";
        if ("fx_scnyrub".equals(c)) return "人民币/卢布";
        if ("sh000688".equals(c)) return "科创50";
        return null;
    }

    private static boolean isChina(String code) {
        return code != null && code.matches("^(sh|sz|bj)\\d{6}$");
    }

    private static boolean isSina(String code) {
        if (code == null) return false;
        String lower = code.toLowerCase(Locale.ROOT);
        return lower.startsWith("hf_") || lower.startsWith("gb_") || lower.startsWith("fx_");
    }

    private static Map<String, Quote> fetchTencentQuotes(List<String> codes) throws Exception {
        Map<String, Quote> byCode = new HashMap<>();
        if (codes == null || codes.isEmpty()) return byCode;

        StringBuilder q = new StringBuilder();
        for (String code : codes) {
            if (q.length() > 0) q.append(',');
            q.append(code);
        }

        String body = httpGet("https://qt.gtimg.cn/q=" + q, Charset.forName("GBK"),
                "https://gu.qq.com/");
        for (String line : body.split(";")) {
            int eq = line.indexOf('=');
            int firstQuote = line.indexOf('"');
            int lastQuote = line.lastIndexOf('"');
            if (eq < 0 || firstQuote < 0 || lastQuote <= firstQuote) continue;
            String left = line.substring(0, eq).trim();
            String code = WidgetPrefs.normalize(left.replace("v_", ""));
            String payload = line.substring(firstQuote + 1, lastQuote);
            String[] f = payload.split("~", -1);
            if (f.length < 5) continue;
            String name = f[1].trim();
            double price = parse(f[3]);
            double prev = parse(f[4]);
            if (name.isEmpty() || price <= 0) continue;
            byCode.put(code, new Quote(code, name, price, prev, null));
        }
        return byCode;
    }

    private static List<Double> fetchTencentHistory(String code, int days) throws Exception {
        String u = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param="
                + code + ",day,,," + days + ",qfq";
        String body = httpGet(u, StandardCharsets.UTF_8, "https://finance.qq.com/");
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("data");
        if (data == null) return new ArrayList<>();
        JSONObject symbol = data.optJSONObject(code);
        if (symbol == null) return new ArrayList<>();
        JSONArray rows = symbol.optJSONArray("qfqday");
        if (rows == null) rows = symbol.optJSONArray("day");
        List<Double> closes = new ArrayList<>();
        if (rows == null) return closes;
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null || row.length() < 3) continue;
            double close = parse(row.optString(2));
            if (close > 0) closes.add(close);
        }
        return tail(closes, days);
    }

    private static Map<String, Quote> fetchSinaQuotes(List<String> codes) throws Exception {
        Map<String, Quote> byCode = new HashMap<>();
        if (codes == null || codes.isEmpty()) return byCode;

        StringBuilder q = new StringBuilder();
        for (String code : codes) {
            if (q.length() > 0) q.append(',');
            q.append(code);
        }
        String body = httpGet("https://hq.sinajs.cn/list=" + q, GB18030, SINA_REFERER);
        for (String line : body.split(";")) {
            int eq = line.indexOf('=');
            int firstQuote = line.indexOf('"');
            int lastQuote = line.lastIndexOf('"');
            if (eq < 0 || firstQuote < 0 || lastQuote <= firstQuote) continue;

            String left = line.substring(0, eq).trim();
            int marker = left.indexOf("hq_str_");
            if (marker < 0) continue;
            String rawCode = left.substring(marker + "hq_str_".length());
            String code = WidgetPrefs.normalize(rawCode);
            String payload = line.substring(firstQuote + 1, lastQuote);
            if (payload.trim().isEmpty()) continue;
            String[] f = payload.split(",", -1);

            Quote qv = null;
            if (code.toLowerCase(Locale.ROOT).startsWith("hf_")) {
                qv = parseSinaFuture(code, f);
            } else if (code.toLowerCase(Locale.ROOT).startsWith("gb_")) {
                qv = parseSinaUs(code, f);
            } else if (code.toLowerCase(Locale.ROOT).startsWith("fx_")) {
                qv = parseSinaForex(code, f);
            }
            if (qv != null) byCode.put(code, qv);
        }
        return byCode;
    }

    private static Quote parseSinaFuture(String code, String[] f) {
        if (f.length < 2) return null;
        double price = parse(f[0]);
        double pct = parse(f[1]);
        if (price <= 0) return null;
        double prev = previousFromPercent(price, pct);
        String name = knownName(code);
        if (name == null && f.length > 13 && !f[13].trim().isEmpty()) name = f[13].trim();
        if (name == null) name = code;
        return new Quote(code, name, price, prev, null);
    }

    private static Quote parseSinaUs(String code, String[] f) {
        if (f.length < 3) return null;
        String name = f[0].trim();
        double price = parse(f[1]);
        double pct = parse(f[2]);
        if (price <= 0) return null;
        double prev = previousFromPercent(price, pct);
        String fixed = knownName(code);
        if (fixed != null) name = fixed;
        if (name.isEmpty()) name = code;
        return new Quote(code, name, price, prev, null);
    }

    private static Quote parseSinaForex(String code, String[] f) {
        // Sina forex: time,buy,sell,close_prev,volatility,open,high,low,close,name,...
        if (f.length < 9) return null;
        double price = parse(f[8]);
        double prev = parse(f[3]);
        if (price <= 0) return null;
        String name = f.length > 9 ? f[9].trim() : "";
        String fixed = knownName(code);
        if (fixed != null) name = fixed;
        if (name.isEmpty()) name = code;
        return new Quote(code, name, price, prev, null);
    }

    private static double previousFromPercent(double price, double pct) {
        double d = 1.0 + pct / 100.0;
        if (price <= 0 || Math.abs(d) < 1e-9) return 0;
        return price / d;
    }

    private static List<Double> fetchSinaHistory(String code, int days) throws Exception {
        if ("hf_GC".equals(code)) return fetchSinaFutureHistory("GC", days);
        if ("gb_ixic".equals(code)) return fetchSinaUsHistory(".IXIC", days);
        if ("fx_scnyrub".equals(code)) return fetchSinaForexHistory("fx_scnyrub", days);

        String lower = code.toLowerCase(Locale.ROOT);
        if (lower.startsWith("hf_")) return fetchSinaFutureHistory(code.substring(3), days);
        if (lower.startsWith("gb_")) return fetchSinaUsHistory(code.substring(3).toUpperCase(Locale.ROOT), days);
        if (lower.startsWith("fx_")) return fetchSinaForexHistory(code, days);
        return new ArrayList<>();
    }

    private static List<Double> fetchSinaFutureHistory(String symbol, int days) throws Exception {
        String u = "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var=/"
                + "GlobalFuturesService.getGlobalFuturesDailyKLine?symbol=" + symbol;
        String body = httpGet(u, StandardCharsets.UTF_8, SINA_REFERER);
        return parseJsonpCloses(body, days);
    }

    private static List<Double> fetchSinaUsHistory(String symbol, int days) throws Exception {
        String safe = symbol.startsWith(".") ? symbol : symbol.toUpperCase(Locale.ROOT);
        String u = "https://stock.finance.sina.com.cn/usstock/api/jsonp.php/var/"
                + "US_MinKService.getDailyK?symbol=" + safe + "&num=" + Math.max(days, 20);
        String body = httpGet(u, StandardCharsets.UTF_8, SINA_REFERER);
        return parseJsonpCloses(body, days);
    }

    private static List<Double> fetchSinaForexHistory(String symbol, int days) throws Exception {
        String u = "https://vip.stock.finance.sina.com.cn/forex/api/jsonp.php/var=/"
                + "NewForexService.getDayKLine?symbol=" + symbol;
        String body = httpGet(u, StandardCharsets.UTF_8, SINA_REFERER);

        List<Double> fromJson = parseJsonpCloses(body, days);
        if (!fromJson.isEmpty()) return fromJson;

        // Some Sina forex responses wrap a pipe-separated payload in JSONP.
        // Strip the outer parentheses/quotes without fragile escaped quote matching.
        int openParen = body.indexOf('(');
        int closeParen = body.lastIndexOf(')');
        if (openParen < 0 || closeParen <= openParen) return new ArrayList<>();

        String payload = body.substring(openParen + 1, closeParen).trim();
        if (payload.length() >= 2 && payload.charAt(0) == '"' && payload.charAt(payload.length() - 1) == '"') {
            payload = payload.substring(1, payload.length() - 1);
        }
        payload = payload.replace("\\\"", "\"").replace("\\/", "/");

        List<Double> closes = new ArrayList<>();
        for (String rec : payload.split("\\|")) {
            String rowText = rec.trim();
            if (rowText.startsWith(",")) rowText = rowText.substring(1);
            if (rowText.isEmpty()) continue;
            String[] f = rowText.split(",");
            // Historical forex daily rows are normally date,open,high,low,close.
            if (f.length >= 5) {
                double close = parse(f[4]);
                if (close > 0) closes.add(close);
            }
        }
        return tail(closes, days);
    }

    private static List<Double> parseJsonpCloses(String body, int days) {
        List<Double> closes = new ArrayList<>();
        if (body == null) return closes;
        int start = body.indexOf('[');
        int end = body.lastIndexOf(']');
        if (start < 0 || end <= start) return closes;
        try {
            JSONArray rows = new JSONArray(body.substring(start, end + 1));
            for (int i = 0; i < rows.length(); i++) {
                Object item = rows.opt(i);
                double close = 0;
                if (item instanceof JSONArray) {
                    JSONArray row = (JSONArray) item;
                    if (row.length() >= 5) close = parse(row.optString(4));
                    if (close <= 0 && row.length() >= 3) close = parse(row.optString(2));
                } else if (item instanceof JSONObject) {
                    JSONObject row = (JSONObject) item;
                    close = parse(row.optString("close"));
                    if (close <= 0) close = parse(row.optString("c"));
                }
                if (close > 0) closes.add(close);
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return tail(closes, days);
    }

    private static List<Double> tail(List<Double> values, int count) {
        if (values == null || values.isEmpty()) return new ArrayList<>();
        int from = Math.max(0, values.size() - Math.max(2, count));
        return new ArrayList<>(values.subList(from, values.size()));
    }

    private static String httpGet(String url, Charset charset, String referer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(7000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 AshareWidget/0.3.4");
        if (referer != null) conn.setRequestProperty("Referer", referer);
        conn.connect();
        int response = conn.getResponseCode();
        if (response != 200) {
            conn.disconnect();
            throw new IllegalStateException("HTTP " + response);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream())) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        return new String(bos.toByteArray(), charset);
    }

    private static double parse(String s) {
        try {
            if (s == null) return 0;
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
