package com.fire119.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Small, durable activity feed. It records only actions actually started or completed on device. */
public final class AutomationLog {
    private static final String PREFS = "119fire_native";
    private static final String KEY = "automation_recent";
    private AutomationLog() {}

    public static synchronized void add(Context context, String message) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(p.getString(KEY, "[]"));
            JSONArray next = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("message", message == null ? "자동 처리" : message);
            item.put("at", System.currentTimeMillis());
            next.put(item);
            for (int i = 0; i < old.length() && i < 7; i++) next.put(old.getJSONObject(i));
            p.edit().putString(KEY, next.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static JSONArray recent(Context context, int limit) {
        JSONArray out = new JSONArray();
        try {
            JSONArray all = new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
            for (int i = 0; i < all.length() && i < limit; i++) out.put(all.getJSONObject(i));
        } catch (Exception ignored) { }
        return out;
    }
}
