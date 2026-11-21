package com.rootterm.fm;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 版本更新检查（纯 framework，无第三方库）。
 *
 * <p>支持两种更新源格式：</p>
 * <ul>
 *   <li><b>GitHub Releases API</b>（如
 *       {@code https://api.github.com/repos/owner/repo/releases/latest}）：
 *       读取 {@code tag_name} / {@code body} / 第一个 APK 资源的
 *       {@code browser_download_url}；</li>
 *   <li><b>简单 JSON 清单</b>：
 *       <code>{"versionCode":57,"versionName":"2.7.1","url":"https://.../app.apk","notes":"修复..."}</code>。</li>
 * </ul>
 */
public final class UpdateChecker {

    /** 检查结果。 */
    public static final class Result {
        public boolean ok;
        public String versionName = "";
        public int versionCode;
        public String notes = "";
        public String downloadUrl = "";
        public String error = "";
    }

    public interface Callback {
        void onResult(Result r);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {
    }

    /** 后台线程检查，结果回到主线程。 */
    public static void check(final String feedUrl, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Result r = doCheck(feedUrl);
                final Result fr = r;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(fr);
                    }
                });
            }
        }, "update-check").start();
    }

    private static Result doCheck(String feedUrl) {
        Result r = new Result();
        if (feedUrl == null || feedUrl.trim().isEmpty()) {
            r.error = "未设置更新源";
            return r;
        }
        HttpURLConnection conn = null;
        try {
            URL u = new URL(feedUrl.trim());
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "TFManager/" + AppVersion.NAME);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                r.error = "HTTP " + code;
                return r;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            String body = bos.toString("UTF-8").trim();

            // 网盘/网页分享链接（如夸克网盘）：不是 JSON，无法自动比对版本
            if (!body.startsWith("{")) {
                r.error = "该链接是网页/网盘分享页，无法自动比对版本";
                r.downloadUrl = feedUrl.trim();
                return r;
            }

            boolean github = feedUrl.contains("api.github.com");
            if (github) {
                String tag = str(body, "tag_name");
                r.versionName = tag.replaceFirst("^[vV]", "");
                r.versionCode = digits(r.versionName);
                r.notes = str(body, "body");
                r.downloadUrl = firstApkAsset(body);
            } else {
                r.versionName = str(body, "versionName");
                r.versionCode = num(body, "versionCode");
                r.notes = str(body, "notes");
                r.downloadUrl = str(body, "url");
                if (r.versionCode == 0) {
                    r.versionCode = digits(r.versionName);
                }
            }
            if (r.versionName.isEmpty() && r.versionCode == 0) {
                r.error = "无法解析更新信息";
                return r;
            }
            r.ok = true;
            return r;
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------- 极简 JSON 取值

    /** 取字符串字段："key": "value"。 */
    private static String str(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return "";
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return "";
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int k = q1 + 1; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '\\' && k + 1 < json.length()) {
                char e = json.charAt(k + 1);
                k++;
                if (e == 'n') {
                    sb.append('\n');
                } else if (e == 't') {
                    sb.append('\t');
                } else if (e == 'r') {
                    sb.append('\r');
                } else if (e == 'u' && k + 4 < json.length()) {
                    try {
                        sb.append((char) Integer.parseInt(json.substring(k + 1, k + 5), 16));
                        k += 4;
                    } catch (Throwable ignored) {
                    }
                } else {
                    sb.append(e);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 取数字字段："key": 123。 */
    private static int num(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return 0;
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return 0;
        }
        int k = colon + 1;
        while (k < json.length() && !Character.isDigit(json.charAt(k))
                && json.charAt(k) != '-') {
            k++;
        }
        int start = k;
        while (k < json.length() && (Character.isDigit(json.charAt(k)) || json.charAt(k) == '-')) {
            k++;
        }
        try {
            return Integer.parseInt(json.substring(start, k));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 第一个 .apk 资源的下载地址。 */
    private static String firstApkAsset(String json) {
        int idx = 0;
        while (true) {
            int i = json.indexOf("\"browser_download_url\"", idx);
            if (i < 0) {
                return "";
            }
            int colon = json.indexOf(':', i);
            int q1 = json.indexOf('"', colon + 1);
            int q2 = json.indexOf('"', q1 + 1);
            if (q1 < 0 || q2 < 0) {
                return "";
            }
            String url = json.substring(q1 + 1, q2);
            if (url.toLowerCase(Locale.US).endsWith(".apk")) {
                return url;
            }
            idx = q2;
        }
    }

    /**
     * 点分版本号 → 语义化编码（major*10000 + minor*100 + patch）。
     * 例："2.7.2" → 20702；"v2.7" → 20700；解析失败返回 0。
     */
    private static int digits(String s) {
        if (s == null) {
            return 0;
        }
        String v = s.trim().replaceFirst("^[vV]", "");
        String[] parts = v.split("[^0-9]+");
        int major = 0;
        int minor = 0;
        int patch = 0;
        try {
            if (parts.length > 0 && !parts[0].isEmpty()) {
                major = Integer.parseInt(parts[0]);
            }
            if (parts.length > 1 && !parts[1].isEmpty()) {
                minor = Integer.parseInt(parts[1]);
            }
            if (parts.length > 2 && !parts[2].isEmpty()) {
                patch = Integer.parseInt(parts[2]);
            }
        } catch (Throwable ignored) {
            return major * 10000 + minor * 100 + patch;
        }
        return major * 10000 + minor * 100 + patch;
    }
}