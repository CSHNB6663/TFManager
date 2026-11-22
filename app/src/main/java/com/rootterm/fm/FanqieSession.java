package com.rootterm.fm;

import android.content.Context;
import android.content.SharedPreferences;

/** 番茄小说登录态（cookie）持久化。 */
public final class FanqieSession {

    private static final String PREF = "fanqie";

    private FanqieSession() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String cookie(Context c) {
        String v = p(c).getString("cookie", "");
        return v == null ? "" : v;
    }

    public static void save(Context c, String cookie) {
        p(c).edit().putString("cookie", cookie == null ? "" : cookie).apply();
    }

    public static void clear(Context c) {
        p(c).edit().remove("cookie").apply();
    }

    public static boolean loggedIn(Context c) {
        String v = cookie(c);
        return v != null && v.contains("sessionid");
    }
}
