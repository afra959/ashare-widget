package com.eternal.asharewidget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class WidgetQuoteCache {
    private static final String PREFS = "widget_quote_cache";

    private WidgetQuoteCache() {}

    public static void save(Context c, int id, List<Quote> quotes, String error) {
        JSONArray arr = new JSONArray();
        if (quotes != null) {
            for (Quote q : quotes) {
                JSONObject o = new JSONObject();
                try {
                    o.put("code", q.code);
                    o.put("name", q.name);
                    o.put("price", q.price);
                    o.put("prev", q.previousClose);
                    JSONArray hist = new JSONArray();
                    for (Double v : q.history) hist.put(v);
                    o.put("history", hist);
                    arr.put(o);
                } catch (Exception ignored) {}
            }
        }
        prefs(c).edit()
                .putString("quotes_" + id, arr.toString())
                .putString("error_" + id, error == null ? "" : error)
                .apply();
    }

    public static List<Quote> load(Context c, int id) {
        List<Quote> out = new ArrayList<>();
        String raw = prefs(c).getString("quotes_" + id, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                List<Double> hist = new ArrayList<>();
                JSONArray h = o.optJSONArray("history");
                if (h != null) {
                    for (int j = 0; j < h.length(); j++) hist.add(h.optDouble(j));
                }
                out.add(new Quote(
                        o.optString("code"),
                        o.optString("name"),
                        o.optDouble("price"),
                        o.optDouble("prev"),
                        hist));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static String error(Context c, int id) {
        return prefs(c).getString("error_" + id, "");
    }

    public static void clear(Context c, int id) {
        prefs(c).edit()
                .remove("quotes_" + id)
                .remove("error_" + id)
                .apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
