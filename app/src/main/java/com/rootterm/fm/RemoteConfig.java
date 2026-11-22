package com.rootterm.fm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;

/**
 * 云端配置（热更新）。
 *
 * <p>启动时从仓库拉取 {@code config.json}，可远端调整：</p>
 * <ul>
 *   <li>{@code notice} —— 公告（首次拉取后弹一次）</li>
 *   <li>{@code updateFeed} —— 更新源地址（可随时切镜像）</li>
 *   <li>{@code fontMap} —— 番茄字体混淆映射补丁（番茄换字体时无需发版）</li>
 *   <li>{@code minVersionCode} —— 低于此版本强制更新</li>
 * </ul>
 *
 * <p>拉取失败时使用本地缓存，绝不阻塞启动。</p>
 */
public final class RemoteConfig {

    /** 配置文件在仓库里的路径（走 api.github.com，国内可直连）。 */
    private static final String API_URL =
            "https://api.github.com/repos/CSHNB6663/TFManager/contents/config.json";

    private static final String PREF = "remote_cfg";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static String sRawJson;
    private static String sNotice = "";
    private static String sUpdateFeed = "";
    private static int sMinVersionCode;
    private static final HashMap<Integer, Character> sFontMap = new HashMap<Integer, Character>();
    private static boolean sLoaded;

    private RemoteConfig() {
    }

    /** 回调。 */
    public interface Callback {
        void onDone(boolean changed);
    }

    // ---------------------------------------------------------------- 读取

    public static String notice() {
        return sNotice;
    }

    /** 远端指定的更新源（空串表示用本地默认）。 */
    public static String updateFeed() {
        return sUpdateFeed;
    }

    public static int minVersionCode() {
        return sMinVersionCode;
    }

    /** 远端字体映射补丁（可能为空）。 */
    public static HashMap<Integer, Character> fontMap() {
        return sFontMap;
    }

    /** 从缓存加载（同步、极快，可在 onCreate 调用）。 */
    public static void loadCache(Context c) {
        if (sLoaded) {
            return;
        }
        sLoaded = true;
        try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sRawJson = p.getString("json", "");
            parse(sRawJson);
        } catch (Throwable ignored) {
        }
    }

    /** 后台刷新云端配置，结果回主线程。 */
    public static void refresh(final Context c, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean changed = false;
                try {
                    String json = fetch();
                    if (json != null && !json.isEmpty() && !json.equals(sRawJson)) {
                        sRawJson = json;
                        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                                .edit().putString("json", json).apply();
                        parse(json);
                        changed = true;
                    }
                } catch (Throwable ignored) {
                }
                final boolean fChanged = changed;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) {
                            cb.onDone(fChanged);
                        }
                    }
                });
            }
        }, "remote-cfg").start();
    }

    // ---------------------------------------------------------------- 网络

    private static String fetch() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API_URL).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "TFManager/" + AppVersion.NAME);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            String body = bos.toString("UTF-8");
            // GitHub contents API 返回 {"content":"base64...","encoding":"base64"}
            String b64 = jsonStr(body, "content");
            if (b64 != null && !b64.isEmpty()) {
                String clean = b64.replaceAll("\\s", "");
                byte[] data = android.util.Base64.decode(clean,
                        android.util.Base64.DEFAULT);
                return new String(data, "UTF-8");
            }
            // 兼容直接给裸 JSON 的情况
            if (body.trim().startsWith("{")) {
                return body;
            }
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------- 解析

    private static void parse(String json) {
        sNotice = "";
        sUpdateFeed = "";
        sMinVersionCode = 0;
        sFontMap.clear();
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        try {
            String notice = jsonStr(json, "notice");
            if (notice != null) {
                sNotice = notice;
            }
            String feed = jsonStr(json, "updateFeed");
            if (feed != null) {
                sUpdateFeed = feed.trim();
            }
            Integer min = jsonNum(json, "minVersionCode");
            if (min != null) {
                sMinVersionCode = min.intValue();
            }
            // fontMap: {"22709":"她", ...}  十进制码位 → 字符
            int i = json.indexOf("\"fontMap\"");
            if (i >= 0) {
                int open = json.indexOf('{', i);
                int close = json.indexOf('}', open);
                if (open > 0 && close > open) {
                    String obj = json.substring(open + 1, close);
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("\"(\\d+)\"\\s*:\\s*\"(.)\"")
                            .matcher(obj);
                    while (m.find()) {
                        try {
                            int cp = Integer.parseInt(m.group(1));
                            sFontMap.put(cp, m.group(2).charAt(0));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String jsonStr(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return null;
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = q1 + 1; k < json.length(); k++) {
            char ch = json.charAt(k);
            if (ch == '\\' && k + 1 < json.length()) {
                char e = json.charAt(++k);
                if (e == 'n') {
                    sb.append('\n');
                } else if (e == 't') {
                    sb.append('\t');
                } else if (e == 'u' && k + 4 < json.length()) {
                    try {
                        sb.append((char) Integer.parseInt(
                                json.substring(k + 1, k + 5), 16));
                        k += 4;
                    } catch (Throwable ignored) {
                    }
                } else {
                    sb.append(e);
                }
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static Integer jsonNum(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return null;
        }
        int k = colon + 1;
        while (k < json.length() && !Character.isDigit(json.charAt(k))
                && json.charAt(k) != '-') {
            k++;
        }
        int st = k;
        while (k < json.length() && (Character.isDigit(json.charAt(k))
                || json.charAt(k) == '-')) {
            k++;
        }
        try {
            return Integer.valueOf(json.substring(st, k));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否已有可用配置。 */
    public static boolean hasConfig() {
        return sRawJson != null && !sRawJson.isEmpty();
    }
}
